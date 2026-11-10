package kr.aigyeot.app.store

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kr.aigyeot.app.link.DAY
import kr.aigyeot.app.link.LinkRoom
import kr.aigyeot.app.link.SENT_ID_KEEP_MS
import kr.aigyeot.app.link.candidateSong
import kr.aigyeot.app.link.ulid
import kr.aigyeot.app.link.db.OutboxEntity
import kr.aigyeot.app.store.db.AlarmLogEntity
import kr.aigyeot.app.store.db.AppDatabase
import kr.aigyeot.app.store.db.ChildProfileEntity
import kr.aigyeot.app.store.db.ContextSampleEntity
import kr.aigyeot.app.store.db.ControlLogEntity
import kr.aigyeot.app.store.db.EngineSnapshotEntity
import kr.aigyeot.app.store.db.EpisodeEntity
import kr.aigyeot.app.store.db.PlayLogEntity
import kr.aigyeot.app.store.db.SessionEntity
import kr.aigyeot.app.store.db.SteerWindowEntity
import kr.aigyeot.engine.CaptureContext
import kr.aigyeot.engine.CarSeat
import kr.aigyeot.engine.CloseSession
import kr.aigyeot.engine.Command
import kr.aigyeot.engine.DEFAULT_METHODS
import kr.aigyeot.engine.EngineState
import kr.aigyeot.engine.EpisodeRecord
import kr.aigyeot.engine.EpisodeTrigger
import kr.aigyeot.engine.Event
import kr.aigyeot.engine.FirstTry
import kr.aigyeot.engine.Gear
import kr.aigyeot.engine.HandoffCause
import kr.aigyeot.engine.HandoffKind
import kr.aigyeot.engine.LogAlarm
import kr.aigyeot.engine.Method
import kr.aigyeot.engine.MethodKind
import kr.aigyeot.engine.Params
import kr.aigyeot.engine.SecondAction
import kr.aigyeot.engine.TryResult
import kr.aigyeot.engine.Unjudged
import kr.aigyeot.engine.alarmContinues
import kr.aigyeot.engine.settle
import kr.aigyeot.engine.statSince
import kr.aigyeot.engine.HandoffLedger
import kr.aigyeot.engine.Profile
import kr.aigyeot.engine.RoomFacts
import kr.aigyeot.shared.ReportSummaryBody
import kr.aigyeot.engine.SaveEpisode
import kr.aigyeot.engine.SettleHandoff
import kr.aigyeot.engine.SaveFeedingStart
import kr.aigyeot.engine.SavePlay
import kr.aigyeot.engine.SaveSession
import kr.aigyeot.engine.SaveSnapshot
import kr.aigyeot.engine.SaveSteerWindow
import kr.aigyeot.engine.SteerWindowRecord
import kr.aigyeot.engine.TurnSignal
import kr.aigyeot.engine.fresh
import kr.aigyeot.shared.ProtocolJson

/** EngineState 스냅숏 JSON. 필드가 늘어도 옛 스냅숏을 읽게 모르는 키는 버린다. */
val SnapshotJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * 기록(store). 계약 4.3 '받는 곳'이 store인 할 일을 저장한다. 호스트 스레드 하나(쓰기 줄 하나)에서 부른다(2.4 '저장 순서').
 * 예외는 설정 화면의 [saveProfile](IO 스레드, Room 트랜잭션이 직렬화한다). 실패 처리는 C302(DbFailure.kt).
 * [scenarioId]는 지금 도는 장면 id(G44, 아니면 null).
 */
