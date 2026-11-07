package kr.aigyeot.phone.domain.repository

import kotlinx.coroutines.flow.StateFlow
import kr.aigyeot.phone.domain.model.CareFile
import kr.aigyeot.phone.domain.model.Connection
import kr.aigyeot.phone.domain.model.Credentials
import kr.aigyeot.phone.domain.model.creds
import kr.aigyeot.phone.domain.model.PhoneState
import java.io.File

// 저장소 인터페이스(arch-phone-android 2.1). 구현은 data/에 있다. 사실 하나는 저장소 하나가 원본이다(P1).

fun interface Clock {
    fun now(): Long
}

interface Logger {
    fun d(msg: String)
    fun w(msg: String)
    fun e(msg: String, t: Throwable? = null)
}

/**
 * `phone_state.json`(PhoneState)과 지금 연결([Connection])의 원본. 둘은 한 잠금에서 함께 바뀐다.
 * 상태 쓰기 입구는 넷뿐이다: [commit](그 자격일 때만), [commitLocal](연결과 따로인 표시), [switchTo](연결 세대 바꿈), [adopt](다시 읽은 자격).
 * 저장이 확인됐을 때만 메모리를 바꾼다. 받은 메시지는 저장이 참일 때만 ack한다(계약 1.4).
 */
interface PhoneStateRepository {
    val state: StateFlow<PhoneState>
    val connection: StateFlow<Connection>

    /** 시작할 때 디스크의 자격을 읽지 못했다(예외). 연결 없음과 다르다: 녹음·보낼 함·옮길 수유를 지우지 않는다. */
    val credsUnreadableAtStart: Boolean

    val creds: Credentials? get() = connection.value.creds

    fun isCurrent(c: Credentials): Boolean = creds == c

    /** 강제 끊김 정리를 마쳤다: 연결이 없을 때만 GONE으로 둔다(그사이 새로 연결했으면 그대로). */
    fun showGone(deviceId: String)

    /** GONE을 걷는다. `onlyDevice`가 있으면 그 기기의 GONE일 때만. */
    fun hideGone(onlyDevice: String? = null)

    /** `c`가 지금 자격이 아니면(이전 연결의 응답) 아무것도 하지 않고 거짓. `afterSave`는 저장이 된 뒤 같은 잠금 안에서 돈다. */
    suspend fun commit(c: Credentials, afterSave: (() -> Unit)? = null, f: (PhoneState) -> PhoneState): Boolean

    /** 연결과 따로인 일(기록 지우기·수유 옮기기 표시·띠 닫기)만 쓴다. 자격이 없어도 저장한다. */
    suspend fun commitLocal(f: (PhoneState) -> PhoneState): Boolean

    /** 자격과 상태를 함께 바꾼다. `onlyIf`가 있으면 그 자격일 때만. 끊을 때(`next == null`)는 저장이 실패해도 자격은 잊는다. */
    suspend fun switchTo(next: Credentials?, make: (PhoneState) -> PhoneState, onlyIf: Credentials? = null): Boolean

    /** 시작 때 읽지 못한 자격을 다시 읽었다: `recover`가 준 상태를 저장한 뒤 자격을 공개한다. 이미 자격이 있으면 참. */
    suspend fun adopt(c: Credentials, recover: (PhoneState) -> PhoneState?): Boolean

    /** 시작 때 복구한 상태(요청 필요 표시 포함)를 저장한다. 그사이 바뀌었으면 하지 않는다. */
    suspend fun saveRecoveredStart()

    /** 읽지 못한 상태 파일을 옮겨 둔 사본이 있거나 옮기지 못했다: 녹음 정리를 하지 않는다. */
    fun recoveryPending(): Boolean

    /** 사용자가 기록을 지웠다: 옮겨 둔 사본을 지운다. 지웠거나 없으면 참. */
    suspend fun deleteRecoveryCopy(): Boolean

    /** 연결 끊기·401: 사본·임시 파일을 지우고, 지킬 표시가 없으면 원본도 지운다(잠금 안에서 판단). */
    suspend fun discardRecovery()
}

/** `care.json`(CareFile)의 원본. 쓰기 입구는 [update] 하나다. 잠금은 '기록 지우기'의 표시 저장과 사본 정리가 함께 쓴다. */
interface CareRepository {
    val care: StateFlow<CareFile>

    /** 바꾸고 저장한다. 저장이 확인됐을 때만 메모리를 바꾼다. `f`가 null을 돌려주면(규칙 위반) 바꾸지 않고 거짓. */
    suspend fun update(f: (CareFile) -> CareFile?): Boolean

    /** 돌봄 잠금 안에서 `block`. 안에서 [update]·[export]·[deleteCopies]를 부르지 않는다(재진입 안 됨). */
    suspend fun <T> locked(block: suspend () -> T): T

    /** 돌봄 사본(손상 사본·내보낸 CSV·이름을 바꾸기 전에 죽어 남은 임시 파일)을 지운다. 다 지웠거나 없으면 참. */
    suspend fun deleteCopies(): Boolean

    /** 캐시 `export/`에 지난 내보내기를 지우고 새 파일을 쓴다(돌봄 잠금 안: 지운 뒤 지운 기록이 담긴 사본이 다시 생기지 않게). 실패하면 null. */
    suspend fun export(fileName: String, render: (CareFile) -> String): File?
}

