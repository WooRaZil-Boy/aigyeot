package kr.aigyeot.app.detect

import kr.aigyeot.engine.CrySegment
import kr.aigyeot.engine.Event
import kr.aigyeot.engine.InputLost
import kr.aigyeot.engine.InputRestored
import kr.aigyeot.engine.PauseReason
import kr.aigyeot.engine.PauseReason.DETECTOR_TIMEOUT
import kr.aigyeot.engine.PauseReason.MIC_ERROR
import kr.aigyeot.engine.PauseReason.SILENCED

/**
 * 감지 판단 층: 창(0.975초 PCM)을 받아 CrySegment·InputLost·InputRestored를 [emit]으로 낸다.
 * 사건 큐·호스트는 모른다. [emit]은 읽기 스레드와 감시 스레드에서 불리니 큐에 넣기만 하는 스레드 안전한 함수여야 한다.
 * 같은 사유의 InputLost는 복귀 전까지 한 번만 낸다.
 */
class CryDetector(head: CryHead, private val scorer: WindowScorer, private val clock: () -> Long, private val emit: (Event) -> Unit) {
    private val tracker = SegmentTracker(head)
    private val pauses = mutableSetOf<PauseReason>()
    private var started = false
    private var graceUntil = 0L // 입력을 새로 연 뒤 첫 구간이 나올 수 있는 가장 이른 때. 이 전에는 무응답으로 치지 않는다
    private var lastSegmentAt = 0L
    private var zeroRun = 0
    private var lastZero = false // 마지막 창이 전부 0: 무음 3초를 확인하는 중
    private var zeroDeadline = 0L // 그 확인 대기가 끝나는 시각. 표본이 더 안 와 SILENCED가 안 나가도 이때부터는 무응답 검사를 한다
    private var closed = false

    /** 장면 재생기가 `cry: true` 녹음을 넣은 시각. 켜 있는 동안 구간에 실린다. */
    @Volatile var injectedCryAt: Long? = null

    /** 입력을 열 때 부른다. 감시 기준 시각을 잡는다. */
    @Synchronized fun start() {
        started = true
        lastSegmentAt = clock()
        graceUntil = lastSegmentAt + FIRST_SEGMENT_MS
    }

    /**
     * 앞 감지기·복원에서 아직 풀리지 않은 끊김 사유를 이어받는다(InputLost는 다시 내지 않는다). 실제 입력이 돌아오면 사유마다 InputRestored를 낸다:
     * MIC_ERROR·SILENCED는 첫 창에서(그 창이 아직 무음이면 SILENCED는 그대로), DETECTOR_TIMEOUT은 구간이 나올 때.
     */
    @Synchronized fun assumePaused(reasons: Set<PauseReason>) {
        pauses += reasons
    }

    /** 지금 풀리지 않은 끊김 사유. 서비스가 끝날 때 다음 감지기에 넘긴다. */
    @Synchronized fun pauses(): Set<PauseReason> = pauses.toSet()

    /** 멈춘 뒤로는 onWindow·finish·checkTimeout이 아무것도 내지 않는다(점수 계산 중이던 결과도 버린다). onMicError도 force 없이는 내지 않는다. */
    @Synchronized fun shutdown() {
        closed = true
    }

    /**
     * 읽기 오류·마이크 서비스 비정상 종료. 러너가 보내는 오류는 잠금 안에서 [shutdown] 여부를 다시 본다(종료 뒤에 InputLost가 나가면 이어받기가 어긋난다).
     * 서비스 [kr.aigyeot.app.detect.MicService.onDestroy]가 보내는 마지막 알림만 [force]로 닫힌 뒤에도 낸다.
     */
    @Synchronized fun onMicError(force: Boolean = false) {
        if (force || !closed) lose(MIC_ERROR)
    }

