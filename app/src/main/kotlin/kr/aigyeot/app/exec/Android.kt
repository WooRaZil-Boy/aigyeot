package kr.aigyeot.app.exec

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.WaypointCmdType
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.HvacSeatTemperatureListener
import ai.pleos.playground.vehicle.listener.HvacTemperatureListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import kr.aigyeot.app.common.CarPaths
import kr.aigyeot.app.detect.MicService
import kr.aigyeot.engine.Area
import kr.aigyeot.engine.CabinTemp
import kr.aigyeot.engine.CallMode
import kr.aigyeot.engine.ControlKind
import kr.aigyeot.engine.Event
import kr.aigyeot.engine.FocusChange
import kr.aigyeot.engine.GearChanged
import kr.aigyeot.engine.Method
import kr.aigyeot.engine.MethodKind
import kr.aigyeot.engine.RearDoor
import kr.aigyeot.engine.RestCandidate
import kr.aigyeot.engine.SeatHeat
import kr.aigyeot.engine.Side
import java.util.Locale
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInit

// 실제 Pleos·안드로이드 연결. 판단·규칙은 Player·Voice·Controls에 있고, 여기는 호출을 옮기기만 한다(JVM 시험 밖, 에뮬레이터로 확인).

private const val TAG = "exec"

/**
 * exec 스레드 하나. 예약과 SDK 콜백을 모두 같은 잠금 안에서 돌린다. Exec.stop 뒤에도 끝내지 않는다:
 * stop 뒤 start가 다시 오므로 예약·콜백이 살아 있어야 하고, 쉬는 HandlerThread는 싸다(Exec 하나에 하나).
 */
internal class ExecThread : Sched {
    val lock = Any()
    private val thread = HandlerThread("exec").apply { start() }
    val handler = Handler(thread.looper)
    fun guard(f: () -> Unit) { synchronized(lock) { try { f() } catch (e: Exception) { Log.w(TAG, "callback", e) } } }
    fun post(f: () -> Unit) { handler.post { guard(f) } }
    override fun after(ms: Long, task: () -> Unit): () -> Unit {
        val r = Runnable { guard(task) }
        handler.postDelayed(r, maxOf(ms, 0))
        return { handler.removeCallbacks(r) }
    }
    fun quit() = thread.quitSafely()
}

/** 실제 연결 묶음. [Exec]의 공개 생성자가 만든다. */
internal fun androidIo(ctx: Context, clock: () -> Long, post: (Event) -> Unit): ExecIo {
    val ex = ExecThread()
    val car = PleosCar(ctx, ex, clock, post)
    val navi = PleosNavi(ctx, ex, clock, post)
    val tts = PleosTts(ctx, ex)
    val stt = PleosStt(ctx, ex)
    return ExecIo(ex.lock, ex, AndroidAudio(ctx, ex), tts, stt, PcmTap(ex), car, navi,
        start = { runCatching { car.start() }; runCatching { navi.start() }; runCatching { tts.start() }; runCatching { stt.start() } },
        stop = { runCatching { car.stop() }; runCatching { navi.stop() }; runCatching { tts.release() }; runCatching { stt.release() } })
}

// ---- 재생 ----

internal class AndroidAudio(private val ctx: Context, private val ex: ExecThread) : AudioPort {
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private var focus: AudioFocusRequest? = null

