package kr.aigyeot.phone.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kr.aigyeot.shared.DeviceInfo
import kr.aigyeot.shared.Envelope
import kr.aigyeot.shared.Platform
import kr.aigyeot.shared.ProtocolJson
import kr.aigyeot.shared.Role
import kr.aigyeot.shared.SongRejectReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.time.ZoneOffset

/** 폰 메시지 처리·상태 로직(계약 3.2·5.6·7.4·7.5). 예제는 shared/의 계약 JSON을 그대로 쓴다. */
class PhoneStateTest {
    private val now = 1_762_300_000_000L
    private val owner = PhoneState.paired("c_7F3AK2N9X0Q4M8T1V6B5R3H2CD", "d_5KQ2M8T1V6B5R3H2CD7F3AK2N9", Role.OWNER, now)
    private val guardian = owner.copy(role = Role.GUARDIAN)

    private fun fixture(name: String): Envelope =
        ProtocolJson.decodeFromString(Envelope.serializer(), File("../../shared/src/test/resources/protocol/$name.json").readText())

    /** 중계가 전달한 모양으로(seq·car를 채워서). 보내는 쪽 예제에는 car가 없다. */
    private fun Envelope.withSeq(seq: Long, id: String = ulid()) = copy(seq = seq, id = id, car = owner.carId)

    @Test
    fun 연결_코드_정규화와_링크_검사() {
        assertEquals("042917", normalizeCode(" 042-917 "))
        assertEquals("7K3QM2XW9D", normalizeCode("7k3qm-2xw9d"))
        assertEquals("0110000000", normalizeCode("OIL0000000")) // Crockford: O→0, I·L→1
        assertNull(normalizeCode("12345"))
        assertNull(normalizeCode("7K3QM2XW9U")) // U는 Crockford에 없다
        assertEquals("042917", codeFromLink("aigyeot://pair?code=042917"))
        assertEquals("7K3QM2XW9D", codeFromLink("aigyeot://pair?code=7K3QM-2XW9D"))
        assertNull(codeFromLink("aigyeot://pair?code=evil"))
        assertNull(codeFromLink("https://example.com/pair?code=042917"))
        assertNull(codeFromLink("aigyeot://pair?code=%ZZ")) // 잘못된 인코딩도 죽지 않고 잘못된 링크
        assertTrue(isUlid("01JC9A00Q4M8T1V6B5R3H2CD7F"))
        assertFalse(isUlid("x/y"))
        assertFalse(isUlid("01JC9A00Q4M8T1V6B5R3H2CD7U")) // U는 Crockford에 없다
        assertTrue(isInviteCode("7K3QM2XW9D"))
    }

    @Test
    fun 시각은_초와_시차를_늘_쓴다() {
        val t = 1_762_300_800_000L // 정각(0초)
        assertEquals("2025-11-05T09:00:00+09:00", rfc3339(t, ZoneId.of("Asia/Seoul")))
        assertEquals("2025-11-05T00:00:00Z", rfc3339(t, ZoneOffset.UTC))
        assertEquals(t, instantOf("2025-11-05T09:00:00+09:00"))
        assertEquals(instantOf("2026-11-05T08:12:21+09:00"), instantOf("2026-11-04T23:12:21Z"))
        assertNull(instantOf("어제"))
        assertEquals(1000L, backoffMs(0))
        assertEquals(60_000L, backoffMs(10))
        assertEquals(26, ulid().length)
    }

    @Test
    fun 같은_id는_한_번만_처리한다() {
        val env = fixture("feeding_state")
        val once = owner.receive(env, now, live = true).state
        assertEquals(413L, once.feeding?.seq)
        assertTrue(env.id in once.processed)
        assertEquals(now, once.lastMessageAt) // 차에서 온 메시지
        val again = once.copy(feeding = null).receive(env, now + 1, live = true).state
        assertNull(again.feeding)
    }

