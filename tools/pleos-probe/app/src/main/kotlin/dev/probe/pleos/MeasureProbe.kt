package dev.probe.pleos

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import android.app.Activity
import android.media.*
import android.os.Bundle
import android.util.Log
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.sqrt
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit

// 첫날 위험 실측(1103): 5초 듣기 STT 오인식(stt), 녹음 중 재생 되돌아옴·Gleo 무음(rec). 태그 MEASURE.
// 실행: am start --user 10 -n dev.probe.pleos/.MeasureProbe --es cmd stt [--ei n 10] [--ei sec 5] [--es bus bus100_audio_zone_1] [--es file 재생할pcm]
//       am start ... --es cmd rec --ei sec 20 --es tag 이름 [--es bus …] [--es file …] [--ei playAt 3]   (녹음은 files/이름.pcm으로 저장)
class MeasureProbe : Activity() {
    private fun log(m: String) = Log.i("MEASURE", m)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.getStringExtra("cmd")) {
            "stt" -> stt()
            "sttpcm" -> stt(intent.getStringExtra("files")!!.split(","))
            "rec" -> thread { rec() }
        }
    }

    // bus로 sec초 동안 소리를 낸다. file이 있으면 그 PCM을, 없으면 백색소음(±3000)을 반복한다
    private fun play(bus: String?, sec: Int, file: String?) {
        val am = getSystemService(AudioManager::class.java)
        val src = file?.let { f -> File(filesDir, "$f.pcm").readBytes().let { b -> ShortArray(b.size / 2).also { java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) } } }
            ?: ShortArray(16000) { kotlin.random.Random.nextInt(-3000, 3000).toShort() }
        val tr = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(32000).build()
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.address == bus }?.let { tr.setPreferredDevice(it) }
        tr.play()
        val end = System.currentTimeMillis() + sec * 1000L
        var i = 0
        log("PLAY start bus=$bus routed=${tr.routedDevice?.address} file=$file")
        while (System.currentTimeMillis() < end) { val n = minOf(8000, src.size - i); tr.write(src, i, n); i = (i + n) % src.size }
        tr.stop(); tr.release()
        log("PLAY end")
    }

    // files가 있으면 마이크 대신 PCM(16kHz mono s16le, 5초)을 sendAudio로 100ms씩 실시간 속도로 넣는다(공유 PCM 경로). 파일 하나가 시도 하나
    private fun stt(files: List<String>? = null) {
        val n = files?.size ?: intent.getIntExtra("n", 10); val sec = intent.getIntExtra("sec", 5)
        val bus = intent.getStringExtra("bus"); val file = intent.getStringExtra("file")
        val s = SpeechToText(this, SttMode.ON_DEVICE)
        var last = ""; var err = ""; var started = false
        s.initialize(object : SttInit {
            override fun onSuccess() {
                s.addListener(object : ResultListener {
                    override fun onUpdated(text: String, completed: Boolean) { last = text; log("  text '$text' final=$completed") }
                    override fun onUpdatedEpdData(on: Long, off: Long) {}
                    override fun onStartedRecognition() { started = true }
                    override fun onEndedRecognition() {}
                    override fun onError(e: SttError) { err += "$e;" }
                    override fun onReady() {}
                    override fun onUpdatedRms(rms: Double) {}
                })
                s.setLocale(Locale.KOREA)
                thread {
                    for (i in 1..n) {
                        last = ""; err = ""; started = false
                        if (files == null) s.request()
                        Thread.sleep(700)
                        log("LISTEN $i ${files?.get(i - 1) ?: ""}")
                        if (bus != null) thread { play(bus, sec, file) }
                        if (files != null) {
                            val pcm = File(filesDir, files[i - 1] + ".pcm").readBytes()
                            for (k in pcm.indices step 3200) { s.sendAudio(pcm.copyOfRange(k, minOf(k + 3200, pcm.size))); Thread.sleep(100) }
                            s.completeAudioSend()
                        } else {
                            Thread.sleep(sec * 1000L)
                            s.stop()
                        }
                        Thread.sleep(2500)
                        log("RESULT $i started=$started chars=${last.replace(" ", "").length} text='$last' err='$err'")
                    }
                    log("STT DONE")
                }
            }
            override fun onFailure(e: SttError) { log("STT init fail $e") }
        })
    }

    private fun rec() {
        val sec = intent.getIntExtra("sec", 10); val tag = intent.getStringExtra("tag") ?: "rec"
        val bus = intent.getStringExtra("bus"); val file = intent.getStringExtra("file")
        val playAt = intent.getIntExtra("playAt", 2)
        val am = getSystemService(AudioManager::class.java)
        val r = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, 64000)
        log("REC $tag state=${r.state}")
        val cb = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(c: MutableList<AudioRecordingConfiguration>) {
                log("REC $tag callback silenced=${c.firstOrNull { it.clientAudioSessionId == r.audioSessionId }?.isClientSilenced} n=${c.size}")
            }
        }
        am.registerAudioRecordingCallback(cb, null)
        r.startRecording()
        val out = File(filesDir, "$tag.pcm").outputStream()
        val buf = ShortArray(16000)
        repeat(sec) { i ->
            if (i == playAt && (bus != null || file != null)) thread { play(bus, sec - playAt - 1, file) }
            var got = 0
            while (got < buf.size) { val k = r.read(buf, got, buf.size - got); if (k < 0) break; got += k }
            val rms = sqrt(buf.sumOf { it.toDouble() * it } / buf.size)
            val conf = am.activeRecordingConfigurations.firstOrNull { it.clientAudioSessionId == r.audioSessionId }
            log("REC $tag t=$i rms=${"%.1f".format(rms)} silenced=${conf?.isClientSilenced} confFound=${conf != null} recState=${r.recordingState}")
            val b = java.nio.ByteBuffer.allocate(buf.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN); buf.forEach { b.putShort(it) }; out.write(b.array())
        }
        out.close(); am.unregisterAudioRecordingCallback(cb); r.stop(); r.release()
        log("REC $tag DONE")
    }
}
