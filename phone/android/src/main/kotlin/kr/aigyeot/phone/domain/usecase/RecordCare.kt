package kr.aigyeot.phone.domain.usecase

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kr.aigyeot.phone.domain.model.APP_ZONE
import kr.aigyeot.phone.domain.model.CareEvent
import kr.aigyeot.phone.domain.model.CareFile
import kr.aigyeot.phone.domain.model.CareKind
import kr.aigyeot.phone.domain.model.FeedSide
import kr.aigyeot.phone.domain.model.FeedTrigger
import kr.aigyeot.phone.domain.model.PhoneState
import kr.aigyeot.phone.domain.model.delete
import kr.aigyeot.phone.domain.model.editOngoing
import kr.aigyeot.phone.domain.model.endNow
import kr.aigyeot.phone.domain.model.endUnknown
import kr.aigyeot.phone.domain.model.markedMigratedFeedings
import kr.aigyeot.phone.domain.model.migrated
import kr.aigyeot.phone.domain.model.purged
import kr.aigyeot.phone.domain.model.restartNotice
import kr.aigyeot.phone.domain.model.start
import kr.aigyeot.phone.domain.model.undoDelete
import kr.aigyeot.phone.domain.model.upsert
import kr.aigyeot.phone.domain.model.withoutCleared
import kr.aigyeot.phone.domain.repository.CareRepository
import kr.aigyeot.phone.domain.repository.Clock
import kr.aigyeot.phone.domain.repository.Logger
import kr.aigyeot.phone.domain.repository.PhoneStateRepository
import kr.aigyeot.phone.domain.repository.SongFileStore
import kr.aigyeot.phone.domain.state.Notices

/**
 * 돌봄 기록(phone-care-plan 6장) 쓰기. 연결과 상관없이 쓴다(U7). `care.json` 쓰기는 모두 [save] 한 입구를 지난다(P7):
 * 지우다 만 '기록 지우기'(R10)를 이 저장에서 먼저 마친다. 사용자가 바꾼 기록은 저장 뒤 차로 보낼지 본다(R6 '돌봄 기록 저장·고치기 뒤').
 * 동작은 앱 범위에서 끝난다(화면이 사라져도).
 */