class Store(
    private val db: AppDatabase,
    private val clock: () -> Long,
    private val scenarioId: () -> String? = { null },
    private val p: Params = Params(),
) {
    internal val dao = db.storeDao()   // 화면은 profileFlow() 등 읽기 함수만 쓴다(U1). internal은 시험용
    private val context = ContextRecorder(p.contextHalfMs, p.contextStepMs)

    /** 할 일 하나를 저장한다. 실패해도 호스트 스레드를 멈추지 않고 로그만 남긴다. */
    fun handle(cmd: Command) { bestEffort("store ${cmd::class.simpleName}") { apply(cmd) }; if (cmd is CloseSession) purgeOld() }   // C302 A

    /** C160: 인계 알림의 보낼 함 넣기와 한 트랜잭션으로 묶을 쓰기. 실패는 삼키지 않고 던져 바깥 트랜잭션을 되돌린다. */
    fun applyAll(cmds: List<Command>) = cmds.forEach(::apply)

    /** C207: 한 Step의 store 쓰기와 스냅숏을 한 트랜잭션으로. 실패는 던진다(호출자가 C173으로 프로세스를 끝낸다). */
    fun applyAllTx(cmds: List<Command>) = tx { applyAll(cmds) }

    /**
     * C262: 세션의 자식 쓰기는 부모 session 행이 없으면(기록 지움·보관 정리로 사라진 세션) 버리고 로그만 남긴다.
     * 늦게 오는 쓰기(닫힌 세션 인계 장부·C141 등)는 그 세션의 recordsCleared를 몰라 외래키 오류로 묶음을 깨기 때문에 쓰기 입구 한 곳에서 막는다.
     * 같은 트랜잭션에서는 앞선 SaveSession이 먼저 적용되므로 새 세션의 첫 쓰기는 버려지지 않는다.
     */
    private fun parentGone(cmd: Command): Boolean {
        val sid = when (cmd) {
            is SaveEpisode -> cmd.record.sessionId
            is SavePlay -> cmd.record.sessionId
            is LogAlarm -> cmd.sessionId
            is CaptureContext -> dao.episode(cmd.episodeId)?.sessionId ?: return true.also { Log.w(TAG, "부모 에피소드 없음: 버림 $cmd") }
            is SaveSteerWindow -> cmd.record.sessionId
            is CloseSession -> cmd.sessionId
            else -> return false
        }
        return sessionGone(sid, cmd)
    }

    private fun sessionGone(sid: String?, what: Any) = (sid != null && dao.session(sid) == null).also { if (it) Log.w(TAG, "부모 세션 없음: 버림 $what") }

    private fun apply(cmd: Command) {
        if (parentGone(cmd)) return
        when (cmd) {
            is SaveSession -> saveSession(cmd)
            is SaveEpisode -> tx { dao.upsertEpisode(cmd.record.toEntity(dao.episode(cmd.record.id))) }   // 결정 ③: 확정 칸·guardian_notified는 지금 행 값(UPSERT 구문이 없어 읽고 합친다)
            is SettleHandoff -> dao.settleHandoff(cmd.episodeId, cmd.kind.name, cmd.toldDriver, cmd.restName)
            is SavePlay -> dao.upsertPlay(cmd.record.toEntity())
            is LogAlarm -> logAlarm(cmd)
            is CaptureContext -> dao.insertContext(context.capture(cmd.episodeId, cmd.alarmAt).map { it.toEntity() })
            is SaveSteerWindow -> dao.upsertSteer(cmd.record.toEntity())
            is CloseSession -> closeSession(cmd.sessionId)   // C211: 90일 정리(G40)는 묶음 트랜잭션 밖에서 purgeOld로(안에서 삼킨 실패가 바깥 묶음을 몰래 되돌린다)
            is SaveSnapshot -> saveSnapshot(cmd.state)
            is SaveFeedingStart -> dao.saveFeedingStart(cmd.at)
            else -> error("store가 받지 않는 할 일: $cmd")   // DeleteRecords·DeleteRelayRecords는 clearRecords로 함께 받는다
        }
    }

    fun saveSnapshot(s: EngineState) = dao.saveSnapshot(EngineSnapshotEntity(1, clock(), SnapshotJson.encodeToString(EngineState.serializer(), s)))

    fun loadSnapshot(): EngineState? = dao.snapshot()?.let {
        runCatching { SnapshotJson.decodeFromString(EngineState.serializer(), it.state) }
            .onFailure { e ->
                Log.w(TAG, "스냅숏을 읽지 못해 처음 상태로 시작", e)
                bestEffort("orphan_close") { tx { dao.closeOpenRealEpisodes(clock()); dao.closeOpenRealSessions(clock()) } }   // D1: 처음 상태에는 그 세션이 없다
            }.getOrNull()
    }

    /**
     * 5.9·C40·C90: DeleteRecords, 바로 뒤 SaveSnapshot, DeleteRelayRecords의 records_clear 넣기를 한 트랜잭션으로.
     * 연결·노래·프로필·처리한 메시지·나머지 outbox는 남긴다.
     */
    /** C226: 실패를 삼키지 않고 던진다. 호스트가 C173처럼 die로 처리한다(트랜잭션이라 부분 상태는 남지 않는다). */
    fun clearRecords(snapshot: EngineState) {
        tx {
            dao.clearContext(); dao.clearPlays(); dao.clearAlarms(); dao.clearSteer()
            dao.clearEpisodes(); dao.clearSessions(); dao.clearControls()
            val now = clock()
            // L84: 보낼 함의 L82 항목 + 10분 안에 보낸 id(만료로 보낼 함에서 지워진 보내는 중 항목 포함), 중복 없음
            val dropIds = (dao.reportOutboxIds() + dao.sentIdsSince(now - SENT_ID_KEEP_MS)).distinct(); dao.clearReportOutbox()
            saveSnapshot(snapshot)
            dao.insertOutbox(OutboxEntity(ulid(now), "records_clear", null, buildJsonObject { put("drop_ids", JsonArray(dropIds.map(::JsonPrimitive))) }.toString(), now, 0, now))
        }
        context.clear()
        _clearedAt.value = clock()
    }

    private val _clearedAt = kotlinx.coroutines.flow.MutableStateFlow(0L)
    /** 마지막으로 기록을 지운 시각(이 프로세스 안, 화면 전용). 홈 물어보기 카드가 지운 전의 질문·답을 내리는 데 쓴다(값이라 흐름이 합쳐져도 놓치지 않는다). */
    val clearedAt: kotlinx.coroutines.flow.StateFlow<Long> = _clearedAt

    /** 장면 경계(F17·C92): 장면 표본이 실제 에피소드의 '울음 직전 30초'에 섞이지 않게 맥락 기록기를 비운다. 기다리던 캡처는 작성 중인 칸까지 쓰고 끝낸다(C319). */
    fun resetContext() { val rows = context.reset(); bestEffort("context_sample") { insertLive(rows) } }   // C302 A: 부수 일

    fun logControl(row: ControlLogEntity) = bestEffort("control_log") { if (!sessionGone(row.sessionId, row)) dao.insertControl(row) }   // C302 A. C40·C262: 지운 세션의 제어 기록을 되살리지 않는다

    /** 맥락 기록기(F17)에 사건마다 넣는다. 붙잡아 둔 캡처가 있으면 이번에 마감된 초를 쓴다. */
    fun observe(e: Event, s: EngineState) {
        var once: List<ContextSample>? = null   // 기록기는 사건마다 한 번만 먹인다(다시 할 때는 저장만 다시)
        bestEffort("context_sample") {   // C302 A. C274: 부모 조회부터 저장까지 전부 부수 일. 실패가 Step을 끊지 않는다
            insertLive(once ?: context.observe(e, s).also { once = it })
        }
    }

    /** C262: 부모 에피소드 행이 없는(지운·정리된) 캡처는 입구에서 버린다. 한 줄이 외래키를 어기면 같은 묶음의 다른 에피소드 줄까지 잃는다. */
    private fun insertLive(rows: List<ContextSample>) {
        val live = rows.filter { r -> (dao.episode(r.episodeId) != null).also { if (!it) { context.drop(r.episodeId); Log.w(TAG, "부모 에피소드 없음: 버림 ${r.episodeId}") } } }
        if (live.isNotEmpty()) dao.insertContext(live.map { it.toEntity() })
    }

    // ---- 처음 상태·복원 대조에 쓰는 Room 값 ----

    /**
     * 설정 화면 저장(계약 4.3 SaveFeedingStart '더 나중일 때만'). 화면이 열린 뒤 폰이 더 나중 수유 시각을 커밋했을 수 있어,
     * last_feeding_start는 지금 행의 값을 지키고 행이 없을 때만 [edited]의 값을 쓴다.
     */
    fun saveProfile(edited: ChildProfileEntity) = tx {
        val links = LinkRoom(db, p)
        val before = links.candidate()
        val now = dao.profile()
        dao.upsertProfile(if (now != null) edited.copy(lastFeedingStart = now.lastFeedingStart) else edited)
        links.candidateMoved(before, clock())   // C303 ⑥(B9): 부모 노래 켬·끔으로 후보 ③이 바뀌면 같은 트랜잭션에서 두 곡의 song_status(폰 표시도 같은 함수)
    }

    fun profile(): Profile? = dao.profile()?.toProfile()
    fun feedingStart(): Long? = dao.profile()?.lastFeedingStart
    fun otherGuardians(): Int = db.linkDao().otherGuardians()   // C303 A12: phone_link 쿼리는 LinkDao 한 곳

    /** 판정된 1차 시도(28일, 장면 제외). 판단 코어 history 입력. */
    fun history(now: Long): List<FirstTry> = dao.firstTries(p.statSince(now)).map {
        FirstTry(it.at, Method(MethodKind.valueOf(it.method), it.songId), it.result == "SUCCESS")
    }

    // 호스트 읽기(복원). 실패 범주는 호출자가 정한다: 같은 읽기를 부르는 자리마다 S(retrying 뒤 die)·A(bestEffort)로 다르다(계약 6.0).
    /** 대기 리포트 세션 id(복원 ⑤, A). */
    fun pendingReportIds(): List<String> = dao.pendingReports().map { it.id }

    /** 복원 ③: 이 재생이 이미 기록됐는지(S). */
    fun playLogged(playId: String): Boolean = dao.playLogged(playId)

    /** 복원 ②에 넘길 Room 사실(S). 에피소드 행은 상태가 가리키는 것(열린 에피소드·인계 장부, C152)만 읽는다. */
    fun roomFacts(s: EngineState, now: Long): RoomFacts {
        val x = s.session
        val ids = (listOfNotNull(x?.episode?.id) + s.handoffs.mapNotNull { it.episodeId }).distinct()
        val rows = ids.mapNotNull { id -> dao.episode(id)?.let { id to it } }.toMap()
        return RoomFacts(
            sessionEnded = x != null && dao.session(x.id)?.endedAt != null,
            sessionPlayedMs = x?.let { dao.sessionPlayedMs(it.id) } ?: 0,
            alarmEpisodes = x?.let { dao.episodes(it.id).count { e -> e.trigger == EpisodeTrigger.ALARM.name } } ?: 0,
            episodePlayedMs = x?.episode?.let { dao.episodePlayedMs(it.id) } ?: 0,
            episodes = rows.mapValues { it.value.toRecord() },
            history = history(now),
            told = rows.mapNotNull { (id, e) -> e.handoffToldDriver?.let { id to it } }.toMap(),
        )
    }

    // 화면 읽기(U1이 ui/**·MainActivity의 g.store.dao·g.store.db 직접 읽기를 이 함수로 바꾼다). 범주 U 고정이라 retryOnDbError를 여기서 건다(C293).
    fun profileFlow() = dao.profileFlow().retryOnDbError("profile")
    fun activeSongsFlow() = dao.activeSongsFlow().retryOnDbError("songs")
    fun selectionFlow() = dao.selectionFlow().retryOnDbError("selection")
    fun sessionsFlow() = dao.sessionsFlow().retryOnDbError("sessions")
    fun reportChanges(sessionId: String) = dao.reportChanges(sessionId).retryOnDbError("report")
    /** 리포트 한 건(한 트랜잭션, Report.kt [readReport]). 두 번째 값은 인계가 아직 안 끝났는지. */
    fun okControlsFlow(sessionId: String) = dao.okControlsFlow(sessionId).retryOnDbError("controls")
    /**
     * 세션의 이어진 울음마다 처음 시각(홈 '오늘 하루' 차에서 줄의 마름모·횟수). 에피소드를 연 경보와 기록만 한 경보(G4: 통화·대기 중 등)를 합치고,
     * 판단 코어 문턱(alarmContinues)으로 이어진 것은 하나로 센다(통화 중 시작해 끝난 뒤 이어진 울음이 두 줄로 남아도 한 번).
     * 판정 창에서 다시 운 것(recried·2차 뒤 울음)은 경보가 아니라 세지 않는다: 리포트 목록 '울음 n번'(U47)·폰 '차에서 울음'과 같은 정의이고, 그 울음의 시각은 기록에 없다.
     */
    fun cryRuns(sessionId: String): List<Long> = cryRunStarts(dao.loggedAlarmSpans(sessionId).map { it.firstAt to it.lastAt } + dao.episodes(sessionId).mapNotNull { e -> e.alarmAt?.let { it to it } })

    /** 세션의 마지막 에피소드(열린 세션도, 홈 정차 화면 '에피소드' 카드). 없으면 null. 리포트와 같은 변환(timeline)이다. */
    fun lastEpisode(sessionId: String): kr.aigyeot.shared.TimelineEpisode? {
        val e = dao.episodes(sessionId).lastOrNull() ?: return null
        return timeline(e, dao.plays(sessionId).filter { it.episodeId == e.id }) { id -> id?.let { dao.song(it)?.name } }
    }

    fun readReport(sessionId: String, now: Long): Pair<ReportSummaryBody?, Boolean> = readReport(db, sessionId) { buildReport(it, sessionId, now) }

    /**
     * 다음 세션 음원(명세서 5장). ①②(백색소음·뱃속 소리)와 자장가는 앱 기본이라 늘 넣는다(11-06 전 프로필에 WOMB가 없어도 쓴다).
     * ③은 link와 같은 규칙 하나(`candidateSong`, contracts-link 8.4. 프로필의 부모 노래 끔 포함, C303 ⑥).
     */
    fun methods(): List<Method> =
        DEFAULT_METHODS + listOfNotNull(candidateSong(dao.profile(), dao.activeSongs(), dao.selection())?.let { Method(MethodKind.SONG, it) })

    fun initialState(seed: Long): EngineState =
        EngineState.fresh(seed, profile(), methods(), history(clock()), feedingStart(), otherGuardians())

    /** G40: 90일 지난 기록을 지운다(열린 세션은 남긴다). */
    fun purgeOld() = bestEffort("purge") {   // C302 A
        val cut = clock() - p.retentionDays * DAY
        tx { dao.purgeSessions(cut); dao.purgeControls(cut) }
    }

    // C302 A(C292): 장면 끝 정리는 다 실패하면 로그만. 다음 시작 복원이 다시 닫는다
    fun closeScenarioSessions(now: Long) = bestEffort("scenario_close") { tx { dao.closeScenarioEpisodes(now); dao.closeScenarioSessions(now); dao.settleScenarioHandoffs() } }

    /**
     * C290: 장면 끝에서 장면 상태의 알림 장부로 그 에피소드 행을 먼저 정확히 확정한다([closeScenarioSessions]의 말함 짐작보다 앞).
     * 장부에 남은 줄은 끝내 말하지 못한 것: 말했는지는 false. 이미 값이 있는 행은 건드리지 않는다. guardian_notified는 link가 넣었을 때만 참이라 손대지 않는다(결정 ③).
     * V20: 판단 코어의 버림 확정과 같은 규칙(`HandoffLedger.settle` → SettleHandoff)이라 다시 고른 인계 종류(rebind)도 행에 쓴다.
     * shortcut: 장면 중 프로세스가 죽으면 장부가 없어 [closeScenarioSessions]의 말함 짐작으로 닫힌다(알려진 한계, 칸을 더하는 Room 마이그레이션이 필요해 두었다).
     */
    fun settleSceneHandoffs(ledger: List<HandoffLedger>) = bestEffort("scenario_ledger") {   // C302 A
        tx { ledger.mapNotNull { it.settle(told = false) }.forEach { c -> if (dao.episode(c.episodeId)?.let { it.handoffToldDriver == null } == true) apply(c) } }
    }

    // ---- 할 일별 저장 ----

    private fun saveSession(cmd: SaveSession) = tx {
        val r = cmd.record
        val old = dao.session(r.id)
        val scenario = old?.scenarioId ?: scenarioId()
        dao.upsertSession(SessionEntity(
            r.id, r.startedAt, r.endedAt, r.trigger.name, r.micPath?.name, r.outputRoute?.name, r.driveMin, r.playedMs,
            r.capReached, r.restLog, r.restore,
            reportMessageId = old?.reportMessageId ?: r.reportMessageId,
            // 리포트를 이미 넣었으면(pending=false, id 있음) 되돌리지 않는다. 장면 세션은 리포트를 보내지 않는다(G44)
            reportPending = r.reportPending && scenario == null && old?.reportMessageId == null,
            scenarioId = scenario, driverUserId = r.driverUserId,
        ))
    }

    private fun logAlarm(cmd: LogAlarm) {
        val last = dao.lastAlarm(cmd.sessionId)
        // '이어진 경보'(G3): 문턱은 판단 코어 alarmContinues 하나(C303 A1). 이 표는 같은 이유끼리만 잇는다(기록만 한 경보, G4. 판단 코어 alarmRun과 뜻이 달라 합치지 않는다)
        if (last != null && last.reason == cmd.reason.name && alarmContinues(last.lastAt, cmd.at, p)) dao.extendAlarm(last.id, cmd.at)
        else dao.insertAlarm(AlarmLogEntity(sessionId = cmd.sessionId, firstAt = cmd.at, lastAt = cmd.at, reason = cmd.reason.name))
    }

    /**
     * 2.4·13a: 앞선 저장은 같은 쓰기 줄에서 이미 커밋됐다. 리포트를 만들어 outbox에 넣고 report_pending = false를 한 트랜잭션으로.
     * 장면 세션·보낼 폰이 없는 세션은 리포트를 넣지 않고 표시만 내린다(G44, contracts-link 8.5 '만들지 않는 때').
     */
    fun closeSession(sessionId: String) = tx { closeTx(sessionId) }

    private fun closeTx(sessionId: String) {
        val s = dao.session(sessionId) ?: return
        if (!s.reportPending) return
        if (s.scenarioId != null || db.linkDao().phoneCount() == 0) return dao.reportDone(sessionId, s.reportMessageId)   // C303 A13
        val body = buildReport(dao, sessionId, clock(), p, finalizing = true) ?: return
        val now = clock()
        val id = s.reportMessageId ?: ulid(now)
        dao.insertOutbox(OutboxEntity(id, "report_summary", null, encodeReport(body), now, 0, now))
        dao.reportDone(sessionId, id)
    }

    private fun tx(block: () -> Unit) = db.runInTransaction(Runnable(block))

    companion object {
        private const val TAG = "store"
    }
}