    private fun rear(): AudioDeviceInfo? = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.address == REAR_ZONE }
    override fun hasRearOutput() = rear() != null

    override fun requestFocus(onChange: (FocusChange) -> Unit): Boolean {
        abandonFocus()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(media)
            .setOnAudioFocusChangeListener({ c -> ex.guard { focusOf(c)?.let(onChange) } }, ex.handler).build()
        if (am.requestAudioFocus(req) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        focus = req
        return true
    }

    override fun abandonFocus() { focus?.let { am.abandonAudioFocusRequest(it) }; focus = null }

    override fun open(method: Method, rear: Boolean, onRouted: (String?) -> Unit, onError: () -> Unit): Track? = try {
        val dev = if (rear) rear() else null
        when (method.kind) {
            MethodKind.WHITE_NOISE -> NoiseTrack(media, dev, ex, onRouted, onError)
            MethodKind.LULLABY -> ctx.assets.openFd(LULLABY_ASSET).use { fd ->
                FileTrack(media, dev, ex, onRouted, onError) { it.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) }
            }
            MethodKind.SONG -> {
                val f = CarPaths.songFile(ctx, method.songId ?: error("songId 없음"))
                check(f.isFile) { "노래 파일 없음 ${f.name}" }
                FileTrack(media, dev, ex, onRouted, onError) { it.setDataSource(f.path) }
            }
        }
    } catch (e: Exception) { Log.w(TAG, "open $method", e); null }

    private fun focusOf(c: Int) = when (c) {
        AudioManager.AUDIOFOCUS_LOSS -> FocusChange.LOSS
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> FocusChange.LOSS_TRANSIENT
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> FocusChange.LOSS_TRANSIENT_CAN_DUCK
        AudioManager.AUDIOFOCUS_GAIN -> FocusChange.GAIN
        else -> null
    }

    companion object {
        /**
         * 음원 ② 녹음 자장가(11-04 렌더링): 브람스 「자장가」 Op.49 No.4(퍼블릭 도메인), MIDI Rabanus Flavus(Wikimedia Commons, CC0,
         * 'Guten Abend, gut Nacht (Wiegenlied).mid')의 네 성부를 GM 10번 오르골로 바꿔 FluidSynth 2.6.2와 FluidR3_GM(Frank Wen, MIT,
         * Debian fluid-soundfont-gm 3.1-6)으로 44.1kHz 렌더링 → 모노 → −5.0 dB → AAC-LC 64kbps. ffmpeg ebur128로 −30.0 LUFS, 정점 −12.5 dBFS, 65.8초.
         * 없으면 PlaybackRejected(ERROR).
         */
        const val LULLABY_ASSET = "sound/lullaby.m4a"
    }
}

/** 음원 ① 백색소음을 실시간으로 만들어 흘린다. 쓰는 스레드는 트랙이 멈추면(pause) 기다린다. */
internal class NoiseTrack(attrs: AudioAttributes, dev: AudioDeviceInfo?, private val ex: ExecThread,
                          onRouted: (String?) -> Unit, private val onError: () -> Unit) : Track {
    private val rate = WhiteNoise.RATE
    private val track = AudioTrack.Builder().setAudioAttributes(attrs)
        .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), rate / 5 * 2))
        .setTransferMode(AudioTrack.MODE_STREAM).build()
    private val noise = WhiteNoise()
    private val size = rate / 20                             // 50ms
    private val lock = Object()
    @Volatile private var running = true
    @Volatile private var paused = false
    private val writer = Thread({ loop() }, "noise")

    init {
        try {
            dev?.let { track.setPreferredDevice(it) }
            track.addOnRoutingChangedListener(AudioRouting.OnRoutingChangedListener { r -> ex.guard { onRouted(r.routedDevice?.address) } }, ex.handler)
        } catch (e: Exception) { track.release(); throw e }
    }

    private fun loop() {
        val b = ShortArray(size)
        while (running) {
            synchronized(lock) { while (paused && running) lock.wait() }   // 멈춘 트랙의 write는 바로 돌아오므로 난수를 쉬지 않고 만들지 않게 기다린다
            if (!running) return
            noise.fill(b)
            var off = 0
            while (off < b.size && running && !paused) {                   // 짧게 돌아온 write는 남은 만큼 이어 쓴다. 멈췄으면 버린다
                val n = track.write(b, off, b.size - off)
                if (n < 0) { if (running) ex.post(onError); return }   // exec 스레드로 넘긴다: 거기서 release()가 이 스레드를 join해도 자기 자신을 기다리지 않는다
                off += n
            }
        }
    }

    override fun start(): String? {
        track.setVolume(0f)                                  // 소리 없이 시작한다. 이득은 Player가 경로를 안 뒤에 gain()으로 정한다
        track.play()
        track.write(ShortArray(size), 0, size)               // 무음 첫 버퍼를 먼저 써야 경로가 정해진다(프로브 SoundProbe.noise와 같은 순서)
        val routed = track.routedDevice?.address
        writer.start()
        return routed
    }
    override fun routed(): String? = track.routedDevice?.address
    override fun gain(g: Float) { track.setVolume(g) }
    override fun pause() { paused = true; track.pause() }
    override fun resume() { track.play(); synchronized(lock) { paused = false; lock.notifyAll() } }
    override fun release() {
        running = false
        synchronized(lock) { lock.notifyAll() }
        runCatching { track.pause(); track.flush(); track.stop() }   // 바로 멈추고(pause·flush), stop이 막힌 write를 깨운다
        runCatching { writer.join(300) }
        track.release()
    }
}

