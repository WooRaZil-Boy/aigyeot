package kr.aigyeot.app.exec

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kr.aigyeot.engine.AmbientValue

/** 실행기 낮춤 장부: `DimAmbient`로 실제로 쓴 값(종류별 before·set). 값은 밝기 정수, 색은 24비트 RGB. */
@Serializable
internal data class LedgerEntry(val brightness: AmbientValue? = null, val color: AmbientValue? = null) {
    val isEmpty: Boolean get() = brightness == null && color == null
}

/** 장부 저장소. 프로세스가 죽어도 남는다(장면 상태는 저장하지 않아 판단 코어가 되돌릴 값을 모르는 경우를 실행기가 맡는다). 비어 있으면 null. */
internal interface LedgerStore {
    fun read(): LedgerEntry?
    fun write(e: LedgerEntry?) // null = 지움
}

internal object NoLedger : LedgerStore {
    override fun read(): LedgerEntry? = null
    override fun write(e: LedgerEntry?) {}
}

/** 앱 저장소의 작은 파일 하나(`ambient_ledger`). 임시 파일에 쓴 뒤 바꿔치기해 원자적으로 쓴다. 못 읽으면(없음·깨짐) 빈 장부. */
internal class FileLedger(private val file: File) : LedgerStore {
    override fun read(): LedgerEntry? = runCatching { Json.decodeFromString<LedgerEntry>(file.readText()).takeUnless { it.isEmpty } }.getOrNull()

    override fun write(e: LedgerEntry?) {
        if (e == null || e.isEmpty) {
            file.delete()
            return
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(Json.encodeToString(LedgerEntry.serializer(), e))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
