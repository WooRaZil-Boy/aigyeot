package kr.aigyeot.app.detect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.content.ContextCompat
import kr.aigyeot.engine.Event
import kr.aigyeot.engine.InputLost
import kr.aigyeot.engine.MicPath
import kr.aigyeot.engine.PauseReason
import kr.aigyeot.engine.MicService as MicServiceEvent

/**
 * 마이크 Foreground Service(foregroundServiceType=microphone, START_NOT_STICKY). 주행 내내 입력을 읽어 감지 사건을 낸다.
 *
 * **앱 화면이 있을 때만 [start]를 부른다**(명세서 6장): 화면 없는 시작은 막혀 있다(11-02 실측). 화면에서 시작하면 앱이 뒤로 가도 녹음이 이어진다.
 * 예기치 않게 멈추면 InputLost(MIC_ERROR) + MicService(running=false)를 낸다(C69). 프로세스가 죽으면 서비스는 저절로 다시 뜨지 않으며(START_NOT_STICKY),
 * 그 복원은 호스트의 몫이다(계약 2.4).
 */
class MicService : Service() {
    private var runner: DetectorRunner? = null
    private var detector: CryDetector? = null
    private var path: MicPath? = null
    @Volatile private var intentional = false
    private var ended = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { intentional = true; stopSelf(); return START_NOT_STICKY }
        if (runner != null) return START_NOT_STICKY // 이미 듣는 중
        path = intent?.getStringExtra(EXTRA_PATH)?.let { runCatching { MicPath.valueOf(it) }.getOrNull() }
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) { // 앱 화면 없이 시작하면 SecurityException(11-02 실측)
            intentional = false
            stopSelf()
            return START_NOT_STICKY
        }
        // 판별층·모델 준비는 MicService(running=true)보다 먼저 한다. 실패하면 onDestroy가 InputLost(MIC_ERROR) + MicService(running=false)만 낸다(계약 2.4, C69·C70)
        val prep = prepareDetect(
            { assets.open(HEAD_ASSET).bufferedReader().use { it.readText() } },
            { sourceFactory?.invoke(this) ?: MicPcmSource(this) },
            { MediaPipeScorer(this) },
        )
        if (prep == null) { stopSelf(); return START_NOT_STICKY }
        val (head, source, sc) = prep
        emit(MicServiceEvent(clock(), true, path))
        val d = CryDetector(head, sc, clock, emit).also { detector = it }
        d.assumePaused(pendingPauses); pendingPauses = emptySet() // 앞 서비스·복원에서 풀리지 않은 사유: 실제 입력이 돌아오면 사유마다 InputRestored
        // 판별기는 읽기 스레드가 끝나는 길에서 놓는다(처리 중인 classify와 close가 겹치지 않게)
        runner = DetectorRunner(source, d, onClose = { sc.close() }) { unexpected -> if (!unexpected) intentional = true; stopSelf() }.also { it.start() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        val wasIntentional = intentional // 읽기 스레드가 멈추며 바꾸기 전에 먼저 읽는다
        runner?.stop() // 이 뒤로 감지기는 사건을 내지 않는다(InputRestored가 서비스 꺼짐 뒤에 나가지 않게)
        if (!ended) {
            ended = true
            if (!wasIntentional) { // 오류·시스템이 멈춤
                if (detector != null) detector?.onMicError(force = true) else emit(InputLost(clock(), PauseReason.MIC_ERROR))
                pendingPauses = pendingPauses + PauseReason.MIC_ERROR
            }
            detector?.let { pendingPauses = pendingPauses + it.pauses() } // 다음 서비스가 이어받는다
            emit(MicServiceEvent(clock(), false, null))
        }
        super.onDestroy()
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "듣기", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL).setContentTitle("아이곁이 듣는 중이에요")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setOngoing(true).build()
    }

    companion object {
        const val ACTION_STOP = "kr.aigyeot.app.detect.STOP"
        private const val EXTRA_PATH = "path"
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL = "mic"
        const val HEAD_ASSET = "cry_head24_rt.json"

        /** 호스트가 서비스를 시작하기 전에 건다. 사건 큐에 넣기만 하는 스레드 안전한 함수. */
        @Volatile var emit: (Event) -> Unit = {}
        @Volatile var clock: () -> Long = System::currentTimeMillis
        /** 장면 재생(시뮬레이션 입력): 마이크 대신 녹음 파일 입력원을 넣는다. null이면 마이크. */
        @Volatile var sourceFactory: ((Context) -> PcmSource)? = null
        /**
         * 아직 풀리지 않은 끊김 사유. 서비스가 끝날 때 자동으로 채워지고, 다음 시작 때 새 감지기가 이어받는다.
         * 프로세스가 죽었다가 복원하는 호스트는 시작 전에 직접 넣는다(예: 복원한 MIC_ERROR).
         */
        @Volatile var pendingPauses: Set<PauseReason> = emptySet()

        /** 앱 화면이 있을 때만 부른다. [path]는 시작 경로(홈 버튼·알림·앱 실행). */
        fun start(context: Context, path: MicPath) =
            ContextCompat.startForegroundService(context, Intent(context, MicService::class.java).putExtra(EXTRA_PATH, path.name))

        fun stop(context: Context) = context.startService(Intent(context, MicService::class.java).setAction(ACTION_STOP))
    }
}

/** 판별층 → 입력원 → 모델 순으로 준비한다. 하나라도 실패하면 먼저 만든 것을 닫고 null. 서비스에서 떼어 JVM에서 시험한다. */
internal fun <S> prepareDetect(readHead: () -> String, makeSource: () -> PcmSource, makeScorer: () -> S): Triple<CryHead, PcmSource, S>?
    where S : WindowScorer, S : java.io.Closeable {
    val head = try { CryHead.parse(readHead()) } catch (e: Exception) { return null }
    val source = try { makeSource() } catch (e: Exception) { return null }
    val sc = try { makeScorer() } catch (e: Throwable) { source.close(); return null }
    return Triple(head, source, sc)
}
