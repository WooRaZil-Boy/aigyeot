package kr.aigyeot.app.store

import android.util.Log
import kotlinx.serialization.json.Json
import kr.aigyeot.app.link.db.OutboxEntity
import kr.aigyeot.app.store.db.AlarmLogEntity
import kr.aigyeot.app.store.db.AppDatabase
import kr.aigyeot.app.store.db.ChildProfileEntity
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
import kr.aigyeot.engine.EngineState
import kr.aigyeot.engine.Event
import kr.aigyeot.engine.FirstTry
import kr.aigyeot.engine.LogAlarm
import kr.aigyeot.engine.Method
import kr.aigyeot.engine.MethodKind
import kr.aigyeot.engine.Params
import kr.aigyeot.engine.Profile
import kr.aigyeot.engine.SaveEpisode
import kr.aigyeot.engine.SaveFeedingStart
import kr.aigyeot.engine.SavePlay
import kr.aigyeot.engine.SaveSession
import kr.aigyeot.engine.SaveSnapshot
import kr.aigyeot.engine.SaveSteerWindow
import kr.aigyeot.engine.fresh
import kr.aigyeot.shared.ProtocolJson

/** EngineState 스냅숏 JSON. 필드가 늘어도 옛 스냅숏을 읽게 모르는 키는 버린다. */
val SnapshotJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * 기록(store). 계약 4.3 '받는 곳'이 store인 할 일을 저장한다. 호스트 스레드 하나(쓰기 줄 하나)에서만 부른다(2.4 '저장 순서').
 * [scenarioId]는 지금 도는 장면 id(G44, 아니면 null).
 */