/** 음원 ②③ 파일(자장가 wav, 부모 노래 m4a). 노래는 이미 −30 LUFS 사본이다(SongLoudness). */
internal class FileTrack(attrs: AudioAttributes, dev: AudioDeviceInfo?, ex: ExecThread, onRouted: (String?) -> Unit, onError: () -> Unit,
                         source: (MediaPlayer) -> Unit) : Track {
    private val mp = MediaPlayer()
    init {
        try {
            mp.apply {
                setAudioAttributes(attrs)
                source(this)
                isLooping = true                             // 56초보다 짧은 음원도 endAt까지(끝은 Player가 정한다)
                dev?.let { setPreferredDevice(it) }
                setOnErrorListener { _, _, _ -> ex.guard(onError); true }
                addOnRoutingChangedListener({ r -> ex.guard { onRouted(r.routedDevice?.address) } }, ex.handler)
                setVolume(0f, 0f)                            // 소리 없이 시작한다. 이득은 Player가 gain()으로 처음 정한다
                prepare()
            }
        } catch (e: Exception) { mp.release(); throw e }     // source·prepare가 던지면 MediaPlayer를 놓고 다시 던진다
    }
    override fun start(): String? { mp.start(); return mp.routedDevice?.address }
    override fun routed(): String? = mp.routedDevice?.address
    override fun gain(g: Float) = mp.setVolume(g, g)
    override fun pause() = mp.pause()
    override fun resume() = mp.start()
    override fun release() { runCatching { mp.stop() }; mp.release() }
}

// ---- 말하기·듣기 ----

/**
 * C72: USAGE_NOTIFICATION + CONTENT_TYPE_SPEECH, 포커스 GAIN_TRANSIENT_MAY_DUCK, Locale.KOREA, 시작 때 빈 문장으로 예열. 직렬화는 [TtsGate].
 * start()마다 SDK 객체와 게이트를 새로 만들고 release()에서 놓는다(stop 뒤 다시 start 가능).
 */
internal class PleosTts(private val ctx: Context, private val ex: ExecThread) : TtsPort {
    private val am = ctx.getSystemService(AudioManager::class.java)
    private var tts: TextToSpeech? = null
    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs)
        .setOnAudioFocusChangeListener({}, ex.handler).build()
    private var gate = newGate()

    private fun newGate() = TtsGate(
        sdkSpeak = { (tts ?: error("tts 없음")).speak(it, true) }, sdkStop = { tts?.stop() },
        requestFocus = { am.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED },
        abandonFocus = { am.abandonAudioFocusRequest(focus) }, sched = ex)

    fun start() {
        val g = newGate().also { gate = it }
        val t = TextToSpeech(ctx, TtsMode.ON_DEVICE).also { tts = it }
        val events = object : EventListener {
            override fun onStart() {}
            override fun onReady() {}
            override fun onDone() = ex.guard { g.settled(true) }
            override fun onError(e: TtsError) = ex.guard { Log.w(TAG, "tts $e"); g.settled(false) }
            override fun onStop() = ex.guard { g.settled(false) }
            override fun onUpdatedRms(rms: Double) {}
        }
        t.initialize(object : TtsInit {
            override fun onSuccess() = ex.guard {
                if (tts !== t) return@guard                          // 그사이 release됐다
                t.setLocale(Locale.KOREA)
                t.setAudioAttributes(attrs)
                t.addEventListener(events)
                g.warm()
            }
            override fun onFailure(e: TtsError) = ex.guard { Log.w(TAG, "tts init $e"); g.initFailed() }
        })
    }

    override fun speak(text: String, done: (Boolean) -> Unit) = gate.speak(text, done)
    override fun stop() = gate.stop()
    fun release() { gate.stop(); val t = tts; tts = null; runCatching { t?.release() } }
}

/**
 * STT ON_DEVICE에 PCM을 직접 넣는다. 듣기 하나가 sendAudio… completeAudioSend 한 묶음이다(11-03 sttpcm 실측과 같은 순서). 직렬화는 [SttGate].
 * start()마다 SDK 객체와 게이트를 새로 만들고 release()에서 놓는다.
 */
