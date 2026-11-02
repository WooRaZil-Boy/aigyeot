package dev.probe.pleos

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier
import com.google.mediapipe.tasks.components.containers.AudioData
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.audio.core.RunningMode
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.concurrent.thread
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInit

// '아이곁' 실측: 에뮬레이터 안에서 YAMNet이 울음·기침을 가르는지, Pleos STT가 한국어 호소를 글로 바꾸는지, TTS가 말하는지 기록하는 일회용 화면
// 입력(16kHz mono s16le .pcm, yamnet.tflite)은 `adb shell run-as dev.probe.pleos --user 10 sh -c 'cat > …'`로 표준 입력을 흘려 앱 files 폴더에 넣는다(운전자 user 10은 adb push가 막힘, README 6장)
// 실행: am start -n dev.probe.pleos/.SoundProbe --es cmd yamnet|stt|sttmic|tts|noise|priv [--es file 이름] [--es text 문장] [--es bus 주소 --ei sec 초]
class SoundProbe : Activity() {
    private fun log(msg: String) { Log.i("SOUND", msg) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dir = filesDir
        val cmd = intent.getStringExtra("cmd")
        log("CMD $cmd dir=$dir")
        when (cmd) {
            "yamnet" -> thread { yamnet(dir) }
            "stt" -> stt(File(dir, intent.getStringExtra("file") + ".pcm"))
            "sttmic" -> stt(null)
            "tts" -> tts(intent.getStringExtra("text") ?: "뒤에 아이가 울어요. 온도 낮췄고, 4분 뒤 쉼터예요")
            "priv" -> priv()
            "noise" -> thread { noise(intent.getStringExtra("bus")) }
        }
    }

    // 앱이 만든 백색소음을 뒷좌석 존 출력(bus100_audio_zone_1)으로 보낼 수 있는지
    private fun noise(bus: String?) {
        val am = getSystemService(android.media.AudioManager::class.java)
        val outs = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
        log("OUTS ${outs.joinToString { it.address }}")
        val tr = android.media.AudioTrack.Builder()
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(android.media.AudioFormat.Builder().setSampleRate(16000).setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(32000).build()
        outs.firstOrNull { it.address == bus }?.let { log("NOISE preferred=${tr.setPreferredDevice(it)} $bus") }
        tr.play()
        val buf = ShortArray(16000) { kotlin.random.Random.nextInt(-3000, 3000).toShort() }
        repeat(intent.getIntExtra("sec", 3)) { tr.write(buf, 0, buf.size); if (it == 0) log("NOISE routed=${tr.routedDevice?.address}") }
        tr.stop(); tr.release()
    }

    // 화자 좌석 구분·음원 분리·통화 음성·TTS 존 지정이 일반 앱에 열려 있는지
    private fun priv() {
        val s = SpeechToText(this, SttMode.ON_DEVICE)
        val t = TextToSpeech(this, TtsMode.ON_DEVICE)
        s.initialize(object : SttInit {
            override fun onSuccess() {
                // Kotlin에서는 private로 보이는 게터라 자바 게터를 직접 부른다
                val ai = { s.javaClass.getMethod("getAmbientAi").invoke(s) as ai.pleos.playground.stt.api.AmbientAiApi }
                tryLog("stt.ambientAi.setEnableSpeechSourceSeparation") { ai().setEnableSpeechSourceSeparation(true) }
                tryLog("stt.ambientAi.setSpeakerRecognitionMethod") { ai().setSpeakerRecognitionMethod(1) }
                tryLog("stt.callApi") { (s.javaClass.getMethod("getCallApi").invoke(s) as ai.pleos.playground.stt.api.CallApi).completeRxAudioSend() }
            }
            override fun onFailure(e: SttError) = log("STT init fail $e")
        })
        t.initialize(object : TtsInit {
            override fun onSuccess() {
                tryLog("tts.setAudioZoneId(1)") { t.setAudioZoneId(1) }
                tryLog("tts.ambientAi.setSpeaker") { (t.javaClass.getMethod("getAmbientAi").invoke(t) as ai.pleos.playground.tts.api.AmbientAi).setSpeaker("x") }
            }
            override fun onFailure(e: TtsError) = log("TTS init fail $e")
        })
    }

    private fun tryLog(what: String, f: () -> Unit) = try { f(); log("PRIV $what ok") } catch (e: Throwable) { log("PRIV $what ERR $e") }

    private fun yamnet(dir: File) {
        val model = File(dir, "yamnet.tflite").readBytes()
        val buf = ByteBuffer.allocateDirect(model.size).order(ByteOrder.nativeOrder()).put(model).also { it.rewind() }
        val c = AudioClassifier.createFromOptions(this, AudioClassifier.AudioClassifierOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(buf).build())
            .setRunningMode(RunningMode.AUDIO_CLIPS).build())
        // 학습한 울음 판별층(공개 학습 코드: tools/cry-retrain/rt_train.py): 고른 YAMNet 클래스들의 최근 3창 평균·최대 → 로지스틱
        var thr = 0.7 // 판별층 파일의 "threshold"가 있으면 그 값
        val head = listOf("cry_head24_rt.json", "cry_head24.json").map { File(dir, it) }.firstOrNull { it.exists() }?.let { org.json.JSONObject(it.readText()) }?.let { j ->
            thr = j.optDouble("threshold", 0.7)
            val w = j.getJSONArray("w"); val cl = j.getJSONArray("classes")
            Triple(IntArray(cl.length()) { cl.getInt(it) }, DoubleArray(w.length()) { w.getDouble(it) }, j.getDouble("bias"))
        }
        val watch = listOf("Baby cry, infant cry", "Crying, sobbing", "Whimper", "Wail, moan", "Child speech, kid speaking", "Cough", "Snoring", "Screaming", "Children shouting")
        dir.listFiles { f -> f.extension == "pcm" }!!.sorted().forEach { f ->
            val pcm = f.readBytes()
            val s = ShortArray(pcm.size / 2).also { ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }
            val data = AudioData.create(AudioData.AudioDataFormat.builder().setNumOfChannels(1).setSampleRate(16000f).build(), s.size)
            data.load(FloatArray(s.size) { s[it] / 32768f })
            val t = System.nanoTime()
            val r = c.classify(data)
            val ms = (System.nanoTime() - t) / 1_000_000
            r.classificationResults().forEach { cr ->
                val cats = cr.classifications()[0].categories()
                val top = cats.take(3).joinToString { "${it.categoryName()}:${"%.2f".format(it.score())}" }
                val seen = cats.filter { it.categoryName() in watch && it.score() >= 0.02f }.joinToString { "${it.categoryName()}:${"%.2f".format(it.score())}" }
                log("YAMNET ${f.nameWithoutExtension} t=${cr.timestampMs().orElse(-1)} $top | $seen")
            }
            log("YAMNET ${f.nameWithoutExtension} ${s.size / 16000.0}s took ${ms}ms")
            head?.let { (cl, w, b) ->
                var win = r.classificationResults().map { cr -> FloatArray(521).also { a -> cr.classifications()[0].categories().forEach { a[it.index()] = it.score() } } }
                if (win.size in 1..2) win = List(3) { win[it % win.size] } // 창이 3개보다 적으면 rt_train.segs처럼 앞 창을 반복해 3창으로 채운다
                val p = win.windowed(3).map { seg ->
                    val z = b + cl.indices.sumOf { k -> w[k] * seg.sumOf { it[cl[k]].toDouble() } / 3 + w[cl.size + k] * seg.maxOf { it[cl[k]].toDouble() } }
                    1 / (1 + Math.exp(-z))
                }
                val alarm = p.size > 1 && p.zipWithNext().any { (a, c) -> minOf(a, c) >= thr } // 3초 구간 2개 연속
                log("HEAD ${f.nameWithoutExtension} cry=${p.joinToString { "%.2f".format(it) }} alarm=$alarm")
            }
        }
        c.close()
    }