/** 디스크의 연결 비밀값(안드로이드는 Keystore로 감싼다, iOS는 Keychain). 두 앱 같은 이름. */
interface CredentialRepository {
    /** 저장된 자격. 없거나 키를 지웠으면 null. 읽지 못하면 예외(끊었다고 확정된 것이 아니다). 시작 때 한 번 막고 읽는다. */
    fun loadBlocking(): Credentials?

    suspend fun load(): Result<Credentials?>

    /** 디스크에 쓴 것이 확인되면 참. null이면 지운다(못 지우면 키를 지운다). */
    suspend fun save(c: Credentials?): Boolean

    /** 읽지 못하는 연결 정보를 버린다(암호문과 키 둘 다). */
    suspend fun reset(): Boolean
}

/** 녹음 파일(`files/songs/`)과 임시 녹음(`cache/recording-*`). 파일 잠금으로 새 녹음의 이동·커밋과 정리를 줄 세운다(7.4·7.5). iOS와 같은 이름. */
interface SongFileStore {
    fun file(name: String): File

    /** 파일 잠금 안에서 `block`. 이 잠금 안에서 상태 잠금을 잡을 수는 있지만 반대 순서로는 잡지 않는다. 안에서 [clean]·[purge]를 부르지 않는다. */
    suspend fun <T> locked(block: suspend () -> T): T

    /** 녹음을 앱 폴더로 복사한다(잠금 안에서). */
    suspend fun copyIn(recording: File, name: String): Boolean

    suspend fun delete(file: File): Boolean

    /** 목록에 없는 녹음 파일을 지운다(상태를 커밋한 뒤에만). 읽지 못한 상태 사본이 있으면 지우지 않는다. 다 지웠으면 참. */
    suspend fun clean(): Boolean

    /** 연결 끊기·401: 상태 사본을 버리고(지킬 표시는 남김) 녹음을 모두 지운다. */
    suspend fun purge()

    /** 새 녹음 시트의 임시 파일. */
    fun newRecording(): File

    /** 앞 프로세스가 남겨 시작 때 지울 임시 녹음인가(만들 때 목록을 정한다). */
    fun isLeftover(recording: File): Boolean

    suspend fun deleteLeftovers()

    /** 기록 지우기: `upTo`(누른 시각)까지 쓰인 임시 녹음을 지운다. 다 지웠거나 없으면 참. 폴더를 못 읽으면 거짓. */
    suspend fun deleteRecordingsUpTo(upTo: Long?): Boolean
}

/** prefs 표시 키. 값의 뜻과 쓰는 곳은 [kr.aigyeot.phone.domain.state.LinkSwitch]가 정한다(화면은 이 표시를 보지 않는다). */
enum class Mark { GONE_CLEANUP_PENDING, LEAVING, PAIR_DONE, RECORDS_ONLY, ASKED_NOTIFICATIONS }

/** 한 번에 쓸 표시 변경. */
sealed interface MarkEdit {
    data class Put(val mark: Mark, val value: String?) : MarkEdit
    data class Flag(val mark: Mark, val on: Boolean) : MarkEdit
    data class Remove(val mark: Mark) : MarkEdit
}

/** 작은 표시 파일(prefs). `commit`은 디스크에 쓴 것을 확인하고, `apply`는 기다리지 않는다. 여러 변경은 한 편집에 쓴다. */
interface LinkMarks {
    val installId: String
    fun get(mark: Mark): String?
    fun flag(mark: Mark): Boolean
    fun commit(vararg edits: MarkEdit): Boolean
    fun apply(vararg edits: MarkEdit)

    /** [commit]을 IO 스레드에서. 저장과 함께 기다리는 곳(연결 마침·GONE 표시)만 쓴다. */
    suspend fun commitInBackground(vararg edits: MarkEdit): Boolean
}

interface PushTokens {
    /** Firebase 설정이 없거나 시간 안에 받지 못하면 null. */
    suspend fun token(): String?
}

interface NetworkWatcher {
    /** 기본 네트워크가 생길 때마다 `onAvailable`. 이미 보고 있으면 아무것도 하지 않는다. */
    fun watch(onAvailable: () -> Unit)
    fun unwatch()
}

/** 잠금 화면 진행 중 알림(C6). 기록이 바뀔 때만 다시 그린다(R15). */
fun interface OngoingNotice {
    fun show(care: CareFile)
}

/** 우리 노래 녹음·듣기(명세서 12장, 구현 계획 4장). 녹음은 앱 화면에서만 한다. */
interface SongRecorder {
    /** 녹음을 시작한다. 실패하면(마이크를 다른 앱이 씀 등) 거짓. 최대 길이·크기에 닿으면 멈추고 `onLimit`. */
    fun start(file: File, onLimit: () -> Unit): Boolean

    /** 멈추고 파일이 쓸 만하면 참. */
    fun stop(): Boolean

    /** 재생을 시작했으면 참. 파일이 없거나 읽지 못하면 거짓. */
    fun play(file: File, onDone: () -> Unit): Boolean

    fun stopPlaying()

    /** 길이(초). 읽지 못하면 null. */
    fun durationS(file: File): Double?
}