class RecordCare(
    private val care: CareRepository,
    private val state: PhoneStateRepository,
    private val reconcile: ReconcileCareSummary,
    private val songs: SongFileStore,
    private val notices: Notices,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val log: Logger,
    /** debug 빌드만 시연 시드를 받는다(U50). */
    private val allowDebugSeed: Boolean,
) {
    private val zone get() = APP_ZONE.id

    /**
     * 돌봄 기록을 바꾸고 저장한다. 저장이 확인됐을 때만 메모리를 바꾼다. `f`가 null이면(규칙 위반) 바꾸지 않는다.
     * 지우다 만 '기록 지우기'를 이 저장에서 먼저 마친다. 그때의 id만 빼므로 그 뒤 적은 기록은 남는다(R10, iOS `updateCare`와 같음).
     */
    suspend fun save(f: (CareFile) -> CareFile?): Boolean {
        val wiping = state.state.value.clearingCareIds
        val since = state.state.value.clearingSince
        val ok = care.update { cur ->
            val base = wiping?.let { cur.withoutCleared(it) } ?: cur
            // 바꾼 뒤에도 뺀다: 아직 보이던 지울 기록을 고쳐 저장해도 되살아나지 않는다(iOS `updateCare`와 같음).
            f(base)?.let { n -> wiping?.let { n.withoutCleared(it) } ?: n }
        }
        // 표시 끄기에 실패해도 다음에 같은 id를 다시 빼므로 새 기록은 지우지 않는다. 마쳤으면 어느 저장이든(별명 포함) 빈 요약을 넣는다(R19).
        if (ok && wiping != null) {
            endClear(wiping, since)
            reconcile.summaryOnly()
        }
        return ok
    }

    /** 사용자가 돌봄 기록을 바꿨다: 저장한 뒤 차로 보낼지 본다. */
    private suspend fun commit(f: (CareFile) -> CareFile?): Boolean {
        if (!save(f)) return false
        syncToCar(FeedTrigger.CARE_SAVED)
        return true
    }

    /**
     * R5·R19를 정해진 계기에서만(R6, [FeedTrigger]): 수유 옮기기(R11)를 먼저 하고, 계기가 시작할 때의 자격으로 보낼 함을 맞춘다.
     * 연결이 없거나 owner가 아니면 하지 않는다.
     */
    fun syncToCar(why: FeedTrigger) {
        scope.launch {
            val c = state.creds ?: return@launch
            migrateIfNeeded()
            reconcile.feedingAndSummary(c)
            log.d("수유 보내기 확인: $why")
        }
    }

    /** 앱 시작 계기(R6·R19). 시작 때 자격을 못 읽었으면 연결 전 다시 읽기가 성공했을 때 한 번 더 부른다. */
    fun syncAtStart() = syncToCar(FeedTrigger.APP_START)

    /** R11·U22: 수유 기록 옮기기를 아직 못 했으면 한다(시작과 보내기 계기마다, iOS와 같음). state.json의 보낸 표시를 먼저 채운다. */
    suspend fun migrateIfNeeded() {
        if (care.care.value.migratedFeedings) return
        val mark: (PhoneState) -> PhoneState = { it.markedMigratedFeedings() }
        if (!(state.creds?.let { state.commit(it, f = mark) } ?: state.commitLocal(mark))) return
        // 옛 연결에서 넘어온 원본도 함께 옮긴다. 다 옮겼으면 그 원본은 지운다(남아도 해롭지 않다).
        if (save { it.migrated(state.state.value.feedings + state.state.value.migrationFeedings, zone) } && state.state.value.migrationFeedings.isNotEmpty()) {
            state.commitLocal { it.copy(migrationFeedings = emptyList()) }
        }
    }

    /** R10: 지우다 만 '기록 지우기'를 마친다(시작·앞으로 올 때, 그사이 죽었거나 care.json 저장 실패). 연결이 없어도 한다. */
    suspend fun finishClear() {
        if (state.state.value.clearingCareIds != null) save { it } // 저장이 지울 id를 빼고, 마치면 빈 요약을 넣는다
    }

    /** R8 '앱을 열 때': 30일 지난 지운 기록을 정리한다. */
    suspend fun purgeOld() = save { it.purged(clock.now()) }

    /**
     * 지울 id를 뺀 care.json을 저장한 뒤: 상태 손상 사본 → 녹음(지운 상태 기준) → 누른 때까지의 임시 녹음 → 돌봄 사본·내보낸 CSV를 지우고(7.4 '캐시'),
     * 지운 것이 확인되면 표시를 끈다. 그사이 죽거나 사본을 못 지우면 표시가 남아 다음 저장·시작에 다시 한다.
     */
    private suspend fun endClear(ids: List<String>, since: Long?) {
        val cleaned = state.deleteRecoveryCopy() && songs.clean() && songs.deleteRecordingsUpTo(since) && care.deleteCopies()
        // 그사이 다시 지웠으면(id나 누른 시각이 바뀜) 그 요청의 정리가 끝내게 남긴다.
        if (cleaned) state.commitLocal { if (it.clearingCareIds == ids && it.clearingSince == since) it.copy(clearingCareIds = null, clearingSince = null) else it }
    }

    // ---- 사용자 동작 ----

    /** 빠른 버튼·타이머 시작(R1·R2·U21). 앞 기록을 닫았으면 띠를 띄운다. */
    fun start(kind: CareKind, side: FeedSide? = null) {
        val at = clock.now() // 누른 시각: 저장 잠금을 기다리는 사이 기록 지우기를 눌러도 그 전에 누른 기록은 지울 대상이다(R10)
        scope.launch {
            var closed: CareEvent? = null
            val ok = commit { f -> f.start(kind, at, zone, side).also { closed = it.closedPrevious }.file }
            closed?.takeIf { ok }?.let { notices.showCare(restartNotice(it)) }
        }
    }

    /** 타이머·잠금 화면 '끝내기'. 최대 길이를 넘었으면 끝 모름으로 닫힌다(U14). 알림 수신기가 저장이 끝날 때까지 기다리게 작업을 돌려준다. */
    fun end(id: String): Job = scope.launch { commit { it.endNow(id, clock.now()) } }

    /** 타이머 '끝 시각 모름으로 닫기'(U14). */
    fun endUnknown(id: String) {
        scope.launch { commit { it.endUnknown(id, clock.now()) } }
    }

    /** 빠른 기록·고치기 저장. 규칙(R3)에 맞지 않으면 거짓(화면이 먼저 `problem`으로 안내한다). */
    suspend fun saveEvent(e: CareEvent, editing: Boolean = false): Boolean = scope.async { commit { it.upsert(e, clock.now(), editing) } }.await()

    /** 타이머: 저장 잠금 안에서 지금 진행 중인 그 기록에만 바꿀 것을 얹는다. */
    suspend fun editOngoing(id: String, f: (CareEvent) -> CareEvent): Boolean = scope.async { commit { it.editOngoing(id, clock.now(), f) } }.await()

    /** 기록 지우기(밀기·화면 읽기). 저장하지 못하면 `onFail`(화면이 밀린 줄을 되돌린다). */
    fun delete(id: String, onFail: () -> Unit = {}) {
        scope.launch { if (commit { it.delete(id, clock.now()) }) notices.showUndo(id) else onFail() }
    }

    fun undoDelete(id: String) {
        scope.launch { commit { it.undoDelete(id) } }
    }

    fun setNickname(name: String?) {
        scope.launch { commit { it.copy(childNickname = name?.trim()?.takeIf(String::isNotEmpty)) } } // care.json 저장 뒤 계기(R19)
    }

    fun setSleepDiaper(on: Boolean) {
        scope.launch { commit { it.copy(sleepDiaperEnabled = on) } } // 요약의 잠·기저귀 칸이 바뀐다(R19)
    }

    /** debug 시연 시드(U50, `src/debug`의 수신기)만 부른다. 사용자 저장과 같은 길(저장 뒤 계기)로 통째로 바꾼다. release에서는 하지 않는다. */
    fun replaceForDebug(f: (CareFile) -> CareFile): Job = scope.launch { if (allowDebugSeed) commit(f) }
}
