package kr.aigyeot.phone.domain.state

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kr.aigyeot.phone.domain.model.Banner
import kr.aigyeot.phone.domain.model.CareUndo
import kr.aigyeot.phone.domain.model.Credentials
import kr.aigyeot.phone.domain.model.DisconnectRun
import kr.aigyeot.phone.domain.model.LeaveWatch
import kr.aigyeot.phone.domain.model.SAVE_FAILED
import kr.aigyeot.phone.domain.model.dropNotice
import kr.aigyeot.phone.domain.model.withDropNotices
import kr.aigyeot.phone.domain.model.withNoticeLine
import kr.aigyeot.phone.domain.repository.LinkMarks
import kr.aigyeot.phone.domain.repository.Mark
import kr.aigyeot.phone.domain.repository.MarkEdit
import java.util.concurrent.ConcurrentHashMap

// 앱 수명 메모리 상태의 원본(arch-phone-android 4장). 칸마다 뜻 하나(P2), 바꾸는 곳은 그 클래스의 메서드뿐이다(P3).

/** 화면 위쪽 띠·배너·되돌리기. 저장된 버림(`PhoneState.unseenDrops`)이 원본인 줄은 그것에서 계산해 더한다(U54). */
class Notices {
    private val _notice = MutableStateFlow<String?>(null)

    /** 버림·저장 실패 같은 영구 실패 안내. 닫을 때까지 보인다. 줄마다 다른 안내다([withNoticeLine]). */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _careNotice = MutableStateFlow<String?>(null)

    /** 돌봄 기록 알림 띠("앞 수유를 끝냈어요", "연결을 끊었어요…", 7장). 닫을 때까지 보인다. */
    val careNotice: StateFlow<String?> = _careNotice.asStateFlow()

    private val _banner = MutableStateFlow<Banner?>(null)
    val banner: StateFlow<Banner?> = _banner.asStateFlow()

    private val _undo = MutableStateFlow<CareUndo?>(null)

    /** 방금 지운 손 기록·가져온 기록. 탭 화면이 "지웠어요"·"기록 n건을 가져왔어요" + "되돌리기"를 5초 보인다(7장·U64). */
    val undo: StateFlow<CareUndo?> = _undo.asStateFlow()

    /** 사용자가 닫은 버림(메시지 id). 지우는 저장을 기다리는 사이 다른 버림의 저장이 끝나도 다시 띄우지 않는다. */
    private val dismissed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun addLine(line: String) = _notice.update { withNoticeLine(it, line) }

    fun saveFailed() = addLine(SAVE_FAILED)

    /** U54: 저장해 둔 버린 메시지 안내를 띠에 더한다. 떠 있는 안내는 덮지 않고, 같은 글은 한 번. */
    fun showDrops(unseenDrops: Map<String, String>) =
        _notice.update { withDropNotices(it, unseenDrops.filterKeys { id -> id !in dismissed }.values.toList()) }

    /** 뒤늦게 차 반영이 확인돼 버림 표시에서 걷은 수유가 있으면, 남은 수유 버림이 없을 때 띠의 그 줄을 지운다(U54). */
    fun pruneFeedingDrop(unseenDrops: Map<String, String>) {
        val line = dropNotice("feeding_started") ?: return
        if (unseenDrops.any { (id, type) -> type == "feeding_started" && id !in dismissed }) return
        _notice.update { u -> u?.lines()?.filter { it != line }?.joinToString("\n")?.ifEmpty { null } }
    }

    /** 띠를 닫았다. 그때 보이던 버림 id는 다시 띄우지 않는다. */
    fun dismiss(seen: Set<String>) {
        dismissed += seen
        _notice.value = null
    }

    /** 연결 끊김 정리: 옛 연결의 띠(버림 안내 등)와 배너를 걷는다(iOS `wipeAll`의 `notice = nil`과 같음). */
    fun clearForWipe() {
        _banner.value = null
        _notice.value = null
        dismissed.clear()
    }

    /** 새 연결을 마쳤다: 화면 띠를 처음으로. */
    fun clearForPair() {
        _notice.value = null
        _careNotice.value = null
        _banner.value = null
        _undo.value = null
    }

    fun showBanner(b: Banner) {
        _banner.value = b
    }