    @Test
    fun feeding_state는_큰_seq가_이기고_보낸_시각이면_차에_반영됨() {
        val pressed = owner.startFeeding(now, id = "01JC8Y2K6N3T5Q9W0ZB4H7X2DM")
            .let { it.copy(feedings = it.feedings.map { f -> f.copy(at = "2026-11-04T23:12:21Z") }) }
        val newer = fixture("feeding_state") // seq 413, last_feeding_start 08:12:21+09:00
        val s = pressed.receive(newer, now, live = true).state
        assertTrue(s.feedings.single().reflected)
        val older = newer.copy(id = ulid(), seq = 400, body = buildJsonObject { put("last_feeding_start", null as String?); put("window_start_expected", null as String?) })
        assertEquals(413L, s.receive(older, now, live = true).state.feeding?.seq)
    }

    @Test
    fun song_status는_곡마다_큰_seq가_이기고_거절이면_owner가_지움을_보낸다() {
        val accepted = fixture("song_status") // seq 425, accepted, candidate
        val rejected = fixture("song_status_rejected").withSeq(430)
        val s1 = owner.receive(accepted, now, live = true).state
        assertEquals(SongPhase.ACCEPTED, s1.songs.single().phase)
        assertTrue(s1.songs.single().candidate)
        // 늦게 온 작은 seq는 버린다
        val stale = owner.receive(accepted.withSeq(500), now, live = true).state.receive(rejected.withSeq(420), now, live = true).state
        assertEquals(SongPhase.ACCEPTED, stale.songs.single().phase)

        val s2 = s1.receive(rejected, now, live = true).state
        // 같은 곡의 더 큰 seq 거절이 또 와도 지움 요청은 하나뿐(이미 지움이면 보내지 않는다)
        assertEquals(1, s2.receive(rejected.withSeq(431), now, live = true).state.outbox.size)
        val song = s2.songs.single()
        assertEquals(SongPhase.REJECTED, song.phase)
        assertEquals(SongRejectReason.PEAK_LIMIT, song.reason)
        val removal = s2.outbox.single().envelope
        assertEquals("song_removed", removal.type)
        assertEquals(song.songId, removal.body["song_id"]!!.jsonPrimitive.content)
        // 이 폰에서 A를 골랐어도 차가 B를 후보로 알리면 A는 후보가 아니다(마지막 선택이 이긴다)
        val a = "01JC90A0Q4M8T1V6B5R3H2CD7F"
        val chose = s1.selectSong(a, now)
        val other = accepted.withSeq(600).copy(body = buildJsonObject { put("song_id", a); put("name", "나비야"); put("status", "accepted"); put("reason", null as String?); put("duration_s", 94.5); put("candidate", false) })
        assertFalse(chose.receive(other, now, live = true).state.songs.single { it.songId == a }.candidate)
        // guardian은 보내지 않는다
        assertTrue(guardian.receive(rejected, now, live = true).state.outbox.isEmpty())
        // 이 폰에서 지운 곡은 늦게 온 받음으로 되살리지 않는다
        val removed = s1.removeSong(song.songId, now)
        assertEquals(SongPhase.REMOVED, removed.receive(accepted.withSeq(440), now, live = true).state.songs.single().phase)
        // 이름을 모르는 곡의 removed(L45)
        val unknown = owner.receive(fixture("song_status_removed"), now, live = true).state
        assertEquals(SongPhase.REMOVED, unknown.songs.single().phase)
        assertTrue(unknown.visibleSongs.isEmpty())
    }

    @Test
    fun report_summary는_세션마다_큰_seq가_이긴다() {
        val r = fixture("report_summary")
        val s = owner.receive(r, now, live = false).state
        val sid = s.reports.keys.single()
        assertEquals(455L, s.reports[sid]?.seq)
        val older = r.withSeq(450)
        assertEquals(455L, s.receive(older, now, live = false).state.reports[sid]?.seq)
        val newer = r.withSeq(460)
        assertEquals(460L, s.receive(newer, now, live = false).state.reports[sid]?.seq)
    }