internal class PleosStt(private val ctx: Context, private val ex: ExecThread) : SttPort {
    private var stt: SpeechToText? = null
    private var ready = false
    private var gate = newGate()

    private fun newGate() = SttGate(
        sendAudio = { if (ready) runCatching { stt?.sendAudio(it) } },
        completeAudioSend = { if (ready) runCatching { stt?.completeAudioSend() } },
        stopSdk = { if (ready) runCatching { stt?.stop() } })

    fun start() {
        val g = newGate().also { gate = it }
        ready = false
        val s = SpeechToText(ctx, SttMode.ON_DEVICE).also { stt = it }
        val listener = object : ResultListener {
            override fun onUpdated(text: String, completed: Boolean) = ex.guard { g.result(text, completed) }
            override fun onUpdatedEpdData(on: Long, off: Long) {}
            override fun onStartedRecognition() {}
            override fun onEndedRecognition() {}
            override fun onError(e: SttError) = ex.guard { Log.w(TAG, "stt $e"); g.error() }
            override fun onReady() {}
            override fun onUpdatedRms(rms: Double) {}
        }
        s.initialize(object : SttInit {
            override fun onSuccess() = ex.guard { if (stt === s) { s.setLocale(Locale.KOREA); s.addListener(listener); ready = true } }
            override fun onFailure(e: SttError) { Log.w(TAG, "stt init $e") }
        })
    }

    override fun begin(onText: (String, Boolean) -> Unit) = gate.begin(onText)
    override fun feed(pcm: ByteArray) = gate.feed(pcm)
    override fun finish() = gate.finish()
    override fun cancel() = gate.cancel()
    fun release() { cancel(); ready = false; val s = stt; stt = null; runCatching { s?.release() } }
}

/** 창(표본 [n], 끝이 [now])에서 open([openedAt]) 이후 몫의 표본 수. 앞의 나머지는 듣기 전 소리라 버린다. */
internal fun keepTail(n: Int, openedAt: Long, now: Long, rate: Int = 16_000): Int = ((now - openedAt) * rate / 1000).coerceIn(0L, n.toLong()).toInt()

/** MicService.pcmTap을 잡는다. 감지 스레드는 복사만 하고 바로 돌아간다(MicService 주석). */
internal class PcmTap(private val ex: ExecThread) : MicTap {
    private var tap: ((ShortArray, Int) -> Unit)? = null
    override fun open(sink: (ByteArray) -> Unit) {
        val openedAt = SystemClock.elapsedRealtime()
        val t: (ShortArray, Int) -> Unit = { pcm, n ->
            // MicService는 창을 다 읽은 뒤 부르므로 첫 창에는 open 전 소리가 섞인다. 그 몫을 앞에서 잘라 낸다.
            // ponytail: 듣기 끝 직전 말이 다음 창으로 넘어가 빠지는 쪽은 알려진 한계(창 975ms 단위)
            val k = keepTail(n, openedAt, SystemClock.elapsedRealtime())
            if (k > 0) {
                val b = ByteArray(k * 2)
                for (i in 0 until k) { val s = pcm[n - k + i].toInt(); b[2 * i] = s.toByte(); b[2 * i + 1] = (s shr 8).toByte() }
                ex.post { sink(b) }
            }
        }
        tap = t
        MicService.pcmTap = t
    }
    override fun close() { if (MicService.pcmTap === tap) MicService.pcmTap = null; tap = null }
}

// ---- 차량 신호·제어 ----

private fun seat(a: Area) = SeatArea(SeatValue.valueOf(a.name))
private fun window(a: Area) = WindowArea(WindowValue.valueOf(a.name))
private val REAR_SEATS = listOf(Area.ROW_2_LEFT, Area.ROW_2_RIGHT)

/**
 * 차량 구독(계약 3.3)과 제어(4.2). C71: 리스너를 받는 4인자 Car.createCar로 붙고, CarService가 다시 준비되면 다시 구독한다.
 * Pleos Vehicle은 준비 신호가 없어 프로브처럼 initialize 1.5초 뒤 구독한다(설계값).
 */
internal class PleosCar(private val ctx: Context, private val ex: ExecThread, private val clock: () -> Long, private val post: (Event) -> Unit) : ControlPort {
    private var vehicle: Vehicle? = null
    private var car: Car? = null
    private var props: CarPropertyManager? = null
    private val am = ctx.getSystemService(AudioManager::class.java)
    private var changed = Changed()                      // start마다 새로: 다시 시작하면 지금 기어·통화를 다시 낸다
    private val sampler = Sampler(clock, post)
    private var cancelSub: () -> Unit = {}

