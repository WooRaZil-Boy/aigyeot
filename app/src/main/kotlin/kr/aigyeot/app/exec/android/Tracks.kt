package kr.aigyeot.app.exec.android

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRouting
import android.media.AudioTrack
import android.media.MediaPlayer
import kr.aigyeot.app.exec.Track
import kr.aigyeot.app.exec.WhiteNoise

/** 음원 백색소음을 실시간으로 만들어 흘린다. 쓰는 스레드는 트랙이 멈추면(pause) 기다린다. */
internal class NoiseTrack(
    attrs: AudioAttributes,
    dev: AudioDeviceInfo?,
    private val ex: ExecThread,
    onRouted: (String?) -> Unit,
    private val onError: () -> Unit
) : Track {
    private val rate = WhiteNoise.RATE
    private val track = AudioTrack.Builder().setAudioAttributes(attrs)
        .setAudioFormat(
            AudioFormat.Builder().setSampleRate(rate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
        )
        .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), rate / 5 * 2))
        .setTransferMode(AudioTrack.MODE_STREAM).build()
    private val noise = WhiteNoise()
    private val size = rate / 20 // 50ms
    private val lock = Object()

    @Volatile private var running = true

    @Volatile private var paused = false
    private val writer = Thread({ loop() }, "noise")

    init {
        try {
            dev?.let { track.setPreferredDevice(it) }
            track.addOnRoutingChangedListener(AudioRouting.OnRoutingChangedListener { r -> ex.guard { onRouted(r.routedDevice?.address) } }, ex.handler)
        } catch (e: Exception) {
            track.release()
            throw e
        }
    }

    private fun loop() {
        val b = ShortArray(size)
        while (running) {
            synchronized(lock) { while (paused && running) lock.wait() } // 멈춘 트랙의 write는 바로 돌아오므로 난수를 쉬지 않고 만들지 않게 기다린다
            if (!running) return
            noise.fill(b)
            var off = 0
            while (off < b.size && running && !paused) { // 짧게 돌아온 write는 남은 만큼 이어 쓴다. 멈췄으면 버린다
                val n = track.write(b, off, b.size - off)
                if (n < 0) {
                    if (running) ex.post(onError)
                    return
                } // exec 스레드로 넘긴다: 거기서 release()가 이 스레드를 join해도 자기 자신을 기다리지 않는다
                off += n
            }
        }
    }

    override fun start(): String? {
        track.setVolume(0f) // 소리 없이 시작한다. 이득은 Player가 경로를 안 뒤에 gain()으로 정한다
        track.play()
        track.write(ShortArray(size), 0, size) // 무음 첫 버퍼를 먼저 써야 경로가 정해진다(프로브 SoundProbe.noise와 같은 순서)
        val routed = track.routedDevice?.address
        writer.start()
        return routed
    }
    override fun routed(): String? = track.routedDevice?.address
    override fun gain(g: Float) {
        track.setVolume(g)
    }
    override fun pause() {
        paused = true
        track.pause()
    }
    override fun resume() {
        track.play()
        synchronized(lock) {
            paused = false
            lock.notifyAll()
        }
    }
    override fun release() {
        running = false
        synchronized(lock) { lock.notifyAll() }
        runCatching {
            track.pause()
            track.flush()
            track.stop()
        } // 바로 멈추고(pause·flush), stop이 막힌 write를 깨운다
        runCatching { writer.join(300) }
        track.release()
    }
}

/** 음원 파일(자장가 wav, 부모 노래 m4a). 노래는 이미 −30 LUFS 사본이다(SongLoudness). */
internal class FileTrack(
    attrs: AudioAttributes,
    dev: AudioDeviceInfo?,
    ex: ExecThread,
    onRouted: (String?) -> Unit,
    onError: () -> Unit,
    source: (MediaPlayer) -> Unit
) : Track {
    private val mp = MediaPlayer()
    init {
        try {
            mp.apply {
                setAudioAttributes(attrs)
                source(this)
                isLooping = true // 56초보다 짧은 음원도 endAt까지(끝은 Player가 정한다)
                dev?.let { setPreferredDevice(it) }
                setOnErrorListener { _, _, _ ->
                    ex.guard(onError)
                    true
                }
                addOnRoutingChangedListener({ r -> ex.guard { onRouted(r.routedDevice?.address) } }, ex.handler)
                setVolume(0f, 0f) // 소리 없이 시작한다. 이득은 Player가 gain()으로 처음 정한다
                prepare()
            }
        } catch (e: Exception) {
            mp.release()
            throw e
        } // source·prepare가 던지면 MediaPlayer를 놓고 다시 던진다
    }
    override fun start(): String? {
        mp.start()
        return mp.routedDevice?.address
    }
    override fun routed(): String? = mp.routedDevice?.address
    override fun gain(g: Float) = mp.setVolume(g, g)
    override fun pause() = mp.pause()
    override fun resume() = mp.start()
    override fun release() {
        runCatching { mp.stop() }
        mp.release()
    }
}
