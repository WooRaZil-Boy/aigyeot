package kr.aigyeot.phone.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.Serializable
import kr.aigyeot.phone.core.PhoneState
import kr.aigyeot.shared.ProtocolJson
import kr.aigyeot.shared.Role
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 연결 비밀값(계약 7.4). 끊거나 401이면 지운다. */
@Serializable
data class Credentials(val carId: String, val deviceId: String, val token: String, val role: Role)

/**
 * Android Keystore AES-GCM 키로 감싸 앱 전용 SharedPreferences에 둔다. 백업에서 뺀다(매니페스트).
 * 단순화: 계약 7.4는 DataStore지만 버전 목록에 없어 SharedPreferences를 쓴다. 감싸는 방식은 같다.
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("secret", Context.MODE_PRIVATE)

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

    fun load(): Credentials? = runCatching {
        val raw = Base64.decode(prefs.getString(VALUE, null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12)) }
        ProtocolJson.decodeFromString(Credentials.serializer(), cipher.doFinal(raw, 12, raw.size - 12).decodeToString())
    }.getOrNull()

    /** 디스크에 쓴 것이 확인되면 참. `apply()`는 쓰기 실패를 알리지 않아 `commit()`을 쓴다. IO 스레드에서 부른다. */
    fun save(c: Credentials?): Boolean = runCatching {
        if (c == null) return@runCatching prefs.edit().remove(VALUE).commit()
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(ProtocolJson.encodeToString(Credentials.serializer(), c).encodeToByteArray())
        prefs.edit().putString(VALUE, Base64.encodeToString(sealed, Base64.NO_WRAP)).commit()
    }.getOrDefault(false)

    private companion object {
        const val ALIAS = "aigyeot_link"
        const val VALUE = "credentials"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}

/**
 * 폰 상태 한 파일. 임시 파일에 쓰고 fsync한 뒤 이름을 바꾼다. 어느 단계든 실패하면 IOException을 던져,
 * 부르는 쪽이 메모리 상태를 바꾸거나 ack하지 않게 한다(계약 1.4 'ack는 처리를 저장한 뒤').
 * `AtomicFile.finishWrite`는 동기화·이름 바꾸기 실패를 기록만 하고 돌아올 수 있어 쓰지 않는다.
 */
class StateStore(private val file: File) {
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
    fun load(): PhoneState {
        if (!file.exists()) return PhoneState()
        return runCatching { ProtocolJson.decodeFromString(PhoneState.serializer(), file.readText()) }.getOrElse {
            val size = file.length()
            runCatching { backup.delete() }
            val moved = file.renameTo(backup)
            val copied = !moved && runCatching { file.copyTo(backup, overwrite = true) }.isSuccess
            unsafe = !((moved || copied) && backup.isFile && backup.length() == size)
            PhoneState()
        }
    }

    /**
     * 연결 끊기·401: 지킬 것이 없다. 옮겨 둔 백업과, 백업하지 못한 원본까지 지우고 [unsafe]를 푼다(7.4 '연결 끊기: 위 전부를 지운다').
     */
    fun discardRecovery() {
        backup.delete()
        if (unsafe) file.delete()
        unsafe = false
    }

    fun save(s: PhoneState) {
        if (unsafe) throw IOException("백업하지 못한 상태 파일을 덮어쓰지 않는다")
        FileOutputStream(temp).use { out ->
            out.write(ProtocolJson.encodeToString(PhoneState.serializer(), s).encodeToByteArray())
            out.flush()
            out.fd.sync()
        }
        if (!temp.renameTo(file)) throw IOException("상태 파일 이름 바꾸기 실패")
    }
}