    private fun <T : Any> emit(key: String, v: T, e: () -> Event) = changed.emit(key, v, e, post)

    /** 한 구독(subscribe)의 신호 리스너 묶음. 콜백 첫 줄에서 구독 번호가 다르면 무시한다(CarService 재연결로 같은 start 안에서 다시 구독해도 옛 구독의 늦은 응답을 버린다)(앞 회차의 늦은 응답이 새 구독을 건드리지 않게). */
    private inner class Listeners(val sub: Int) {
        fun on(f: () -> Unit) = ex.guard { if (subGen == sub) f() }
        val gearL = object : GearSelectionListener {
            override fun onSelected(g: VehicleGear?) = on { gearOf(g)?.let { emit("gear", it) { GearChanged(clock(), it) } } }
            override fun onFailed(e: Exception) { Log.w(TAG, "gear", e) }
        }
        val doorL = Side.entries.associateWith { side ->
            object : DoorPositionListener {
                override fun onPositionUpdated(p: Int?) = on { door(side, p) }
                override fun onFailed(e: Exception) { Log.w(TAG, "door $side", e) }
            }
        }
        val seatL = REAR_SEATS.associateWith { a ->
            object : HvacSeatTemperatureListener {
                override fun onHvacSeatTemperatureUpdated(l: Int?) = on { l?.let { emit("seat$a", it) { SeatHeat(clock(), a, it) } } }
                override fun onFailed(e: Exception) { Log.w(TAG, "seat $a", e) }
            }
        }
        val cabinL = object : HvacTemperatureListener {
            override fun onHvacTemperatureUpdated(v: Float?) = on { readCabin(sub) }   // 단위가 정해진 읽기로 다시 읽는다
            override fun onFailed(e: Exception) { Log.w(TAG, "cabin", e) }
        }
        val steerL = object : SteeringWheelAngleListener {
            override fun onSteeringWheelAngleUpdated(angle: Float?) = on { sampler.steer(angle) }
            override fun onFailed(e: Exception) { Log.w(TAG, "steer", e) }
        }
        val brakeL = object : BrakePedalStatusListener {
            override fun onBrakePedalStatusUpdated(pressed: Boolean?) = on { sampler.brake(pressed) }
            override fun onFailed(e: Exception) { Log.w(TAG, "brake", e) }
        }
        val turnL = object : TurnSignalStateListener {
            override fun onTurnSignalStateUpdated(v: TurnSignalValue?) = on { sampler.turn(turnOf(v)) }
            override fun onFailed(e: Exception) { Log.w(TAG, "turn", e) }
        }
        val speedL = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(v: CarPropertyValue<*>) = on { sampler.speedMps(v.value as? Float) }
            override fun onErrorEvent(propId: Int, zone: Int) { Log.w(TAG, "speed error $zone") }
        }
    }
    private var ls: Listeners? = null
    private var modeL: AudioManager.OnModeChangedListener? = null   // start마다 새로(시작 번호를 잡는다)

    private fun door(side: Side, pos: Int?) { val open = (pos ?: 0) > 0; emit("door$side", open) { RearDoor(clock(), side, open) } }
    private fun call(mode: Int) { val c = inCall(mode); emit("call", c) { CallMode(clock(), c) } }
    private fun readCabin(sub: Int) = vehicle?.getHVAC()?.getHvacTemperatureCurrent(seat(Area.ROW_1_LEFT), TemperatureUnit.CELSIUS,
        { v -> ex.guard { if (subGen == sub) v?.let { emit("cabin", it.toDouble()) { CabinTemp(clock(), it.toDouble()) } } } }, { e -> Log.w(TAG, "cabin", e) })

    private var gen = 0                                   // 시작 번호(start·stop마다 +1): Car 수명 콜백·첫 기어 읽기·구독 예약·통화 모드 리스너가 잡는다
    private var subGen = 0                                // 구독 번호(subscribe·unsubscribe마다 +1): Listeners 묶음·subscribe 안의 get 콜백·readCabin이 잡는다

    fun start() {
        val my = ++gen
        changed = Changed()
        vehicle = Vehicle(ctx).also { runCatching { it.initialize() }.onFailure { e -> Log.w(TAG, "vehicle init", e) } }
        // 구독(Car 준비 + 1.5초)을 기다리지 않고 지금 기어를 한 번 읽는다. 실패하면 조용히 넘어가고 구독 때 다시 읽는다(Changed가 중복을 막는다)
        runCatching { vehicle?.getGear()?.getCurrentGearSelectionState({ g -> ex.guard { if (gen == my) gearOf(g)?.let { emit("gear", it) { GearChanged(clock(), it) } } } }, {}) }
        car = Car.createCar(ctx, ex.handler, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready ->
            ex.guard {
                if (gen != my) return@guard                                   // 앞 회차 Car의 늦은 수명 콜백
                cancelSub()
                if (ready) cancelSub = ex.after(SUBSCRIBE_DELAY_MS) { if (gen == my) subscribe(c) } else { subGen++; props = null }   // 끊기면 다시 준비될 때 새로. 다음 subscribe 전까지 옛 구독의 늦은 콜백도 막는다
            }
        }
        if (Build.VERSION.SDK_INT >= 31) {
            // executor에 이미 넣은 옛 콜백은 제거해도 취소되지 않으므로 시작 번호로 거른다
            val ml = AudioManager.OnModeChangedListener { m -> ex.guard { if (gen == my) call(m) } }
            modeL = ml
            am.addOnModeChangedListener(ctx.mainExecutor, ml)
        }
        call(am.mode)
    }

    private fun subscribe(c: Car) {
        unsubscribe()
        val v = vehicle ?: return
        val sub = ++subGen
        val l = Listeners(sub).also { ls = it }
        runCatching {
            v.getGear().registerGearSelection(l.gearL)
            v.getGear().getCurrentGearSelectionState({ g -> l.on { gearOf(g)?.let { emit("gear", it) { GearChanged(clock(), it) } } } }, {})
            l.doorL.forEach { (side, d) -> v.getDoor().registerDoorPosition(DoorArea(if (side == Side.LEFT) DoorValue.ROW_2_LEFT else DoorValue.ROW_2_RIGHT), d) }
            l.seatL.forEach { (a, x) -> v.getSeat().registerHvacSeatTemperature(seat(a), x) }
            l.seatL.keys.forEach { a -> v.getSeat().getHvacSeatTemperature(seat(a), { h -> l.on { h?.let { emit("seat$a", it) { SeatHeat(clock(), a, it) } } } }, {}) }
            v.getHVAC().registerHvacTemperatureCurrent(seat(Area.ROW_1_LEFT), l.cabinL)
            readCabin(sub)
            v.getSteeringWheel().registerSteeringWheelAngle(l.steerL)
            v.getBrake().registerBrakePedalStatus(l.brakeL)
            v.getTurnSignal().registerTurnSignalState(l.turnL)
        }.onFailure { Log.w(TAG, "subscribe vehicle", it) }
        runCatching {
            props = (c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager).also {
                it.registerCallback(l.speedL, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_FAST)
            }
        }.onFailure { Log.w(TAG, "subscribe speed(CAR_SPEED 권한?)", it) }
    }

    /** 그 회차의 묶음을 해제한다. */
    private fun unsubscribe() {
        subGen++                                          // 옛 구독의 늦은 콜백을 먼저 막는다
        val l = ls ?: return
        ls = null
        val v = vehicle
        if (v != null) {
            runCatching { v.getGear().unregisterGearSelection(l.gearL) }
            l.doorL.forEach { (side, d) -> runCatching { v.getDoor().unregisterDoorPosition(DoorArea(if (side == Side.LEFT) DoorValue.ROW_2_LEFT else DoorValue.ROW_2_RIGHT), d) } }
            l.seatL.forEach { (a, x) -> runCatching { v.getSeat().unregisterHvacSeatTemperature(seat(a), x) } }
            runCatching { v.getHVAC().unregisterHvacTemperatureCurrent(seat(Area.ROW_1_LEFT), l.cabinL) }
            runCatching { v.getSteeringWheel().unregisterSteeringWheelAngle(l.steerL) }
            runCatching { v.getBrake().unregisterBrakePedalStatus(l.brakeL) }
            runCatching { v.getTurnSignal().unregisterTurnSignalState(l.turnL) }
        }
        runCatching { props?.unregisterCallback(l.speedL) }
        props = null
    }

    fun stop() {
        gen++
        cancelSub()
        unsubscribe()
        modeL?.let { ml -> runCatching { am.removeOnModeChangedListener(ml) } }; modeL = null
        runCatching { car?.disconnect() }
        runCatching { vehicle?.release() }
        car = null; vehicle = null
    }

    override fun read(kind: ControlKind, area: Area, cb: (Double?) -> Unit) {
        val v = vehicle ?: return cb(null)
        val fail: (Exception) -> Unit = { ex.guard { cb(null) } }
        try {
            when (kind) {
                ControlKind.SEAT_HEAT -> v.getSeat().getHvacSeatTemperature(seat(area), { x -> ex.guard { cb(x?.toDouble()) } }, fail)
                ControlKind.WINDOW_LOCK -> v.getWindow().getCurrentWindowLockState(window(area), { x -> ex.guard { cb(x?.let { if (it) 1.0 else 0.0 }) } }, fail)
                ControlKind.READING_LIGHT -> v.getLight().getReadingLightSwitch(seat(area), { x -> ex.guard { cb(x?.let { if (it == LightSwitch.ON) 1.0 else 0.0 }) } }, fail)
                ControlKind.HVAC_TEMP -> v.getHVAC().getHvacTemperature(seat(area), TemperatureUnit.CELSIUS, { x -> ex.guard { cb(x?.toDouble()) } }, fail)
            }
        } catch (e: Exception) { cb(null) }
    }

    override fun write(kind: ControlKind, area: Area, value: Double, ack: (Boolean) -> Unit) {
        val v = vehicle ?: return ack(false)
        val fail: (Exception) -> Unit = { e -> ex.guard { Log.w(TAG, "write $kind $area ${e.message}"); ack(false) } }
        try {
            when (kind) {
                ControlKind.SEAT_HEAT -> v.getSeat().setHvacSeatTemperature(seat(area), value.toInt(), { _, _ -> ex.guard { ack(true) } }, fail)
                ControlKind.WINDOW_LOCK -> v.getWindow().setWindowLock(window(area), value == 1.0, { _, _ -> ex.guard { ack(true) } }, fail)
                ControlKind.READING_LIGHT -> v.getLight().setReadingLightSwitch(seat(area), if (value == 1.0) LightSwitch.ON else LightSwitch.OFF, { _ -> ex.guard { ack(true) } }, fail)
                ControlKind.HVAC_TEMP -> v.getHVAC().setHvacTemperature(seat(area), value.toFloat(), TemperatureUnit.CELSIUS, { _, _ -> ex.guard { ack(true) } }, fail)
            }
        } catch (e: Exception) { ack(false) }
    }

    private companion object { const val SUBSCRIBE_DELAY_MS = 1_500L }
}