    /** 배너를 닫는다. `onlyId`가 있으면 그 id의 배너일 때만(그사이 새 배너가 떴으면 새 것은 남긴다, 연결 계약 5.6). */
    fun clearBanner(onlyId: String? = null) = _banner.update { if (onlyId == null || it?.alertId == onlyId) null else it }

    fun showCare(text: String) {
        _careNotice.value = text
    }

    fun clearCare() {
        _careNotice.value = null
    }

    fun showUndo(u: CareUndo) {
        _undo.value = u
    }

    private val _fresh = MutableStateFlow<String?>(null)

    /** 기록 시트가 막 저장한 기록 id(U88). 기록 탭 줄 강조·오늘 하루 띠 새 표시가 한 번 보인 뒤 [clearFresh]로 걷는다(iOS `fresh`). */
    val fresh: StateFlow<String?> = _fresh.asStateFlow()

    fun markFresh(id: String) {
        _fresh.value = id
    }

    /** 그 기록일 때만 걷는다(그사이 다른 기록을 저장했으면 새 것은 남긴다). */
    fun clearFresh(id: String) = _fresh.update { if (it == id) null else it }

    fun clearUndo() {
        _undo.value = null
    }
}

/** 화면이 앞에 있는지(액티비티 onStart~onStop). 폰은 앞에 있을 때만 붙고, 뒤에서는 보낼 함 타이머를 걸지 않는다(2장·7.5). */
class AppVisibility {
    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    /** [kr.aigyeot.phone.domain.usecase.AppLifecycle]만 부른다. */
    fun set(on: Boolean) {
        _visible.value = on
    }
}

/**
 * 연결 전환(연결하기·사용자 끊기·강제 끊김)이 함께 보는 상태와 prefs 표시의 원본. 칸마다 쓰는 곳이 하나다(아래 주석).
 * 메모리 칸은 메인 흐름에서만 읽고 쓴다(`@Volatile`은 다른 스레드에서도 읽는 칸). 화면은 이 표시들을 보지 않고 `Connection`만 본다.
 */
class LinkSwitch(private val marks: LinkMarks, private val notices: Notices) {
    /**
     * 연결 전환 잠금. 토큰·상태·파일·소켓을 함께 바꾸는 것(연결 마침, 끊기·401 정리)을 줄 세운다.
     * 잡는 순서는 이것 → 상태 잠금·파일 잠금이다. 안에서 이것을 다시 잡지 않는다(재진입 안 됨).
     */
    val transitionLock = Mutex()

    /** 401·4001 정리를 하나씩 한다. 사용자 끊기·연결하기는 이것이 끝날 때까지 기다린다. */
    val lostLock = Mutex()

    // ---- Pair가 쓴다 ----

    /** 연결을 시작한 횟수. 끊김 정리를 하는 사이 새 연결을 시작했는지(이미 끝났어도) 본다. */
    var pairGen = 0

    var pairJob: Job? = null

    /** 지금 도는 연결이 옛 연결을 끊고 바꾸는 것인가(확인 화면의 '끊고 연결하기'). */
    @Volatile
    var pairReplacing = false

    fun pairActive() = pairJob?.isActive == true

    // ---- Disconnect가 쓴다(pairDuringLeave는 Pair도 켠다) ----

    /** 사용자가 요청한 끊기. 그 사이 소켓 4001·다른 요청의 401은 강제 해제가 아니라 이 끊기의 결과일 수 있다. */
    val leave = LeaveWatch<Credentials>()
    var leavingRun: Deferred<String?>? = null
    var disconnectJob: Job? = null

    /** 설정 끊기가 도는 동안 연결을 시작했다. 그 연결이 화면을 맡는다(이미 끝나 실패했어도). */
    @Volatile
    var pairDuringLeave = false

    fun disconnectActive() = disconnectJob?.isActive == true

    // ---- WipeLink가 쓴다 ----

    /** 끊을 때 디스크의 비밀값을 지우지 못했다(이 프로세스). 다음 실행이 옛 자격을 다시 읽으니 복구 표시를 지우지 않는다. 새 자격을 저장하면 거짓. */
    @Volatile
    var secretsLeft = false

