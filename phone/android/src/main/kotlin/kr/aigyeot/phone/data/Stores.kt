package kr.aigyeot.phone.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kr.aigyeot.phone.domain.model.CareFile
import kr.aigyeot.phone.domain.model.CareJson
import kr.aigyeot.phone.domain.model.Credentials
import kr.aigyeot.phone.domain.model.PhoneState
import kr.aigyeot.phone.domain.repository.CredentialRepository
import kr.aigyeot.phone.domain.repository.Logger
import kr.aigyeot.shared.ProtocolJson
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore AES-GCM 키로 감싸 앱 전용 SharedPreferences에 둔다. 백업에서 뺀다(매니페스트).
 * 단순화: 계약 7.4는 DataStore지만 버전 목록에 없어 SharedPreferences를 쓴다. 감싸는 방식은 같다.
 */
class KeystoreCredentialRepository(context: Context) : CredentialRepository {
    private val prefs = context.getSharedPreferences("secret", Context.MODE_PRIVATE)

    override fun loadBlocking(): Credentials? = read()

    override suspend fun load(): Result<Credentials?> = withContext(Dispatchers.IO) { runCatching { read() } }

    override suspend fun save(c: Credentials?): Boolean = withContext(Dispatchers.IO) { write(c) }

    override suspend fun reset(): Boolean = withContext(Dispatchers.IO) { wipe() }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    /**
     * 저장된 자격. 없거나 키를 지웠으면([revokeKey], 끊기 확정. 백업은 꺼 두어 다른 기기로 암호문만 옮겨지지 않는다) null.
     * 읽지 못하면(Keystore 오류, 암호문이 키와 맞지 않거나 형식이 깨짐) 예외: 끊었다고 확정된 것이 아니므로 부르는 쪽은 '연결 없음'과 달리
     * 녹음 파일·보낼 함·옮길 수유를 지우지 않고, 거듭 실패하면 지우고 새로 연결할지 묻는다(11-07 결정).
     */
    private fun read(): Credentials? {
        val stored = prefs.getString(VALUE, null) ?: return null
        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(ALIAS, null) as? SecretKey ?: return null
        val raw = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, raw, 0, 12)) }
        return ProtocolJson.decodeFromString(Credentials.serializer(), cipher.doFinal(raw, 12, raw.size - 12).decodeToString())
    }

    /** 디스크에 쓴 것이 확인되면 참. `apply()`는 쓰기 실패를 알리지 않아 `commit()`을 쓴다. IO 스레드에서 부른다. */
    private fun write(c: Credentials?): Boolean = runCatching {
        if (c == null) return@runCatching prefs.edit().remove(VALUE).commit() || revokeKey()
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(ProtocolJson.encodeToString(Credentials.serializer(), c).encodeToByteArray())
        prefs.edit().putString(VALUE, Base64.encodeToString(sealed, Base64.NO_WRAP)).commit()
    }.getOrDefault(false)

    /**
     * 읽지 못하는 연결 정보를 버린다('지우고 연결하기', 11-07 결정): 암호문과 키를 둘 다 지운다.
     * 읽기 실패의 원인이 키 고장이면 키를 남겨서는 새 자격도 저장하지 못한다. 다음 [save]가 새 키를 만든다. IO 스레드에서 부른다.
     */
    private fun wipe(): Boolean {
        val removed = runCatching { prefs.edit().remove(VALUE).commit() }.getOrDefault(false)
        return revokeKey() && removed
    }

    /**
     * 연결 끊기·401에서 지우기를 디스크에 쓰지 못했다: 키를 지워 남은 암호문을 다시 못 읽게 한다(7.4).
     * 다음 실행의 [load]는 키가 없어 null(연결 없음)이 되고, 다음 연결은 새 키로 덮어쓴다.
     */
    private fun revokeKey(): Boolean =
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }.isSuccess

    private companion object {
        const val ALIAS = "aigyeot_link"
        const val VALUE = "credentials"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}

/** 폰 상태(`PhoneState`) 파일. */
fun StateStore(file: File) = FileStore(file, PhoneState.serializer(), ProtocolJson) { PhoneState() }

/** 돌봄 기록(`care.json`, phone-care-plan 6장). 키는 iOS와 같은 camelCase다. */
fun CareStore(file: File) = FileStore(file, CareFile.serializer(), CareJson) { CareFile() }