// ---- 내비 ----

/**
 * RouteState(계약 3.3)와 addWaypoint. 남은 시간은 DrivingInfo.destination.duration(초)으로 본다(C75, 첫날 실제 값으로 확정, 미판정).
 * TBT에는 좌표가 없어 경유지 좌표·수유실은 휴게소 공공데이터(RestAreas)에서 찾는다. 양방향 휴게소처럼 이름이 둘이면
 * 지금 위치를 한 번 받아 곧은 거리로 먼 쪽 줄을 빼고 하나가 남을 때만 고른다(RestAreas.place). 고를 수 없으면 넣지 않는다.
 * 결과: ADD 알림 뒤 경유지 목록을 다시 읽어 보낸 좌표 300m 안의 경유지가 있을 때만 성공(F19 다시 읽기, waypointPresent).
 * 앞 요청의 늦은 알림·응답은 Controls가 요청을 닫을 때 [cancelWaypoint]로 비워 다음 요청의 결과가 되지 않는다(C65·C95).
 */
internal class PleosNavi(private val ctx: Context, private val ex: ExecThread, clock: () -> Long, post: (Event) -> Unit) : NaviPort {
    private var helper: NaviHelper? = null
    private val route = RouteFeed(clock, post)
    private var pending: ((Boolean, String) -> Unit)? = null          // addWaypoint 결과 콜백을 기다림
    private var targetRows: List<RestArea> = RestAreas.all               // 대조에 쓸 자료(쉼터 종류에 맞춘다)
    private var target: RestArea? = null                              // 보낸 경유지 줄(대조 대상)
    private var awaitingHere: Pair<RestCandidate, (Boolean, String) -> Unit>? = null   // 위치를 받아야 고를 수 있음
    private var listener: NaviHelperEventListener? = null
    private var gen = 0                                               // 시작 회차(start·stop마다 +1). 앞 회차의 늦은 콜백·응답을 버린다