    // ---- 설정 끊기 결과(Disconnect·LoseConnection이 끝을 적고, 화면이 받으면 지운다) ----

    private val _disconnectRun = MutableStateFlow<DisconnectRun?>(null)
    val disconnectRun: StateFlow<DisconnectRun?> = _disconnectRun.asStateFlow()

    fun setDisconnectRun(r: DisconnectRun?) {
        _disconnectRun.value = r
    }

    /** 도는 끊기가 없으면 지난 결과를 걷는다. */
    fun clearDisconnectRun() {
        if (!disconnectActive()) _disconnectRun.value = null
    }

    // ---- 사용자 끊기 표시(prefs `leaving_device`) ----

    /** 사용자 끊기를 시작한 연결의 기기 id. 값 뒤의 `|done`은 중계가 끊기를 확인했다(204·401)는 뜻이다. */
    fun leavingId(): String? = marks.get(Mark.LEAVING)?.substringBefore('|')

    /** 중계가 사용자 끊기를 확인했다(204·401): 다음 실행은 중계에 묻지 않고 정리를 마친다. */
    fun leavingDone(): Boolean = marks.get(Mark.LEAVING)?.endsWith(LEFT_MARK) == true

    /** 끊기를 시작한다: 중계가 지운 뒤 화면을 보이기 전에 죽어도 다음 실행이 U25 b로 마치게 적는다. 적지 못하면 거짓. */
    fun leavingStarted(c: Credentials): Boolean {
        if (marks.commit(MarkEdit.Put(Mark.LEAVING, c.deviceId))) return true
        marks.commit(MarkEdit.Remove(Mark.LEAVING)) // commit이 실패해도 메모리 값은 바뀌었다: 뒤의 401이 사용자 끊기로 보이지 않게
        return false
    }

    /**
     * 중계가 끊기를 확인했다(204·401): 정리 전에 적어, 정리 도중 죽어도 다음 실행이 중계에 묻지 않고 마치게 한다.
     * 메인 흐름에서 바로 쓴다(작은 파일): IO로 미루면 그사이 실패한 끊기가 지운 표시를 늦게 다시 쓴다.
     */
    fun leftConfirmed(c: Credentials) {
        marks.commit(MarkEdit.Put(Mark.LEAVING, c.deviceId + LEFT_MARK))
    }

    /** 끊기 표시를 걷는다. `now`면 바로 쓴다(곧 죽어도 다음 실행이 다시 보이지 않게). */
    fun leavingCleared(now: Boolean = false) {
        if (now) marks.commit(MarkEdit.Remove(Mark.LEAVING)) else marks.apply(MarkEdit.Remove(Mark.LEAVING))
    }

    // ---- goneCleanupPending(prefs `gone_device`) ----
    // 강제 끊김(401·4001) 정리·확인이 남은 기기 id. 재시작 복구 기록이며 화면 상태가 아니다(화면 GONE은 `Connection.Gone`, 11-08 결정).
    // 수명(P6) — 쓰는 곳은 아래 두 함수뿐이다:
    // | 사건                                         | 표시                                            |
    // | 401·4001 정리 시작(지우기 전)                 | 세움(디스크에 쓴 것을 확인) — goneCleanupStarted |
    // | 표시를 적는 사이 사용자 끊기가 정리를 맡음    | 걷음                                            |
    // | 새 연결이 결과를 맡음·다른 연결로 바뀜        | 비밀값이 남았으면 둠, 아니면 걷음               |
    // | 사용자 끊기가 끝남(같은 기기)                 | 비밀값이 남았으면 둠, 아니면 걷음               |
    // | 사용자 끊기 실패 + 그사이 그 연결의 401·4001  | 끊기 표시를 지우는 같은 편집에서 세움           |
    // | 새 연결 마침                                  | 걷음                                            |
    // | GONE 화면에서 고름                            | 비밀값이 남았으면 둠, 아니면 걷음('기록만 쓰기'와 한 편집) |
    // | 시작 복구: 연결 없음(GONE 화면) 뒤 사용자 끊기 마침, 다른 연결의 옛 표시 | 걷음                 |
    // | 시작 복구: 지금 자격의 표시                   | 둠. 정리를 이어 한다(LoseConnection)            |

