package dev.probe.pleos

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.util.Log
import kotlin.concurrent.thread
import kotlin.math.sqrt

// 마이크 용도 Foreground Service. tag로 어떤 경로(화면에서 시작·부팅·백그라운드)에서 시작했는지 남긴다
class MicService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val tag = intent?.getStringExtra("tag") ?: "fg-activity"
        try {
            startForeground(2, micNotification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            Log.i("MICTEST", "$tag startForeground(MICROPHONE) ok")
        } catch (e: Exception) {
            Log.i("MICTEST", "$tag startForeground(MICROPHONE) ERR ${e.javaClass.simpleName} ${e.message}")
            stopSelf(); return START_NOT_STICKY
        }
        thread { recordAndReport(this, tag, 8); stopSelf() }
        return START_NOT_STICKY
    }
}

fun micNotification(ctx: Context): Notification {
    val nm = ctx.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel("mic", "mic", NotificationManager.IMPORTANCE_LOW))
    return Notification.Builder(ctx, "mic").setContentTitle("mic probe").setSmallIcon(android.R.drawable.ic_btn_speak_now).build()
}

// seconds초 동안 1초마다 음량을 남기고, 시스템이 이 녹음을 무음 처리했는지(isClientSilenced)를 함께 남긴다
fun recordAndReport(ctx: Context, tag: String, seconds: Int) {
    val rate = 16000
    val buf = ShortArray(rate)
    val rec = try {
        AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, rate * 2)
    } catch (e: Exception) { Log.i("MICTEST", "$tag AudioRecord init ERR ${e.javaClass.simpleName} ${e.message}"); return }
    Log.i("MICTEST", "$tag AudioRecord state=${rec.state}")
    if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return }
    val am = ctx.getSystemService(AudioManager::class.java)
    try { rec.startRecording() } catch (e: Exception) { Log.i("MICTEST", "$tag startRecording ERR $e"); rec.release(); return }
    repeat(seconds) { i ->
        val got = rec.read(buf, 0, buf.size)
        val rms = if (got > 0) sqrt(buf.take(got).sumOf { it.toDouble() * it } / got) else -1.0
        val conf = am.activeRecordingConfigurations.firstOrNull { it.clientAudioSessionId == rec.audioSessionId }
        Log.i("MICTEST", "$tag t=$i read=$got rms=${"%.1f".format(rms)} recState=${rec.recordingState} silenced=${conf?.isClientSilenced} confFound=${conf != null}")
    }
    rec.stop(); rec.release()
    Log.i("MICTEST", "$tag done")
}