    /** start마다 새로 만들어 그 회차 번호를 잡는다. 회차가 다르면 아무것도 하지 않는다. */
    private fun newListener(my: Int) = object : NaviHelperEventListener {
        private fun on(f: () -> Unit) = ex.guard { if (gen == my) f() }
        override fun onNavigationStatus(status: NavigationStatus) = on { route.routing(status == NavigationStatus.ROUTE_DRIVE) }
        override fun onRouteStateInfo(info: RouteStateInfo) = on { route.routing(info.isRouting) }
        override fun onRouteCancelled() = on { route.routing(false) }
        override fun onRouteEnded() = on { route.routing(false) }
        override fun onDrivingInfo(info: DrivingInfo) = on { route.remain(info.destination?.duration) }
        override fun onTBTInfo(list: List<TBTInfo>) = on { route.rests(restsOf(list) { RestAreas.nursing(it) }) }
        override fun onCurrentLocationInfo(info: CurrentLocationInfo) = on {
            awaitingHere?.let { (rest, done) -> awaitingHere = null; send(rest, Here(info.latitude, info.longitude), done) }
        }
        override fun onWaypointChanged(info: WaypointChangedInfo) = on {
            // 어느 요청의 알림인지 알 수 없으니 바로 성공으로 보지 않고 경유지 목록을 다시 읽어 대조한다
            if (info.cmdType == WaypointCmdType.ADD && pending != null && target != null) runCatching { helper?.getWaypointInfo() }
        }
        override fun onWayPointInfo(list: List<WaypointInfo>) = on {
            val t = target ?: return@on
            if (waypointPresent(list.map { it.latitude to it.longitude }, t, targetRows)) pending?.let { pending = null; target = null; it(true, "added") }
        }
        // 어느 요청의 오류인지 알 수 없어 결과로 쓰지 않는다(실패는 Controls의 10초 시간 초과가 정한다)
        override fun onError(code: NaviErrorCode) = on { Log.w(TAG, "navi error ${code.name}") }
    }

