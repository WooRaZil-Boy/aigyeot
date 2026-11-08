package kr.aigyeot.app.exec.android

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kr.aigyeot.app.exec.ExecIo
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
    fun guard(f: () -> Unit) { synchronized(lock) { try { f() } catch (e: Exception) { Log.w(TAG, "callback", e) } } }
    fun post(f: () -> Unit) { handler.post { guard(f) } }
    override fun after(ms: Long, task: () -> Unit): () -> Unit {
        val r = Runnable { guard(task) }
        handler.postDelayed(r, maxOf(ms, 0))
        return { handler.removeCallbacks(r) }
    }
    fun quit() = thread.quitSafely()
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
        resubscribeSpeed ={ ex.post { car.resubscribeSpeed() } })
}
