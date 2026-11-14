package kr.aigyeot.phone.data

import android.content.ContentResolver
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kr.aigyeot.phone.domain.model.PUSH_TOKEN_TIMEOUT_MS
import kr.aigyeot.phone.domain.repository.LinkMarks
import kr.aigyeot.phone.domain.repository.Logger
import kr.aigyeot.phone.domain.repository.Mark
import kr.aigyeot.phone.domain.repository.MarkEdit
import kr.aigyeot.phone.domain.repository.NetworkWatcher
import kr.aigyeot.phone.domain.repository.PushTokens
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.coroutines.resume

class AndroidLogger(private val tag: String) : Logger {
    override fun d(msg: String) {
        Log.d(tag, msg)
    }

    override fun w(msg: String) {
        Log.w(tag, msg)
    }

    override fun e(msg: String, t: Throwable?) {
        Log.e(tag, msg, t)
    }
}

/** prefs `install`의 표시. 값의 뜻은 domain이 정하고, 여기서는 키 이름과 쓰기만 맡는다. */
class PrefsLinkMarks(context: Context) : LinkMarks {
    private val prefs = context.getSharedPreferences("install", Context.MODE_PRIVATE)

    override val installId: String by lazy {
        prefs.getString("id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("id", it).apply() }
    }

    override fun get(mark: Mark): String? = prefs.getString(key(mark), null)

    override fun flag(mark: Mark): Boolean = prefs.getBoolean(key(mark), false)

    override fun commit(vararg edits: MarkEdit): Boolean = edit(edits).commit()

    override fun apply(vararg edits: MarkEdit) = edit(edits).apply()

    override suspend fun commitInBackground(vararg edits: MarkEdit): Boolean = withContext(Dispatchers.IO) { edit(edits).commit() }

    private fun edit(edits: Array<out MarkEdit>) = prefs.edit().also { e ->
        for (x in edits) when (x) {
            is MarkEdit.Put -> e.putString(key(x.mark), x.value)
            is MarkEdit.Flag -> e.putBoolean(key(x.mark), x.on)
            is MarkEdit.Remove -> e.remove(key(x.mark))
        }
    }

    private fun key(mark: Mark) = when (mark) {
        Mark.GONE_CLEANUP_PENDING -> "gone_device" // 강제 끊김(401·4001) 정리·확인이 남은 기기 id(재시작 복구 기록)
        Mark.LEAVING -> "leaving_device" // 사용자 끊기를 시작한 연결의 기기 id, 중계가 확인하면 뒤에 `|done`
        Mark.PAIR_DONE -> "pair_done_device" // 완료 화면을 아직 넘기지 않은 연결의 기기 id
        Mark.RECORDS_ONLY -> "records_only"
        Mark.ASKED_NOTIFICATIONS -> "asked_notifications"
    }
}

class FcmPushTokens(private val context: Context, private val log: Logger) : PushTokens {
    /** Firebase 설정 파일이 없으면 null. */
    @Suppress("DEPRECATION") // Firebase Messaging 26의 register() 대신 token을 쓴다
    override suspend fun token(): String? {
        if (FirebaseApp.getApps(context).isEmpty()) return null
        return withTimeoutOrNull(PUSH_TOKEN_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                FirebaseMessaging.getInstance().token.addOnCompleteListener { t ->
                    cont.resume(if (t.isSuccessful) t.result else null)
                }
            }
        }.also { if (it == null) log.w("FCM 토큰 없음") }
    }
}

class AndroidNetworkWatcher(private val context: Context) : NetworkWatcher {
    private var callback: ConnectivityManager.NetworkCallback? = null

    override fun watch(onAvailable: () -> Unit) {
        if (callback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onAvailable()
        }
        context.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(cb)
        callback = cb
    }

    override fun unwatch() {
        val cb = callback ?: return
        context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb)
        callback = null
    }
}

/**
 * 기록 가져오기: 고른 문서를 메모리로만 읽는다(앱 폴더에 두지 않는다). `limit`바이트까지만 읽어
 * 너무 큰 파일은 나머지를 읽지 않는다. 열거나 읽지 못하면 null(import.failed).
 */
suspend fun readDocument(resolver: ContentResolver, uri: String, limit: Int): ByteArray? = withContext(Dispatchers.IO) {
    runCatching {
        resolver.openInputStream(Uri.parse(uri))?.use { s ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(DEFAULT_BUFFER_SIZE)
            while (out.size() < limit) {
                val n = s.read(buf, 0, minOf(buf.size, limit - out.size()))
                if (n < 0) break
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    }.getOrNull()
}
