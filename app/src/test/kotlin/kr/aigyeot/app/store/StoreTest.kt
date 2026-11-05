package kr.aigyeot.app.store

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kr.aigyeot.app.store.db.AppDatabase
import kr.aigyeot.app.store.db.ContextSampleEntity
import kr.aigyeot.app.store.db.ControlLogEntity
import kr.aigyeot.app.store.db.EpisodeEntity
import kr.aigyeot.app.store.db.PlayLogEntity
import kr.aigyeot.app.store.db.SessionEntity
import kr.aigyeot.app.store.db.SteerWindowEntity
import kr.aigyeot.engine.*
import kr.aigyeot.shared.SteeringUnavailableReason
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val T0 = 1_800_000_000_000L

@RunWith(RobolectricTestRunner::class)
class StoreTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build()
    private val dao = db.storeDao()
    private var now = T0
    private val store = Store(db, { now })

    @After fun close() = db.close()

    private fun sql(q: String) = db.openHelper.writableDatabase.execSQL(q)
    private fun count(q: String) = db.openHelper.readableDatabase.query(q).use { it.moveToFirst(); it.getInt(0) }
    private fun phone() = sql("INSERT INTO phone_link (id, platform, role, linked_at, last_seen_at, handoff_alerts) VALUES ('d1', 'android', 'owner', $T0, NULL, 0)")

    private fun session(id: String = "S1", ended: Long? = T0 + 600_000, route: OutputRoute? = OutputRoute.REAR_ZONE, pending: Boolean = true) =
        store.handle(SaveSession(SessionRecord(id, T0, ended, OpenTrigger.DOOR_THEN_D, MicPath.HOME_BUTTON, route, 10, 56_000, false, "[]", null, null, pending, null, 10)))

    /** 한 에피소드: 경보 → 자장가 56초 → 성공. 맥락·조향·제어 기록까지 채운다. */
    private fun fullSession() {
        session()
        dao.upsertEpisode(EpisodeEntity("E1", "S1", T0 + 60_000, T0 + 200_000, "ALARM", T0 + 60_000, 2_400, "LULLABY", null, "SUCCESS", null,
            null, null, null, null, null, null, false, false, 61_000, false, 56_000, emptyList()))
        dao.upsertPlay(PlayLogEntity("P1", "S1", "E1", "AUTO_FIRST", "LULLABY", null, T0 + 63_000, T0 + 119_000, 56_000, "DEADLINE"))
        dao.insertContext((0..60).map { i -> ContextSampleEntity("E1", T0 + 30_000 + i * 1_000L, 50.0 + i % 3, i == 5, if (i % 10 < 5) 0.0 else 5.0, "NONE", "D", 24.5, null, null, null) })
        dao.upsertSteer(SteerWindowEntity("S1", T0, 2, false, true, false, 4))
        dao.insertControl(ControlLogEntity(sessionId = "S1", at = T0 + 10_000, api = "Seat.setHvacSeatTemperature", target = "ROW_2_LEFT", goal = "0", actual = "0", ackMs = 120, appliedMs = 300, ok = true, retries = 0))
    }

    @Test fun reportFields_matchContractAndSharedExample() {
        fullSession()
        val ours = Json.parseToJsonElement(encodeReport(buildReport(dao, "S1", now)!!)).jsonObject
        val example = Json.parseToJsonElement(File("../shared/src/test/resources/protocol/report_summary.json").readText()).jsonObject["body"]!!.jsonObject
        assertSameShape(example, ours, "body")
        assertEquals("cry_alarm", ours.str("timeline", "trigger")); assertEquals("success", ours.str("timeline", "result"))
        assertEquals("2026-01-15T17:01:00+09:00".length, ours["started_at"].toString().trim('"').length, "시각은 +09:00 ISO 초 단위")
        assertTrue(ours["started_at"].toString().endsWith("+09:00\""))
    }

    /** 예제에 있는 키가 같은 순서로 모두 있는지(배열은 첫 원소끼리). 값의 null 여부는 보지 않는다. */
    private fun assertSameShape(want: JsonElement, got: JsonElement?, path: String) {
        when (want) {
            is JsonObject -> {
                val g = got as? JsonObject ?: return assertTrue(got.toString() == "null", "$path: 객체가 아님")
                assertEquals(want.keys.toList(), g.keys.toList(), "$path 키 순서")
                want.forEach { (key, v) -> assertSameShape(v, g[key], "$path.$key") }
            }
            is JsonArray -> (got as? JsonArray)?.firstOrNull()?.let { g -> want.firstOrNull()?.let { assertSameShape(it, g, "$path[0]") } }
            else -> {}
        }
    }

    private fun JsonObject.str(arr: String, key: String) = (this[arr] as JsonArray)[0].jsonObject[key].toString().trim('"')

    @Test fun r13_noPlayback_outputRouteNull() {
        session(route = null)
        val body = encodeReport(buildReport(dao, "S1", now)!!)
        assertTrue("\"output_route\":\"no_playback\"" in body)
    }

    @Test fun closeSession_reportToOutboxAndPendingFalse() {
        phone(); fullSession()
        store.handle(CloseSession("S1"))
        val s = dao.session("S1")!!
        assertEquals(false, s.reportPending)
        assertEquals(1, count("SELECT COUNT(*) FROM outbox WHERE type = 'report_summary' AND id = '${s.reportMessageId}'"))
        store.handle(CloseSession("S1"))   // 다시 와도 한 번만
        assertEquals(1, count("SELECT COUNT(*) FROM outbox"))
    }

    @Test fun closeSession_noPhone_noOutboxButPendingCleared() {
        fullSession()
        store.handle(CloseSession("S1"))
        assertEquals(0, count("SELECT COUNT(*) FROM outbox"))
        assertEquals(false, dao.session("S1")!!.reportPending)
    }

    @Test fun saveSession_scenarioNeverPending_andKeepsReportId() {
        val sc = Store(db, { now }, scenarioId = { "A" })
        sc.handle(SaveSession(SessionRecord("S2", T0, null, OpenTrigger.DOOR_THEN_D, null, null, 0, 0, false, "[]", null, null, false, null, 10)))
        sc.handle(SaveSession(SessionRecord("S2", T0, T0 + 1, OpenTrigger.DOOR_THEN_D, null, null, 0, 0, false, "[]", null, null, true, null, 10)))
        assertEquals("A", dao.session("S2")!!.scenarioId)
        assertEquals(false, dao.session("S2")!!.reportPending, "장면 세션은 report_pending을 참으로 쓰지 않는다")
        phone(); session(); store.handle(CloseSession("S1"))
        val id = dao.session("S1")!!.reportMessageId
        session()   // 늦게 온 같은 세션 저장이 리포트 id를 지우지 않는다
        assertEquals(id, dao.session("S1")!!.reportMessageId)
        assertEquals(false, dao.session("S1")!!.reportPending)
    }

    @Test fun logAlarm_joinsContinuousAlarms_g3() {
        session(ended = null)
        store.handle(LogAlarm("S1", T0 + 1_000, AlarmOnly.IN_CALL))
        store.handle(LogAlarm("S1", T0 + 2_000, AlarmOnly.IN_CALL))
        store.handle(LogAlarm("S1", T0 + 60_000, AlarmOnly.IN_CALL))
        store.handle(LogAlarm("S1", T0 + 61_000, AlarmOnly.WAITING))
        assertEquals(3, count("SELECT COUNT(*) FROM alarm_log"))
        assertEquals(2_000, count("SELECT last_at - $T0 FROM alarm_log ORDER BY id LIMIT 1"), "이어진 경보는 첫 줄의 last_at만 늘린다")
    }

    @Test fun logAlarm_gapOfThreeSteps_startsNewRow() {
        session(ended = null)
        assertEquals(975L, Params().yamnetHopMs)
        store.handle(LogAlarm("S1", T0, AlarmOnly.IN_CALL))
        store.handle(LogAlarm("S1", T0 + 975, AlarmOnly.IN_CALL))
        assertEquals(1, count("SELECT COUNT(*) FROM alarm_log"), "한 걸음(975ms) 간격은 이어진 경보")
        store.handle(LogAlarm("S1", T0 + 975 + 2_925, AlarmOnly.IN_CALL))
        assertEquals(2, count("SELECT COUNT(*) FROM alarm_log"), "세 걸음(2,925ms) 간격은 끊긴 경보라 새 줄")
    }

    private fun profile(feeding: Long?) = kr.aigyeot.app.store.db.ChildProfileEntity("c", "콩이", "2026-03", "LEFT", 180, feeding, emptyList(), false, false, T0)

    @Test fun saveProfile_keepsLaterFeedingStart() {
        dao.upsertProfile(profile(T0 + 10))
        store.saveProfile(profile(T0).copy(nickname = "새 별명"))   // 설정 화면은 열릴 때의(더 이른) 수유 시각을 들고 있다
        assertEquals(T0 + 10, store.feedingStart(), "폰이 커밋한 더 나중 수유 시각을 되돌리지 않는다")
        assertEquals("새 별명", dao.profile()!!.nickname)
    }

    @Test fun saveProfile_noRow_usesGivenFeedingStart() {
        assertNull(dao.profile())
        store.saveProfile(profile(T0))
        assertEquals(T0, store.feedingStart())
    }

    @Test fun report_hugeSession_truncatedUnderEnvelopeLimit_oldestFirst() {
        session()
        val n = 3_000
        for (i in 0 until n) {
            dao.upsertEpisode(EpisodeEntity("E%05d".format(i), "S1", T0 + i * 1_000L, T0 + i * 1_000L + 500, "ALARM", T0 + i * 1_000L, 1_000, null, null, null, null,
                null, null, null, null, null, null, false, false, 0, false, 0, emptyList()))
        }
        assertEquals(n, dao.episodes("S1").size)
        val r = buildReport(dao, "S1", now)!!
        val size = encodeReport(r).toByteArray().size
        assertTrue(r.timeline.size < n, "줄었다")
        assertTrue(size <= 64 * 1024 - 2048, "봉투 여유를 뺀 한도 아래여야 한다: $size")
        assertEquals(true, r.truncated)
        assertEquals("E%05d".format(n - 1), r.timeline.last().episodeId, "새 것은 남는다")
        assertTrue(r.timeline.first().episodeId > "E00000", "오래된 것부터 줄었다")
    }

    @Test fun saveFeedingStart_onlyLater() {
        dao.upsertProfile(kr.aigyeot.app.store.db.ChildProfileEntity("c", "콩이", "2026-03", "LEFT", 180, T0, emptyList(), false, false, T0))
        store.handle(SaveFeedingStart(T0 - 1))
        assertEquals(T0, store.feedingStart())
        store.handle(SaveFeedingStart(T0 + 1))
        assertEquals(T0 + 1, store.feedingStart())
    }

    @Test fun methods_thirdCandidate_selectedElseNewest() {
        dao.upsertProfile(kr.aigyeot.app.store.db.ChildProfileEntity("c", "콩이", "2026-03", "LEFT", null, null,
            listOf(Method(MethodKind.WHITE_NOISE), Method(MethodKind.LULLABY), Method(MethodKind.SONG)), false, false, T0))
        assertEquals(listOf(Method(MethodKind.WHITE_NOISE), Method(MethodKind.LULLABY)), store.methods(), "쓸 곡이 없으면 ③ 없음")
        fun song(id: String, seq: Long, status: String = "ACTIVE") = sql("INSERT OR REPLACE INTO song (id, name, received_at, format, duration_s, lufs, normalized_path, status, reject_reason, added_seq, last_seq) VALUES ('$id', '$id', $T0, 'AAC', 60.0, -30.0, NULL, '$status', NULL, $seq, $seq)")
        song("a", 1); song("b", 2)
        assertEquals(Method(MethodKind.SONG, "b"), store.methods().last(), "고른 적 없으면 added_seq가 가장 큰 곡")
        sql("INSERT INTO song_selection (song_id, seq) VALUES ('a', 5)")
        assertEquals(Method(MethodKind.SONG, "a"), store.methods().last(), "고른 곡")
        song("a", 1, "DELETED")
        assertEquals(Method(MethodKind.SONG, "b"), store.methods().last(), "고른 곡이 지워지면 다시 가장 새 곡")
    }

    @Test fun methods_thirdCandidate_sameRuleAsLink() {
        dao.upsertProfile(kr.aigyeot.app.store.db.ChildProfileEntity("c", "콩이", "2026-03", "LEFT", null, null,
            listOf(Method(MethodKind.WHITE_NOISE), Method(MethodKind.LULLABY), Method(MethodKind.SONG)), false, false, T0))
        fun song(id: String, seq: Long?, status: String = "ACTIVE") = sql("INSERT OR REPLACE INTO song (id, name, received_at, format, duration_s, lufs, normalized_path, status, reject_reason, added_seq, last_seq) VALUES ('$id', '$id', $T0, 'AAC', 60.0, -30.0, NULL, '$status', NULL, ${seq ?: "NULL"}, ${seq ?: 0})")
        val link = db.linkDao()
        fun third() = store.methods().firstOrNull { it.kind == MethodKind.SONG }?.songId
        fun rule() = kr.aigyeot.app.link.candidateSong(link.songs(), link.selection())
        song("n", null); song("a", -2); song("b", 3, "PENDING")
        assertEquals(rule(), third()); assertEquals("a", third())
        sql("INSERT INTO song_selection (song_id, seq) VALUES ('b', 9)")   // 고른 곡이 아직 쓸 수 없다
        assertEquals(rule(), third()); assertEquals("a", third())
        song("b", 3)
        assertEquals(rule(), third()); assertEquals("b", third())
    }

    @Test fun report_carActions_countOnlySucceededControls() {
        fullSession()   // 열선 성공 한 줄
        dao.insertControl(ControlLogEntity(sessionId = "S1", at = T0 + 11_000, api = "Seat.setHvacSeatTemperature", target = "ROW_2_RIGHT", goal = "0", actual = "3", ackMs = 120, appliedMs = null, ok = false, retries = 1))
        dao.insertControl(ControlLogEntity(sessionId = "S1", at = T0 + 12_000, api = "setWindowLock", target = "ROW_2_LEFT", goal = "1", actual = "0", ackMs = 120, appliedMs = null, ok = false, retries = 1))
        val r = buildReport(dao, "S1", now)!!
        val acts = r.carActions.associate { it.action to it.count }
        assertEquals(1, acts[kr.aigyeot.shared.CarActionKind.SEAT_HEATER_OFF], "car_actions는 차가 대신 한 일: 실패 줄은 세지 않는다")
        assertEquals(0, acts[kr.aigyeot.shared.CarActionKind.REAR_WINDOW_LOCK] ?: 0, "실패한 잠금만 있으면 항목 없음")
        assertEquals(1, r.controls.single { it.target == "ROW_2_RIGHT" }.failed, "실패는 controls에 남는다")
    }

    @Test fun contextRecorder_capturesMinus30ToPlus30_everySecond() {
        session(ended = null)
        dao.upsertEpisode(EpisodeEntity("E1", "S1", T0, null, "ALARM", T0 + 40_000, 1_000, null, null, null, null, null, null, null, null, null, null, false, false, 0, false, 0, emptyList()))
        val s = EngineState.fresh(1, null, emptyList(), emptyList(), null, 0).let { it.copy(car = it.car.copy(gear = Gear.D)) }
        var t = T0
        while (t <= T0 + 40_000) { store.observe(VehicleSample(t, 40.0, false, 0.0, TurnSignal.NONE), s); t += 100 }
        store.handle(CaptureContext("E1", T0 + 40_000))
        while (t <= T0 + 80_000) { store.observe(VehicleSample(t, 40.0, false, 0.0, TurnSignal.NONE), s); t += 100 }
        val rows = dao.context("E1")
        // 줄의 at은 칸의 끝이다: T0+10초 줄은 9~10초 칸, T0+70초 줄은 69~70초 칸(경보 +30초까지 마감된 칸)
        assertEquals(T0 + 10_000, rows.first().at); assertEquals(T0 + 70_000, rows.last().at)
        assertEquals(61, rows.size)
        assertTrue(rows.zipWithNext().all { (a, b) -> b.at - a.at == 1_000L })
    }

    private fun stateWith(playing: Boolean): EngineState {
        val base = EngineState.fresh(1, null, emptyList(), emptyList(), null, 0)
        val play = Play("P", PlayKind.AUTO, PlayPurpose.AUTO_FIRST, Method(MethodKind.LULLABY), if (playing) T0 + 5_200 else null, T0 + 60_000, PlayMode.NORMAL)
        val ses = SessionState(
            id = "S1", openedAt = T0, trigger = OpenTrigger.DOOR_THEN_D, methods = emptyList(), phase = Phase.ATTEMPT, attemptNo = 1, play = play,
            judgeFrom = null, windowEnd = null, episode = null, pauses = emptySet(), quietMs = 0, playedMs = 0, capReached = false, driveFrom = null,
            longDriveOffered = false, lastPAt = null, carseatFrom = T0, s17Said = false, micNoticeArmed = false, lastOffer = emptyMap(), pendingAsk = null,
            windowLockBefore = null, frontAudible = false, comfortDone = false, nursingStop = null, waypointPending = null, recordsCleared = false, pendingAlarm = null,
        )
        return base.copy(car = base.car.copy(gear = Gear.D), session = ses)
    }

    @Test fun preCry_playbackStartedAfterAlarmInSameSecond_isNotPlaying() {
        session(ended = null)
        val alarm = T0 + 5_100
        val ep = EpisodeEntity("E1", "S1", T0, null, "ALARM", alarm, 1_000, null, null, null, null, null, null, null, null, null, null, false, false, 0, false, 0, emptyList())
        dao.upsertEpisode(ep)
        val before = stateWith(false); val after = stateWith(true)
        var t = T0
        while (t <= alarm) { store.observe(VehicleSample(t, 40.0, false, 0.0, TurnSignal.NONE), before); t += 100 }
        store.handle(CaptureContext("E1", alarm))
        while (t <= T0 + 7_000) { store.observe(VehicleSample(t, 40.0, false, 0.0, TurnSignal.NONE), after); t += 100 }   // +200ms에 재생이 시작된다
        val rows = dao.context("E1")
        assertTrue(rows.any { it.at > alarm && it.playback != null }, "경보 뒤 칸에는 재생 중이 담겼다(전제)")
        assertTrue(rows.any { it.at <= alarm }, "경보 이전 줄이 있다(전제)")
        assertEquals(false, preCry(ep, rows)!!.playing)
    }

    @Test fun contextRecorder_clear_dropsOpenBucket() {
        val c = ContextRecorder()
        val s = stateWith(false)
        c.observe(VehicleSample(T0 + 100, 40.0, false, 0.0, TurnSignal.NONE), s)
        c.observe(CrySegment(T0 + 200, T0, T0 + 200, 0.9, true), s)   // 작성 중인 칸(T0~T0+1초)에 울음 확률이 담긴다
        c.clear()
        c.observe(VehicleSample(T0 + 1_000, 40.0, false, 0.0, TurnSignal.NONE), s)
        c.observe(VehicleSample(T0 + 2_000, 40.0, false, 0.0, TurnSignal.NONE), s)
        val rows = c.capture("E", T0 + 1_000)
        assertEquals(1, rows.size, "지우기 전 칸은 마감되지 않고, 지운 뒤 칸 하나만 있다(전제)")
        assertNull(rows[0].cryP, "지우기 전 울음 확률이 남지 않는다")
    }

    @Test fun purgeOld_90days_keepsOpenAndRecent() {
        session("old", ended = T0 + 1)
        session("open", ended = null)
        dao.upsertPlay(PlayLogEntity("P", "old", null, "REQUEST", "LULLABY", null, T0, T0 + 1, 1, "DEADLINE"))
        now = T0 + 91 * Store.DAY
        session("new", ended = now - 1)
        store.purgeOld()
        assertNull(dao.session("old")); assertEquals(0, count("SELECT COUNT(*) FROM play_log"), "자식 행도 함께")
        assertEquals(2, count("SELECT COUNT(*) FROM session"))
    }

    @Test fun reversals_countsThresholdTurns() {
        assertEquals(0, reversals(listOf(0.0, 1.0, 2.0, 1.5, 2.5), 3.0))
        assertEquals(2, reversals(listOf(0.0, 5.0, 1.0, 6.0), 3.0))
    }

    @Test fun steering_afterResponse_onlyLaterSameBandPlayingWindows() {
        val cry = SteerWindowEntity("S1", T0, 2, false, true, false, 6)
        val ok = SteerWindowEntity("S1", T0 + 60_000, 2, false, false, true, 8)
        val otherBand = ok.copy(startAt = T0 + 120_000, speedBand = 4, reversals = 100)
        val before = ok.copy(startAt = T0 - 60_000, reversals = 50)
        val base = (1..5).map { SteerWindowEntity("S0", T0 - it * 60_000, 2, false, false, false, it) }
        val s = steering(listOf(before, cry, ok, otherBand), base)
        assertTrue(s.available && before.startAt < cry.startAt && otherBand.speedBand !in setOf(cry.speedBand))
        assertEquals(8.0, s.afterResponsePerMin, "울음 창 뒤, 같은 속도대 창만 센다")
    }

    @Test fun steering_reasonNoCry_onlyWhenSessionHasNoCryWindow() {                  // C112
        val base = (1..5).map { SteerWindowEntity("S0", T0 - it * 60_000, 2, false, false, false, it) }
        val noCry = SteerWindowEntity("S1", T0, 2, false, false, false, 4)
        val excludedCry = SteerWindowEntity("S1", T0, 2, true, true, false, 6)
        assertEquals(SteeringUnavailableReason.NO_CRY, steering(listOf(noCry), base).unavailableReason)
        assertEquals(SteeringUnavailableReason.NO_CRY, steering(emptyList(), base).unavailableReason)
        assertEquals(SteeringUnavailableReason.NO_COMPARABLE_WINDOW, steering(listOf(excludedCry), base).unavailableReason, "울음 창이 있었지만 모두 제외")
        assertEquals(SteeringUnavailableReason.NO_COMPARABLE_WINDOW, steering(listOf(excludedCry, noCry), base).unavailableReason)
        assertEquals(SteeringUnavailableReason.NO_COMPARABLE_WINDOW, steering(listOf(excludedCry.copy(excluded = false, speedBand = 4)), base).unavailableReason, "기존: 평소 창 없는 속도대")
    }

    @Test fun round1_halfUp_toOneDecimal() {                                           // C112
        assertEquals(80.0, round1(80.00000381469727))
        assertEquals(58.3, round1(58.25))
        assertEquals(58.2, round1(58.24))
        assertEquals(0.1, round1(0.05))
    }

    @Test fun preCry_speedRoundedToOneDecimal() {                                      // C112
        val alarm = T0 + 5_000
        val ep = EpisodeEntity("E1", "S1", T0, null, "ALARM", alarm, 1_000, null, null, null, null, null, null, null, null, null, null, false, false, 0, false, 0, emptyList())
        fun row(at: Long, v: Double) = ContextSampleEntity("E1", at, v, false, 0.0, "NONE", "D", 24.0, null, null, null)
        val c = preCry(ep, listOf(row(alarm - 1_000, 80.00000381469727), row(alarm, 58.25)))!!
        assertEquals(80.0, c.speedKmhMax)
        assertEquals(69.1, c.speedKmhAvg)      // (80.0000038 + 58.25) / 2 = 69.125
    }

    @Test fun steering_comparesSameBandBaseline() {
        val cry = SteerWindowEntity("S1", T0, 2, false, true, false, 6)
        val base = (1..5).map { SteerWindowEntity("S0", T0 - it * 60_000, 2, false, false, false, it) }
        val s = steering(listOf(cry), base)
        assertTrue(s.available); assertEquals(3.0, s.baselinePerMin); assertEquals(6.0, s.duringCryPerMin)
        assertEquals(false, steering(listOf(cry), base.map { it.copy(speedBand = 4) }).available)
        assertEquals(false, steering(emptyList(), base).available)
    }

    @Test fun stats_onlyCurrentSongAndLast28Days() {
        val third = Method(MethodKind.SONG, "b")
        val methods = listOf(Method(MethodKind.WHITE_NOISE), Method(MethodKind.LULLABY), third)
        val d = Store.DAY
        val h = listOf(
            *Array(3) { FirstTry(T0 - it * d, Method(MethodKind.SONG, "a"), true) },          // 다른 곡의 성공
            *Array(2) { FirstTry(T0 - it * d, third, false) },                                // 지금 곡은 2번뿐
            *Array(3) { FirstTry(T0 - 29 * d - it, Method(MethodKind.LULLABY), true) },       // 29일 전
        )
        val out = kr.aigyeot.app.ui.stats(h, methods, T0)
        assertEquals("부모 노래: 아직 모름", out.last(), "다른 곡의 성공이 섞이지 않는다")
        assertEquals("자장가: 아직 모름", out[1], "29일 전 시도는 세지 않는다")
        assertEquals(2, kr.aigyeot.app.ui.stats(h, methods.dropLast(1), T0).size, "③이 없으면 부모 노래 줄을 뺀다")
        val more = h + FirstTry(T0, third, true)
        assertEquals("부모 노래: 3번 중 1번", kr.aigyeot.app.ui.stats(more, methods, T0).last())
    }

    // ---- 검토 수정 C6~C8 ----

    @Test fun report_hugeControls_stillUnderLimit() {
        session()
        for (i in 0 until 5_000) dao.insertControl(ControlLogEntity(sessionId = "S1", at = T0 + i, api = "Api$i", target = "T$i", goal = "0", actual = "0", ackMs = 1, appliedMs = 1, ok = true, retries = 0))
        val r = buildReport(dao, "S1", now)!!
        val size = encodeReport(r).toByteArray().size
        assertTrue(dao.controls("S1").size == 5_000, "제어 기록이 많다(전제)")
        assertTrue(size <= 64 * 1024 - 2048, "한도 아래여야 한다: $size")
        assertEquals(true, r.truncated)
    }

    @Test fun baselineWindows_excludesScenarioSessions() {
        session()   // 실제 세션 S1, driver 10
        Store(db, { now }, scenarioId = { "A" }).handle(SaveSession(SessionRecord("S2", T0, T0 + 1, OpenTrigger.DOOR_THEN_D, null, null, 0, 0, false, "[]", null, null, false, null, 10)))
        dao.upsertSteer(SteerWindowEntity("S1", T0, 2, false, false, false, 3))
        dao.upsertSteer(SteerWindowEntity("S2", T0, 2, false, false, false, 30))
        assertEquals(listOf("S1"), dao.baselineWindows(10).map { it.sessionId }, "장면 세션 창은 평소 기준선에 섞이지 않는다")
    }

    @Test fun steering_perBand_noChangeWhenOnlyBandMixDiffers() {
        fun w(at: Int, band: Int, r: Int, cry: Boolean = false, play: Boolean = false) = SteerWindowEntity("S1", T0 + at * 60_000L, band, false, cry, play, r)
        val base = (1..6).map { w(-it, 2, 2) } + (7..8).map { w(-it, 4, 20) }       // 속도대 2가 훨씬 많다
        val s = listOf(w(0, 2, 2, cry = true)) + (1..4).map { w(it, 4, 20, cry = true) } + w(9, 4, 20, play = true)
        val r = steering(s, base)
        assertEquals(r.baselinePerMin, r.duringCryPerMin, "속도대 비중만 달라도 증가로 보지 않는다")
        assertEquals(r.baselinePerMin, r.afterResponsePerMin)
        assertTrue(r.duringCryZ == null || r.duringCryZ == 0.0, "mad가 0이면 null, 아니면 0")
    }

    @Test fun steering_ignoresCryWindowsInBandsWithoutBaseline() {
        val base = (1..5).map { SteerWindowEntity("S0", T0 - it * 60_000, 2, false, false, false, it) }
        val cry = listOf(SteerWindowEntity("S1", T0, 2, false, true, false, 2)) +
            (1..2).map { SteerWindowEntity("S1", T0 + it * 60_000, 4, false, true, false, 20) }
        assertTrue(cry.any { it.speedBand == 4 } && base.none { it.speedBand == 4 }, "평소 창 없는 속도대의 울음 창이 있다(전제)")
        val s = steering(cry, base)
        assertEquals(2.0, s.duringCryPerMin)
        assertEquals(false, steering(cry.filter { it.speedBand == 4 }, base).available, "비교할 창이 없다")
    }

    @Test fun contextRecorder_reset_dropsScenarioSamples() {
        val c = ContextRecorder()
        val s = stateWith(false)
        c.observe(VehicleSample(T0 + 100, 99.0, false, 0.0, TurnSignal.NONE), s)
        c.observe(Tick(T0 + 1_100), s)   // 장면 표본이 든 칸이 마감돼 recent에 들어간다
        assertEquals(1, c.capture("X", T0 + 1_000).size, "reset 전에는 장면 칸이 나온다(전제)")
        c.reset()
        c.observe(Tick(T0 + 2_100), s)
        c.observe(Tick(T0 + 3_100), s)
        assertTrue(c.capture("E", T0 + 2_000).isEmpty(), "reset 뒤 이전 표본이 캡처에 나오지 않는다")
    }
}
