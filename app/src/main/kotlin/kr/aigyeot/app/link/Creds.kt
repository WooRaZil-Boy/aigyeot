package kr.aigyeot.app.link

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kr.aigyeot.app.common.CarPaths
import kr.aigyeot.app.link.db.RelayCredentialEntity
import kr.aigyeot.app.store.retryingSuspend

private const val TAG = "Link"
private const val RELINK = "휴대폰을 다시 연결해 주세요."            // 9장 차 토큰 무효

internal class Cred(val carId: String, val deviceId: String, val token: String)

/**
 * 차 자격(8.2, 8.6): 읽기·등록·무효 처리. 자격은 link가 나눠 쓰는 두 공유 상태 중 하나다(다른 하나는 LinkStatus 흐름).
 * 소켓 닫기·연결 코드 비우기·받기 뒤 처리·깨우기는 생성자 람다로 받는다.
 */
internal class Creds(
    private val context: Context,
    private val room: LinkRoom,
    private val relay: Relay,
    private val cipher: TokenCipher,
    private val clock: () -> Long,
    private val status: MutableStateFlow<LinkStatus>,
    private val rx: Mutex,                              // 받기는 한 번에 하나, seq 순서(8.3). Link가 만든 하나를 함께 쓴다
    private val onRegistered: () -> Unit,               // 등록 전에 쌓인 보낼 함·받을 노래를 바로
    private val dropSocket: () -> Unit,
    private val resetPairing: () -> Unit,               // 화면이 열려 있으면 연결 코드를 비우고 코드 반복을 깨운다
    private val after: (Applied) -> Unit,
) {
    private val dao = room.dao
    @Volatile var cred: Cred? = null
        private set
    private val credLock = Mutex()
    @Volatile private var nextRegisterAt = 0L                   // 차 등록 간격(8.6 '차 토큰 무효'): 이 시각 전에는 등록하지 않는다
    @Volatile private var registerTries = 0

    /** 연결·코드 받기가 성공하면 등록 간격을 처음으로. */
    fun resetRegisterTries() { registerTries = 0 }

    /** 저장된 자격만 읽는다(등록하지 않는다): 자격이 없으면 확인할 방이 없다. */
    fun storedCred(): Cred? = cred ?: dao.credential()?.let { row ->
        runCatching { cipher.open(row.tokenEncrypted) }.getOrNull()?.let { Cred(row.carId, row.deviceId, it) }?.also { cred = it }
    }

    suspend fun credential(): Cred? {
        cred?.let { return it }
        val row = dao.credential()
        if (row != null) {
            runCatching { cipher.open(row.tokenEncrypted) }.getOrNull()?.let { return Cred(row.carId, row.deviceId, it).also { c -> cred = c } }
            Log.w(TAG, "차 토큰을 풀지 못함: 차 토큰 무효로 처리")   // 키가 없어졌다(다른 기기로 복원 등). 옛 방은 쓸 수 없다
            invalidate()
        }
        return credLock.withLock {
            cred ?: if (clock() < nextRegisterAt) null else when (val r = relay.registerCar().also { nextRegisterAt = clock() + backoffMs(registerTries++) }) {
                is Res.Ok -> {
                    val v = r.value
                    val sealed = runCatching { cipher.seal(v.carToken) }.getOrElse { Log.e(TAG, "차 토큰 감싸기 실패", it); return@withLock null }
                    room.tx { dao.clearCredential(); dao.insertCredential(RelayCredentialEntity(v.carId, v.deviceId, sealed)) }
                    Cred(v.carId, v.deviceId, v.carToken).also {
                        cred = it; status.update { s -> s.copy(registered = true) }
                        onRegistered()   // 등록 전에 쌓인 보낼 함·받을 노래를 바로
                    }
                }
                is Res.Err -> null.also { Log.w(TAG, "차 등록 실패 status=${r.status} code=${r.code}") }
                Res.Offline -> null
            }
        }
    }

    /**
     * 차 토큰 무효(401, 닫기 4001): 자격·PhoneLink를 지우고 옛 방 순번을 버린다. 다음 계기에 새로 등록한다(8.6, G7).
     * [c]는 401을 받은 요청의 자격. 그사이 다시 등록했으면 옛 토큰의 늦은 401로 새 방을 지우지 않는다.
     */
    suspend fun invalidate(c: Cred? = null) {
        if (!credLock.withLock { (c == null || cred == null || cred === c).also { if (it) cred = null } }) return
        dropSocket()   // 옛 토큰 소켓
        resetPairing()
        status.update { it.copy(registered = false, error = RELINK) }   // 정리가 실패해도 다시 연결 안내는 남긴다
        rx.withLock {
            // C302 W: 다 실패하면 던져 부른 일꾼이 다시 한다(옛 자격 행이 남아 다음 401이 이 정리를 다시 부른다)
            val (out, gone) = retryingSuspend("새 방 정리") { Applied().let { it to room.resetForNewRoom(clock(), it) } }
            gone.forEach { CarPaths.songDownload(context, it).delete(); CarPaths.songFile(context, it).delete() }
            after(out)
        }
    }
}
