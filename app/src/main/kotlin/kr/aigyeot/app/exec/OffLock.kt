package kr.aigyeot.app.exec

/**
 * 잠금 밖 바인더 레인. SDK(차량·내비·음성) 호출은 바인더를 타서 오래 막힐 수 있다(VHAL이 죽어 CarService가 다시 붙을 때 2차례 ANR).
 * 실행기 잠금([lock])을 쥔 채 바인더를 부르면 그 잠금을 기다리는 main·host·binder 스레드가 함께 선다.
 * 그래서 연결·수명 콜백은 잠금 안에서 장부(상태 칸)만 만지고, 바인더 호출은 [lane](별도 스레드)에서 하고, 결과만 잠금 안으로 돌려받는다.
 * [lane]은 넘긴 일을 호출한 스레드가 아닌 곳에서 차례로 돌린다(실제는 ExecThread의 바인더 전용 HandlerThread).
 */
internal class OffLock(private val lock: Any, private val lane: (() -> Unit) -> Unit) {
    /** [f]를 잠금 밖 레인에서 돌린다. 호출한 쪽은 바로 돌아온다. 던져도 레인을 끊지 않는다. */
    fun run(f: () -> Unit) = lane { runCatching(f) }

    /** 바인더 읽기 [binder]는 레인(잠금 밖)에서, 결과 [done]은 잠금 안에서. 못 읽으면(던지면) null. */
    fun <T> read(binder: () -> T?, done: (T?) -> Unit) = run {
        val v = runCatching(binder).getOrNull()
        synchronized(lock) { runCatching { done(v) } }
    }
}
