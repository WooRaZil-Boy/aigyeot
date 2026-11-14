package kr.aigyeot.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** 역할. `relay`는 중계가 만든 메시지의 보낸 쪽. */
@Serializable
enum class Role {
    @SerialName("car")
    CAR,

    @SerialName("owner")
    OWNER,

    @SerialName("guardian")
    GUARDIAN,

    @SerialName("relay")
    RELAY
}

/** 플랫폼. */
@Serializable
enum class Platform {
    @SerialName("pleos")
    PLEOS,

    @SerialName("android")
    ANDROID,

    @SerialName("ios")
    IOS
}

/** 봉투의 `from`. 중계가 만든 메시지는 `device`가 `null`. */
@Serializable
data class Sender(val role: Role, val device: String?)

/**
 * 봉투. 시각(`ts`)은 RFC 3339 문자열 그대로 둔다.
 * `seq`·`car`·`from`은 중계가 채우므로 보낼 때는 비워도 된다. `to`는 차가 `feeding_state`·`song_status`를 한 폰에만 보낼 때 쓴다.
 * `type`은 모르는 값을 받아도 읽히도록 문자열로 둔다. body는 [bodySerializers]로 풀 수 있다.
 */
@Serializable
data class Envelope(
    val v: Int,
    val id: String,
    val seq: Long? = null,
    val type: String,
    val car: String? = null,
    val to: String? = null,
    val from: Sender? = null,
    val ts: String,
    val body: JsonObject
)
