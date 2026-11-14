package kr.aigyeot.app.link

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kr.aigyeot.engine.noLeak

/** 중계 프롬프트·도구 스키마(relay/src/llm.ts)의 모든 식별자(도구 이름·주제 값·소리 종류·필드 이름)가 글에 새면 군말 검사가 잡는다. 목록을 따로 두지 않고 길이 규칙으로 잡으므로 스키마가 바뀌어도 어긋나지 않는다. */
class LeakIdentifiersTest {
    @Test fun everySchemaIdentifierIsCaught() {
        val text = listOf("../relay/src/llm.ts", "relay/src/llm.ts").map(::File).first { it.exists() }.readText()
        val names = Regex("""name: "([a-z_]+)"""").findAll(text).map { it.groupValues[1] }
        val fields = Regex("""(\w+): \{ type: """").findAll(text).map { it.groupValues[1] }
        val enums = Regex("""enum: \[([^\]]+)]""").findAll(text).flatMap { m -> Regex(""""([a-z_]+)"""").findAll(m.groupValues[1]).map { it.groupValues[1] } }
        val ids = (names + fields + enums).toSet()
        assertTrue(ids.size >= 21, "식별자를 읽지 못했다: $ids") // 도구 4 + 필드 3 + 주제 10 + 소리 4
        for (id in ids) {
            assertFalse(noLeak(id), id)
            assertFalse(noLeak("$id 쪽으로 답해요."), id)
        }
    }
}