/** 화면·Room의 프로필 → 판단 코어 Profile. */
/** [spans](처음·마지막 경보 시각)를 시간순으로 이어 붙여 이어진 울음마다 처음 시각. */
internal fun cryRunStarts(spans: List<Pair<Long, Long>>, p: Params = Params()): List<Long> {
    val out = mutableListOf<Long>(); var end = Long.MIN_VALUE
    for ((a, b) in spans.sortedBy { it.first }) {
        if (out.isNotEmpty() && (a <= end || kr.aigyeot.engine.alarmContinues(end, a, p))) end = maxOf(end, b) else { out += a; end = b }
    }
    return out
}

fun ChildProfileEntity.toProfile() = Profile(birthYm, CarSeat.valueOf(carseat), feedingIntervalMin, hasSibling, frontFallbackAllowed)

/** Room 행 → 판단 코어 기록(복원 대조의 Room 사실). [toEntity]의 거꾸로(확정 칸·guardian_notified는 판단 코어 기록에 없다). */
internal fun EpisodeEntity.toRecord() = EpisodeRecord(
    id, sessionId, startedAt, endedAt, EpisodeTrigger.valueOf(trigger), alarmAt, alarmDelayMs,
    firstMethod?.let(MethodKind::valueOf), firstSongId, firstResult?.let(TryResult::valueOf), firstUnjudged?.let(Unjudged::valueOf),
    secondAction?.let(SecondAction::valueOf), secondMethod?.let(MethodKind::valueOf), secondSongId, secondResult?.let(TryResult::valueOf),
    handoffCause?.let(HandoffCause::valueOf), handoffKind?.let(HandoffKind::valueOf), waypointAdded, cryMs, recried, playedMs, excludeReasons,
)

