package kr.aigyeot.app.ui

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 화면에 나가는 글자를 파일 하나로 고정하는 비교 도우미. `src/test/resources/golden/ui/<이름>`과 [actual]을 줄 단위로 맞춘다.
 * 일부러 바꿨다면 시스템 속성 `golden.update=true` 또는 환경 변수 `GOLDEN_UPDATE=true`(Gradle 시험 JVM에는 환경 변수가 넘어간다)로 다시 쓰고 diff를 확인한다.
 */
internal fun assertGolden(name: String, actual: String) {
    val file = File("src/test/resources/golden/ui/$name")
    val text = actual.trimEnd() + "\n"
    if (System.getProperty("golden.update") == "true" || System.getenv("GOLDEN_UPDATE") == "true") {
        file.parentFile.mkdirs()
        file.writeText(text)
        return
    }
    assertTrue(file.exists(), "골든 파일이 없다: ${file.path}(처음이면 golden.update=true로 만든다)")
    assertEquals(file.readText(), text, "화면 글자가 골든 ${file.name}과 다르다")
}
