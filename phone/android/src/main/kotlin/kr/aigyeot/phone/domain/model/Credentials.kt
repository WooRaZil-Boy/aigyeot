package kr.aigyeot.phone.domain.model

import kotlinx.serialization.Serializable
import kr.aigyeot.shared.Role

/** 연결 비밀값. 끊거나 401이면 지운다. 같은 값이면 같은 연결 세대다(비동기 결과는 보낼 때 잡은 이 값으로 처리한다). */
@Serializable
data class Credentials(val carId: String, val deviceId: String, val token: String, val role: Role)
