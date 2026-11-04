package kr.aigyeot.app.scenario

import kr.aigyeot.engine.Method
import kr.aigyeot.engine.MethodKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceLoudnessTest {
    @Test fun `백색소음은 exec 생성기와 BS·1770 측정으로 −30 LUFS`() {
        assertEquals(-30.0, whiteNoiseLufs()!!, 0.5)
    }

    @Test fun `못 잰 값(null)은 캐시하지 않고 다음에 다시 잰다, 잰 값은 한 번만`() {
        var calls = 0
        var next: Double? = null
        val l = SourceLoudness { calls++; next }
        val m = Method(MethodKind.LULLABY, null)
        assertNull(l.lufs(m))
        next = -30.2
        assertEquals(-30.2, l.lufs(m)!!, 0.0)   // 실패를 영구히 남기지 않는다
        next = -40.0
        assertEquals(-30.2, l.lufs(m)!!, 0.0)   // 성공값은 캐시
        assertEquals(2, calls)
    }
}