    fun goneCleanupPending(): String? = marks.get(Mark.GONE_CLEANUP_PENDING)

    /** 401·4001 정리를 시작한다(지우기 전). 디스크에 쓴 것이 확인되면 참. */
    suspend fun goneCleanupStarted(deviceId: String): Boolean = marks.commitInBackground(MarkEdit.Put(Mark.GONE_CLEANUP_PENDING, deviceId))

    /** 위 표의 나머지 사건. `Acknowledged`만 결과를 돌려준다(쓰지 못하면 거짓, 화면은 그대로 두고 알린다). */
    fun goneCleanup(e: GoneCleanupEvent): Boolean {
        val pending = Mark.GONE_CLEANUP_PENDING
        when (e) {
            GoneCleanupEvent.TakenByLeave, GoneCleanupEvent.Paired, GoneCleanupEvent.Stale -> marks.apply(MarkEdit.Remove(pending))
            GoneCleanupEvent.HandedOver -> if (!secretsLeft) marks.apply(MarkEdit.Remove(pending))
            is GoneCleanupEvent.UserLeft -> if (!secretsLeft && goneCleanupPending() == e.deviceId) marks.apply(MarkEdit.Remove(pending))
            // 끊기 표시 지우기와 복구 표시를 한 편집에 적는다: 그 사이에 죽어도 다음 실행이 정리를 마친다.
            is GoneCleanupEvent.LeaveFailed ->
                marks.commit(*listOfNotNull(MarkEdit.Remove(Mark.LEAVING), MarkEdit.Put(pending, e.deviceId).takeIf { e.lost }).toTypedArray())
            is GoneCleanupEvent.Acknowledged -> {
                // '기록만 계속 쓸게요'는 같은 편집에 적는다: 둘 사이에 죽어 첫 안내로 가지 않게(U25 a). 바로 쓴다: 고른 뒤 곧 죽어도 끊김 화면이 다시 뜨지 않게.
                val before = goneCleanupPending() to marks.flag(Mark.RECORDS_ONLY)
                val edits = listOfNotNull(MarkEdit.Remove(pending).takeIf { !secretsLeft }, MarkEdit.Flag(Mark.RECORDS_ONLY, true).takeIf { e.recordsOnly })
                if (!marks.commit(*edits.toTypedArray())) {
                    // 실패한 commit도 메모리 값은 바꿨다: 디스크(옛 값)와 맞게 되돌린다(회전·재구성 때 화면 판단이 어긋나지 않게).
                    marks.apply(MarkEdit.Put(pending, before.first), MarkEdit.Flag(Mark.RECORDS_ONLY, before.second))
                    return false
                }
            }
        }
        return true
    }

    // ---- 기록만 쓰기·사용자 끊기 끝(U7·U25) ----

    /** 첫 화면에서 '먼저 기록만 쓸게요'를 골랐거나 끊긴 뒤 기록만 쓰기로 했다. 다음 실행부터 바로 탭으로 간다. */
    val recordsOnly: Boolean get() = marks.flag(Mark.RECORDS_ONLY)

    fun chooseRecordsOnly() = marks.apply(MarkEdit.Flag(Mark.RECORDS_ONLY, true))

    /** 설정에서 직접 끊은 뒤(U25 b): 탭(연결 안 됨)의 오늘로 가고 띠를 보인다. */
    fun afterUserDisconnect() {
        chooseRecordsOnly()
        notices.showCare("연결을 끊었어요. 돌봄 기록은 이 휴대폰에 남아 있어요.")
    }

    private companion object {
        const val LEFT_MARK = "|done"
    }
}

/** [LinkSwitch.goneCleanup]의 사건(표 한 줄에 하나). */
sealed interface GoneCleanupEvent {
    data object TakenByLeave : GoneCleanupEvent
    data object HandedOver : GoneCleanupEvent
    data class UserLeft(val deviceId: String) : GoneCleanupEvent
    data class LeaveFailed(val deviceId: String, val lost: Boolean) : GoneCleanupEvent
    data object Paired : GoneCleanupEvent
    data class Acknowledged(val recordsOnly: Boolean) : GoneCleanupEvent
    data object Stale : GoneCleanupEvent
}
