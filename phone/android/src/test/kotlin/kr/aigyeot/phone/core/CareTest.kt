package kr.aigyeot.phone.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import kr.aigyeot.shared.Envelope
import kr.aigyeot.shared.FeedingStateBody
import kr.aigyeot.shared.ProtocolJson
import kr.aigyeot.shared.ReportSummaryBody
import kr.aigyeot.shared.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 돌봄 기록 규칙 R1~R17, 표준 예제, 내보내기(U29)(phone-care-plan 6·13장). 시험 이름은 13장 글자 그대로다(iOS는 `test_` + 같은 글자).
 * 계획 0장 U14~U24(최대 길이 넘은 진행 중은 끝 모름, 차 기록 글자, 겹침 정의, 경과 형식, 잠 재시작, 옮길 때 보낸 표시, 코드 포인트)도 여기서 본다.
 */
class CareTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private val zone = "Asia/Seoul"
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, seoul).toInstant().toEpochMilli()

    private val now = at(2026, 11, 5, 15, 0)
    private val owner = PhoneState.paired("c_7F3AK2N9X0Q4M8T1V6B5R3H2CD", "d_5KQ2M8T1V6B5R3H2CD7F3AK2N9", Role.OWNER, now)

    private fun ev(
        id: String,
        kind: CareKind,
        start: Long,
        end: Long? = null,
        endUnknown: Boolean = false,
        side: FeedSide? = null,
        ml: Int? = null,
        note: String? = null,
        deletedAt: Long? = null,
    ) = CareEvent(id, kind, start, end, endUnknown, zone, side, ml, null, note, CareSource.PHONE, start, start, deletedAt)

    private fun feed(id: String, start: Long, end: Long? = null, endUnknown: Boolean = end == null, deletedAt: Long? = null) =
        ev(id, CareKind.FEED, start, end, endUnknown, deletedAt = deletedAt)

    private val feedingSent get() = { s: PhoneState -> s.outbox.filter { it.envelope.type == "feeding_started" } }

    @Test
    fun `진행 중 수유는 하나만`() {
        var f = CareFile().start(CareKind.FEED, now - 30 * MINUTE, zone).file
        f = f.start(CareKind.FEED, now - 10 * MINUTE, zone).file
        assertEquals(1, f.events.count { it.kind == CareKind.FEED && it.isOngoing })
        assertEquals(now - 10 * MINUTE, f.ongoing(CareKind.FEED)!!.startAt)
        // 수유 중에 잠을 시작할 수는 있다.
        f = f.start(CareKind.SLEEP, now, zone).file
        assertNotNull(f.ongoing(CareKind.FEED))
        assertNotNull(f.ongoing(CareKind.SLEEP))
        // 끝 모름·끝난 기록·메모는 진행 중이 아니다.
        assertFalse(feed("a", now).isOngoing)
        assertFalse(feed("b", now, now + MINUTE).isOngoing)
        assertFalse(ev("c", CareKind.NOTE, now, note = "x").isOngoing)
        // 저장(시트·고치기)으로도 진행 중을 둘로 만들 수 없다. 진행 중인 그 기록을 고치는 것은 된다.
        val running = f.ongoing(CareKind.FEED)!!
        assertNull(f.upsert(ev("other", CareKind.FEED, now - 5 * MINUTE), now))
        assertNotNull(f.upsert(running.copy(feedSide = FeedSide.LEFT), now))
        assertNotNull(f.upsert(feed("past", now - 2 * HOUR), now)) // 끝 모름은 진행 중이 아니다
        // 타이머의 쪽·양 고치기는 지금 진행 중인 그 기록에만 얹는다. 그사이 끝났으면 화면의 옛 사본으로 끝 시각을 덮지 않는다.
        val sided = f.editOngoing(running.id, now) { it.copy(feedSide = FeedSide.RIGHT) }!!
        assertEquals(FeedSide.RIGHT, sided.ongoing(CareKind.FEED)!!.feedSide)
        val ended = f.endNow(running.id, now)
        assertNull(ended.editOngoing(running.id, now) { it.copy(feedSide = FeedSide.RIGHT) })
        assertNull(f.delete(running.id, now).editOngoing(running.id, now) { it })
    }

    @Test
    fun `새 수유가 진행 중 수유를 끝낸다`() {
        val first = CareFile().start(CareKind.FEED, now, zone, FeedSide.LEFT)
        assertFalse(first.endedPrevious)
        val second = first.file.start(CareKind.FEED, now + 20 * MINUTE, zone)
        assertTrue(second.endedPrevious)
        assertEquals("앞 수유를 끝냈어요", restartNotice(second.closedPrevious!!))
        val prev = second.file.events.single { it.id == first.event.id }
        assertEquals(now + 20 * MINUTE, prev.endAt)
        assertFalse(prev.endUnknown)
        assertEquals(now + 20 * MINUTE, prev.updatedAt)
        // 3시간을 넘긴 진행 중 수유는 끝 모름으로 닫는다(U14, R3을 어기지 않음). 잠금 화면 '끝내기'도 같다.
        val lateStart = first.file.start(CareKind.FEED, now + 3 * HOUR + 1, zone)
        val late = lateStart.file.events.single { it.id == first.event.id }
        assertNull(late.endAt)
        assertTrue(late.endUnknown)
        assertEquals("앞 수유를 끝냈어요 · 끝 시각 모름", restartNotice(lateStart.closedPrevious!!))
        assertTrue(first.event.overMax(now + 3 * HOUR + 1)) // 타이머는 끝 시각 고르기·끝 모름으로 닫기를 준다
        assertFalse(first.event.overMax(now + 3 * HOUR))
        val unknown = first.file.endUnknown(first.event.id, now + HOUR).events.single()
        assertTrue(unknown.endUnknown)
        assertNull(unknown.endAt)
        val ended = first.file.endNow(first.event.id, now + 4 * HOUR).events.single()
        assertNull(ended.endAt)
        assertTrue(ended.endUnknown)
        assertEquals(now + 3 * HOUR, first.file.endNow(first.event.id, now + 3 * HOUR).events.single().endAt) // 꼭 3시간은 된다
        // 잠은 16시간이 기준이고, 잠을 또 시작해도 같은 규칙이다(U21).
        val sleep = CareFile().start(CareKind.SLEEP, now, zone)
        assertEquals(now + 16 * HOUR, sleep.file.endNow(sleep.event.id, now + 16 * HOUR).events.single().endAt)
        assertTrue(sleep.file.endNow(sleep.event.id, now + 16 * HOUR + 1).events.single().endUnknown)
        val again = sleep.file.start(CareKind.SLEEP, now + HOUR, zone)
        assertEquals(now + HOUR, again.closedPrevious!!.endAt)
        assertEquals("앞 잠을 끝냈어요", restartNotice(again.closedPrevious))
        assertEquals("앞 잠을 끝냈어요 · 끝 시각 모름", restartNotice(sleep.file.start(CareKind.SLEEP, now + 16 * HOUR + 1, zone).closedPrevious!!))
    }

    @Test
    fun `미래 시각과 최대 길이를 넘는 기록은 저장하지 않는다`() {
        val f = CareFile()
        fun p(e: CareEvent) = e.problem(now)
        assertNull(p(feed("a", now + MINUTE)))
        assertEquals(CareProblem.FUTURE, p(feed("a", now + MINUTE + 1)))
        assertEquals(CareProblem.FUTURE, p(feed("a", now - HOUR, now + MINUTE + 1)))
        assertNull(p(feed("a", now - HOUR, now - HOUR))) // 끝 = 시작은 된다
        assertEquals(CareProblem.END_BEFORE_START, p(feed("a", now - HOUR, now - HOUR - 1)))
        assertNull(p(feed("a", now - 3 * HOUR, now)))
        assertEquals(CareProblem.TOO_LONG, p(feed("a", now - 3 * HOUR - 1, now)))
        assertNull(p(ev("s", CareKind.SLEEP, now - 16 * HOUR, now)))
        assertEquals(CareProblem.TOO_LONG, p(ev("s", CareKind.SLEEP, now - 16 * HOUR - 1, now)))
        assertNull(p(ev("b", CareKind.FEED, now, endUnknown = true, side = FeedSide.BOTTLE, ml = 400)))
        assertNull(p(ev("b", CareKind.FEED, now, endUnknown = true, side = FeedSide.BOTTLE, ml = 0)))
        assertEquals(CareProblem.AMOUNT, p(ev("b", CareKind.FEED, now, endUnknown = true, side = FeedSide.BOTTLE, ml = 401)))
        assertEquals(CareProblem.AMOUNT, p(ev("b", CareKind.FEED, now, endUnknown = true, side = FeedSide.BOTTLE, ml = -1)))
        assertNull(p(ev("n", CareKind.NOTE, now, note = "가".repeat(200))))
        assertNull(p(ev("n", CareKind.NOTE, now, note = "👶".repeat(200)))) // 코드 포인트로 센다(U24): 이모지 200개는 된다
        assertEquals(CareProblem.NOTE_TOO_LONG, p(ev("n", CareKind.NOTE, now, note = "👶".repeat(201))))
        assertEquals(CareProblem.NOTE_TOO_LONG, p(ev("n", CareKind.NOTE, now, note = "가".repeat(201))))
        // 저장 거부: 파일은 그대로.
        assertEquals("앞으로의 시각은 적을 수 없어요", problemText(CareProblem.FUTURE, CareKind.FEED))
        assertEquals("잠은 16시간까지 적을 수 있어요", problemText(CareProblem.TOO_LONG, CareKind.SLEEP))
        assertNull(f.upsert(feed("a", now + 2 * MINUTE), now))
        assertNull(f.upsert(feed("a", now - 4 * HOUR, now), now))
        assertEquals(1, f.upsert(feed("a", now - HOUR, now), now)!!.events.size)
    }

    @Test
    fun `겹치는 수유는 경고하고 저장할 수 있다`() {
        val base = CareFile(events = listOf(feed("a", at(2026, 11, 5, 10), at(2026, 11, 5, 10, 30))))
        fun o(e: CareEvent, f: CareFile = base) = f.overlaps(e, now)
        assertTrue(o(feed("b", at(2026, 11, 5, 10, 20), at(2026, 11, 5, 10, 40))))
        assertFalse(o(feed("b", at(2026, 11, 5, 10, 30), at(2026, 11, 5, 10, 50)))) // 끝점만 닿음
        assertFalse(o(feed("b", at(2026, 11, 5, 9, 40), at(2026, 11, 5, 10))))
        assertTrue(o(feed("b", at(2026, 11, 5, 10)))) // 끝 모름 = 한 점, 시작이 같음
        assertTrue(o(feed("b", at(2026, 11, 5, 10, 15)))) // 구간 안의 점
        assertFalse(o(feed("b", at(2026, 11, 5, 10, 30)))) // 끝에 닿은 점
        assertFalse(o(feed("b", at(2026, 11, 5, 10), at(2026, 11, 5, 10)))) // 길이 0인 끝난 기록은 빈 구간: 시작이 같아도 안 겹침
        assertFalse(o(feed("b", at(2026, 11, 5, 10, 10)), CareFile(events = listOf(feed("z", at(2026, 11, 5, 10, 10), at(2026, 11, 5, 10, 10))))))
        val point = CareFile(events = listOf(feed("p", at(2026, 11, 5, 9))))
        assertTrue(o(feed("b", at(2026, 11, 5, 9)), point)) // 두 점은 같은 시각이면 겹침
        assertFalse(o(feed("b", at(2026, 11, 5, 9, 1)), point))
        assertTrue(o(feed("b", at(2026, 11, 5, 8, 30), at(2026, 11, 5, 9, 30)), point)) // 구간 안의 점(점이 기존 쪽)
        assertFalse(o(feed("a", at(2026, 11, 5, 10, 20), at(2026, 11, 5, 10, 40)))) // 자기 자신(고치기)
        assertFalse(o(ev("s", CareKind.SLEEP, at(2026, 11, 5, 10, 10), at(2026, 11, 5, 10, 20)))) // 다른 종류
        // 진행 중은 지금까지.
        val ongoing = CareFile(events = listOf(ev("r", CareKind.FEED, at(2026, 11, 5, 14))))
        assertTrue(o(feed("b", at(2026, 11, 5, 14, 50)), ongoing))
        assertFalse(o(feed("b", now), ongoing))
        // 기저귀·메모는 겹침을 보지 않는다(U16).
        val diapers = CareFile(events = listOf(ev("d1", CareKind.DIAPER, now)))
        assertFalse(o(ev("d2", CareKind.DIAPER, now), diapers))
        // 지운 기록과는 겹치지 않는다.
        assertFalse(o(feed("b", at(2026, 11, 5, 10, 15)), base.delete("a", now)))
        // 경고만 하고 저장은 된다.
        val saved = base.upsert(feed("b", at(2026, 11, 5, 10, 20), at(2026, 11, 5, 10, 40)), now)!!
        assertEquals(2, saved.events.size)
    }

    @Test
    fun `더 늦고 24시간 안의 수유만 차로 보내고 같은 저장에서 표시를 올린다`() {
        val events = listOf(feed("old", now - 3 * HOUR), feed("new", now - HOUR, now - 50 * MINUTE))
        val s = owner.reconciledFeeding(events, now, id = "01JC9A00Q4M8T1V6B5R3H2CD7F")
        val sent = feedingSent(s).single()
        assertEquals(rfc3339(now - HOUR), sent.envelope.body["at"]!!.jsonPrimitive.content)
        assertEquals(now - HOUR, s.lastSentFeedingAt)
        assertEquals("01JC9A00Q4M8T1V6B5R3H2CD7F", s.feedings.single().msgId) // 전송 상태 표시(보내는 중 → 보냄 → 반영됨)
        assertEquals(rfc3339(now - HOUR), s.feedings.single().at)
        assertEquals(now - HOUR, s.feedings.single().startAt) // 중계가 `at`을 고쳐도 오늘 카드와 맞출 보낸 시작
        // 다시 불러도 또 보내지 않는다.
        assertEquals(s, s.reconciledFeeding(events, now + MINUTE))
        // 24시간 경계: 꼭 24시간 전은 보내고, 그보다 이르면 보내지 않는다.
        assertEquals(1, feedingSent(owner.reconciledFeeding(listOf(feed("a", now - DAY)), now)).size)
        assertEquals(owner, owner.reconciledFeeding(listOf(feed("a", now - DAY - 1)), now))
        // owner·연결됨일 때만. 지운 수유는 보지 않는다.
        assertEquals(owner.copy(role = Role.GUARDIAN), owner.copy(role = Role.GUARDIAN).reconciledFeeding(events, now))
        assertEquals(PhoneState(), PhoneState().reconciledFeeding(events, now))
        val deleted = listOf(feed("a", now - 2 * HOUR), feed("b", now - HOUR, deletedAt = now))
        assertEquals(now - 2 * HOUR, owner.reconciledFeeding(deleted, now).lastSentFeedingAt)
        // 보낼 함과 표시는 한 파일 한 번의 저장이다: 저장이 실패하면 둘 다 디스크에 없다.
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val store = kr.aigyeot.phone.data.StateStore(File(dir, "state.json"))
        store.save(s)
        val back = store.load()
        assertEquals(now - HOUR, back.lastSentFeedingAt)
        assertEquals(sent, feedingSent(back).single())
        val blocked = File(dir, "blocked.json").apply { mkdirs(); File(this, "x").writeText("x") } // 디렉터리라 이름 바꾸기가 실패한다
        assertTrue(runCatching { kr.aigyeot.phone.data.StateStore(blocked).save(s) }.isFailure)
        dir.deleteRecursively()
    }

    @Test
    fun `수유 보내기는 정해진 계기에서만 일어난다`() {
        val main = File("src/main/kotlin")
        val sources = main.walkTopDown().filter { it.extension == "kt" }.associate { it.relativeTo(main).path to it.readText() }
        // feeding_started 봉투는 한 곳(startFeeding)에서만 만들고, startFeeding은 reconciledFeeding만 부른다.
        val makers = sources.filterValues { "envelope(\"feeding_started\"" in it }
        assertEquals(setOf("kr/aigyeot/phone/core/PhoneState.kt"), makers.keys)
        val callers = sources.flatMap { (path, text) ->
            Regex("""\bstartFeeding\(""").findAll(text)
                .filterNot { text.substring(0, it.range.first).endsWith("fun PhoneState.") } // 정의 자체
                .map { path to text.substring(text.lastIndexOf("fun ", it.range.first)).substringBefore("(") }
                .toList()
        }
        assertEquals(listOf("kr/aigyeot/phone/core/PhoneState.kt" to "fun PhoneState.reconciledFeeding"), callers)
        // 앱은 정해진 네 계기에서만 reconcileFeeding을 부른다(R6).
        val model = sources.getValue("kr/aigyeot/phone/AppModel.kt")
        val calls = Regex("""reconcileFeeding\(FeedTrigger\.(\w+)\)""").findAll(model).map { it.groupValues[1] }.toSet()
        assertEquals(FeedTrigger.entries.map { it.name }.toSet(), calls)
        assertEquals(calls.size, Regex("""reconcileFeeding\(FeedTrigger\.""").findAll(model).count())
        assertEquals(setOf("APP_START", "FOREGROUND", "CARE_SAVED", "PAIRED"), FeedTrigger.entries.map { it.name }.toSet())
        // 돌봄 기록을 바꾸는 동작은 보낼 함을 건드리지 않는다(다른 파일): 보내기는 reconcile을 거쳐야만 생긴다.
        val started = CareFile().start(CareKind.FEED, now, zone)
        assertTrue(owner.outbox.isEmpty())
        assertEquals(1, feedingSent(owner.reconciledFeeding(started.file.events, now)).size)
    }

    @Test
    fun `더 이른 수유는 보내지 않는다`() {
        val sent = owner.reconciledFeeding(listOf(feed("a", now - HOUR)), now)
        assertEquals(1, sent.outbox.size)
        // 더 이른 수유를 적었다.
        val earlier = listOf(feed("a", now - HOUR), feed("b", now - 2 * HOUR))
        assertEquals(sent, sent.reconciledFeeding(earlier, now))
        // 가장 늦은 수유를 더 이른 시각으로 고쳤다.
        assertEquals(sent, sent.reconciledFeeding(listOf(feed("a", now - 90 * MINUTE)), now))
        // 같은 시각도 다시 보내지 않는다. 더 늦게 고치면 보낸다.
        assertEquals(sent, sent.reconciledFeeding(listOf(feed("a", now - HOUR)), now))
        assertEquals(2, sent.reconciledFeeding(listOf(feed("a", now - 30 * MINUTE)), now).outbox.size)
    }

    @Test
    fun `지운 기록은 표시하고 30일 뒤 지운다`() {
        val f = CareFile(events = listOf(feed("a", now - HOUR), ev("n", CareKind.NOTE, now - 30 * MINUTE, note = "트림")))
        val d = f.delete("a", now)
        assertEquals(now, d.events.single { it.id == "a" }.deletedAt)
        assertEquals(f.events.single { it.id == "a" }.updatedAt, d.events.single { it.id == "a" }.updatedAt) // 표준 예제처럼 고친 시각은 그대로
        assertEquals(listOf("n"), d.dayEvents(LocalDate.of(2026, 11, 5), seoul).map { it.id })
        assertNull(lastFeedingAt(d, owner))
        assertEquals(f, d.undoDelete("a")) // 되돌리기
        // 진행 중 수유를 지우고 새로 시작한 뒤 되돌리면 되살린 것은 끝 모름(R1: 진행 중은 하나만).
        val running = CareFile().start(CareKind.FEED, now - 10 * MINUTE, zone)
        val restarted = running.file.delete(running.event.id, now - 5 * MINUTE).start(CareKind.FEED, now - MINUTE, zone).file
        val undone = restarted.undoDelete(running.event.id)
        assertEquals(1, undone.events.count { it.isOngoing })
        assertTrue(undone.events.single { it.id == running.event.id }.endUnknown)
        assertTrue(running.file.delete(running.event.id, now).undoDelete(running.event.id).events.single().isOngoing) // 겹치지 않으면 그대로 진행 중
        assertEquals(2, d.purged(now + 30 * DAY - 1).events.size)
        assertEquals(listOf("n"), d.purged(now + 30 * DAY).events.map { it.id })
        // 차에 보낸 수유를 지워도 보낼 함에는 아무것도 생기지 않는다(지우기 메시지가 없다, 2단계).
        val sent = owner.reconciledFeeding(f.events, now)
        assertEquals(sent, sent.reconciledFeeding(d.events, now))
    }

    @Test
    fun `연결을 끊어도 돌봄 기록은 남는다`() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val care = kr.aigyeot.phone.data.CareStore(File(dir, "care.json"))
        val state = kr.aigyeot.phone.data.StateStore(File(dir, "state.json"))
        val file = CareFile(events = listOf(feed("a", now - HOUR)), childNickname = "콩이")
        care.save(file)
        state.save(owner.reconciledFeeding(file.events, now))
        // 끊기(앱·차 쪽 모두)는 state.json만 빈 상태로 바꾼다. lastSentFeedingAt도 함께 사라진다.
        state.save(PhoneState())
        state.discardRecovery() // 끊기의 복구 파일 정리는 상태 파일에만 한다
        assertEquals(file, care.load())
        assertNull(state.load().lastSentFeedingAt)
        // 새 차에 붙으면 24시간 안의 최신 수유가 다시 간다(U6).
        val again = PhoneState.paired("c_NEW", "d_NEW", Role.OWNER, now + MINUTE).reconciledFeeding(care.load().events, now + MINUTE)
        assertEquals(now - HOUR, again.lastSentFeedingAt)
        dir.deleteRecursively()
    }

    @Test
    fun `이 휴대폰의 기록 지우기는 돌봄 기록도 지운다`() {
        val file = CareFile(events = listOf(feed("a", now - HOUR)), childNickname = "콩이", sleepDiaperEnabled = true, migratedFeedings = true)
        val state = owner.reconciledFeeding(file.events, now)
        val cleared = file.cleared()
        assertTrue(cleared.events.isEmpty())
        assertTrue(cleared.migratedFeedings) // 지운 수유 기록을 다시 옮겨 오지 않는다
        val clearedState = state.clearedRecords()
        assertTrue(clearedState.feedings.isEmpty())
        // 차에 이미 보낸 표시는 남는다(R7). 지운 뒤 더 이른 수유를 적어도 다시 보내지 않고, 더 늦은 수유는 보낸다.
        assertEquals(now - HOUR, clearedState.lastSentFeedingAt)
        assertEquals(clearedState, clearedState.reconciledFeeding(cleared.events, now))
        assertEquals(clearedState, clearedState.reconciledFeeding(listOf(feed("early", now - 2 * HOUR)), now))
        assertEquals(1, feedingSent(clearedState.reconciledFeeding(listOf(feed("late", now - MINUTE)), now)).size - feedingSent(clearedState).size)
    }

    @Test
    fun `지금 수유 기록을 한 번만 옮긴다`() {
        val r1 = FeedingRecord("01JC8Y2K6N3T5Q9W0ZB4H7X2DM", "2026-11-05T08:12:21+09:00", sentAt = now, reflected = true)
        val r2 = FeedingRecord("01JC8Y2K6N3T5Q9W0ZB4H7X2DN", "2026-11-05T11:00:00+09:00")
        val existing = feed(r2.msgId, instantOf(r2.at)!!) // 앞 실행이 옮기다 죽었다(같은 기록)
        val m = CareFile(events = listOf(existing)).migrated(listOf(r1, r2), zone)
        assertTrue(m.migratedFeedings)
        assertEquals(2, m.events.size) // 같은 id는 건너뛴다
        val moved = m.events.single { it.id == r1.msgId }
        assertEquals(CareKind.FEED, moved.kind)
        assertEquals(instantOf(r1.at), moved.startAt)
        assertNull(moved.endAt)
        assertTrue(moved.endUnknown)
        assertEquals(CareSource.PHONE, moved.source)
        assertEquals(zone, moved.zone)
        assertEquals(moved.startAt, moved.createdAt)
        // 두 번째 실행은 하지 않는다.
        val r3 = FeedingRecord("01JC8Y2K6N3T5Q9W0ZB4H7X2DP", "2026-11-05T12:00:00+09:00")
        assertEquals(m, m.migrated(listOf(r1, r2, r3), zone))
        // U22: 같은 일에서 state.json의 보낸 표시를 옮긴 수유 중 가장 늦은 시각으로 채운다. 그래서 옛 경로가 보낸 수유를 다시 보내지 않는다.
        val old = owner.copy(feedings = listOf(r1, r2))
        val marked = old.markedMigratedFeedings()
        assertEquals(instantOf(r2.at), marked.lastSentFeedingAt)
        assertEquals(marked, marked.reconciledFeeding(m.events, now))
        assertEquals(marked, marked.markedMigratedFeedings()) // 다시 해도 같다(그사이 죽어 다시 옮겨도 결과가 같다)
        assertEquals(owner, owner.markedMigratedFeedings()) // 옮길 것이 없으면 그대로
    }

    @Test
    fun `마지막 수유는 기록과 차 값 중 늦은 것`() {
        val car = Versioned(1, FeedingStateBody("2026-11-05T08:00:00+09:00", null))
        val withCar = owner.copy(feeding = car)
        val t08 = at(2026, 11, 5, 8)
        assertNull(lastFeedingAt(CareFile(), owner))
        assertEquals(t08, lastFeedingAt(CareFile(), withCar)) // 차 값만
        assertEquals(at(2026, 11, 5, 9), lastFeedingAt(CareFile(events = listOf(feed("a", at(2026, 11, 5, 9)))), withCar)) // 기록이 최신
        assertEquals(t08, lastFeedingAt(CareFile(events = listOf(feed("a", at(2026, 11, 5, 7)))), withCar)) // 차가 최신
        assertEquals(t08, lastFeedingAt(CareFile(events = listOf(feed("a", at(2026, 11, 5, 9), deletedAt = now))), withCar)) // 지운 것은 빼고
        // 전송 상태 기록(feedings)은 보지 않는다.
        assertNull(lastFeedingAt(CareFile(), owner.copy(feedings = listOf(FeedingRecord("m", "2026-11-05T09:00:00+09:00")))))
    }

    @Test
    fun `자정을 넘는 기록은 시작한 날에 두고 합계는 나눠 센다`() {
        val sleep = ev("s", CareKind.SLEEP, at(2026, 11, 4, 23), at(2026, 11, 5, 2))
        val f = CareFile(events = listOf(sleep))
        assertEquals(listOf("s"), f.dayEvents(LocalDate.of(2026, 11, 4), seoul).map { it.id })
        assertTrue(f.dayEvents(LocalDate.of(2026, 11, 5), seoul).isEmpty())
        assertEquals(HOUR, durationOn(f.events, CareKind.SLEEP, LocalDate.of(2026, 11, 4), seoul))
        assertEquals(2 * HOUR, durationOn(f.events, CareKind.SLEEP, LocalDate.of(2026, 11, 5), seoul))
        // 저장은 UTC, 보일 때는 그 기록의 시간대: 같은 순간이 기록 시간대의 날짜·시각으로 보인다.
        val utc = sleep.copy(zone = "UTC")
        assertEquals(LocalDate.of(2026, 11, 4), utc.day(seoul))
        assertEquals("오후 2:00", koClock(utc.startAt, ZoneId.of(utc.zone)))
        assertEquals("오후 11:00", koClock(sleep.startAt, seoul))
        // 하루 목록 줄(7.1 log.row): 구간은 그 기록 시간대, 끝 오전·오후는 같으면 뺀다(U20). 자정을 넘으면 붙인다.
        assertEquals("잠 · 오후 11:00–오전 2:00 · 3시간", logRowText(sleep, seoul))
        // U20: 끝이 시작과 같은 오전·오후면 날짜가 달라도 끝의 오전·오후를 뺀다(iOS와 같음).
        assertEquals("오후 11:00–1:00", "${koClock(at(2026, 11, 4, 23), seoul)}–${koClockAfter(at(2026, 11, 4, 23), at(2026, 11, 5, 13), seoul)}")
        assertEquals("수유 · 오후 12:52–1:07 · 왼쪽", logRowText(ev("f", CareKind.FEED, at(2026, 11, 5, 12, 52), at(2026, 11, 5, 13, 7), side = FeedSide.LEFT), seoul))
        assertEquals("수유 · 오후 3:00–3:05 · 젖병 120ml", logRowText(ev("b", CareKind.FEED, at(2026, 11, 5, 15), at(2026, 11, 5, 15, 5), side = FeedSide.BOTTLE, ml = 120), seoul))
        assertEquals("수유 · 오후 3:00–3:05", logRowText(ev("b", CareKind.FEED, at(2026, 11, 5, 15), at(2026, 11, 5, 15, 5)), seoul))
        // U41: 시작과 끝이 같은 분이면 시각 하나만. 초가 달라도 같은 분이면 같다. 분이 바뀌면 구간.
        val f457 = at(2026, 11, 5, 16, 57)
        assertEquals("수유 · 오후 4:57 · 왼쪽", logRowText(ev("u", CareKind.FEED, f457, f457 + 30_000, side = FeedSide.LEFT), seoul))
        assertEquals("잠 · 오후 4:57 · 1분 미만", logRowText(ev("u", CareKind.SLEEP, f457, f457 + 30_000), seoul))
        assertEquals("수유 · 오후 4:57–4:58", logRowText(ev("u", CareKind.FEED, f457 + 59_000, f457 + MINUTE), seoul))
        assertEquals("주행 · 오후 4:57", CarLine("s", null, f457, f457 + 1_000, null, null).text(seoul))
        assertEquals("수유 · 오후 12:00 · 끝 시각 모름", logRowText(feed("u", at(2026, 11, 5, 12)), seoul))
        assertEquals("수유 · 오후 12:00 · 왼쪽 · 끝 시각 모름", logRowText(ev("u", CareKind.FEED, at(2026, 11, 5, 12), endUnknown = true, side = FeedSide.LEFT), seoul)) // U37
        assertEquals("수유 · 오후 12:00 · 젖병 80ml · 끝 시각 모름", logRowText(ev("u", CareKind.FEED, at(2026, 11, 5, 12), endUnknown = true, side = FeedSide.BOTTLE, ml = 80), seoul))
        // U37: 수유·잠·기저귀에 단 메모는 둘째 줄, 메모 기록은 첫 줄에만.
        assertEquals("트림 함", memoLine(ev("m", CareKind.FEED, at(2026, 11, 5, 12), endUnknown = true, note = "트림 함")))
        assertNull(memoLine(ev("m", CareKind.NOTE, at(2026, 11, 5, 12), note = "트림 함")))
        assertNull(memoLine(feed("u", at(2026, 11, 5, 12))))
        assertEquals("수유 중 · 오후 2:00부터", logRowText(ev("o", CareKind.FEED, at(2026, 11, 5, 14)), seoul))
        assertEquals("자는 중 · 오후 2:00부터", logRowText(ev("o", CareKind.SLEEP, at(2026, 11, 5, 14)), seoul))
        assertEquals("잠 · 오전 9:00 · 끝 시각 모름", logRowText(ev("u", CareKind.SLEEP, at(2026, 11, 5, 9), endUnknown = true), seoul))
        assertEquals("기저귀 · 오후 3:10 · 소변", logRowText(ev("d", CareKind.DIAPER, at(2026, 11, 5, 15, 10)).copy(diaper = Diaper.WET), seoul))
        assertEquals("메모 · 오후 3:20 · 트림 잘 함", logRowText(ev("n", CareKind.NOTE, at(2026, 11, 5, 15, 20), note = "트림 잘 함"), seoul))
        // 시·분만 고르는 위젯: 아직 안 온 시각은 어제로, 끝 시각은 시작 뒤로 넘긴다.
        assertEquals(at(2026, 11, 4, 23, 50), latestAtOrBefore(23, 50, at(2026, 11, 5, 0, 30), seoul))
        assertEquals(at(2026, 11, 5, 0, 10), latestAtOrBefore(0, 10, at(2026, 11, 5, 0, 30), seoul))
        assertEquals(at(2026, 11, 5, 1, 0), firstAtOrAfter(1, 0, at(2026, 11, 4, 23, 0), seoul))
        assertEquals(at(2026, 11, 4, 23, 30), firstAtOrAfter(23, 30, at(2026, 11, 4, 23, 0), seoul))
        // 타이머 시작 시각 수정: 지금 시작에 가장 가까운 쪽. 하루 넘게 진행된 기록을 그대로 확인하면 날짜가 바뀌지 않는다.
        val started = at(2026, 11, 4, 15, 0) + 20_000
        assertEquals(started, nearestAtOrBefore(15, 0, started, at(2026, 11, 5, 16, 1), seoul))
        assertEquals(at(2026, 11, 4, 14, 50), nearestAtOrBefore(14, 50, started, at(2026, 11, 5, 16, 1), seoul))
        assertEquals(at(2026, 11, 5, 0, 10), nearestAtOrBefore(0, 10, at(2026, 11, 4, 23, 50), at(2026, 11, 5, 0, 31), seoul)) // 자정 너머 가까운 쪽
        assertEquals(at(2026, 11, 4, 23, 40), nearestAtOrBefore(23, 40, at(2026, 11, 4, 23, 50), at(2026, 11, 5, 0, 31), seoul))
        assertEquals(at(2026, 11, 4, 0, 50), nearestAtOrBefore(0, 50, at(2026, 11, 4, 23, 50), at(2026, 11, 5, 0, 31), seoul)) // 가까운 쪽이 미래면 그 앞
        // 선택기는 분까지다. 초가 있는 시작과 같은 분을 고르면 다음 날로 넘기지 않고 시작 시각을 쓴다.
        assertEquals(at(2026, 11, 5, 10, 0) + 30_000, firstAtOrAfter(10, 0, at(2026, 11, 5, 10, 0) + 30_000, seoul))
        assertEquals(at(2026, 11, 5, 10, 1), firstAtOrAfter(10, 1, at(2026, 11, 5, 10, 0) + 30_000, seoul))
        assertEquals(at(2026, 11, 6, 9, 59), firstAtOrAfter(9, 59, at(2026, 11, 5, 10, 0) + 30_000, seoul))
        // 진행 중·끝 모름은 길이를 세지 않는다(R14).
        val live = listOf(ev("o", CareKind.SLEEP, at(2026, 11, 5, 14)), ev("u", CareKind.SLEEP, at(2026, 11, 5, 9), endUnknown = true), sleep)
        assertEquals(2 * HOUR, durationOn(live, CareKind.SLEEP, LocalDate.of(2026, 11, 5), seoul))
    }

    @Test
    fun `패턴은 7일 중앙값이고 5개 미만이면 숨긴다`() {
        fun day(d: Int, h: Int, mi: Int = 0) = at(2026, 11, d, h, mi)
        // 11-05가 오늘. 11-04 수유 2번, 11-05 수유 4번: 간격 3h, (11-04 12시 → 11-05 0시) 12h는 빠짐, 2h, 2h30m, 4h(끝 모름도 시작은 셈)
        val feeds = listOf(
            feed("a", day(4, 9), day(4, 9, 20)),
            feed("b", day(4, 12)),
            feed("c", day(5, 0), day(5, 0, 15)),
            feed("d", day(5, 2)),
            feed("e", day(5, 4, 30)),
            feed("x", day(5, 5), deletedAt = now), // 지운 것은 빼고
            feed("old", day(4, 9) - 7 * DAY), // 7일 밖
        )
        val p4 = carePattern(CareFile(events = feeds), emptyList(), now, seoul)
        assertEquals(LocalDate.of(2026, 10, 30), p4.days.first())
        assertEquals(LocalDate.of(2026, 11, 5), p4.days.last())
        assertEquals(listOf(0, 0, 0, 0, 0, 2, 3), p4.feedsPerDay)
        // U27: 평균은 그 종류 기록이 있는 날만 나눠 센다(5개 ÷ 2일). 기록한 날이 없으면 "기록 없음"이고 "n일 기준"은 보이지 않는다.
        assertEquals(2, p4.feedDays)
        assertEquals("평균 2.5회", avgCountText(p4.feedsPerDay, p4.feedDays))
        assertEquals("2일 기준", daysText(p4.feedDays))
        assertEquals("평균 3.0회", avgCountText(listOf(0, 3, 3), 2))
        assertEquals("기록 없음", avgCountText(List(7) { 0 }, 0))
        assertNull(daysText(0))
        assertEquals("기록이 더 쌓이면 보여 드려요", intervalText(p4.feedIntervalMs))
        assertEquals("중앙값 2시간 30분", intervalText(150 * MINUTE))
        assertNull(p4.feedIntervalMs) // 쓸 간격 3개(3h, 2h, 2h30m): 5개 미만
        assertNull(carePattern(CareFile(events = feeds + feed("f", day(5, 8, 30))), emptyList(), now, seoul).feedIntervalMs) // 꼭 4개도 숨김
        val more = feeds + feed("f", day(5, 8, 30)) + feed("g", day(5, 10, 30))
        // 간격: 3h, 2h, 2h30m, 4h, 2h → 정렬 2h, 2h, 2h30m, 3h, 4h → 중앙값 2h30m
        assertEquals(150 * MINUTE, carePattern(CareFile(events = more), emptyList(), now, seoul).feedIntervalMs)
        // 짝수 개면 가운데 둘의 평균(정수 나눗셈): 2h, 2h, 2h30m, 3h, 4h, 1h → 1h, 2h, 2h, 2h30m, 3h, 4h → (2h + 2h30m) / 2
        val even = more + feed("h", day(5, 11, 30))
        assertEquals(135 * MINUTE, carePattern(CareFile(events = even), emptyList(), now, seoul).feedIntervalMs)
        // 꼭 10시간은 남기고, 10시간을 넘으면 뺀다.
        val tenH = listOf(0, 10, 11, 12, 13, 14).map { feed("t$it", day(4, it)) }
        assertEquals(HOUR, carePattern(CareFile(events = tenH), emptyList(), now, seoul).feedIntervalMs)
        // 10h+1ms 간격을 넣으면 5개가 되어 보이고, 빼면 4개라 숨는다.
        val overTenH = listOf(feed("y", day(4, 0) - 1)) + tenH.drop(1)
        assertNull(carePattern(CareFile(events = overTenH), emptyList(), now, seoul).feedIntervalMs)
        // R3의 1분 여유로 적은 내일 0시 수유는 오늘까지의 간격에 넣지 않는다(넣으면 간격 5개가 되어 보인다).
        val late = at(2026, 11, 5, 23, 59) + 30_000
        val evening = listOf(18, 19, 20, 21, 22).map { feed("e$it", day(5, it)) } + feed("next", at(2026, 11, 6, 0))
        assertNull(carePattern(CareFile(events = evening), emptyList(), late, seoul).feedIntervalMs)
    }

    @Test
    fun `진행 중 경과는 시작 시각으로 계산한다`() {
        val started = CareFile().start(CareKind.FEED, now, zone, FeedSide.LEFT)
        val e = started.event
        // 저장하지 않고 지금 시각으로만 계산한다: 같은 기록이 시각에 따라 다르게 보인다.
        assertEquals(12 * MINUTE, e.elapsed(now + 12 * MINUTE))
        assertEquals("수유 중 · 12분 · 왼쪽", ongoingText(e, now + 12 * MINUTE))
        assertEquals("수유 중 · 2시간 10분 · 왼쪽", ongoingText(e, now + 130 * MINUTE))
        assertEquals("자는 중 · 40분", ongoingText(ev("s", CareKind.SLEEP, now), now + 40 * MINUTE))
        assertEquals("수유 중 · 방금", ongoingText(e.copy(feedSide = null), now - MINUTE)) // 시계가 뒤로 가도 음수가 아니다(U20 1분 미만)
        assertEquals("수유 중 · 1분 · 왼쪽", ongoingText(e, now + MINUTE))
        assertEquals("12:34", timerText(12 * MINUTE + 34_000))
        assertEquals("0:40", timerText(40_000))
        assertEquals("1:02:34", timerText(HOUR + 2 * MINUTE + 34_000))
        assertEquals("2시간 10분 전", agoText(now - 130 * MINUTE, now))
        assertEquals("방금", agoText(now - 59_000, now))
        assertEquals("방금", elapsedText(59_999))
        // U27: 길이·평균은 "방금"을 쓰지 않는다.
        assertEquals("0분", durationText(0))
        assertEquals("1분 미만", durationText(59_999))
        assertEquals("1분", durationText(MINUTE))
        assertEquals("2시간", durationText(2 * HOUR))
        assertEquals("2시간 10분", durationText(130 * MINUTE))
        assertEquals("12분", elapsedText(12 * MINUTE + 59_000))
        assertEquals("1시간", elapsedText(HOUR)) // 딱 맞는 시간(11-05 결정)
        assertEquals("1시간 1분", elapsedText(HOUR + MINUTE))
        assertEquals(started.file, started.file) // 진행 중 표시는 파일을 바꾸지 않는다
    }

    @Test
    fun `잠과 기저귀를 끄면 버튼과 패턴에서 숨긴다`() {
        assertEquals(listOf(CareKind.FEED, CareKind.NOTE), quickKinds(sleepDiaper = false))
        assertEquals(listOf(CareKind.FEED, CareKind.NOTE, CareKind.SLEEP, CareKind.DIAPER), quickKinds(sleepDiaper = true))
        val events = listOf(ev("s", CareKind.SLEEP, at(2026, 11, 5, 9), at(2026, 11, 5, 10)), ev("d", CareKind.DIAPER, at(2026, 11, 5, 11)))
        val off = carePattern(CareFile(events = events), emptyList(), now, seoul)
        assertNull(off.sleepPerDayMs)
        assertNull(off.diapersPerDay)
        val on = carePattern(CareFile(events = events + ev("o", CareKind.SLEEP, at(2026, 11, 5, 14)), sleepDiaperEnabled = true), emptyList(), now, seoul)
        assertEquals(HOUR, on.sleepPerDayMs!!.last()) // 진행 중 잠은 합계에 넣지 않는다(R14)
        assertEquals(1, on.sleepDays)
        // U31: 자정을 넘는 잠은 걸친 두 날을 모두 센다(23시~2시 → 11-04·11-05). 길이는 그날 몫만 더한다.
        val cross = carePattern(CareFile(events = listOf(ev("x", CareKind.SLEEP, at(2026, 11, 4, 23), at(2026, 11, 5, 2))), sleepDiaperEnabled = true), emptyList(), now, seoul)
        assertEquals(2, cross.sleepDays)
        assertEquals("평균 1시간 30분", avgDurationText(cross.sleepPerDayMs!!, cross.sleepDays))
        assertEquals("평균 1시간", avgDurationText(on.sleepPerDayMs, on.sleepDays))
        assertEquals("평균 1.0회", avgCountText(on.diapersPerDay!!, on.diaperDays))
        assertEquals(0, off.sleepDays + carePattern(CareFile(sleepDiaperEnabled = true), emptyList(), now, seoul).sleepDays)
        assertEquals("기록 없음", avgDurationText(List(7) { 0L }, 0))
        assertEquals(1, on.diapersPerDay!!.last())
        // 이미 적은 기록은 지우지 않고 타임라인에는 계속 보인다.
        assertEquals(listOf("s", "d"), CareFile(events = events).dayEvents(LocalDate.of(2026, 11, 5), seoul).map { it.id })
    }

    @Test
    fun `리포트에서 차 기록 줄을 만든다`() {
        val env = ProtocolJson.decodeFromString(Envelope.serializer(), File("../../shared/src/test/resources/protocol/report_summary.json").readText())
        val report = ProtocolJson.decodeFromJsonElement(ReportSummaryBody.serializer(), env.body)
        val lines = carLines(listOf(report))
        assertEquals(2, lines.size)
        val drive = lines[0]
        assertTrue(drive.isDrive)
        assertEquals(report.sessionId, drive.sessionId)
        assertEquals("주행 · 오전 8:00–8:42", drive.text(seoul))
        assertEquals("차에서 울음 · 오전 8:05 · 자장가 · 그쳤어요", lines[1].text(seoul)) // U37: 한 줄에 시각
        assertEquals(instantOf("2026-11-05T08:05:12+09:00"), lines[1].startAt)
        // 리포트에 없는 값은 칸째 뺀다. 결과 글자는 리포트 값만 옮긴다.
        val ep = report.timeline.single()
        val none = carLines(listOf(report.copy(timeline = listOf(ep.copy(sound = null, result = null)))))[1]
        assertEquals("차에서 울음 · 오전 8:05", none.text(seoul))
        val failed = carLines(listOf(report.copy(timeline = listOf(ep.copy(result = kr.aigyeot.shared.EpisodeResult.FAILURE)))))[1]
        assertEquals("차에서 울음 · 오전 8:05 · 자장가 · 계속 울었어요", failed.text(seoul))
        val unknown = carLines(listOf(report.copy(timeline = listOf(ep.copy(sound = kr.aigyeot.shared.SoundRef(kr.aigyeot.shared.SoundMethod.WHITE_NOISE, "x"), result = kr.aigyeot.shared.EpisodeResult.UNDETERMINED)))))[1]
        assertEquals("차에서 울음 · 오전 8:05 · 백색소음 · 알 수 없음", unknown.text(seoul))
        // 소리 바꾸기 요청으로 시작한 에피소드는 '울음' 줄로 만들지 않는다(11-05 결정).
        val switched = report.copy(timeline = listOf(ep, ep.copy(episodeId = "e2", trigger = kr.aigyeot.shared.EpisodeTrigger.SWITCH_SOUND)))
        assertEquals(listOf(null, ep.episodeId), carLines(listOf(switched)).map { it.episodeId })
        // 차 기록 줄은 저장하지 않는다(리포트에서 그릴 때 만든다). 패턴의 '차 울음 에피소드 수'도 같은 줄에서 센다.
        assertEquals(1, carePattern(CareFile(), listOf(switched), now, seoul).carCryEpisodes)
        assertEquals(0, carePattern(CareFile(), listOf(switched), at(2026, 11, 4, 23, 59), seoul).carCryEpisodes) // 기기 기준 내일이면 7일에 넣지 않는다(R14)
        // 주행 끝 시각: 같은 오전·오후면 생략, 다르면 붙인다(11-05 결정).
        assertEquals("주행 · 오전 11:50–오후 12:20", CarLine("s", null, at(2026, 11, 5, 11, 50), at(2026, 11, 5, 12, 20), null, null).text(seoul))
    }

    @Test
    fun `표준 예제를 읽고 다시 쓰면 같다`() {
        val text = File("src/test/resources/care/sample.json").readText()
        val file = CareJson.decodeFromString(CareFile.serializer(), text)
        assertEquals("콩이", file.childNickname)
        assertTrue(file.sleepDiaperEnabled)
        assertEquals(6, file.events.size)
        assertEquals(FeedSide.LEFT, file.events[0].feedSide)
        assertTrue(file.events[1].endUnknown)
        assertEquals(120, file.events[2].amountMl)
        assertTrue(file.events[3].isOngoing) // 잠 진행 중
        assertEquals(Diaper.WET, file.events[4].diaper)
        assertEquals(1793860000000, file.events[5].deletedAt)
        // 다시 쓰면 같은 JSON(키 순서 무관, 모든 키를 쓰고 null도 키를 쓴다).
        val written = CareJson.encodeToString(CareFile.serializer(), file)
        assertEquals(Json.parseToJsonElement(text), Json.parseToJsonElement(written))
        val keys = (Json.parseToJsonElement(written) as kotlinx.serialization.json.JsonObject)["events"].let { it as kotlinx.serialization.json.JsonArray }
            .map { (it as kotlinx.serialization.json.JsonObject).keys }
        assertTrue(keys.all { it.size == 14 })
        // 없는 키는 기본값, 모르는 키는 무시.
        val sparse = CareJson.decodeFromString(
            CareFile.serializer(),
            """{"version":1,"events":[{"id":"x","kind":"NOTE","startAt":1,"zone":"Asia/Seoul","source":"PHONE","createdAt":1,"updatedAt":1,"later":true}],"future":1}""",
        )
        assertFalse(sparse.sleepDiaperEnabled)
        assertFalse(sparse.migratedFeedings)
        assertNull(sparse.childNickname)
        assertFalse(sparse.events.single().endUnknown)
        assertNull(sparse.events.single().deletedAt)
        // 파일 저장소로도 왕복한다.
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val store = kr.aigyeot.phone.data.CareStore(File(dir, "care.json"))
        store.save(file)
        assertEquals(file, store.load())
        val onDisk: JsonElement = Json.parseToJsonElement(File(dir, "care.json").readText())
        assertEquals(Json.parseToJsonElement(text), onDisk)
        dir.deleteRecursively()
    }

    @Test
    fun `내 기록을 정해진 CSV로 내보낸다`() {
        val file = CareJson.decodeFromString(CareFile.serializer(), File("src/test/resources/care/sample.json").readText())
        // 지운 메모는 빼고 시작 시각 순. UTF-8 BOM, CRLF, 빈 칸은 비움, 진행 중은 end 빈 칸·end_unknown false.
        val expected = "\uFEFF" + listOf(
            "kind,start,end,end_unknown,side,amount_ml,diaper,note",
            "FEED,2026-11-05T09:00:00+09:00,2026-11-05T09:15:00+09:00,false,LEFT,,,",
            "FEED,2026-11-05T12:00:00+09:00,,true,,,,",
            "FEED,2026-11-05T15:00:00+09:00,2026-11-05T15:05:00+09:00,false,BOTTLE,120,,",
            "DIAPER,2026-11-05T15:10:00+09:00,,false,,,WET,",
            "SLEEP,2026-11-05T16:00:00+09:00,,false,,,,",
        ).joinToString("\r\n") + "\r\n"
        assertEquals(expected, file.toCsv(seoul))
        assertTrue(file.toCsv(seoul).toByteArray(Charsets.UTF_8).take(3) == listOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        // 메모: RFC 4180(쉼표·따옴표·줄바꿈이 있으면 감싸고 따옴표는 두 번). 시각은 그 기록 시간대의 오프셋(초 단위).
        val notes = CareFile(
            events = listOf(
                ev("n1", CareKind.NOTE, at(2026, 11, 5, 9), note = "트림 잘 함"),
                ev("n2", CareKind.NOTE, at(2026, 11, 5, 10) + 999, note = "트림, \"잘\" 함\n다시"),
                ev("n3", CareKind.NOTE, at(2026, 11, 5, 11), note = "x").copy(zone = "UTC"),
            ),
        )
        val lines = notes.toCsv(seoul).removePrefix("\uFEFF").split("\r\n")
        assertEquals("NOTE,2026-11-05T09:00:00+09:00,,false,,,,트림 잘 함", lines[1])
        assertEquals("NOTE,2026-11-05T10:00:00+09:00,,false,,,,\"트림, \"\"잘\"\" 함\n다시\"", lines[2])
        assertEquals("NOTE,2026-11-05T02:00:00Z,,false,,,,x", lines[3])
        assertEquals("aigyeot-care-20261105.csv", exportFileName(now, seoul))
        // 차 기록 줄은 넣지 않는다: CSV는 care.json의 손 기록만 읽는다(리포트를 받지 않는다).
        assertEquals("\uFEFF" + CSV_HEADER + "\r\n", CareFile().toCsv(seoul))
    }
}