    @Test
    fun 오래된_인계_알림은_배너_없이_목록에만_들어간다() {
        val alert = fixture("handoff_alert")
        val at = { ms: Long -> alert.copy(body = JsonObject(alert.body.toMap() + ("occurred_at" to JsonPrimitive(rfc3339(ms))))) }
        val old = guardian.receive(at(now - 3 * 3600_000L), now, live = true)
        assertNull(old.banner)
        assertEquals(1, old.state.alerts.size)
        assertNotNull(guardian.receive(at(now - 60_000L).copy(id = ulid(now)), now, live = true).banner)
    }

    @Test
    fun 인계_알림은_같은_id를_한_번만_띄운다() {
        val alert = fixture("handoff_alert")
        // WebSocket으로 먼저 오면 배너
        val live = guardian.receive(alert, now, live = true)
        assertNotNull(live.banner)
        assertTrue(alert.id in live.state.shown)
        // 받은 함에서 꺼낸 것은 배너 없이 목록에만
        val fromInbox = guardian.receive(alert, now, live = false)
        assertNull(fromInbox.banner)
        assertEquals(1, fromInbox.state.alerts.size)
        // 이미 띄운 id는 뒤에 다시 와도 띄우지 않는다
        val again = live.state.copy(processed = emptyMap()).receive(alert, now, live = true)
        assertNull(again.banner)
        // 다른 차의 늦은 푸시: 본문이 지금 차 것이 아니면 배너도 목록도 없다(앱은 본문 확인 뒤에만 띄운다)
        val otherCar = guardian.copy(carId = "c_OTHER00000000000000000000").receive(alert, now, live = true)
        assertNull(otherCar.banner)
        assertTrue(otherCar.state.alerts.isEmpty())
    }

    @Test
    fun 다른_차_방의_메시지와_모르는_type은_적용하지_않는다() {
        val other = fixture("feeding_state").copy(car = "c_OTHER00000000000000000000")
        assertNull(owner.receive(other, now, live = true).state.feeding)
        assertNull(owner.receive(fixture("feeding_state").copy(car = null), now, live = true).state.feeding)
        assertNull(PhoneState().receive(fixture("feeding_state"), now, live = true).state.feeding) // 연결 전
        val unknown = fixture("ack").copy(type = "future_type", seq = 1)
        assertEquals(owner, owner.receive(unknown, now, live = true).state)
    }

    @Test
    fun 수유_보내기_결과() {
        val s = owner.startFeeding(now)
        val item = s.outbox.single()
        assertEquals(item, s.nextToSend(now))
        // 네트워크 오류: 1·2·4…초 뒤 다시
        val offline = s.afterSend(item, SendResult.Offline, now)
        assertEquals(now + 1000, offline.outbox.single().nextTryAt)
        assertNull(offline.nextToSend(now))
        // 성공: 중계가 고친 시각을 보여 준다
        val fixed = "2026-11-05T08:12:21Z"
        val sent = s.afterSend(item, SendResult.Sent(buildJsonObject { put("at", fixed) }), now + 5)
        assertTrue(sent.outbox.isEmpty())
        assertEquals(fixed, sent.feedings.single().at)
        assertEquals(now + 5, sent.feedings.single().sentAt)
        // feeding_state(고친 시각)가 POST 응답보다 먼저 와도 '차에 반영됨'을 놓치지 않는다
        val early = s.receive(fixture("feeding_state"), now, live = true).state // 08:12:21+09:00 = 2026-11-04T23:12:21Z
        assertFalse(early.feedings.single().reflected)
        val late = early.afterSend(item, SendResult.Sent(buildJsonObject { put("at", "2026-11-04T23:12:21Z") }), now + 9)
        assertTrue(late.feedings.single().reflected)
        // 다시 보내도 같은 실패는 버리고 알린다
        val bad = s.afterSend(item, SendResult.Failed(400, "bad_request"), now)
        assertTrue(bad.outbox.isEmpty())
        assertNotNull(bad.feedings.single().failed)
        // 429는 Retry-After 뒤
        assertEquals(now + 30_000, s.afterSend(item, SendResult.Failed(429, "rate_limited", 30), now).outbox.single().nextTryAt)
        // 401은 그대로(연결 끊김은 부르는 쪽)
        assertEquals(s, s.afterSend(item, SendResult.Failed(401, "unauthorized"), now))
    }