    fun start() {
        val my = ++gen
        val l = newListener(my).also { listener = it }
        helper = NaviHelper(ctx).also { h ->
            h.initialize({ ex.guard { if (gen == my) { h.addListener(l); h.getRouteStateInfo(); h.getTBTInfo() } } }, { e -> Log.w(TAG, "navi init", e) })
        }
    }

    fun stop() {
        gen++
        cancelWaypoint()
        val l = listener; listener = null
        helper?.let { h -> runCatching { l?.let(h::removeListener); h.release() } }
        helper = null
    }

    override fun addWaypoint(restId: String, rest: RestCandidate?, done: (Boolean, String) -> Unit) {
        cancelWaypoint()                                                 // 앞 요청은 Controls가 이미 닫았다
        rest ?: return done(false, "unknown_rest")
        val h = helper ?: return done(false, "no_navi")
        when (RestAreas.match(rest.name, RestAreas.rowsOf(rest.kind)).size) {   // 졸음쉼터는 졸음쉼터 자료에서
            0 -> done(false, "no_coords")
            1 -> send(rest, null, done)
            else -> { awaitingHere = rest to done; runCatching { h.getCurrentLocationInfo() }.onFailure { awaitingHere = null; done(false, "no_location") } }
        }
    }

    override fun cancelWaypoint() { pending = null; awaitingHere = null; target = null }

    private fun send(rest: RestCandidate, here: Here?, done: (Boolean, String) -> Unit) {
        val h = helper ?: return done(false, "no_navi")
        val rows = RestAreas.rowsOf(rest.kind)
        val a = RestAreas.place(rest.name, rest.distanceKm, here, rows) ?: return done(false, "ambiguous")
        pending = done
        target = a
        targetRows = rows
        try {
            h.addWaypoint(RequestWaypointInfo(latitude = a.lat, longitude = a.lon, poiName = rest.name, waypointIndex = WaypointIndex.FIRST,
                poiId = "", address = "${a.route} ${a.name}", poiSubId = "0"))
        } catch (e: Exception) { pending = null; target = null; done(false, "error ${e.message}") }
    }
}