    /** 창 하나. [window]는 [Detect.WINDOW_SAMPLES]표본. 점수 계산은 잠금 밖에서 해서 감시([checkTimeout])가 막히지 않는다. */
    fun onWindow(window: ShortArray, silenced: Boolean) {
        val end = clock()
        val silent = synchronized(this) {
            if (closed) return
            if (MIC_ERROR in pauses) restore(MIC_ERROR, end) // 새 입력이 돌아옴
            val last = window.indexOfLast { it != 0.toShort() }
            val allZero = last < 0
            val run = zeroRun + (if (allZero) window.size else window.indexOfFirst { it != 0.toShort() }) // 이 창의 앞쪽까지 이은 연속 0
            zeroRun = if (allZero) run else window.size - 1 - last // 다음 창으로 이어질 끝쪽 연속 0
            if (allZero && !lastZero) zeroDeadline = end - Detect.WINDOW_MS + Detect.TIMEOUT_MS // 0 확인 대기의 고정 만료: 첫 0 창 시작 + 3초
            lastZero = allZero
            // isClientSilenced가 없어도 rms 0.0이 3초면 무음. 복귀하는 창의 앞쪽 0까지 합쳐 문턱을 넘으면 먼저 SILENCED를 낸다
            if (silenced || run >= Detect.SILENCE_SAMPLES) lose(SILENCED)
            if (!silenced && !allZero && SILENCED in pauses) restore(SILENCED, end) // 무음 표시가 꺼지고 0이 아닌 표본이 올 때만 복귀(전부 0이면 그대로)
            // rms 0.0인 창은 아직 SILENCED 전이라도 추적기에 넣지 않고 비운다: 무음이 낀 구간을 '조용함'(그침)으로 내보내면 되돌릴 수 없다
            // 마지막 구간 수신 시각은 건드리지 않는다: 가끔 끼는 0 창이 무응답 감시를 미루지 못하게. 무음 확인 중에는 checkTimeout이 기다린다
            if (allZero) tracker.reset()
            silenced || allZero
        }
        if (silent) return
        val scores = scorer.score(window)
        synchronized(this) {
            if (closed) return
            tracker.push(scores, end, clock(), injectedCryAt)?.let(::publish)
        }
    }

    // 구간이 나가는 하나뿐인 길: 수신 시각 갱신과 무응답 복귀가 구간보다 먼저
    private fun publish(seg: CrySegment) {
        lastSegmentAt = seg.at
        graceUntil = 0 // 첫 구간을 받았으니 유예는 끝: 이후 기준은 마지막 구간 시각뿐
        if (DETECTOR_TIMEOUT in pauses) restore(DETECTOR_TIMEOUT, seg.at)
        emit(seg)
    }

    /** 입력이 끝났을 때(녹음 파일). 창이 3개보다 적으면 채운 구간 하나를 낸다. */
    @Synchronized fun finish() {
        if (!closed) tracker.flushShort(clock(), injectedCryAt)?.let(::publish)
    }

    /**
     * 판별기 무응답: 구간이 3초 넘게 안 오면 InputLost(DETECTOR_TIMEOUT). 감시 스레드가 주기적으로 부른다.
     * 입력을 새로 연 뒤(열기·MIC_ERROR·SILENCED 복귀)의 첫 구간은 창 3개가 차야 나오므로 기준을 그 + 2.925초로 늦춘다. 구간을 한 번 받은 뒤(무응답 복귀 포함)에는
     * 마지막 구간 시각부터 3초다. 다른 사유로 멈춘 동안은 보지 않는다.
     */
    @Synchronized fun checkTimeout() {
        if (closed || !started || pauses.isNotEmpty() || (lastZero && clock() <= zeroDeadline)) return // 무음 확인 중에는 기다린다(만료되면 원래 검사)
        val ref = maxOf(lastSegmentAt, graceUntil)
        if (clock() - ref > Detect.TIMEOUT_MS) lose(DETECTOR_TIMEOUT)
    }

    private fun lose(reason: PauseReason) {
        if (!pauses.add(reason)) return
        tracker.reset() // 끊기기 전 창을 복귀 뒤 구간에 섞지 않는다
        emit(InputLost(clock(), reason))
    }

    private fun restore(reason: PauseReason, at: Long) {
        pauses.remove(reason)
        lastSegmentAt = at
        if (reason != DETECTOR_TIMEOUT) graceUntil = at + FIRST_SEGMENT_MS // 무응답 복귀는 구간을 받은 것이라 유예가 없다
        emit(InputRestored(at, reason))
    }

    private companion object {
        val FIRST_SEGMENT_MS = Detect.SEGMENT_WINDOWS * Detect.WINDOW_MS
    }
}