    @Test
    fun 버려진_노래_삭제와_선택은_안내가_있다() {
        val id = "01JC90A0Q4M8T1V6B5R3H2CD7F"
        assertNotNull(dropNotice("song_removed"))
        assertNotNull(dropNotice("song_selected"))
        assertNull(dropNotice("song_added"))
        val s = owner.addSong(id, "나비야", 94.5, "x.m4a", now).removeSong(id, now)
        val item = s.outbox.single { it.envelope.type == "song_removed" }
        assertTrue(s.afterSend(item, SendResult.Failed(403, "forbidden"), now).outbox.none { it.envelope.id == item.envelope.id })
    }

    @Test
    fun 노래는_파일을_먼저_올린_뒤_song_added() {
        val s = owner.addSong("01JC90A0Q4M8T1V6B5R3H2CD7F", "나비야", 94.5, "x.m4a", now)
        val item = s.outbox.single()
        assertEquals("song_added", item.envelope.type)
        assertEquals("01JC90A0Q4M8T1V6B5R3H2CD7F", item.songId)
        val up = s.afterSend(item, SendResult.Uploaded, now)
        assertTrue(up.outbox.single().uploaded)
        val missing = up.afterSend(up.outbox.single(), SendResult.Failed(422, "song_blob_missing"), now)
        assertFalse(missing.outbox.single().uploaded)
        val quota = up.afterSend(up.outbox.single(), SendResult.Failed(409, "song_quota"), now)
        assertEquals(1, quota.outbox.single().attempts)
        val done = up.afterSend(up.outbox.single(), SendResult.Sent(item.envelope.body), now)
        assertEquals(SongPhase.WAITING, done.songs.single().phase)
        val tooBig = s.afterSend(item, SendResult.Failed(413, "too_large"), now)
        assertTrue(tooBig.outbox.isEmpty())
        assertNotNull(tooBig.songs.single().error)
        // 지우면 올리기는 빼고 song_removed를 보낸다
        val removed = s.removeSong("01JC90A0Q4M8T1V6B5R3H2CD7F", now)
        assertEquals(listOf("song_removed"), removed.outbox.map { it.envelope.type })
        assertTrue(removed.visibleSongs.isEmpty())
        val picked = owner.selectSong("01JC90A0Q4M8T1V6B5R3H2CD7F", now)
        assertEquals("song_selected", picked.outbox.single().envelope.type)
        assertTrue(picked.selecting("01JC90A0Q4M8T1V6B5R3H2CD7F"))
        assertFalse(picked.selecting("01JC90B0Q4M8T1V6B5R3H2CD7F"))
    }

