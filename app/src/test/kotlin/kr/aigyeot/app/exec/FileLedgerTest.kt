package kr.aigyeot.app.exec

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kr.aigyeot.engine.AmbientValue

// 낮춤 장부 파일(ambient_ledger): 프로세스가 죽어도 남고, 임시 파일을 남기지 않으며, 깨진 파일은 빈 장부다
class FileLedgerTest {
    private val dir = Files.createTempDirectory("ledger").toFile().also { it.deleteOnExit() }
    private val file = File(dir, "ambient_ledger.json")

    @Test fun `쓴 값은 새 인스턴스(프로세스 재시작)가 읽고 지우면 파일이 없어진다`() {
        val e = LedgerEntry(AmbientValue(70, 20), AmbientValue(0xFFFFFF, 0x3050A0))
        FileLedger(file).write(e)
        assertEquals(e, FileLedger(file).read())
        assertFalse(File(dir, "ambient_ledger.json.tmp").exists(), "임시 파일을 남기지 않는다")
        FileLedger(file).write(null)
        assertNull(FileLedger(file).read())
        assertFalse(file.exists())
    }

    @Test fun `깨진 파일은 빈 장부로 읽는다`() {
        file.writeText("{깨짐")
        assertNull(FileLedger(file).read())
    }
}
