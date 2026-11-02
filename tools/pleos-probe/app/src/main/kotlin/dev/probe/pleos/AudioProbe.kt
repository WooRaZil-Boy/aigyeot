package dev.probe.pleos

import android.app.Activity
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import kotlin.concurrent.thread
import kotlin.math.sqrt
import kotlin.random.Random

// 차 마이크로 소리를 듣고(울음 감지 입력) 차 스피커로 백색소음을 내는 경로가 3rd-party 앱에 열려 있는지 확인하는 일회용 실측
class AudioProbe : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        thread {
            try {
                val rate = 16000
                val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val rec = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 4)
                Log.i("PROBE", "MIC state=${rec.state} (1=initialized)")
                rec.startRecording()
                val buf = ShortArray(rate)
                val n = rec.read(buf, 0, buf.size)
                val rms = sqrt(buf.take(maxOf(n, 0)).sumOf { it.toDouble() * it } / maxOf(n, 1))
                Log.i("PROBE", "MIC read=$n samples rms=${"%.1f".format(rms)} recording=${rec.recordingState}")
                rec.stop(); rec.release()
            } catch (e: Exception) {
                Log.i("PROBE", "MIC failed ${e.javaClass.simpleName} ${e.message}")
            }
            try {
                val rate = 22050
                val noise = ShortArray(rate * 2) { (Random.nextInt(-3000, 3000)).toShort() }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(noise.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
                track.write(noise, 0, noise.size)
                track.play()
                Thread.sleep(500)
                Log.i("PROBE", "SPEAKER playState=${track.playState} (3=playing) head=${track.playbackHeadPosition}")
                Thread.sleep(1600); track.stop(); track.release()
            } catch (e: Exception) {
                Log.i("PROBE", "SPEAKER failed ${e.javaClass.simpleName} ${e.message}")
            }
        }
    }
}
