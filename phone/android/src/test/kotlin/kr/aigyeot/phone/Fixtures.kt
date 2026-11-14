package kr.aigyeot.phone

import kr.aigyeot.shared.Envelope
import kr.aigyeot.shared.ProtocolJson
import java.io.File

// shared/의 예제 파일. 복사본 없이 차 앱·폰 앱이 같은 파일을 읽는다(Gradle 시험은 모듈 폴더에서 돈다).
private val SHARED_RESOURCES = File("../../shared/src/test/resources")

/** shared 예제 파일. [path]는 `src/test/resources` 아래 경로다. */
fun sharedFile(path: String): File = File(SHARED_RESOURCES, path)

/** `protocol/<name>.json`의 메시지 봉투. */
fun fixture(name: String): Envelope =
    ProtocolJson.decodeFromString(Envelope.serializer(), sharedFile("protocol/$name.json").readText())
