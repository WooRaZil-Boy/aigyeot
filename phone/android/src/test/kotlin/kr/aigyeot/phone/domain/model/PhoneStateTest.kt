package kr.aigyeot.phone.domain.model

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
import org.junit.Assert.assertNotEquals
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
    fun `차에 반영된 수유는 버림 안내를 띄우지 않고 늦게 반영되면 걷는다`() {
        // 7.5 '차에 반영됨'·U54: 기록 줄에 실패를 적지 않는 수유는 '보내지 못했어요' 띠도 띄우지 않는다.
        val id = "01JC8Y2K6N3T5Q9W0ZB4H7X2DM"
        val pressed = owner.startFeeding(now, id = id).let { it.copy(feedings = it.feedings.map { f -> f.copy(at = "2026-11-04T23:12:21Z") }) }
        val later = now + 8 * 24 * 60 * MINUTE
        val reflected = pressed.receive(fixture("feeding_state"), now, live = true).state // 08:12:21+09:00 = 같은 시각
        assertTrue(reflected.feedings.single().reflected)
        assertTrue(reflected.pruned(later).unseenDrops.isEmpty()) // 7일 만료로 버려도
        assertTrue(reflected.afterSend(reflected.outbox.single(), SendResult.Failed(400, "bad_request"), now).unseenDrops.isEmpty()) // 영구 실패로 버려도
        assertNull(reflected.afterSend(reflected.outbox.single(), SendResult.Failed(400, "bad_request"), now).feedings.single().failed)
        // 반영 전에 버렸으면 띠에 넣고, 뒤늦게 반영이 확인되면 걷는다.
        val dropped = pressed.pruned(later)
        assertEquals(mapOf(id to "feeding_started"), dropped.unseenDrops)
        assertTrue(dropped.receive(fixture("feeding_state"), later, live = true).state.unseenDrops.isEmpty())
        // 응답을 잃어 다시 보내는 중에 반영이 확인되면 보낼 함 항목에도 적는다: 기록 지우기로 기록 줄이 사라져도 버림 안내를 거른다.
        assertTrue(reflected.outbox.single().reflected)
        val cleared = reflected.clearedRecords()
        assertTrue(cleared.feedings.isEmpty())
        assertTrue(cleared.pruned(later).unseenDrops.isEmpty())
        // 기록 줄이 없어도(밀려남) 같은 시각이면 반영으로 적는다.
        val sameTime = owner.startFeeding(now, id = id, at = instantOf("2026-11-04T23:12:21Z")!!)
        val evicted = sameTime.copy(feedings = emptyList()).receive(fixture("feeding_state"), now, live = true).state
        assertTrue(evicted.outbox.single().reflected)
        // 반영 전에 버리고(띠) 기록을 지운 뒤 늦게 반영이 확인돼도, 버릴 때 적어 둔 시각으로 띠 안내를 걷는다.
        val droppedSame = sameTime.pruned(later)
        assertEquals(mapOf(id to "feeding_started"), droppedSame.unseenDrops)
        assertTrue(droppedSame.clearedRecords().receive(fixture("feeding_state"), later, live = true).state.unseenDrops.isEmpty())
        assertTrue(droppedSame.clearedRecords().receive(fixture("feeding_state"), later, live = true).state.droppedFeedingAt.isEmpty())
        // 보내기 전에 잡은 사본으로 결과를 처리해도(보내는 사이 기록 지우기·반영) 지금 보낼 함의 반영을 본다.
        val stale = sameTime.outbox.single()
        val meanwhile = sameTime.clearedRecords().receive(fixture("feeding_state"), now, live = true).state
        assertTrue(meanwhile.afterSend(stale, SendResult.Failed(400, "bad_request"), now).unseenDrops.isEmpty())
    }

    @Test
    fun `다시 녹음은 옛 곡 지움을 새 곡보다 먼저 한 저장에 넣는다`() {
        // 9장 '노래 거절': 거절·실패한 곡을 그 자리에서 바꾼다(iOS addSong(replacing:)과 같음).
        val one = owner.addSong("s_OLD", "옛 노래", 30.0, "s_OLD.m4a", now).let { s -> s.copy(songs = s.songs.map { it.copy(phase = SongPhase.REJECTED) }) }
        val swapped = one.replacedSong("s_OLD", "s_NEW", "새 노래", 40.0, "s_NEW.m4a", now + 1)
        assertEquals(SongPhase.REMOVED, swapped.songs.first { it.songId == "s_OLD" }.phase)
        assertEquals(listOf("song_removed", "song_added"), swapped.outbox.map { it.envelope.type })
        assertEquals("s_NEW", swapped.outbox.last().songId)
        // 옛 곡이 이미 지워졌으면 더하기만 한다(지움을 두 번 보내지 않는다).
        val gone = one.removeSong("s_OLD", now)
        assertEquals(1, gone.replacedSong("s_OLD", "s_NEW", "새 노래", 40.0, "s_NEW.m4a", now + 1).outbox.count { it.envelope.type == "song_removed" })
        // 3곡이 찼는지는 바꿀 곡을 빼고 센다(G40, iOS와 같음).
        val three = one.addSong("s_B", "b", 1.0, "b.m4a", now).addSong("s_C", "c", 1.0, "c.m4a", now)
        assertTrue(three.songsFull())
        assertFalse(three.songsFull(replacing = "s_OLD"))
        assertFalse(one.songsFull())
        // 시트를 연 사이 차가 받아들였으면 바꾸지 않고 새 곡으로 넣고, 3곡 한도는 그 곡까지 센다(iOS와 같음).
        val accepted = one.copy(songs = one.songs.map { it.copy(phase = SongPhase.ACCEPTED) })
        assertNull(accepted.replaceable("s_OLD"))
        val kept = accepted.replacedSong("s_OLD", "s_NEW", "새 노래", 40.0, "s_NEW.m4a", now + 1)
        assertEquals(SongPhase.ACCEPTED, kept.songs.first { it.songId == "s_OLD" }.phase)
        assertEquals(listOf("song_added", "song_added"), kept.outbox.map { it.envelope.type })
        val threeAccepted = accepted.addSong("s_B", "b", 1.0, "b.m4a", now).addSong("s_C", "c", 1.0, "c.m4a", now)
        assertTrue(threeAccepted.songsFull(threeAccepted.replaceable("s_OLD")))
        // 업로드에 실패한 곡(오류 표시)도 바꿀 수 있다.
        assertEquals("s_OLD", owner.addSong("s_OLD", "옛 노래", 30.0, "s_OLD.m4a", now).let { s -> s.copy(songs = s.songs.map { it.copy(error = "x") }) }.replaceable("s_OLD"))
        // 바꿀 곡이 없으면(새로 녹음) 더하기만 한다.
        assertEquals(listOf("song_added", "song_added"), one.replacedSong(null, "s_NEW", "새 노래", 40.0, "s_NEW.m4a", now + 1).outbox.map { it.envelope.type })
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
    fun `버린 메시지 안내는 서로 덮지 않는다`() {
        // U54: 7.1 outbox.dropped 글. 같은 글은 한 번, 노래 추가는 항목에만 표시한다.
        val lines = withDropNotices(null, listOf("feeding_started", "song_removed", "feeding_started", "song_added", "song_selected", "care_summary"))!!.lines()
        assertEquals(
            listOf(
                "수유 기록을 차로 보내지 못했어요.",
                "노래 삭제를 차로 보내지 못했어요. 차에는 그 노래가 남아 있을 수 있어요.",
                "실험에 쓸 노래 선택을 차로 보내지 못했어요.",
                "돌봄 기록 요약을 차로 보내지 못했어요.",
            ),
            lines,
        )
        // 띠에 떠 있는 안내(다른 버림·저장 실패)는 덮지 않고 줄을 바꿔 더한다.
        val one = withDropNotices(SAVE_FAILED, listOf("feeding_started"))
        assertEquals("$SAVE_FAILED\n수유 기록을 차로 보내지 못했어요.", one)
        val two = withDropNotices(one, listOf("song_removed", "feeding_started"))
        assertEquals("$one\n노래 삭제를 차로 보내지 못했어요. 차에는 그 노래가 남아 있을 수 있어요.", two)
        assertEquals(two, withDropNotices(two, listOf("song_added")))
        assertNull(withDropNotices(null, emptyList()))
        // 버린 안내가 떠 있는 동안 저장이 실패해도 앞 줄을 덮지 않는다(같은 글은 한 번).
        val drops = withDropNotices(null, listOf("feeding_started", "song_removed"))
        val failed = withNoticeLine(drops, SAVE_FAILED)
        assertEquals("$drops\n$SAVE_FAILED", failed)
        assertEquals(failed, withNoticeLine(failed, SAVE_FAILED))
    }

    @Test
    fun `만료로 버린 메시지도 띠로 알린다`() {
        // 7.5 '7일 지난 항목도 버린다' + U54 '7.5대로 버린 것은 outbox.dropped로 알린다'.
        val s = owner.startFeeding(now).selectSong("01JC90A0Q4M8T1V6B5R3H2CD7F", now)
        assertTrue(s.pruned(now + KEEP_IDS_MS - 1).unseenDrops.isEmpty())
        val pruned = s.pruned(now + KEEP_IDS_MS)
        assertTrue(pruned.outbox.isEmpty())
        // 버린 것과 같은 저장에 안내가 남는다
        assertEquals(listOf("feeding_started", "song_selected"), pruned.unseenDrops.values.toList())
        assertEquals("수유 기록을 차로 보내지 못했어요.\n실험에 쓸 노래 선택을 차로 보내지 못했어요.", withDropNotices(null, pruned.unseenDrops.values.toList()))
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
        // 파일부터 다시 올리되 기다림을 둔다: 되풀이되면 기다림이 는다(7.5).
        assertEquals(1, missing.outbox.single().attempts)
        assertTrue(missing.outbox.single().nextTryAt > now)
        assertEquals(1, missing.afterSend(missing.outbox.single(), SendResult.Uploaded, now).outbox.single().attempts)
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
    fun `끊을 때 빈 상태 저장이 실패해도 옛 차 기록이 다시 보이지 않는다`() {
        // 7.4 '연결 끊기: 위 전부를 지운다'. 빈 상태 저장이 실패해 옛 차 상태가 파일에 남아도
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val f = File(dir, "phone_state.json")
        val store = kr.aigyeot.phone.data.StateStore(f)
        val old = owner.startFeeding(now)
        store.save(old)
        // 끊기·401 정리가 파일을 지운다
        store.discardRecovery()
        assertFalse(f.exists())
        assertEquals(PhoneState(), kr.aigyeot.phone.data.StateStore(f).load())
        // 지우기 전에 죽었어도, 자격이 없는 시작은 남은 파일을 쓰지 않는다
        assertEquals(PhoneState(), PhoneState.atStart(old, recovered = null, connected = false))
        assertEquals(old, PhoneState.atStart(old, recovered = null, connected = true))
        val r = PhoneState.paired("c_OTHER", "d_OTHER", Role.OWNER, now)
        assertEquals(r, PhoneState.atStart(old, recovered = r, connected = true))
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
    fun `버린 메시지 안내는 버린 저장과 함께 남는다`() {
        // U54·7.5: 띠를 보기 전에 앱이 죽어도 다시 켜면 알린다. 보낼 함에서 빼는 같은 상태에 type을 적고 state.json으로 다시 읽는다.
        val s = owner.startFeeding(now)
        val item = s.outbox.single()
        val dropped = s.afterSend(item, SendResult.Failed(400, "bad_request"), now)
        assertTrue(dropped.outbox.isEmpty())
        assertEquals(mapOf(item.envelope.id to "feeding_started"), dropped.unseenDrops)
        val reread = ProtocolJson.decodeFromString(PhoneState.serializer(), ProtocolJson.encodeToString(PhoneState.serializer(), dropped))
        assertEquals("수유 기록을 차로 보내지 못했어요.", withDropNotices(null, reread.unseenDrops.values.toList()))
        // 다시 보낼 실패(5xx)·띠 글이 없는 노래 추가는 적지 않는다. 같은 type은 한 번.
        assertTrue(s.afterSend(item, SendResult.Failed(503, "unavailable"), now).unseenDrops.isEmpty())
        val song = owner.addSong("01JC90A0Q4M8T1V6B5R3H2CD7F", "나비야", 94.5, "x.m4a", now)
        assertTrue(song.afterSend(song.outbox.single(), SendResult.Failed(415, "x"), now).unseenDrops.isEmpty())
        // 같은 종류를 또 버리면 띠 글은 한 줄이지만 메시지마다 적는다: 띠를 닫을 때 본 것만 지워, 그사이 새로 버린 것은 남는다.
        val twice = dropped.startFeeding(now).let { it.afterSend(it.outbox.single(), SendResult.Failed(400, "bad_request"), now) }
        assertEquals(2, twice.unseenDrops.size)
        assertEquals("수유 기록을 차로 보내지 못했어요.", withDropNotices(null, twice.unseenDrops.values.toList()))
        assertEquals(listOf("feeding_started"), (twice.unseenDrops - dropped.unseenDrops.keys).values.toList())
        // 보내는 사이 새 요약이 바꾼 옛것(보낼 함에 없음)의 실패는 알리지 않는다(R19).
        assertTrue(s.copy(outbox = emptyList()).afterSend(item, SendResult.Failed(400, "bad_request"), now).unseenDrops.isEmpty())
        // 기록 지우기는 다른 안내를 남기고(띠를 닫을 때만 지운다), 지운 수유의 버림 안내와 그 시각은 걷는다(7.4 '기록을 지운다').
        val mixed = dropped.copy(unseenDrops = dropped.unseenDrops + ("m" to "song_removed"))
        assertTrue(mixed.droppedFeedingAt.isNotEmpty())
        assertEquals(mapOf("m" to "song_removed"), mixed.clearedRecords().unseenDrops)
        assertTrue(mixed.clearedRecords().droppedFeedingAt.isEmpty())
    }

    @Test
    fun `보내는 중인 메시지는 만료 정리에서 빼고 결과를 기다린다`() {
        // 7.5: 보내는 사이 7일을 넘겨도 정리가 먼저 지우고 실패로 적으면, 201을 받아도 실패 표시와 띠가 남는다.
        val s = owner.startFeeding(now)
        val item = s.outbox.single()
        val late = now + KEEP_IDS_MS
        val kept = s.pruned(late, sending = item.envelope.id)
        assertEquals(listOf(item), kept.outbox)
        assertNull(kept.feedings.single().failed)
        assertTrue(kept.unseenDrops.isEmpty())
        val sent = kept.afterSend(item, SendResult.Sent(buildJsonObject { }), late)
        assertNull(sent.feedings.single().failed)
        assertEquals(late, sent.feedings.single().sentAt)
        // 결과 뒤(보내는 중이 아니면) 다음 정리가 버리고 알린다.
        assertEquals(listOf("feeding_started"), kept.pruned(late).unseenDrops.values.toList())
    }

    private val seoul = ZoneId.of("Asia/Seoul")
    private val care1 = CareFile(migratedFeedings = true).start(CareKind.FEED, now - 60 * MINUTE, seoul.id).file
    // 오늘 값은 없지만 기록에서 온 값(마지막 기저귀 시각)이 있는 폰: 날이 바뀌어도 내용이 같다. 빈 요약은 처음에 보내지 않는다(U61).
    private val oldOnly = CareFile(sleepDiaperEnabled = true, migratedFeedings = true).start(CareKind.DIAPER, now - 10 * DAY, seoul.id).file

    @Test
    fun `기록이 없는 폰은 요약을 보내지 않고 지운 뒤에는 빈 요약을 보낸다`() {
        // U61: 같은 차에 owner 폰이 여럿이면 차는 마지막 요약을 쓴다. 기록이 없는 새 폰이 빈 요약으로 다른 폰의 요약을 덮지 않는다.
        val empty = CareFile(migratedFeedings = true)
        assertEquals(owner, owner.reconciledCareSummary(empty, now, seoul))
        // 지운(되돌리기를 기다리는) 기록만, 메모만, 옛 수유만 있어도 요약에 기록에서 온 값이 없어 같다(판단은 보낼 요약 그대로 본다).
        assertEquals(owner, owner.reconciledCareSummary(care1.copy(events = care1.events.map { it.copy(deletedAt = now) }), now, seoul))
        val notesOnly = empty.copy(events = listOf(care1.events.single().copy(id = "n", kind = CareKind.NOTE, endAt = null, note = "메모")))
        assertEquals(owner, owner.reconciledCareSummary(notesOnly, now, seoul))
        assertEquals(owner, owner.reconciledCareSummary(empty.start(CareKind.FEED, now - 10 * DAY, seoul.id).file, now, seoul))
        // 값이 하나라도 있으면 보낸다(오늘 수유, 옛 기저귀 시각).
        assertEquals(listOf("care_summary"), types(owner.reconciledCareSummary(care1, now, seoul)))
        assertEquals(listOf("care_summary"), types(owner.reconciledCareSummary(oldOnly, now, seoul)))
        // 이 연결에서 요약을 넣은 적이 있으면, 기록을 지운 뒤 빈 요약을 보내 차의 옛 요약을 바꾼다(R10).
        val cleared = owner.reconciledCareSummary(care1, now, seoul).clearedRecords(care1.events.map { it.id }).copy(clearingCareIds = null)
        assertEquals(listOf("care_summary"), types(cleared.reconciledCareSummary(empty, now + MINUTE, seoul)))
    }
    private fun feeds(s: PhoneState) = s.outbox.last { it.envelope.type == "care_summary" }.envelope.body["today_feed_count"]?.jsonPrimitive?.content?.toInt()
    private fun types(s: PhoneState) = s.outbox.map { it.envelope.type }

    @Test
    fun `돌봄 요약은 owner가 바뀌었을 때만 보내고 보낼 함에 하나만 둔다`() {
        // R19(U45): as_of를 뺀 body가 보낸 것과 다를 때만, 안 보낸 요약은 하나만. 표시는 같은 상태에서 바뀐다.
        val a = owner.reconciledCareSummary(care1, now, seoul)
        val first = a.outbox.single()
        assertEquals("care_summary", first.envelope.type)
        assertEquals(rfc3339(now, seoul), first.envelope.body["as_of"]?.jsonPrimitive?.content)
        assertEquals(1, feeds(a))
        assertEquals(JsonObject(first.envelope.body - "as_of"), a.lastSentCareSummary)
        // 보낸 뒤에는 as_of만 바뀌면 다시 보내지 않는다.
        val sent = a.afterSend(first, SendResult.Sent(buildJsonObject { }), now)
        assertEquals(sent, sent.reconciledCareSummary(care1, now + MINUTE, seoul))
        // 바뀌면 새것. 다른 메시지는 순서대로 둔다. 안 보낸 요약이 있으면 새것이 그 자리를 바꾼다(하나만).
        val care2 = care1.start(CareKind.FEED, now, seoul.id).file
        val b = sent.reconciledCareSummary(care2, now + MINUTE, seoul).startFeeding(now + MINUTE)
        assertEquals(listOf("care_summary", "feeding_started"), types(b))
        assertEquals(2, feeds(b))
        val c = b.reconciledCareSummary(care2.start(CareKind.FEED, now + 2 * MINUTE, seoul.id).file, now + 2 * MINUTE, seoul)
        assertEquals(listOf("care_summary", "feeding_started"), types(c))
        assertEquals(3, feeds(c))
        // guardian·연결 없음·옮기기(R11) 전은 보내지 않는다. 끊으면 새 상태라 표시도 없다(R9).
        assertEquals(guardian, guardian.reconciledCareSummary(care1, now, seoul))
        assertEquals(PhoneState(), PhoneState().reconciledCareSummary(care1, now, seoul))
        assertEquals(owner, owner.reconciledCareSummary(care1.copy(migratedFeedings = false), now, seoul))
    }

    @Test
    fun `요약을 바꿔 넣어도 자리와 다시 보낼 기다림을 이어받는다`() {
        // 7.5 넣은 순서·같은 id 다시 보내기. 안 보낸 요약만 바꾸며 그 자리에 둔다.
        val queued = owner.reconciledCareSummary(care1, now, seoul).startFeeding(now)
        val s1 = queued.outbox.first()
        // 같은 날 같은 내용이면 그대로 둔다: 같은 id로 다시 보내야 응답을 잃어도 중계가 한 번만 처리한다.
        assertEquals(queued, queued.reconciledCareSummary(care1, now + 10_000, seoul))
        // 내용이 바뀌면 새것이 그 자리에서 바꾼다(하나만).
        val care2 = care1.start(CareKind.FEED, now, seoul.id).file
        val changed = queued.reconciledCareSummary(care2, now + 10_000, seoul)
        assertEquals(listOf("care_summary", "feeding_started"), types(changed))
        assertNotEquals(s1.envelope.id, changed.outbox.first().envelope.id)
        assertEquals(2, feeds(changed))
        // 응답을 잃은(중계가 받았을 수 있는) 요약은 바꾸지 않고 새것을 뒤에 둔다(U57): 새것도 그 기다림 뒤에 간다.
        val unsure = queued.markSentOnce(s1.envelope.id).afterSend(s1, SendResult.Offline, now)
        val behind = unsure.reconciledCareSummary(care2, now + 10_000, seoul)
        assertEquals(listOf(s1.envelope.id), behind.outbox.take(1).map { it.envelope.id })
        assertEquals(listOf("care_summary", "feeding_started", "care_summary"), types(behind))
        // 429로 거절된 요약은 중계에 없다: 보내 보지 않은 것으로 되돌려 새것이 그 자리에서 바꾸고 Retry-After를 이어받는다.
        val waiting = queued.markSentOnce(s1.envelope.id).afterSend(s1, SendResult.Failed(429, "rate_limited", 60), now)
        assertFalse(waiting.outbox.first().tried(null))
        val replaced = waiting.reconciledCareSummary(care2, now + 10_000, seoul)
        assertEquals(listOf("care_summary", "feeding_started"), types(replaced))
        assertNotEquals(s1.envelope.id, replaced.outbox.first().envelope.id)
        assertEquals(now + 60_000, replaced.outbox.first().nextTryAt)
        // 날이 바뀌면 같은 내용이어도 새 as_of로 바꾼다(1.4 24시간·'오늘' 값).
        val nextDay = java.time.Instant.ofEpochMilli(now).atZone(seoul).toLocalDate().plusDays(1).atStartOfDay(seoul).toInstant().toEpochMilli()
        val quiet = oldOnly // 오늘 기록이 없는 폰(옛 기록만). 기록이 아예 없으면 보내지 않는다(U61)
        val pend = owner.reconciledCareSummary(quiet, now, seoul)
        val rolled = pend.reconciledCareSummary(quiet, nextDay, seoul)
        assertEquals(rfc3339(nextDay, seoul), rolled.outbox.single().envelope.body["as_of"]?.jsonPrimitive?.content)
        // 보내는 사이 새 요약이 옛것을 바꿨다: 옛것에 늦게 온 429는 새것이 이어받고, 옛것의 영구 실패는 알리지 않는다.
        val swapped = queued.reconciledCareSummary(care2, now + 1_000, seoul)
        val late = swapped.afterSend(s1, SendResult.Failed(429, "rate_limited", 60), now + 2_000)
        assertEquals(now + 62_000, late.outbox.first().nextTryAt)
        assertFalse(late.outbox.first().tried(null)) // 새것은 아직 중계에 간 적이 없다
        val lost = swapped.afterSend(s1, SendResult.Failed(400, "bad_request"), now + 2_000)
        assertEquals(swapped, lost)
    }

    @Test
    fun `차에 닿지 못한 요약은 내용이 같아도 다시 보낸다`() {
        val a = owner.reconciledCareSummary(care1, now, seoul)
        val s1 = a.outbox.single()
        // 7일 만료·영구 실패로 버렸으면 보낸 표시를 지운다(iOS `expireOutbox`와 같음).
        assertNull(a.pruned(now + KEEP_IDS_MS).lastSentCareSummary)
        val dropped = a.afterSend(s1, SendResult.Failed(400, "unknown_type"), now)
        assertNull(dropped.lastSentCareSummary)
        assertEquals(listOf("care_summary"), types(dropped.reconciledCareSummary(care1, now + MINUTE, seoul)))
        // 시계가 뒤로 갔으면(지금이 마지막 as_of보다 이르다) 차가 무시했을 수 있어 다시 보낸다(3.3).
        val sent = a.afterSend(s1, SendResult.Sent(buildJsonObject { }), now)
        val back = sent.reconciledCareSummary(care1, now - MINUTE, seoul)
        assertEquals(listOf("care_summary"), types(back))
        // 되돌린 시계로 보낸 뒤에도, 그 앞에 더 늦은 as_of를 보냈으니 시계가 따라잡을 때까지 다시 보낸다.
        val backSent = back.afterSend(back.outbox.single(), SendResult.Sent(buildJsonObject { }), now)
        assertEquals(listOf("care_summary"), types(backSent.reconciledCareSummary(care1, now - MINUTE + 10_000, seoul)))
        // 시계를 되돌린 사이 넣고 못 보낸 요약은, 시계가 따라잡으면 같은 내용이어도 지금 as_of로 바꾼다(차가 앞서 받은 더 늦은 as_of보다 이르다).
        val stuck = sent.reconciledCareSummary(care1, now - MINUTE, seoul)
        val freed = stuck.reconciledCareSummary(care1, now + 5 * MINUTE, seoul)
        assertEquals(rfc3339(now + 5 * MINUTE, seoul), freed.outbox.single().envelope.body["as_of"]?.jsonPrimitive?.content)
        val caught = backSent.reconciledCareSummary(care1, now + 10_000, seoul).let { it.afterSend(it.outbox.single(), SendResult.Sent(buildJsonObject { }), now) }
        assertEquals(caught, caught.reconciledCareSummary(care1, now + 20_000, seoul))
        // 폰 시계가 중계보다 5분 넘게 앞서 보냈으면 차가 미래 요약으로 버렸을 수 있어 표시를 지운다(3.3).
        assertNull(a.afterSend(s1, SendResult.Sent(buildJsonObject { }), now, aheadMs = 6 * MINUTE).lastSentCareSummary)
        assertNotNull(a.afterSend(s1, SendResult.Sent(buildJsonObject { }), now, aheadMs = 4 * MINUTE).lastSentCareSummary)
    }

    @Test
    fun `기록 지우기를 마치기 전에는 요약을 보내지 않는다`() {
        // R10·R19: 상태 저장 뒤 care.json을 쓰다 죽거나 저장에 실패해도, 남은 기록으로 요약을 보내지 않고 다음 저장·시작에 이어서 지운다.
        val ids = care1.events.map { it.id }
        val clearing = owner.reconciledCareSummary(care1, now, seoul).clearedRecords(ids)
        assertEquals(ids, clearing.clearingCareIds)
        assertTrue(clearing.outbox.none { it.envelope.type == "care_summary" }) // 지울 기록의 요약은 뺀다
        assertNull(clearing.lastSentCareSummary)
        assertEquals(clearing, clearing.reconciledCareSummary(care1, now, seoul))
        assertEquals(clearing, clearing.reconciled(care1, now, seoul)) // 지울 수유도 보내지 않는다
        // 그때의 id만 지운다: 그 뒤 적은 기록은 이어서 지울 때도 남는다.
        val later = care1.start(CareKind.DIAPER, now + MINUTE, seoul.id).file
        val kept = later.withoutCleared(clearing.clearingCareIds!!)
        assertEquals(listOf(CareKind.DIAPER), kept.events.map { it.kind })
        assertTrue(kept.migratedFeedings) // 지운 수유를 다시 옮겨 오지 않는다
        // 다 지우고 표시를 끈 저장에서 빈 요약을 보낸다.
        val done = clearing.copy(clearingCareIds = null).reconciledCareSummary(care1.withoutCleared(ids), now, seoul)
        assertEquals(0, feeds(done))
        // 지울 id는 연결과 따로다: 연결 없이 시작해도 남기고, 다시 지우면 더한다.
        assertEquals(ids, PhoneState.atStart(clearing, recovered = null, connected = false).clearingCareIds)
        val repaired = PhoneState.paired("c_OTHER", "d_OTHER", Role.OWNER, now) // 자격 저장 직후 죽어 복구가 만든 상태
        assertEquals(ids, PhoneState.atStart(clearing, recovered = repaired, connected = true).clearingCareIds)
        assertEquals(ids + "x", clearing.clearedRecords(listOf("x")).clearingCareIds)
        // 누른 시각도 같이 이어진다(그때까지의 임시 녹음만 지운다). 다시 지우면 늦은 쪽.
        val timed = owner.clearedRecords(ids, at = now)
        assertEquals(now, timed.clearingSince)
        assertEquals(now, PhoneState.atStart(timed, recovered = null, connected = false).clearingSince)
        assertEquals(now, PhoneState().keepingClear(timed).clearingSince)
        assertEquals(now + MINUTE, timed.clearedRecords(listOf("x"), at = now + MINUTE).clearingSince)
    }

    @Test
    fun `수유 옮기기 전에는 수유도 요약도 보내지 않는다`() {
        // R11: 옮기기 전에 보낸 수유 기록은 다음 옮기기에서 다른 id의 수유로 또 옮겨진다(중복). 요약도 수유가 빠진다.
        val unmigrated = care1.copy(migratedFeedings = false)
        assertEquals(owner, owner.reconciled(unmigrated, now, seoul))
        assertEquals(listOf("feeding_started", "care_summary"), types(owner.reconciled(care1, now, seoul)))
        // 보내기 직전 재계산·지우기 마침은 요약만 맞춘다: 수유는 정해진 네 계기에서만 보낸다(R6). 막는 조건은 같다.
        assertEquals(listOf("care_summary"), types(owner.reconciledSummary(care1, now, seoul)))
        assertEquals(owner, owner.reconciledSummary(unmigrated, now, seoul))
        assertEquals(owner.copy(clearingCareIds = listOf("x")), owner.copy(clearingCareIds = listOf("x")).reconciledSummary(care1, now, seoul))
    }

    @Test
    fun `날이 바뀌면 같은 내용의 요약도 다시 보낸다`() {
        // 1.4: 날이 바뀌면 차는 앞 요약의 '오늘' 값을 빼고 24시간 뒤에는 요약을 쓰지 않는다(iOS와 같음).
        val nothing = oldOnly // 오늘 기록이 없는 폰(옛 기록만). 기록이 아예 없으면 보내지 않는다(U61)
        val a = owner.reconciledCareSummary(nothing, now, seoul).let { it.afterSend(it.outbox.single(), SendResult.Sent(buildJsonObject { }), now) }
        assertEquals(a, a.reconciledCareSummary(nothing, now + MINUTE, seoul))
        val nextDay = java.time.Instant.ofEpochMilli(now).atZone(seoul).toLocalDate().plusDays(1).atStartOfDay(seoul).toInstant().toEpochMilli()
        assertEquals(listOf("care_summary"), types(a.reconciledCareSummary(nothing, nextDay, seoul)))
        // 25시간인 날(서머타임 끝)은 같은 날짜여도 24시간이 지날 수 있다: 그때도 다시 보낸다.
        val ny = ZoneId.of("America/New_York")
        val early = java.time.ZonedDateTime.of(2026, 11, 1, 0, 5, 0, 0, ny).toInstant().toEpochMilli()
        val late = java.time.ZonedDateTime.of(2026, 11, 1, 23, 30, 0, 0, ny).toInstant().toEpochMilli()
        val b = owner.reconciledCareSummary(nothing, early, ny).let { it.afterSend(it.outbox.single(), SendResult.Sent(buildJsonObject { }), early) }
        assertEquals(listOf("care_summary"), types(b.reconciledCareSummary(nothing, late, ny)))
        // 안 보낸 요약도 같다.
        val pend = owner.reconciledCareSummary(nothing, early, ny)
        assertNotEquals(pend, pend.reconciledCareSummary(nothing, late, ny))
    }

    @Test
    fun `늦게 온 기기 목록은 그사이 받은 차의 연결을 덮지 않는다`() {
        // 7.2: 실시간으로 받은 차의 봉투는 그 자리에서 차 행을 켠다. 그 전에 요청한 목록이 늦게 와 '꺼짐'으로 되돌리면 안 된다.
        val car = DeviceInfo(deviceId = "d_CAR", role = Role.CAR, platform = Platform.PLEOS, pairedAt = rfc3339(now - DAY), lastSeenAt = rfc3339(now - HOUR), online = false)
        val stale = owner.mergeDevices(5, listOf(car), now)
        assertFalse(stale.devices.single().online)
        val kept = owner.mergeDevices(5, listOf(car), now, carLiveAt = now - 1_000)
        assertTrue(kept.devices.single().online)
        assertEquals(rfc3339(now - 1_000), kept.devices.single().lastSeenAt)
    }

    @Test
    fun `메시지 429는 그 항목을 지워도 Retry-After까지 기다린다`() {
        // 1.1 '429 … Retry-After 뒤', 1.4 메시지는 기기당 분당 60회. 항목을 지우거나 바꿔도, 다시 켜도 지킨다.
        val s = owner.startFeeding(now)
        val item = s.outbox.single()
        val held = s.afterSend(item, SendResult.Failed(429, "rate_limited", 60), now)
        assertEquals(now + 60_000, held.messageHoldUntil)
        assertEquals(held.messageHoldUntil, held.clearedRecords().messageHoldUntil)
        // 첫 보내기가 429로 거절된 요약은 기록 지우기가 뺀다(중계에 없다, R10). 빈 요약도 그 기다림 뒤에 간다.
        val summary = owner.reconciledCareSummary(care1, now, seoul)
        val sid = summary.outbox.single().envelope.id
        val rejected = summary.markSentOnce(sid).afterSend(summary.outbox.single(), SendResult.Failed(429, "rate_limited", 60), now)
        val wiped = rejected.clearedRecords(care1.events.map { it.id })
        assertTrue(wiped.outbox.none { it.envelope.type == "care_summary" })
        assertEquals(now + 60_000, wiped.messageHoldUntil)
        // 응답을 잃은 요약은 남긴다(중계가 받았을 수 있다, U57).
        val unsure = summary.markSentOnce(sid).afterSend(summary.outbox.single(), SendResult.Offline, now)
        assertEquals(listOf(sid), unsure.clearedRecords(care1.events.map { it.id }).outbox.map { it.envelope.id })
        // 연결 전 실패(이름 풀기·연결 실패)도 중계에 없는 것이 확정이다: 기록 지우기가 뺀다. 다시 보낼 간격은 늘린다.
        val unreached = summary.markSentOnce(sid).afterSend(summary.outbox.single(), SendResult.Unreached, now)
        assertFalse(unreached.outbox.single().tried(null))
        assertEquals(1, unreached.outbox.single().attempts)
        assertTrue(unreached.clearedRecords(care1.events.map { it.id }).outbox.none { it.envelope.type == "care_summary" })
        // 앞서 응답을 잃은 요약은 그 뒤 연결 전에 실패해도 남긴다(앞 시도가 늦게 닿을 수 있다, U57).
        val lostThenUnreached = unsure.afterSend(unsure.outbox.single(), SendResult.Unreached, now + 1_000)
        assertEquals(listOf(sid), lostThenUnreached.clearedRecords(care1.events.map { it.id }).outbox.map { it.envelope.id })
        assertEquals(held, ProtocolJson.decodeFromString(PhoneState.serializer(), ProtocolJson.encodeToString(PhoneState.serializer(), held)))
        // 더 짧은 429가 와도 더 늦은 끝을 남긴다.
        assertEquals(now + 60_000, held.withMessageHold(now + 1_000, 5_000).messageHoldUntil)
        // 노래 파일 PUT의 429는 시간당 한도라 그 항목만 기다린다.
        val song = owner.addSong("01JC90A0Q4M8T1V6B5R3H2CD7F", "나비야", 94.5, "x.m4a", now)
        val put = song.afterSend(song.outbox.single(), SendResult.Failed(429, "rate_limited", 3600), now)
        assertNull(put.messageHoldUntil)
        assertEquals(now + 3_600_000, put.outbox.single().nextTryAt)
    }

    @Test
    fun `보내는 중인 요약은 바꾸지 않고 뒤에 둔다`() {
        // U57: 맨 앞 요약이 이미 보낸 적이 있으면 바꾸지 않고 그 뒤에 새것을 하나 둔다(뒤에 있으면 그것만 바꾼다).
        // 바꿔치우면 같은 초의 옛 요약이 중계에 늦게 닿아 seq가 커져 새 요약을 덮을 수 있다(3.3).
        val a = owner.reconciledCareSummary(care1, now, seoul)
        val s1 = a.outbox.single()
        val care2 = care1.start(CareKind.FEED, now, seoul.id).file
        val inflight = a.reconciledCareSummary(care2, now, seoul, sending = s1.envelope.id)
        assertEquals(s1, inflight.outbox.first())
        assertEquals(2, inflight.outbox.size)
        // 다시 바뀌면 뒤의 안 보낸 것만 바꾼다(하나만).
        val care3 = care2.start(CareKind.FEED, now + MINUTE, seoul.id).file
        val again = inflight.reconciledCareSummary(care3, now + MINUTE, seoul, sending = s1.envelope.id)
        assertEquals(listOf(s1.envelope.id, again.outbox.last().envelope.id), again.outbox.map { it.envelope.id })
        assertEquals(3, feeds(again))
        // 응답을 잃고 기다리는(5xx) 요약도 같다. 기록 지우기도 보내 본 것은 남긴다. '보내 봄'은 보내기 전에 적는다(SendOutbox).
        val tried = a.markSentOnce(s1.envelope.id).afterSend(s1, SendResult.Failed(503, "unavailable"), now)
        assertEquals(2, tried.reconciledCareSummary(care2, now + 2_000, seoul).outbox.size)
        assertEquals(listOf(s1.envelope.id), tried.clearedRecords(care1.events.map { it.id }).outbox.map { it.envelope.id })
        assertTrue(a.clearedRecords(care1.events.map { it.id }).outbox.isEmpty()) // 안 보낸 것은 뺀다
        assertEquals(listOf(s1.envelope.id), a.clearedRecords(emptyList(), sending = s1.envelope.id).outbox.map { it.envelope.id })
        // '보내 봄'은 보내기 전에 저장된다: 응답 전에 죽어 `sending`이 사라져도 바꾸지 않는다.
        val marked = a.markSentOnce(s1.envelope.id)
        val reread = ProtocolJson.decodeFromString(PhoneState.serializer(), ProtocolJson.encodeToString(PhoneState.serializer(), marked))
        assertEquals(listOf(s1.envelope.id, "care_summary"), reread.reconciledCareSummary(care2, now, seoul).outbox.mapIndexed { i, it -> if (i == 0) it.envelope.id else it.envelope.type })
        // 앞 요약을 버려도(영구 실패) 뒤에 남은 요약이 있으면 보낸 표시는 그것을 가리키므로 둔다.
        val both = marked.reconciledCareSummary(care2, now, seoul)
        val dropped = both.afterSend(both.outbox.first(), SendResult.Failed(400, "bad_request"), now)
        assertEquals(both.lastSentCareSummary, dropped.lastSentCareSummary)
        assertNull(marked.afterSend(s1, SendResult.Failed(400, "bad_request"), now).lastSentCareSummary) // 남은 것이 없으면 지운다
    }

    @Test
    fun `보내지 않고 멈춘 요약은 표시를 걷고 그사이 남긴 것을 정리한다`() {
        // U57: '보내 봄'을 적은 뒤 429 기다림으로 보내지 않고 멈췄다. 그사이 그 표시 때문에 남긴 것을 걷는 저장에서 정리한다.
        val a = owner.reconciledCareSummary(care1, now, seoul)
        val s1 = a.outbox.single().envelope.id
        val marked = a.markSentOnce(s1)
        // 그사이 기록을 지웠다(보내 봄이라 남겼음): 걷으면 빠지고 보낸 표시도 지운다. 빈 요약은 지우기를 마칠 때 들어간다.
        val cleared = marked.clearedRecords(care1.events.map { it.id })
        assertEquals(listOf(s1), cleared.outbox.map { it.envelope.id })
        assertTrue(cleared.untried(s1).outbox.isEmpty())
        assertNull(cleared.untried(s1).lastSentCareSummary)
        // 그사이 새 요약이 뒤에 붙었다: 보내지 않은 요약은 가장 뒤의 하나만 남는다.
        val care2 = care1.start(CareKind.FEED, now, seoul.id).file
        val behind = marked.reconciledCareSummary(care2, now, seoul)
        assertEquals(2, behind.outbox.size)
        val kept = behind.untried(s1)
        assertEquals(listOf(behind.outbox.last().envelope.id), kept.outbox.map { it.envelope.id })
        assertEquals(behind.lastSentCareSummary, kept.lastSentCareSummary)
        // 그사이 아무 일도 없었다: 표시만 걷어 다시 바꿀 수 있는 요약이 된다.
        assertFalse(marked.untried(s1).outbox.single().sentOnce)
        // 응답을 잃은 적이 있으면(5xx) 그 뒤 연결 전에 실패해도 표시를 걷지 않는다(앞 시도가 늦게 닿을 수 있다).
        val retried = marked.afterSend(a.outbox.single(), SendResult.Failed(503, "unavailable"), now)
        assertTrue(retried.afterSend(retried.outbox.single(), SendResult.Unreached, now + 1_000).outbox.single().tried(null))
    }

    @Test
    fun `같은 차라도 기기나 역할이 바뀌면 옛 상태를 복구하지 않는다`() {
        // 같은 차에 다시 연결하다 자격 저장 직후 죽었다: 옛 상태(다른 기기·역할)를 쓰면 owner인데 guardian으로 남아 보내지 않는다.
        val old = PhoneState.paired("c_SAME", "d_OLD", Role.GUARDIAN, now)
        assertNull(PhoneState.recoverIfNeeded(old, "c_SAME", "d_OLD", Role.GUARDIAN, now))
        val fresh = PhoneState.recoverIfNeeded(old, "c_SAME", "d_NEW", Role.OWNER, now)!!
        assertEquals(Role.OWNER, fresh.role)
        assertEquals("d_NEW", fresh.myDeviceId)
    }

    @Test
    fun `수유 옮기기를 마치기 전에는 끊어도 옮길 원본을 남긴다`() {
        // R11·U22: care.json 저장이 실패해 옮기기를 마치지 못한 채 끊기·새 연결·연결 없는 시작을 거쳐도, 다음 옮기기가 같은 원본을 옮긴다.
        val old = owner.startFeeding(now)
        assertEquals(old.feedings, PhoneState().keepingClear(old, migrating = true).migrationFeedings)
        assertEquals(old.feedings, PhoneState.atStart(old, recovered = null, connected = false, migrating = true).migrationFeedings)
        // 옮기기를 마쳤으면 옛 연결의 것이라 남기지 않는다.
        assertTrue(PhoneState().keepingClear(old).migrationFeedings.isEmpty())
        assertEquals(PhoneState(), PhoneState.atStart(old, recovered = null, connected = false))
        // 새 차에 붙으면 24시간 안의 최신 수유가 다시 간다(U6): 넘어온 원본은 새 연결의 보낸 표시를 채우지 않는다(U22는 지금 연결의 기록만).
        val fresh = PhoneState.paired("c_NEW", "d_NEW", Role.OWNER, now).keepingClear(old, migrating = true)
        assertNull(fresh.markedMigratedFeedings().lastSentFeedingAt)
        val migrated = CareFile().migrated(fresh.feedings + fresh.migrationFeedings, seoul.id)
        assertEquals(listOf("feeding_started"), types(fresh.markedMigratedFeedings().reconciled(migrated, now + MINUTE, seoul)).filter { it == "feeding_started" })
    }
}
