package kr.aigyeot.app.exec

/**
 * SDK·시스템 콜백의 입구 하나. 콜백은 호출 스레드(main일 수 있다)에서 실행기 잠금을 직접 잡지 않는다: 받은 일을 [lane](실행기 스레드, 순서가 있는 한 줄)에
 * 넘기고 바로 돌아오며, 거기서 잠금을 잡아 받은 순서대로 처리한다. 다른 스레드(Exec.handle 등)가 잠금 안에서 차량 바인더에 막혀 있어도 main 콜백이
 * 같이 서지 않는다(ANR 실측 두 차례의 같은 종류). 처리 중 던지면 [onError]로 알리고 이어 간다.
 */
internal class CallbackEntry(private val lock: Any, private val lane: (() -> Unit) -> Unit, private val onError: (Exception) -> Unit = {}) {
    /** 콜백 진입: 호출한 스레드는 기다리지 않고 돌아온다. 처리는 [lane]에서 잠금 안에서, 들어온 순서대로. */
    fun enter(f: () -> Unit) = lane { locked(f) }

    /** 이미 실행기 스레드에서(예약 작업) 잠금 안에서 바로 돌린다. */
    fun locked(f: () -> Unit) = synchronized(lock) {
        try {
            f()
        } catch (e: Exception) {
            onError(e)
        }
    }
}