    @Test
    fun 상태_요청은_24시간_넘게_못_받았을_때_owner만_10분_간격으로() {
        val stale = owner.copy(lastMessageAt = now - 25 * HOUR).onAppOpen(now)
        assertTrue(stale.stateRequestNeeded)
        assertFalse(owner.onAppOpen(now).stateRequestNeeded)
        assertTrue(stale.shouldRequestState(now))
        assertFalse(stale.copy(role = Role.GUARDIAN).shouldRequestState(now))
        assertFalse(stale.copy(lastStateRequestAt = now - 5 * MINUTE).shouldRequestState(now))
        assertTrue(stale.copy(lastStateRequestAt = now - 11 * MINUTE).shouldRequestState(now))
        // 앞선 요청의 늦은 201은 그 뒤 새로 켠 표시를 끄지 않는다
        val gen = stale.stateNeedGen
        assertFalse(stale.stateRequestDone(gen).stateRequestNeeded)
        val renewed = stale.clearedRecords()
        assertTrue(renewed.stateRequestDone(gen).stateRequestNeeded)
        assertFalse(renewed.stateRequestDone(renewed.stateNeedGen).stateRequestNeeded)
        // 기록을 지우면 연결은 남기고 상태 요청을 켠다. 마지막 요청 시각과 수유 보낼 함은 남긴다.
        val withData = owner.receive(fixture("report_summary"), now, live = false).state
            .copy(lastStateRequestAt = now - MINUTE)
            .startFeeding(now)
            .addSong("01JC90A0Q4M8T1V6B5R3H2CD7F", "나비야", 94.5, "x.m4a", now)
        val cleared = withData.clearedRecords()
        assertTrue(cleared.reports.isEmpty())
        assertTrue(cleared.songs.isEmpty())
        assertTrue(cleared.stateRequestNeeded)
        assertEquals(owner.carId, cleared.carId)
        assertEquals(now - MINUTE, cleared.lastStateRequestAt)
        assertFalse(cleared.shouldRequestState(now)) // 10분 간격은 지킨다
        assertEquals(listOf("feeding_started"), cleared.outbox.map { it.envelope.type })
        assertEquals(setOf("x.m4a"), withData.songFiles)
        assertTrue(cleared.songFiles.isEmpty())
    }

    @Test
    fun 만료된_보낼_함은_수유와_곡에_만료_표시를_남긴다() {
        val t = owner.startFeeding(now).addSong("s_1", "a", 30.0, "s_1.m4a", now)
        val p = t.pruned(now + 8 * DAY)
        assertTrue(p.outbox.isEmpty())
        assertNotNull(p.feedings.single().failed)
        assertNotNull(p.songs.single().error)
        // 이미 반영된 수유는 건드리지 않는다.
        val done = t.copy(feedings = t.feedings.map { it.copy(reflected = true) }).pruned(now + 8 * DAY)
        assertNull(done.feedings.single().failed)
        // 7일이 안 됐으면 그대로다.
        val early = t.pruned(now + 6 * DAY)
        assertNull(early.feedings.single().failed)
        assertNull(early.songs.single().error)
    }

    @Test
    fun 만료_뒤_반영_상태를_받으면_수유_실패_표시가_지워진다() {
        val t = owner.startFeeding(now)
        val expired = t.pruned(now + 8 * DAY)
        assertNotNull(expired.feedings.single().failed)
        val at = expired.feedings.single().at
        val body = buildJsonObject { put("last_feeding_start", at); put("window_start_expected", null as String?) }
        val env = fixture("feeding_state").copy(body = body).withSeq(99)
        val r = expired.receive(env, now + 8 * DAY, live = false).state
        assertTrue(r.feedings.single().reflected)
        assertNull(r.feedings.single().failed)
    }

    @Test
    fun 만료_뒤_반영_상태를_받으면_곡_실패_표시가_지워진다() {
        val expired = owner.addSong("s_1", "a", 30.0, "s_1.m4a", now).pruned(now + 8 * DAY)
        assertNotNull(expired.songs.single().error)
        val body = buildJsonObject { put("song_id", "s_1"); put("name", "a"); put("status", "accepted"); put("reason", null as String?); put("duration_s", 30.0); put("candidate", true) }
        val env = fixture("song_status").copy(body = body).withSeq(99)
        val r = expired.receive(env, now + 8 * DAY, live = false).state
        assertEquals(SongPhase.ACCEPTED, r.songs.single().phase)
        assertNull(r.songs.single().error)
    }

    @Test
    fun 차_봉투를_받으면_차_타일이_바로_연결됨이_된다() {
        val car = DeviceInfo("d_car", Role.CAR, Platform.ANDROID, "2026-11-05T08:00:00Z", "2026-11-04T08:00:00Z", online = false)
        val s = owner.copy(devices = listOf(car))
        val r = s.receive(fixture("feeding_state").withSeq(5), now, live = true).state
        assertTrue(r.devices.single().online)
        assertEquals(now / 1000, instantOf(r.devices.single().lastSeenAt)!! / 1000)
        // 받은 함에서 꺼낸(실시간 아닌) 차 봉투는 차 행을 바꾸지 않는다.
        val old = s.receive(fixture("feeding_state").withSeq(5), now, live = false).state
        assertEquals(car, old.devices.single())
    }

