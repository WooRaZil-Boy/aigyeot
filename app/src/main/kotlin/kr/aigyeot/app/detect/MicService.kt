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
import kr.aigyeot.app.R
import kr.aigyeot.engine.CrySegment
import kr.aigyeot.engine.Event
import kr.aigyeot.engine.InputLost
import kr.aigyeot.engine.MicPath
import kr.aigyeot.engine.MicService as MicServiceEvent
import kr.aigyeot.engine.PauseReason
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 마이크 Foreground Service(foregroundServiceType=microphone, START_NOT_STICKY). 주행 내내 입력을 읽어 감지 사건을 낸다.
 *
 * **앱 화면이 있을 때만 [start]를 부른다**: 화면 없는 시작은 막혀 있다. 화면에서 시작하면 앱이 뒤로 가도 녹음이 이어진다.
 * 예기치 않게 멈추면 InputLost(MIC_ERROR) + MicService(running=false)를 낸다. 프로세스가 죽으면 서비스는 저절로 다시 뜨지 않으며(START_NOT_STICKY),
 * 그 복원은 호스트의 몫이다.
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
        } catch (e: Exception) { // 앱 화면 없이 시작하면 SecurityException
            intentional = false
            stopSelf()
            return START_NOT_STICKY
        }
        // 판별층·모델 준비는 MicService(running=true)보다 먼저 한다. 실패하면 onDestroy가 InputLost(MIC_ERROR) + MicService(running=false)만 낸다
        val prep = prepareDetect(
            { assets.open(HEAD_ASSET).bufferedReader().use { it.readText() } },
            { sourceFactory?.invoke(this) ?: MicPcmSource(this) },
            { MediaPipeScorer(this) },
        )
        if (prep == null) { stopSelf(); return START_NOT_STICKY }
        val (head, source, sc) = prep
        emit(MicServiceEvent(clock(), true, path))
        val out = emit
        val d = CryDetector(head, sc, clock) { e -> out(e); if (e is CrySegment) segmentTaps.forEach { it(e) } }.also { detector = it }
        // 앞 서비스·복원에서 풀리지 않은 사유: 실제 입력이 돌아오면 사유마다 InputRestored. assumePausedNow와 겹쳐도 사유를 잃지 않게 한 잠금 안에서
        synchronized(Companion) { d.assumePaused(pendingPauses); pendingPauses = emptySet(); running = d }
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
            }
            synchronized(Companion) { // assumePausedNow와 겹쳐도 사유를 잃지 않게 한 잠금 안에서
                running = null
                if (!wasIntentional) pendingPauses = pendingPauses + PauseReason.MIC_ERROR
                detector?.let { pendingPauses = pendingPauses + it.pauses() } // 다음 서비스가 이어받는다
            }
            emit(MicServiceEvent(clock(), false, null))
        }
        super.onDestroy()
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "듣기", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL).setContentTitle("아이곁이 듣는 중이에요")
            .setSmallIcon(R.drawable.ic_stat_aigyeot).setOngoing(true).build()
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
        /** 읽은 PCM(16kHz mono s16)을 그대로 넘긴다. exec STT가 쓴다. 감지 스레드에서 부르므로 빨리 돌아와야 하고, 배열은 다시 쓰이니 필요하면 복사한다. */
        @Volatile var pcmTap: ((ShortArray, Int) -> Unit)? = null
        /** 판단 코어로 보낸 울음 구간을 그대로 넘긴다(읽기 전용, debug 빌드의 시연·측정 화면이 읽는 울음 확률 선). 감지 스레드에서 부르므로 빨리 돌아와야 한다. */
        val segmentTaps = CopyOnWriteArraySet<(CrySegment) -> Unit>()   // 구독자마다 하나(지울 때 남의 것을 지우지 않게). 비어 있으면 아무 일도 하지 않는다
        /** 지금 도는 서비스의 감지기. 서비스가 시작·끝날 때만 바꾼다. */
        @Volatile internal var running: CryDetector? = null

        /** 서비스가 돌고 있으면 감지기의 풀리지 않은 사유, 아니면 null(호스트 대조용). */
        fun runningPauses(): Set<PauseReason>? = running?.pauses()

        /** 돌고 있는 감지기에 '엔진에만 있는 사유'를 넣는다. 그사이 서비스가 끝났으면 다음 서비스가 이어받게 [pendingPauses]에 넣는다. */
        fun assumePausedNow(reasons: Set<PauseReason>) = synchronized(this) {
            running?.assumePaused(reasons) ?: run { pendingPauses = pendingPauses + reasons }
        }

        /** 앱 화면이 있을 때만 부른다. [path]는 시작 경로(홈 버튼·알림·앱 실행). */
        fun start(context: Context, path: MicPath) =
            ContextCompat.startForegroundService(context, Intent(context, MicService::class.java).putExtra(EXTRA_PATH, path.name))

        fun stop(context: Context) = context.startService(Intent(context, MicService::class.java).setAction(ACTION_STOP))
    }
}

/** 판별층 → 입력원 → 모델 순으로 준비한다. 하나라도 실패하면 먼저 만든 것을 닫고 null. 서비스에서 떼어 JVM에서 시험한다. */
internal fun <S> prepareDetect(readHead: () -> String, makeSource: () -> PcmSource, makeScorer: () -> S): Triple<CryHead, PcmSource, S>?
    where S : WindowScorer, S : Closeable {
    val head = try { CryHead.parse(readHead()) } catch (e: Exception) { return null }
    val source = try { makeSource() } catch (e: Exception) { return null }
    val sc = try { makeScorer() } catch (e: Throwable) { source.close(); return null }
    return Triple(head, source, sc)
}
