package kr.aigyeot.app.detect

import kotlin.concurrent.thread

/**
 * 입력원을 창 단위로 읽어 [CryDetector]에 넣는 스레드 둘(읽기, 감시). 마이크 서비스와 녹음 파일 재생이 함께 쓴다.
 * 끝나면 [onEnd]를 한 번 부른다. unexpected = 읽기·열기 오류(→ 이미 InputLost(MIC_ERROR)가 나갔다), false = 파일 끝. [stop] 뒤에는 부르지 않는다.
 */
class DetectorRunner(
    private val source: PcmSource,
    private val detector: CryDetector,
    private val onClose: () -> Unit = {}, // 입력원을 놓은 뒤 읽기 스레드가 부른다(판별기 닫기)
    private val onEnd: (unexpected: Boolean) -> Unit = {},
) {
    @Volatile private var stopped = false
    private var reader: Thread? = null

    fun start() {
        reader = thread(name = "cry-reader") { run() }
        thread(name = "cry-watchdog", isDaemon = true) {
            while (!stopped && reader?.isAlive == true) {
                try { Thread.sleep(500) } catch (_: InterruptedException) { return@thread }
                detector.checkTimeout()
            }
        }
    }

    /** 일부러 멈춘다(오류로 치지 않는다). */
    fun stop() { stopped = true; detector.shutdown(); runCatching { source.cancel() }; reader?.interrupt() }

    fun join() { reader?.join() }

    private fun run() {
        val buf = ShortArray(Detect.WINDOW_SAMPLES)
        var unexpected = false
        try {
            source.start()
            detector.start()
            while (!stopped) {
                val n = source.read(buf)
                if (stopped) break // 읽는 사이 멈췄다면 결과를 버린다
                if (n == PcmSource.END) { detector.finish(); break }
                if (n < 0) { if (!stopped) { detector.onMicError(); unexpected = true }; break }
                detector.onWindow(buf, source.isSilenced())
            }
        } catch (_: InterruptedException) {
            // stop()이 부른 것
        } catch (e: Exception) {
            if (!stopped) { detector.onMicError(); unexpected = true }
        } finally {
            runCatching { source.close() }
            runCatching { onClose() }
        }
        if (!stopped) onEnd(unexpected) // 일부러 멈춘 뒤에는 알리지 않는다
    }
}