    @Test
    fun 만료된_보낼_함_항목은_보내지_않는다() {
        val t = owner.startFeeding(now)
        assertNotNull(t.nextToSend(now + 6 * DAY))
        assertNull(t.nextToSend(now + 8 * DAY))
    }

    @Test
    fun 만료_항목이_맨_앞이면_정리_뒤_다음_항목이_선택된다() {
        val t = owner.startFeeding(now).startFeeding(now + 7 * DAY)
        val later = now + 8 * DAY
        assertTrue(t.frontExpired(later))
        assertNull(t.nextToSend(later)) // 정리 전에는 보내지 않는다
        val p = t.pruned(later)
        assertFalse(p.frontExpired(later))
        assertEquals(t.outbox[1].envelope.id, p.nextToSend(later)?.envelope?.id)
    }

    @Test
    fun 오래된_id와_기록을_버리고_상태는_파일로_왕복한다() {
        val s = owner.receive(fixture("report_summary"), now, live = false).state.startFeeding(now)
        assertTrue(s.pruned(now + 8 * DAY).processed.isEmpty())
        assertTrue(s.pruned(now + 8 * DAY).outbox.isEmpty())
        assertEquals(1, s.pruned(now + 8 * DAY).reports.size)
        assertTrue(s.pruned(now + 91 * DAY).reports.isEmpty())
        val text = ProtocolJson.encodeToString(PhoneState.serializer(), s)
        assertEquals(s, ProtocolJson.decodeFromString(PhoneState.serializer(), text))
        assertEquals(JsonPrimitive("feeding_started"), JsonPrimitive(s.outbox.single().envelope.type))
    }

