package kr.aigyeot.shared

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

/**
 * 차·폰·중계가 함께 쓰는 Json 설정(contracts-link.md 0장 '공통 표기').
 * - 필드는 snake_case, 모르는 필드는 무시한다.
 * - 기본값이 있는 필드(선택 필드)는 값이 기본값이면 쓰지 않고, 기본값이 없는 null 가능 필드는 `null`을 그대로 쓴다.
 *   그래서 계약의 "또는 null"은 명시적 `null`, "선택"은 빠짐으로 나간다.
 */
@OptIn(ExperimentalSerializationApi::class)
val ProtocolJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = true
    encodeDefaults = false
    namingStrategy = JsonNamingStrategy.SnakeCase
}