class Store(
    val db: AppDatabase,
    private val clock: () -> Long,
    private val scenarioId: () -> String? = { null },
    private val p: Params = Params(),
) {
    val dao = db.storeDao()
    private val context = ContextRecorder(p.contextHalfMs, p.contextStepMs)

    /** 할 일 하나를 저장한다. 실패해도 호스트 스레드를 멈추지 않고 로그만 남긴다. */
    fun handle(cmd: Command) = guard(cmd) {
        when (cmd) {
            is SaveSession -> saveSession(cmd)
            is SaveEpisode -> dao.upsertEpisode(cmd.record.toEntity())
            is SavePlay -> dao.upsertPlay(cmd.record.toEntity())
            is LogAlarm -> logAlarm(cmd)
            is CaptureContext -> dao.insertContext(context.capture(cmd.episodeId, cmd.alarmAt))
            is SaveSteerWindow -> with(cmd.record) { dao.upsertSteer(SteerWindowEntity(sessionId, startAt, speedBand, excluded, cryPresent, playing, reversals)) }
            is CloseSession -> { closeSession(cmd.sessionId); purgeOld() }   // 오래 켜 둔 차도 세션마다 90일 정리(G40)
            is SaveSnapshot -> saveSnapshot(cmd.state)
            is SaveFeedingStart -> dao.saveFeedingStart(cmd.at)
            else -> error("store가 받지 않는 할 일: $cmd")   // DeleteRecords·DeleteRelayRecords는 clearRecords로 함께 받는다
        }
    }

    fun saveSnapshot(s: EngineState) = dao.saveSnapshot(EngineSnapshotEntity(1, clock(), SnapshotJson.encodeToString(EngineState.serializer(), s)))

    fun loadSnapshot(): EngineState? = dao.snapshot()?.let {
        runCatching { SnapshotJson.decodeFromString(EngineState.serializer(), it.state) }
            .onFailure { e -> Log.w(TAG, "스냅숏을 읽지 못해 처음 상태로 시작", e) }.getOrNull()
    }

    /**
     * 5.9·C40·C90: DeleteRecords, 바로 뒤 SaveSnapshot, DeleteRelayRecords의 records_clear 넣기를 한 트랜잭션으로.
     * 연결·노래·프로필·처리한 메시지·나머지 outbox는 남긴다.
     */
    fun clearRecords(snapshot: EngineState) = guard("DeleteRecords") {
        tx {
            dao.clearContext(); dao.clearPlays(); dao.clearAlarms(); dao.clearSteer()
            dao.clearEpisodes(); dao.clearSessions(); dao.clearControls(); dao.clearReportOutbox()
            saveSnapshot(snapshot)
            val now = clock()
            dao.insertOutbox(OutboxEntity(ulid(now), "records_clear", null, "", now, 0, now))
        }
        context.clear()
    }

    /** 장면 경계(F17·C92): 장면 표본이 실제 에피소드의 '울음 직전 30초'에 섞이지 않게 맥락 기록기를 비운다. */
    fun resetContext() = context.reset()

    fun logControl(row: ControlLogEntity) = guard("control_log") { dao.insertControl(row) }

    /** 맥락 기록기(F17)에 사건마다 넣는다. 붙잡아 둔 캡처가 있으면 이번에 마감된 초를 쓴다. */
    fun observe(e: Event, s: EngineState) {
        val rows = context.observe(e, s)
        if (rows.isNotEmpty()) guard("context_sample") { dao.insertContext(rows) }
    }

    // ---- 처음 상태·복원 대조에 쓰는 Room 값 ----

    /**
     * 설정 화면 저장(계약 4.3 SaveFeedingStart '더 나중일 때만'). 화면이 열린 뒤 폰이 더 나중 수유 시각을 커밋했을 수 있어,
     * last_feeding_start는 지금 행의 값을 지키고 행이 없을 때만 [edited]의 값을 쓴다.
     */
    fun saveProfile(edited: ChildProfileEntity) = tx {
        val now = dao.profile()
        dao.upsertProfile(if (now != null) edited.copy(lastFeedingStart = now.lastFeedingStart) else edited)
    }

    fun profile(): Profile? = dao.profile()?.toProfile()
    fun feedingStart(): Long? = dao.profile()?.lastFeedingStart
    fun otherGuardians(): Int = dao.otherGuardians()

    /** 판정된 1차 시도(28일, 장면 제외). 판단 코어 history 입력. */
    fun history(now: Long): List<FirstTry> = dao.firstTries(now - p.statWindowDays * DAY).map {
        FirstTry(it.at, Method(MethodKind.valueOf(it.method), it.songId), it.result == "SUCCESS")
    }

    /**
     * 다음 세션 음원(명세서 5장). 프로필에서 고른 종류 + 후보 ③(고른 곡이 ACTIVE면 그 곡, 아니면 ACTIVE 중 added_seq 최대).
     * 프로필이 없으면 기본(백색소음·자장가·③).
     */
    fun methods(): List<Method> {
        val kinds = dao.profile()?.methods?.map { it.kind } ?: listOf(MethodKind.WHITE_NOISE, MethodKind.LULLABY, MethodKind.SONG)
        val active = dao.activeSongs()
        val chosen = dao.selection()?.songId?.let { id -> active.firstOrNull { it.id == id } }
        val third = (chosen ?: active.firstOrNull())?.let { Method(MethodKind.SONG, it.id) }
        return kinds.distinct().mapNotNull { if (it == MethodKind.SONG) third else Method(it) }
    }

    fun initialState(seed: Long): EngineState =
        EngineState.fresh(seed, profile(), methods(), history(clock()), feedingStart(), otherGuardians())

    /** G40: 90일 지난 기록을 지운다(열린 세션은 남긴다). */
    fun purgeOld() = guard("purge") {
        val cut = clock() - p.retentionDays * DAY
        tx { dao.purgeSessions(cut); dao.purgeControls(cut) }
    }

    fun closeScenarioSessions(now: Long) = guard("scenario_close") { dao.closeScenarioSessions(now) }

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
        // '이어진 경보'(G3): 경보 조건이 이어지면 경보는 YAMNet 한 걸음(975ms)마다 오고, 사이에 조용한 구간이 끼어 끊기면
        // 최소 세 걸음(약 2.9초) 뒤에 온다. 그래서 문턱을 두 걸음으로 둔다(3초면 끊긴 경보까지 합친다)
        if (last != null && last.reason == cmd.reason.name && cmd.at - last.lastAt <= 2 * p.yamnetHopMs) dao.extendAlarm(last.id, cmd.at)
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
        if (s.scenarioId != null || dao.phoneCount() == 0) return dao.reportDone(sessionId, s.reportMessageId)
        val body = buildReport(dao, sessionId, clock(), p) ?: return
        val now = clock()
        val id = s.reportMessageId ?: ulid(now)
        dao.insertOutbox(OutboxEntity(id, "report_summary", null, encodeReport(body), now, 0, now))
        dao.reportDone(sessionId, id)
    }

    private fun tx(block: () -> Unit) = db.runInTransaction(Runnable(block))

    private inline fun guard(what: Any, block: () -> Unit) {
        try { block() } catch (e: Exception) { Log.e(TAG, "저장 실패: $what", e) }
    }

    companion object {
        private const val TAG = "store"
        const val DAY = 86_400_000L
    }
}

/** 화면·Room의 프로필 → 판단 코어 Profile. */
fun ChildProfileEntity.toProfile() = Profile(birthYm, CarSeat.valueOf(carseat), feedingIntervalMin, hasSibling, frontFallbackAllowed)

private fun kr.aigyeot.engine.EpisodeRecord.toEntity() = EpisodeEntity(
    id, sessionId, startedAt, endedAt, trigger.name, alarmAt, alarmDelayMs, firstMethod?.name, firstSongId,
    firstResult?.name, firstUnjudged?.name, secondAction?.name, secondMethod?.name, secondSongId, secondResult?.name,
    handoffCause?.name, handoffKind?.name, waypointAdded, guardianNotified, cryMs, recried, playedMs, excludeReasons,
)

private fun kr.aigyeot.engine.PlayRecord.toEntity() = PlayLogEntity(
    id, sessionId, episodeId, purpose.name, method.name, songId, startedAt, endedAt, playedMs, endReason.name,
)

/** report_summary body를 outbox body_json으로. 시각은 이미 +09:00 문자열이라 link는 그대로 봉투에 넣는다. */
fun encodeReport(b: kr.aigyeot.shared.ReportSummaryBody): String = ProtocolJson.encodeToString(kr.aigyeot.shared.ReportSummaryBody.serializer(), b)