    @Test
    fun 상태_파일은_커밋이_확인될_때만_성공한다() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val store = kr.aigyeot.phone.data.StateStore(File(dir, "state.json"))
        val s = owner.startFeeding(now)
        store.save(s)
        assertEquals(s, store.load())
        // 이름을 바꿀 자리가 비어 있지 않은 폴더면 실패를 알려야 한다(AtomicFile은 기록만 하고 돌아올 수 있다).
        val blocked = File(dir, "blocked.json").apply { mkdirs(); File(this, "x").writeText("x") }
        val failing = kr.aigyeot.phone.data.StateStore(blocked)
        assertTrue(runCatching { failing.save(s) }.isFailure)
        dir.deleteRecursively()
    }

    @Test
    fun 옛_판_상태_파일은_기본값으로_읽고_깨진_파일은_옆에_옮겨_둔다() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val f = File(dir, "phone_state.json")
        // deviceMarks·devicesSnapshotSeq·stateNeedGen·handoffAlerts가 없던 판, 모르는 필드 하나
        f.writeText(
            """{"car_id":"c_7F3AK2N9X0Q4M8T1V6B5R3H2CD","my_device_id":"d_5KQ2M8T1V6B5R3H2CD7F3AK2N9","role":"owner",""" +
                """"songs":[{"song_id":"01JC90A0Q4M8T1V6B5R3H2CD7F","name":"나비야","file":"a.m4a","phase":"ACCEPTED"}],""" +
                """"feedings":[{"msg_id":"01JC8Y2K6N3T5Q9W0ZB4H7X2DM","at":"2026-11-05T08:12:21+09:00"}],"future_field":1}""",
        )
        val old = kr.aigyeot.phone.data.StateStore(f).load()
        assertEquals(Role.OWNER, old.role)
        assertEquals(setOf("a.m4a"), old.songFiles)
        assertTrue(old.deviceMarks.isEmpty())
        assertNull(old.devicesSnapshotSeq)
        assertEquals(0, old.stateNeedGen)
        // 깨진 파일: 빈 상태로 시작하되 원본은 .corrupt로 남고, 이 실행의 저장은 원본을 덮어쓰지 않는다
        f.writeText("{\"car_id\": 깨짐")
        val store = kr.aigyeot.phone.data.StateStore(f)
        assertEquals(PhoneState(), store.load())
        assertEquals("{\"car_id\": 깨짐", store.backup.readText())
        store.save(owner)
        assertEquals("{\"car_id\": 깨짐", store.backup.readText())
        assertEquals(owner, store.load())
        assertFalse(store.unsafe)
        dir.deleteRecursively()
    }

    @Test
    fun 백업하지_못하면_그_실행에서는_원본을_덮어쓰지_않는다() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val f = File(dir, "phone_state.json").apply { writeText("깨짐") }
        // 백업 자리에 비어 있지 않은 폴더가 있으면 옮기기·복사가 모두 실패한다
        File(dir, "phone_state.json.corrupt").apply { mkdirs(); File(this, "x").writeText("x") }
        val store = kr.aigyeot.phone.data.StateStore(f)
        assertEquals(PhoneState(), store.load())
        assertTrue(store.unsafe)
        assertTrue(runCatching { store.save(owner) }.isFailure)
        assertEquals("깨짐", f.readText()) // 원본은 그대로
        // 연결 끊기·401은 지킬 것이 없어 백업하지 못한 원본까지 지우고 저장을 다시 허용한다
        File(dir, "phone_state.json.corrupt").deleteRecursively()
        store.discardRecovery()
        assertFalse(f.exists())
        store.save(owner)
        assertEquals(owner, store.load())
        dir.deleteRecursively()
    }

    @Test
    fun 기존_자격으로_빈_상태를_복구하면_상태를_요청한다() {
        val r = PhoneState.recoverIfNeeded(PhoneState(), owner.carId!!, owner.myDeviceId!!, Role.OWNER, now)
        assertNotNull(r)
        assertTrue(r!!.stateRequestNeeded)
        assertTrue(r.shouldRequestState(now))
        assertNull(PhoneState.recoverIfNeeded(owner, owner.carId, owner.myDeviceId, Role.OWNER, now)) // 같은 차면 그대로
    }

    @Test
    fun 기기_목록은_snapshot_seq보다_새로운_변경을_되돌리지_않는다() {
        val g = "d_8HN1X0Q4M8T1V6B5R3H2CD7F3A"
        val car = DeviceInfo("d_19BE4W7Y2P5S8K1M3N6Q9T0VXA", Role.CAR, Platform.PLEOS, "2026-11-05T07:59:00Z", "2026-11-05T08:21:40Z", true)
        val guardianRow = DeviceInfo(g, Role.GUARDIAN, Platform.IOS, "2026-11-05T09:01:44Z", null, false, true)
        val withG = owner.mergeDevices(90, listOf(car, guardianRow), now)
        assertEquals(listOf(Role.CAR, Role.GUARDIAN), withG.devices.map { it.role })

        // 목록을 기다리는 사이 소켓으로 unpaired(seq 101)를 적용 → snapshot_seq 100 응답이 와도 되살리지 않는다
        val unpaired = fixture("unpaired").withSeq(101) // d_8HN1… guardian
        val gone = withG.receive(unpaired, now, live = true).state
        val merged = gone.mergeDevices(100, listOf(car.copy(online = false), guardianRow), now)
        assertEquals(listOf(Role.CAR), merged.devices.map { it.role })
        assertFalse(merged.devices.single().online) // 차 행은 늘 목록 값
        // 끊긴 기기의 늦은 paired도 되살리지 않는다
        val latePaired = fixture("paired").withSeq(150).let { e ->
            e.copy(body = buildJsonObject { put("device_id", g); put("role", "guardian"); put("platform", "ios"); put("paired_at", "2026-11-05T09:01:44Z"); put("handoff_alerts", true) })
        }
        assertEquals(listOf(Role.CAR), merged.receive(latePaired, now, live = true).state.devices.map { it.role })

        // paired(seq 105, 알림 끔)를 먼저 적용 → snapshot 100(알림 켬)은 그 기기를 덮어쓰지 않는다
        val offAlerts = latePaired.copy(id = ulid(), seq = 105, body = buildJsonObject { put("device_id", g); put("role", "guardian"); put("platform", "ios"); put("paired_at", "2026-11-05T09:01:44Z"); put("handoff_alerts", false) })
        val fresh = owner.mergeDevices(90, listOf(car), now).receive(offAlerts, now, live = true).state
        assertEquals(false, fresh.mergeDevices(100, listOf(car, guardianRow), now).devices.single { it.deviceId == g }.handoffAlerts)
        // 목록보다 늦게 붙은 기기는 목록에 없어도 남고, snapshot 이하에서 붙은 기기는 목록에 없으면 빠진다
        assertTrue(fresh.mergeDevices(100, listOf(car), now).devices.any { it.deviceId == g })
        val at200 = fresh.mergeDevices(200, listOf(car), now)
        assertFalse(at200.devices.any { it.deviceId == g })
        assertEquals(200L, at200.devicesSnapshotSeq)
        // 200을 적용한 뒤 늦게 온 목록 100은 버린다(목록·snapshot_seq 그대로)
        val stale = at200.mergeDevices(100, listOf(car, guardianRow), now)
        assertEquals(at200, stale)
        // 적용한 snapshot_seq 이하의 연결 변경도 버린다
        val newG = "d_NEWG0Q4M8T1V6B5R3H2CD7F3AK"
        val pairedAt = { seq: Long ->
            latePaired.copy(id = ulid(), seq = seq, body = buildJsonObject { put("device_id", newG); put("role", "guardian"); put("platform", "ios"); put("paired_at", "2026-11-05T09:01:44Z"); put("handoff_alerts", true) })
        }
        assertFalse(at200.receive(pairedAt(150), now, live = true).state.devices.any { it.deviceId == newG })
        assertTrue(at200.receive(pairedAt(201), now, live = true).state.devices.any { it.deviceId == newG })
        // snapshot 뒤 작은 seq의 늦은 paired는 버리고, 더 큰 seq는 적용한다
        val snap = owner.mergeDevices(100, listOf(car, guardianRow), now)
        assertEquals(true, snap.receive(offAlerts.copy(id = ulid(), seq = 90), now, live = true).state.devices.single { it.deviceId == g }.handoffAlerts)
        assertEquals(false, snap.receive(offAlerts.copy(id = ulid(), seq = 120), now, live = true).state.devices.single { it.deviceId == g }.handoffAlerts)
    }

    @Test
    fun 마지막_수유_시작은_차_값과_폰_기록_중_최신() {
        val car = Versioned(1, kr.aigyeot.shared.FeedingStateBody("2026-11-04T08:00:00+09:00", null))
        val t08 = instantOf("2026-11-04T08:00:00+09:00")
        assertNull(owner.lastFeedingStart())
        assertEquals(t08, owner.copy(feeding = car).lastFeedingStart()) // 차 값만
        val phone = FeedingRecord("m1", "2026-11-04T09:00:00+09:00")
        assertEquals(instantOf(phone.at), owner.copy(feedings = listOf(phone)).lastFeedingStart()) // 폰 기록만
        assertEquals(instantOf(phone.at), owner.copy(feeding = car, feedings = listOf(phone)).lastFeedingStart()) // 폰이 최신
        val old = FeedingRecord("m2", "2026-11-04T07:00:00+09:00")
        assertEquals(t08, owner.copy(feeding = car, feedings = listOf(old)).lastFeedingStart()) // 차가 최신
        val fixed = old.copy(at = "2026-11-04T10:00:00+09:00") // 중계가 고친 시각
        assertEquals(instantOf(fixed.at), owner.copy(feeding = car, feedings = listOf(fixed)).lastFeedingStart())
    }
}