/**
 * JSON 한 파일. 임시 파일에 쓰고 fsync한 뒤 이름을 바꾼다. 어느 단계든 실패하면 IOException을 던져,
 * 부르는 쪽이 메모리 상태를 바꾸거나 ack하지 않게 한다(계약 1.4 'ack는 처리를 저장한 뒤').
 * `AtomicFile.finishWrite`는 동기화·이름 바꾸기 실패를 기록만 하고 돌아올 수 있어 쓰지 않는다.
 */
class FileStore<T>(private val file: File, private val serializer: KSerializer<T>, private val json: Json, private val empty: () -> T) {
    private val temp = File(file.path + ".new")

    /** 읽지 못한 상태 파일을 옮겨 둔 곳. 있으면 녹음 파일 정리를 하지 않는다(그 파일이 아는 녹음일 수 있다). */
    val backup = File(file.path + ".corrupt")

    /**
     * 읽지 못한 원본을 옮기지도 복사하지도 못했다. 이 실행에서는 저장을 거부해 원본을 덮어쓰지 않고, 녹음 정리도 하지 않는다.
     * 다른 이름에 저장하지 않는 이유: 다음 실행에서 어느 파일이 원본인지 모호해진다. 대신 다음 실행이 백업을 다시 시도한다.
     */
    @Volatile
    var unsafe = false
        private set

    /**
     * 옛 판 파일은 새 필드의 기본값과 `ignoreUnknownKeys`로 읽힌다. 파일이 없으면 빈 상태.
     * 있는데 읽지 못하면 원본을 [backup]으로 옮겨(안 되면 복사해) 두고 빈 상태를 돌려준다. 백업이 확인되지 않으면 [unsafe].
     */
    fun load(): T {
        if (!file.exists()) return empty()
        return runCatching { json.decodeFromString(serializer, file.readText()) }.getOrElse {
            val size = file.length()
            runCatching { backup.delete() }
            val moved = file.renameTo(backup)
            val copied = !moved && runCatching { file.copyTo(backup, overwrite = true) }.isSuccess
            unsafe = !((moved || copied) && backup.isFile && backup.length() == size)
            empty()
        }
    }

    /**
     * 연결 끊기·401: 지킬 것이 없다. 백업·원본을 모두 지우고 [unsafe]를 푼다(7.4 '연결 끊기: 위 전부를 지운다').
     * 원본도 지우는 까닭: 빈 상태 저장이 실패했으면 옛 차의 상태가 파일에 남는다. 없는 파일은 빈 상태로 읽힌다.
     * `keepFile`이면 원본은 남긴다(연결과 따로인 기록 지우기 표시를 담았다. 연결 없는 시작은 그 표시만 읽는다).
     */
    fun discardRecovery(keepFile: Boolean = false) {
        backup.delete()
        temp.delete() // 이름을 바꾸기 전에 죽어 남은 임시 파일
        if (!keepFile) file.delete()
        unsafe = false
    }

    /** 이름을 바꾸기 전에 죽어 남은 임시 파일을 지운다(기록 지우기). 지웠거나 없으면 참. 저장과 같은 잠금 안에서 부른다. */
    fun discardTemp(): Boolean = !temp.exists() || temp.delete()

    fun save(s: T) {
        if (unsafe) throw IOException("백업하지 못한 상태 파일을 덮어쓰지 않는다")
        FileOutputStream(temp).use { out ->
            out.write(json.encodeToString(serializer, s).encodeToByteArray())
            out.flush()
            out.fd.sync()
        }
        if (!temp.renameTo(file)) throw IOException("상태 파일 이름 바꾸기 실패")
    }

    /**
     * 저장 실패 처리 도우미 하나(arch-phone-android 6장): IO 스레드에서 저장하고 실패는 로그로 남긴다. 저장이 확인되면 참.
     * `report`가 있으면(화면·일꾼이 부른 쓰기, U·W 범주) 실패를 띠로도 알린다. 연결 전환·시작 저장은 로그만(T·S 범주, 부른 쪽이 처리).
     */
    suspend fun saveLogged(s: T, log: Logger, what: String, report: (() -> Unit)? = null): Boolean =
        withContext(Dispatchers.IO) { runCatching { save(s) }.onFailure { log.e(what, it) }.isSuccess }
            .also { if (!it) report?.invoke() }
}