/** [old]: 지금 행. 인계 확정 칸(SettleHandoff)과 guardian_notified(link)는 그 값을 지킨다(결정 ③). */
private fun EpisodeRecord.toEntity(old: EpisodeEntity?) = EpisodeEntity(
    id, sessionId, startedAt, endedAt, trigger.name, alarmAt, alarmDelayMs, firstMethod?.name, firstSongId,
    firstResult?.name, firstUnjudged?.name, secondAction?.name, secondMethod?.name, secondSongId, secondResult?.name,
    handoffCause?.name, handoffKind?.name, waypointAdded, old?.guardianNotified ?: false, cryMs, recried, playedMs, excludeReasons,
    old?.handoffRestName, old?.handoffToldDriver,
)

private fun kr.aigyeot.engine.PlayRecord.toEntity() = PlayLogEntity(
    id, sessionId, episodeId, purpose.name, method.name, songId, startedAt, endedAt, playedMs, endReason.name,
)

private fun SteerWindowRecord.toEntity() = SteerWindowEntity(sessionId, startAt, speedBand, excluded, cryPresent, playing, reversals, observed)

/** Room 행 → 조향 창 기록(리포트 계산 입력). [toEntity]의 거꾸로. */
internal fun SteerWindowEntity.toRecord() = SteerWindowRecord(sessionId, startAt, speedBand, excluded, cryPresent, playing, reversals, observed)

/** 맥락 표본 → 행. 창 위치는 읽는 API 미확인(G39)이라 비운다. */
private fun ContextSample.toEntity() = ContextSampleEntity(episodeId, at, speedKmh, brake, steeringDeg, turnSignal.name, gear.name, cabinC, windowPos = null, playback, cryP)

/** Room 행 → 맥락 표본(리포트 계산 입력). [toEntity]의 거꾸로. 행은 [toEntity]만 쓰므로 이름은 늘 enum 값이다. */
internal fun ContextSampleEntity.toSample() = ContextSample(episodeId, at, speedKmh, brake, steeringDeg, TurnSignal.valueOf(turnSignal), Gear.valueOf(gear), cabinC, playback, cryP)

/** report_summary body를 outbox body_json으로. 시각은 이미 +09:00 문자열이라 link는 그대로 봉투에 넣는다. */
fun encodeReport(b: kr.aigyeot.shared.ReportSummaryBody): String = ProtocolJson.encodeToString(kr.aigyeot.shared.ReportSummaryBody.serializer(), b)
