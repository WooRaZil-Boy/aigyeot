package kr.aigyeot.app.exec.android

import android.os.SystemClock
import kr.aigyeot.app.detect.MicService
import kr.aigyeot.app.exec.MicTap

/** 창(표본 [n], 끝이 [now])에서 open([openedAt]) 이후 몫의 표본 수. 앞의 나머지는 듣기 전 소리라 버린다. */
internal fun keepTail(n: Int, openedAt: Long, now: Long, rate: Int = 16_000): Int = ((now - openedAt) * rate / 1000).coerceIn(0L, n.toLong()).toInt()

/** MicService.pcmTap을 잡는다. 감지 스레드는 복사만 하고 바로 돌아간다(MicService 주석). */
internal class PcmTap(private val ex: ExecThread) : MicTap {
    private var tap: ((ShortArray, Int) -> Unit)? = null
    override fun open(sink: (ByteArray) -> Unit) {
        val openedAt = SystemClock.elapsedRealtime()
        val t: (ShortArray, Int) -> Unit = { pcm, n ->
            // MicService는 창을 다 읽은 뒤 부르므로 첫 창에는 open 전 소리가 섞인다. 그 몫을 앞에서 잘라 낸다.
            // 한계: 듣기 끝 직전 말이 다음 창으로 넘어가 빠지는 쪽은 알려진 한계(창 975ms 단위)
            val k = keepTail(n, openedAt, SystemClock.elapsedRealtime())
            if (k > 0) {
                val b = ByteArray(k * 2)
                for (i in 0 until k) {
                    val s = pcm[n - k + i].toInt()
                    b[2 * i] = s.toByte()
                    b[2 * i + 1] = (s shr 8).toByte()
                }
                ex.post { sink(b) }
            }
        }
        tap = t
        MicService.pcmTap = t
    }
    override fun close() {
        if (MicService.pcmTap === tap) MicService.pcmTap = null
        tap = null
    }
}