    // file이 있으면 ON_DEVICE PCM 경로(sendAudio), 없으면 차 마이크 경로(request)
    private fun stt(file: File?) {
        val s = SpeechToText(this, SttMode.ON_DEVICE)
        s.initialize(object : SttInit {
            override fun onSuccess() {
                log("STT init ok")
                s.addListener(object : ResultListener {
                    override fun onUpdated(text: String, completed: Boolean) = log("STT text '$text' final=$completed")
                    override fun onUpdatedEpdData(on: Long, off: Long) = log("STT epd $on $off")
                    override fun onStartedRecognition() = log("STT started")
                    override fun onEndedRecognition() = log("STT ended")
                    override fun onError(e: SttError) = log("STT error $e")
                    override fun onReady() = log("STT ready")
                    override fun onUpdatedRms(rms: Double) {}
                })
                s.setLocale(Locale.KOREA)
                if (file == null) { s.request(); return }
                thread {
                    val pcm = file.readBytes() + ByteArray(32000) // 뒤에 1초 무음을 붙여 끝점 검출을 돕는다
                    for (i in pcm.indices step 3200) { s.sendAudio(pcm.copyOfRange(i, minOf(i + 3200, pcm.size))); Thread.sleep(100) }
                    s.completeAudioSend()
                    log("STT sent ${file.name} ${pcm.size} bytes")
                }
            }
            override fun onFailure(e: SttError) = log("STT init fail $e")
        })
    }

    private fun tts(text: String) {
        val t = TextToSpeech(this, TtsMode.ON_DEVICE)
        t.initialize(object : TtsInit {
            override fun onSuccess() {
                log("TTS init ok")
                t.addEventListener(object : EventListener {
                    override fun onStart() = log("TTS start")
                    override fun onReady() = log("TTS ready")
                    override fun onDone() = log("TTS done")
                    override fun onError(e: TtsError) = log("TTS error $e")
                    override fun onStop() = log("TTS stop")
                    override fun onUpdatedRms(rms: Double) {}
                })
                t.setLocale(Locale.KOREA)
                intent.getIntExtra("zone", -1).takeIf { it >= 0 }?.let { t.setAudioZoneId(it); log("TTS zone $it") } // 1 = 뒷좌석 존
                t.speak(text, true)
                log("TTS speak '$text'")
            }
            override fun onFailure(e: TtsError) = log("TTS init fail $e")
        })
    }
}
