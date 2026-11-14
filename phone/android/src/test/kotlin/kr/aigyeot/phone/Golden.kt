package kr.aigyeot.phone

import java.io.File
import org.junit.Assert.assertEquals

/** 골든 줄에 쓰는 값 글자: 값이 없으면 "-". */
fun Any?.show(): String = this?.toString() ?: "-"

/**
 * 골든 파일 시험: 순수 함수가 내는 글을 `src/test/resources/golden/<name>`에 고정해 두고 그대로인지 본다.
 * iOS는 자기 골든 파일(`AigyeotTests/Golden/`)을 따로 두고 같은 입력에 같은 글이 나오게 맞춘다. 줄은 `키: 값` 꼴이고 키는 입력 파일 이름과 필드 이름이다.
 *
 * 값을 일부러 바꿀 때만 다시 쓴다: 시스템 속성 `golden.update=true` 또는 환경 변수 `GOLDEN_UPDATE=1`.
 */
object Golden {
    private val update = System.getProperty("golden.update") == "true" || System.getenv("GOLDEN_UPDATE") == "1"

    fun check(name: String, lines: List<String>) {
        val file = File("src/test/resources/golden/$name")
        val actual = lines.joinToString("\n") + "\n"
        if (update) {
            file.parentFile.mkdirs()
            file.writeText(actual)
            return
        }
        assertEquals("골든 파일 ${file.path}와 다르다(의도한 변경이면 GOLDEN_UPDATE=1로 다시 쓴다)", file.readText(), actual)
    }
}
