package kr.aigyeot.phone.domain.usecase

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kr.aigyeot.phone.domain.model.Credentials
import kr.aigyeot.phone.domain.repository.Logger
import kr.aigyeot.phone.domain.repository.PhoneStateRepository
import kr.aigyeot.phone.domain.repository.PushTokens
import kr.aigyeot.phone.domain.repository.RelayClient
import kr.aigyeot.phone.domain.repository.RelayResult
import kr.aigyeot.shared.InviteResponse
import kr.aigyeot.shared.PatchDeviceRequest
import kr.aigyeot.shared.PushTokenPatch

// 이 기기의 중계 설정(초대·인계 알림 받기·푸시 토큰). 401은 어디서든 연결 끊김이다.

/** owner의 '초대 코드 만들기'. */
class Invite(private val state: PhoneStateRepository, private val relay: RelayClient, private val lose: LoseConnection, private val log: Logger) {
    suspend operator fun invoke(): Result<InviteResponse> {
        val c = state.creds ?: return Result.failure(IllegalStateException("연결이 없어요"))
        val r = relay.invite(c.token)
        if (!state.isCurrent(c)) return Result.failure(IllegalStateException("연결이 바뀌었어요"))
        return when (r) {
            is RelayResult.Ok -> Result.success(r.value)
            is RelayResult.Err -> {
                if (r.status == 401) lose.lost(c)
                val text = if (r.code == "invite_limit") "쓰지 않은 초대가 3개 있어요" else "초대를 만들지 못했어요. 잠시 뒤에 다시 해 주세요.".also { log.w("초대 실패 ${r.status} ${r.code}") }
                Result.failure(IllegalStateException(text))
            }
            RelayResult.Offline, RelayResult.Unreached -> Result.failure(IllegalStateException("인터넷이 필요해요"))
        }
    }
}

/** guardian의 '인계 알림 받기'. 중계가 원본이고 응답으로 맞춘다. 실패하면 문구. */
class SetHandoffAlerts(private val state: PhoneStateRepository, private val relay: RelayClient, private val lose: LoseConnection) {
    suspend operator fun invoke(on: Boolean): String? {
        val c = state.creds ?: return null
        return when (val r = relay.patchMe(c.token, PatchDeviceRequest(handoffAlerts = on))) {
            is RelayResult.Ok -> null.also { state.commit(c) { it.copy(handoffAlerts = r.value.handoffAlerts) } }
            is RelayResult.Err -> if (r.status == 401) null.also { lose.lost(c) } else "알림 설정을 바꾸지 못했어요. 잠시 뒤에 다시 해 주세요."
            RelayResult.Offline, RelayResult.Unreached -> "인터넷이 필요해요"
        }
    }
}

/** 푸시 토큰 등록. 앱을 열 때마다 같은 값이어도 보낸다. 실패하면 다음 열기 때 다시. */
class RegisterPush(
    private val state: PhoneStateRepository,
    private val relay: RelayClient,
    private val lose: LoseConnection,
    private val tokens: PushTokens,
    private val scope: CoroutineScope
) {
    /** 연결 반복이 붙기 전에 부른다. */
    suspend fun now(c: Credentials) = register(c, tokens.token())

    /** FCM이 새 토큰을 줬다. */
    fun onToken(token: String) {
        scope.launch { state.creds?.let { register(it, token) } }
    }

    private suspend fun register(c: Credentials, token: String?) {
        val body = PatchDeviceRequest(pushToken = token?.let { PushTokenPatch.Set(it) } ?: PushTokenPatch.Keep)
        when (val r = relay.patchMe(c.token, body)) {
            is RelayResult.Ok -> state.commit(c) { it.copy(handoffAlerts = r.value.handoffAlerts) }
            is RelayResult.Err -> if (r.status == 401) lose.lost(c)
            RelayResult.Offline, RelayResult.Unreached -> Unit
        }
    }
}
