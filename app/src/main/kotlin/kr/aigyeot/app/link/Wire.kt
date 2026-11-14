package kr.aigyeot.app.link

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kr.aigyeot.app.common.DAY
import kr.aigyeot.app.common.rfc3339
import kr.aigyeot.engine.MethodKind
import kr.aigyeot.shared.Envelope
import kr.aigyeot.shared.SoundMethod

// 중계와 주고받는 봉투·소리 이름·재시도 간격. ULID·시각 문자열 같은 범용 도우미는 common/Basics.kt에 있다.

internal const val SENT_ID_MAX = 100 // 결과를 모르는 보낸 id는 최근 100개까지(넣은 순서)
internal const val SENT_ID_KEEP_MS = 10 * 60_000L // 보낸 리포트·알림 id 보관: 늦은 POST가 닿을 수 있는 시간
internal const val KEEP_MS = 7 * DAY // 보낼 함·처리한 id·끊김 표시 보관

/** 판단 코어 음원 종류 ↔ 전송 값(sound.method). 리포트·질문 답이 같이 쓴다. */
internal fun soundMethod(k: MethodKind): SoundMethod = when (k) {
    MethodKind.WHITE_NOISE -> SoundMethod.WHITE_NOISE
    MethodKind.WOMB -> SoundMethod.WOMB
    MethodKind.LULLABY -> SoundMethod.LULLABY
    MethodKind.SONG -> SoundMethod.PARENT_SONG
}

/** [soundMethod]의 거꾸로. 전송 글자(`white_noise` 등)나 UNKNOWN이면 null. */
internal fun methodKindOf(m: SoundMethod): MethodKind? = MethodKind.entries.firstOrNull { soundMethod(it) == m }
internal fun methodKindOf(wire: String?): MethodKind? = SoundMethod.entries.firstOrNull { it.wire == wire }?.let(::methodKindOf)

/** 보낼 함 재시도·재접속 간격 1·2·4…60초. */
internal fun backoffMs(attempts: Int): Long = minOf(60_000L, 1000L shl attempts.coerceIn(0, 6))

/** 보낼 봉투. ts는 넣은 시각이라 같은 id로 다시 보내도 내용이 같다('같은 id 다시 보내기'). */
internal fun envelopeOf(id: String, type: String, body: JsonObject, createdAt: Long, to: String? = null) =
    Envelope(v = 1, id = id, type = type, to = to, ts = rfc3339(createdAt), body = body)

// 판단 코어가 SendToPhone body에 epoch ms로 담는 시각 칸. link가 봉투를 씌울 때 RFC 3339로 바꾼다.
private val TIME_KEYS = setOf("at", "occurred_at", "started_at", "ended_at", "last_feeding_start", "window_start_expected")

internal fun withRfc3339Times(body: JsonObject): JsonObject = JsonObject(
    body.mapValues { (k, v) -> v.timeOrSelf(k) }
)

private fun JsonElement.timeOrSelf(key: String): JsonElement {
    if (key !in TIME_KEYS) return this
    val ms = (this as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return this
    return JsonPrimitive(rfc3339(ms))
}
