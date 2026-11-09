package kr.aigyeot.app.exec

/** 속성이 없는 차의 실패(ERROR_NOT_SUPPORTED_PROPERTY). 차가 실제로 답한 것이다. 자동 색 미지원 판정과 준비 판정이 이 함수 하나를 쓴다(계약 4.2). */
internal fun notSupported(e: Exception): Boolean = e.toString().contains("ERROR_NOT_SUPPORTED_PROPERTY")

/**
 * 차 연결 준비 판정(계약 4.2 ① '차 준비 전 명령과 재시도'). Vehicle 2.3.2의 initialize는 서비스 연결을 비동기로 시작하고 완료 콜백이 없어(공식 문서),
 * 돌아온 것만으로는 준비를 알 수 없다. 그래서 가벼운 읽기 하나([request])를 [intervalMs]마다 보내, 차가 실제로 답한 때를 준비로 본다.
 * 준비로 세는 응답은 성공([request]의 `ok`)과 [notSupported] 실패(`fail`)뿐이다. 그 밖의 실패 콜백·예외·응답 없음은 아직 준비 전이라 계속 묻는다.
 * [alive]가 거짓이면(stop·다시 start) 멈춘다. 준비되면 [onReady]를 한 번 부른다. 모든 호출은 실행기 잠금 안.
 */
internal class ReadyProbe(
    private val sched: Sched,
    private val intervalMs: Long,
    private val alive: () -> Boolean,
    private val request: (ok: () -> Unit, fail: (Exception) -> Unit) -> Unit,
    private val onReady: () -> Unit,
) {
    var ready = false
        private set

    fun start() { attempt() }

    private fun attempt() {
        if (ready || !alive()) return
        try { request({ answered() }, { e -> if (notSupported(e)) answered() }) } catch (e: Exception) { /* 연결 전: 다음 간격에 */ }
        sched.after(intervalMs) { attempt() }
    }

    private fun answered() {
        if (ready || !alive()) return
        ready = true
        onReady()
    }
}
