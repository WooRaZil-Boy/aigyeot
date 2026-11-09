package kr.aigyeot.app.exec.android

import android.content.Context
import java.io.File
import kr.aigyeot.app.exec.FileLedger
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kr.aigyeot.app.exec.CallbackEntry
import kr.aigyeot.app.exec.ExecIo
import kr.aigyeot.app.exec.OffLock
import kr.aigyeot.app.exec.Sched
import kr.aigyeot.engine.Event

// 실제 Pleos·안드로이드 연결. 판단·규칙은 Player·Voice·Controls에 있고, 여기는 호출을 옮기기만 한다(JVM 시험 밖, 에뮬레이터로 확인).

internal const val TAG = "exec"

/**
 * exec 스레드 하나. 예약과 SDK 콜백을 모두 같은 잠금 안에서 돌린다. Exec.stop 뒤에도 끝내지 않는다:
 * stop 뒤 start가 다시 오므로 예약·콜백이 살아 있어야 하고, 쉬는 HandlerThread는 싸다(Exec 하나에 하나).
 */
internal class ExecThread : Sched {
    val lock = Any()
    private val thread = HandlerThread("exec").apply { start() }
    val handler = Handler(thread.looper)
    private val binderThread = HandlerThread("exec-binder").apply { start() }   // 바인더 호출 전용(잠금 밖). 막혀도 잠금·exec 스레드를 세우지 않는다
    private val binderHandler = Handler(binderThread.looper)
    val off = OffLock(lock) { binderHandler.post(it) }
    private val entry = CallbackEntry(lock, { handler.post(it) }) { e -> Log.w(TAG, "callback", e) }
    /** SDK·시스템 콜백의 유일한 입구(CallbackEntry). 호출 스레드는 잠금을 기다리지 않고 바로 돌아오고, 처리는 exec 스레드에서 들어온 순서대로 잠금 안에서 한다. */
    fun guard(f: () -> Unit) = entry.enter(f)
    fun post(f: () -> Unit) = entry.enter(f)
    override fun after(ms: Long, task: () -> Unit): () -> Unit {
        val r = Runnable { entry.locked(task) }   // 이미 exec 스레드: 다시 post하지 않는다(removeCallbacks가 취소할 수 있게)
        handler.postDelayed(r, maxOf(ms, 0))
        return { handler.removeCallbacks(r) }
    }
    fun quit() { thread.quitSafely(); binderThread.quitSafely() }
}

/** 실제 연결 묶음. [kr.aigyeot.app.exec.Exec]의 공개 생성자가 만든다. */
internal fun androidIo(ctx: Context, clock: () -> Long, post: (Event) -> Unit): ExecIo {
    val ex = ExecThread()
    val car = PleosCar(ctx, ex, clock, post)
    val navi = PleosNavi(ctx, ex, clock, post)
    val tts = PleosTts(ctx, ex)
    val stt = PleosStt(ctx, ex)
    return ExecIo(ex.lock, ex, AndroidAudio(ctx, ex), tts, stt, PcmTap(ex), car, navi,
        start = { runCatching { car.start() }; runCatching { navi.start() }; runCatching { tts.start() }; runCatching { stt.start() } },
        stop = { runCatching { car.stop() }; runCatching { navi.stop() }; runCatching { tts.release() }; runCatching { stt.release() } },
        reemit = { runCatching { car.reemit() }; runCatching { navi.reemit() } },
        lastGear = { car.lastGear() },
        resubscribeSpeed = { ex.post { car.resubscribeSpeed() } },
        ledger = FileLedger(File(ctx.filesDir, "ambient_ledger.json")), ambient = car, carReady = { car.isReady() }, onCarReady = { f -> car.onReady = f })
}
