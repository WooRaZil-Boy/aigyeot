package kr.aigyeot.app.exec

/**
 * 차 제어 명령의 입구 하나(차 준비 전 명령 대기와 재시도). 차 연결(Vehicle 초기화)이 끝나기 전에 온 명령을 버리거나 바로 실패시키지 않고
 * 받은 순서대로 미뤘다가 [ready]가 되면(연결 쪽이 [readyNow]를 부른다) 실행한다. [timeoutMs] 안에 준비되지 않으면 그 명령의 [fail]을 부른다.
 * 복원 ④ Boot의 되돌림·뒷창 재잠금이 시작 직후 연결 전에 실패해 조명이 낮은 채 남던 것(ANR 고침으로 연결이 비동기가 되며 드러남)을 막는다.
 * 모든 호출은 실행기 잠금 안에서 한다.
 */
internal class CarGate(private val sched: Sched, private val timeoutMs: Long, private val ready: () -> Boolean) {
    private class Entry(val run: () -> Unit, val fail: () -> Unit) { var cancel: () -> Unit = {} }
    private val queue = ArrayDeque<Entry>()

    /** 준비돼 있고 앞서 미룬 것이 없으면 바로, 아니면 줄에 세운다. */
    fun submit(run: () -> Unit, fail: () -> Unit, defer: Boolean = true) {
        if (queue.isEmpty() && ready()) return run()
        if (!defer) return fail()   // 낮춤은 미루지 않는다: 준비를 기다리는 사이 달래기·장면이 끝나면 주인 없는 낮춤이 뒤늦게 실행돼 조명이 낮은 채 남는다
        val e = Entry(run, fail)
        e.cancel = sched.after(timeoutMs) { if (queue.remove(e)) fail() }
        queue += e
    }

    /** 연결이 준비됐다: 미룬 명령을 받은 순서대로 실행한다. */
    fun readyNow() {
        while (ready()) {
            val e = queue.removeFirstOrNull() ?: return
            e.cancel()
            e.run()
        }
    }

    /** Exec.stop: 미룬 명령을 모두 실패로 닫는다. */
    fun failAll() {
        val all = queue.toList(); queue.clear()
        all.forEach { it.cancel(); it.fail() }
    }
}
