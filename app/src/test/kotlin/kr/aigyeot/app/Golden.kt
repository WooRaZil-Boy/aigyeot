package kr.aigyeot.app

import java.io.File
import kotlin.test.fail

/**
 * 골든 파일 비교: 시험이 만든 글을 저장해 둔 글과 한 글자도 다르지 않게 맞춘다.
 * 의도한 변경이면 `GOLDEN_UPDATE=1`(또는 `-Dgolden.update=true`)로 시험을 돌려 파일을 다시 쓴다. 파일이 아직 없을 때도 그렇게 처음 만든다.
 * 파일은 시험 JVM의 작업 폴더(모듈 폴더) 기준 [dir]/[name]에 둔다.
 */
internal object Golden {
    private val update get() = System.getProperty("golden.update") == "true" || System.getenv("GOLDEN_UPDATE") == "1"

    fun check(dir: String, name: String, actual: String) {
        val file = File(dir, name)
        val text = actual.trimEnd('\n') + "\n"
        if (update) {
            file.parentFile!!.mkdirs()
            file.writeText(text)
            return
        }
        if (!file.exists()) fail("골든 파일이 없다: ${file.path} (GOLDEN_UPDATE=1로 시험을 돌려 만든다)")
        val want = file.readText()
        if (want != text) {
            val a = want.lines()
            val b = text.lines()
            val i = (0 until maxOf(a.size, b.size)).first { a.getOrNull(it) != b.getOrNull(it) }
            fail("골든 파일 ${file.path} 와 다르다(${i + 1}번째 줄)\n저장: ${a.getOrNull(i)}\n지금: ${b.getOrNull(i)}")
        }
    }
}
