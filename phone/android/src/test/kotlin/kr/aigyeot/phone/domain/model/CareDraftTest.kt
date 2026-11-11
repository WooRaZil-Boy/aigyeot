package kr.aigyeot.phone.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CareDraftTest {
    private val zone = APP_ZONE
    private val today = LocalDate.of(2026, 11, 10)
    private val now = onDate(today, 9, 30, zone)

    @Test
    fun `시트 질문은 종류마다 정해진 글이다`() {
        assertEquals(listOf("어떻게 먹였어요?", "언제 잤어요?", "어떤 기저귀였어요?", "무엇을 적을까요?"), listOf(CareKind.FEED, CareKind.SLEEP, CareKind.DIAPER, CareKind.NOTE).map(::sheetQuestion))
    }

    @Test
    fun `시작 줄 값은 칩이면 시각과 칩, 직접이면 시각, 다른 날은 날짜와 시각이다`() {
        val d = CareDraft.new("s", CareKind.FEED, day = null, now = now)
        assertEquals("오전 9:30 · 지금", d.startValue(now))
        assertEquals("오전 9:15 · 15분 전", d.pick(15, now).startValue(now))
        assertEquals("오전 9:10", d.pick(null, now).setStart(9, 10, now).startValue(now)) // 직접 고른 오늘
        assertEquals("어제 오후 10:00", d.pick(null, now).setStart(22, 0, now).startValue(now)) // 아직 안 온 시각은 어제
        assertEquals("11월 7일(토) 오후 12:00", CareDraft.new("s", CareKind.DIAPER, day = today.minusDays(3), now = now).startValue(now))
        val e = CareEvent("e", CareKind.FEED, onDate(today, 8, 0, zone), endAt = onDate(today, 8, 20, zone), zone = zone.id, createdAt = now, updatedAt = now)
        assertEquals("오늘 오전 8:00", CareDraft.editing("s", e).startValue(now)) // 고치기는 늘 날짜와 시각
        assertEquals(listOf("시작", "끝", "메모 더하기"), CareDraft.editing("s", e).let { x -> x.rows.map { x.rowName(it) } })
    }

    @Test
    fun `끝 줄 값은 모르면 모름, 알면 시각과 길이다`() {
        val d = CareDraft.new("s", CareKind.SLEEP, day = null, now = now).pick(60, now)
        assertEquals("모름", d.endValue())
        assertEquals("오전 9:10 · 40분", d.setEnd(9, 10).endValue())
        assertEquals("오전 8:00", d.copy(end = onDate(today, 8, 0, zone)).endValue()) // 끝이 시작보다 이르면 시각만
    }

    @Test
    fun `빈 메모 줄은 메모 더하기다`() {
        val d = CareDraft.new("s", CareKind.DIAPER, day = null, now = now)
        assertEquals("메모 더하기", d.rowName(SheetField.MEMO))
        assertNull(d.memoValue())
        assertEquals("메모", d.setNote("트림 잘 함").rowName(SheetField.MEMO))
        assertEquals("트림 잘 함", d.setNote(" 트림 잘 함\n둘째 줄").memoValue())
        // 줄은 종류마다: 수유 시작·끝·메모, 기저귀 시각·메모, 잠 끝·메모(시작은 주인공), 메모 시각(글은 주인공).
        assertEquals(listOf(SheetField.START, SheetField.MEMO), d.rows)
        assertEquals(listOf(SheetField.END, SheetField.MEMO), d.copy(kind = CareKind.SLEEP).rows)
        assertEquals(listOf(SheetField.START), d.copy(kind = CareKind.NOTE).rows)
    }

    @Test
    fun `몇 분 전을 고른 동안 날짜와 시각 알약은 숨긴다`() {
        val d = CareDraft.new("s", CareKind.FEED, day = null, now = now)
        assertFalse(d.showsStart) // '지금'
        assertFalse(d.pick(30, now).showsStart)
        assertTrue(d.pick(null, now).showsStart) // '직접'
        assertTrue(CareDraft.new("s", CareKind.FEED, day = today.minusDays(1), now = now).showsStart) // 칩이 없는 시트(다른 날)
    }

    @Test
    fun `저장 단추 글은 시각과 고른 것과 종류다`() {
        val d = CareDraft.new("s", CareKind.FEED, day = null, now = now)
        assertEquals("오전 9:30 수유 저장하기", d.saveLabel())
        assertEquals("오전 9:30 왼쪽 수유 저장하기", d.toggleSide(FeedSide.LEFT).saveLabel())
        assertEquals("오전 9:30 소변 기저귀 저장하기", CareDraft.new("s", CareKind.DIAPER, day = null, now = now).saveLabel())
        assertEquals("오전 9:30 깨끗함 기저귀 저장하기", CareDraft.new("s", CareKind.DIAPER, day = null, now = now).copy(diaper = Diaper.DRY).saveLabel())
        assertEquals("오전 8:30 잠 저장하기", CareDraft.new("s", CareKind.SLEEP, day = null, now = now).pick(60, now).saveLabel())
        assertEquals("오전 8:30–9:10 잠 저장하기", CareDraft.new("s", CareKind.SLEEP, day = null, now = now).pick(60, now).setEnd(9, 10).saveLabel())
        assertEquals("메모 저장하기", CareDraft.new("s", CareKind.NOTE, day = null, now = now).saveLabel())
    }

    @Test
    fun `젖병 양이 있으면 수유 대신 양을 쓴다`() {
        val b = CareDraft.new("s", CareKind.FEED, day = null, now = now).toggleSide(FeedSide.BOTTLE)
        assertEquals("오전 9:30 젖병 수유 저장하기", b.saveLabel())
        assertEquals("오전 9:30 젖병 120ml 저장하기", b.copy(amount = 120).saveLabel())
    }

    @Test
    fun `22자를 넘으면 잠 구간과 고른 것과 시각 순으로 줄인다`() {
        val noon = onDate(today, 12, 30, zone)
        // ① 잠 구간이 넘치면 시작만: '오전 10:20–오후 12:40 잠 저장하기'(24자) → '오전 10:20 잠 저장하기'
        val sleep = CareDraft.new("s", CareKind.SLEEP, day = null, now = noon).pick(null, noon).setStart(10, 20, noon).setEnd(12, 40)
        assertEquals("오전 10:20–오후 12:40 잠 저장하기", sleep.saveLabel(shorten = false))
        assertEquals("오전 10:20 잠 저장하기", sleep.saveLabel())
        // ② 고른 것을 뺀다: '오후 12:52 젖병 400ml 저장하기'는 22자라 그대로, 시각이 길면 고른 것을 뺀다
        val late = onDate(today, 23, 59, zone)
        val bottle = CareDraft.new("s", CareKind.FEED, day = null, now = late).toggleSide(FeedSide.BOTTLE).copy(amount = 400)
        assertEquals("오후 11:59 젖병 400ml 저장하기", bottle.saveLabel()) // 22자
        val wide = CareDraft.new("s", CareKind.DIAPER, day = null, now = late).copy(diaper = Diaper.BOTH)
        assertEquals("오후 11:59 둘 다 기저귀 저장하기", wide.saveLabel()) // 21자
        // 큰 글자는 줄이지 않는다(두 줄까지)
        assertTrue(sleep.saveLabel(shorten = false).codePointCount(0, sleep.saveLabel(shorten = false).length) > SAVE_LABEL_MAX)
        // 줄인 글은 모두 22자 안이다(어떤 시각·고른 것이어도)
        for (k in CareKind.entries) for (h in listOf(0, 11, 12, 23)) {
            val t = onDate(today, h, 59, zone)
            val x = CareDraft.new("s", k, day = null, now = t).toggleSide(FeedSide.BOTTLE).copy(amount = 400, diaper = Diaper.BOTH).saveLabel()
            assertTrue(x, x.codePointCount(0, x.length) <= SAVE_LABEL_MAX)
        }
    }

    // U82 끝(iOS test_끝만_미래면_끝_줄을_탓한다 등과 같은 이름): 검사 차례는 시작 미래 → 끝 미래 → 끝이 이름 → 너무 김 → 양 → 메모, 글은 7장 R3 그대로.
    private val two = onDate(today, 14, 0, zone)
    private fun timed(start: Long, end: Long?) = CareEvent("e", CareKind.FEED, start, end, zone = zone.id, createdAt = two, updatedAt = two)

    @Test
    fun `끝만 미래면 끝 줄을 탓한다`() {
        // 지금 오후 2시, 시작 오후 1시·끝 오후 3시
        val p = timed(onDate(today, 13, 0, zone), onDate(today, 15, 0, zone)).problem(two)
        assertEquals(CareProblem.FUTURE_END, p)
        assertEquals(SheetField.END, p!!.field())
        assertEquals("앞으로의 시각은 적을 수 없어요", problemText(p, CareKind.FEED))
        // 나머지 값도 하나의 자리: 끝이 시작보다 이름·너무 김 → 끝, 양 → 양 칸, 메모 길이 → 메모
        assertEquals(
            listOf(SheetField.END, SheetField.END, SheetField.AMOUNT, SheetField.MEMO),
            listOf(CareProblem.END_BEFORE_START, CareProblem.TOO_LONG, CareProblem.AMOUNT, CareProblem.NOTE_TOO_LONG).map { it.field() },
        )
    }

    @Test
    fun `시작만 미래면 시작 줄을 탓한다`() {
        val p = timed(onDate(today, 15, 0, zone), null).problem(two)
        assertEquals(CareProblem.FUTURE_START, p)
        assertEquals(SheetField.START, p!!.field())
        assertEquals("앞으로의 시각은 적을 수 없어요", problemText(p, CareKind.FEED))
    }

    @Test
    fun `시작과 끝이 둘 다 미래면 시작 줄을 탓한다`() {
        val p = timed(onDate(today, 15, 0, zone), onDate(today, 15, 30, zone)).problem(two)
        assertEquals(CareProblem.FUTURE_START, p)
        assertEquals(SheetField.START, p!!.field())
    }

    @Test
    fun `오류 한 줄은 탓이 된 칸 바로 아래에 붙는다`() {
        // U82(11-12): 접힌 줄이면 그 줄, 젖병 양이면 양 칸, 잠 시작·메모 글이면 주인공 칸 아래(iOS와 같은 자리).
        val feed = CareDraft.new("s", CareKind.FEED, day = null, now = now)
        assertEquals(ErrorSpot.ROW, feed.errorSpot(SheetField.START))
        assertEquals(ErrorSpot.ROW, feed.errorSpot(SheetField.END))
        assertEquals(ErrorSpot.AMOUNT, feed.errorSpot(SheetField.AMOUNT))
        val sleep = feed.copy(kind = CareKind.SLEEP)
        assertEquals(ErrorSpot.HERO, sleep.errorSpot(SheetField.START)) // 잠 시작은 주인공 칸
        assertEquals(ErrorSpot.ROW, sleep.errorSpot(SheetField.END))
        val note = feed.copy(kind = CareKind.NOTE)
        assertEquals(ErrorSpot.HERO, note.errorSpot(SheetField.MEMO)) // 메모 글은 주인공 글칸
        assertEquals(ErrorSpot.ROW, note.errorSpot(SheetField.START))
    }

    @Test
    fun `메모가 비면 저장 단추가 꺼진다`() {
        val m = CareDraft.new("s", CareKind.NOTE, day = null, now = now)
        assertFalse(m.savable)
        assertFalse(m.setNote("   ").savable)
        assertTrue(m.setNote("트림").savable)
    }

    @Test
    fun `손 기록이 없을 때만 첫 기록 도움말을 보인다`() {
        assertTrue(CareFile().firstRecord)
        val e = CareEvent("e", CareKind.DIAPER, now, zone = zone.id, createdAt = now, updatedAt = now)
        assertFalse(CareFile(events = listOf(e)).firstRecord)
        assertTrue(CareFile(events = listOf(e.copy(deletedAt = now))).firstRecord) // 지운 기록은 세지 않는다(차 기록 줄은 care.json에 없다)
    }

    @Test
    fun `지난번 쪽은 새 기록에만 보이고 고치기에서는 숨긴다`() {
        // U80(11-12, iOS lastSide와 같음): 새 기록은 지우지 않은 가장 늦은 수유의 쪽, 고치기는 어느 기록이든 없음.
        val f1 = CareEvent("a", CareKind.FEED, now - HOUR, zone = zone.id, feedSide = FeedSide.LEFT, createdAt = now, updatedAt = now)
        val f2 = CareEvent("b", CareKind.FEED, now - MINUTE, zone = zone.id, feedSide = FeedSide.RIGHT, createdAt = now, updatedAt = now)
        val file = CareFile(events = listOf(f1, f2))
        assertEquals(FeedSide.RIGHT, file.sheetLastSide(CareDraft.new("s", CareKind.FEED, day = null, now = now)))
        assertEquals(FeedSide.RIGHT, file.sheetLastSide(CareDraft.new("s", CareKind.FEED, day = today.minusDays(1), now = now))) // 지난 기록 적기
        assertNull(file.sheetLastSide(CareDraft.editing("s", f1))) // 09시를 고쳐도 12시 쪽이 붙지 않는다
        assertNull(file.sheetLastSide(CareDraft.editing("s", f2)))
        // 지운 수유는 세지 않는다
        val deleted = CareFile(events = listOf(f1, f2.copy(deletedAt = now)))
        assertEquals(FeedSide.LEFT, deleted.sheetLastSide(CareDraft.new("s", CareKind.FEED, day = null, now = now)))
    }

    @Test
    fun `새 기록은 몇 분 전 칩이 시각을 말하고 직접이면 날짜와 시각을 고른다`() {
        val d = CareDraft.new("s", CareKind.FEED, day = null, now = now)
        assertTrue(d.showsAgoChips)
        assertFalse(d.showsStart)
        assertEquals(now - 15 * MINUTE, d.pick(15, now).start)
        assertTrue(d.pick(null, now).showsStart)
        // 오늘 새 기록에서 시각만 고르면 지금 이전에서 가장 늦은 그 시각(아직 안 온 시각이면 어제).
        assertEquals(onDate(today.minusDays(1), 22, 0, zone), d.pick(null, now).setStart(22, 0, now).start)
        // 날짜를 고르면 끝도 같은 만큼 옮기고, 그 뒤 시각을 골라도 그 날에 둔다.
        val ended = d.setEnd(9, 50)
        val moved = ended.moveStartDay(today.minusDays(2))
        assertEquals(onDate(today.minusDays(2), 9, 30, zone), moved.start)
        assertEquals(20 * MINUTE, moved.end!! - moved.start)
        assertEquals(onDate(today.minusDays(2), 22, 0, zone), moved.setStart(22, 0, now).start)
        // 다른 날에서 연 시트는 그날 낮 12시이고 칩이 없다.
        val other = CareDraft.new("s", CareKind.DIAPER, day = today.minusDays(3), now = now)
        assertFalse(other.showsAgoChips)
        assertEquals(onDate(today.minusDays(3), 12, 0, zone), other.start)
    }

    @Test
    fun `시작을 끝 뒤로 옮겨도 고른 끝은 그대로 두고 저장 검사가 알린다`() {
        val ten = onDate(today, 10, 0, zone)
        fun at(h: Int, m: Int) = onDate(today, h, m, zone)
        // 새 수유: 30분 전(9:30) → 끝 9:50 → '지금'(10:00). 사용자가 고른 끝은 말없이 바꾸지 않고(0분 수유를 만들지 않게, C384) 저장 검사가 R3로 알린다.
        val d = CareDraft.new("s", CareKind.FEED, day = null, now = ten).pick(30, ten).setEnd(9, 50).pick(0, ten)
        assertEquals(ten, d.start)
        assertEquals(at(9, 50), d.end)
        assertEquals(EditCheck.Problem(CareProblem.END_BEFORE_START), CareFile().check(d.event(ten), ten))
        // 직접 고른 시작도 같다. 끝 모름이면 끝을 만들지 않는다.
        val manual = CareDraft.new("s", CareKind.FEED, day = null, now = ten).pick(30, ten).setEnd(9, 50).pick(null, ten).setStart(9, 55, ten)
        assertEquals(at(9, 50), manual.end)
        assertNull(CareDraft.new("s", CareKind.SLEEP, day = null, now = ten).pick(30, ten).pick(0, ten).end)
        // 저장된 끝이 있는 기록을 고칠 때도 끝은 그대로 두고 저장 검사(R3)가 잡는다.
        val e = CareEvent("e", CareKind.FEED, at(9, 0), endAt = at(9, 20), zone = zone.id, createdAt = ten, updatedAt = ten)
        val edit = CareDraft.editing("s", e).setStart(9, 40, ten)
        assertEquals(at(9, 40), edit.start)
        assertEquals(at(9, 20), edit.end)
        assertEquals(EditCheck.Problem(CareProblem.END_BEFORE_START), CareFile(events = listOf(e)).check(edit.event(ten, e), ten))
    }

    @Test
    fun `고치기는 그 기록의 날에 시각만 바꾸고 종류에 맞지 않는 칸은 비운다`() {
        val e = CareEvent("e", CareKind.FEED, onDate(today.minusDays(1), 23, 0, zone), endAt = onDate(today, 0, 20, zone), zone = zone.id,
            feedSide = FeedSide.BOTTLE, amountMl = 120, createdAt = now, updatedAt = now)
        val d = CareDraft.editing("s", e)
        assertFalse(d.showsAgoChips)
        assertEquals(onDate(today.minusDays(1), 22, 0, zone), d.setStart(22, 0, now).start)
        // 쪽을 젖병에서 바꾸면 양은 저장하지 않는다. 끝을 모름으로 하면 끝 시각 모름.
        val left = d.toggleSide(FeedSide.BOTTLE).toggleSide(FeedSide.LEFT).setEndUnknown().event(now, e)
        assertEquals(FeedSide.LEFT, left.feedSide)
        assertNull(left.amountMl)
        assertTrue(left.endUnknown)
        // 메모로 바꾸면 끝·쪽·양이 없다. 메모는 앞뒤 빈칸을 걷고 200자에서 더 받지 않는다.
        val note = d.copy(kind = CareKind.NOTE).setNote("  트림  ").event(now, e)
        assertNull(note.endAt)
        assertNull(note.feedSide)
        assertEquals("트림", note.note)
        assertEquals(NOTE_MAX, d.setNote("가".repeat(NOTE_MAX)).setNote("가".repeat(NOTE_MAX + 1)).note.length)
        assertFalse(d.copy(kind = CareKind.NOTE, note = " ").savable)
    }

    @Test
    fun `저장 전 검사는 R3 문제가 먼저이고 그다음 R4 겹침이다`() {
        val a = CareEvent("a", CareKind.FEED, now - 30 * MINUTE, endAt = now - 10 * MINUTE, zone = zone.id, createdAt = now, updatedAt = now)
        val file = CareFile(events = listOf(a))
        assertEquals(EditCheck.Ok, file.check(a.copy(id = "b", startAt = now - 5 * MINUTE, endAt = now), now))
        assertEquals(EditCheck.Overlap, file.check(a.copy(id = "b", startAt = now - 20 * MINUTE, endAt = now), now))
        assertEquals(EditCheck.Problem(CareProblem.FUTURE_START), file.check(a.copy(id = "b", startAt = now + HOUR, endAt = null), now))
        // 타이머의 시작 수정은 지금 시작에 가장 가까운 그 시각 중 지금 이전, 쪽을 바꾸면 젖병이 아닐 때 양을 비운다.
        assertEquals(onDate(today, 9, 0, zone), a.withStartPicked(9, 0, now + MINUTE).startAt)
        assertNull(a.copy(feedSide = FeedSide.BOTTLE, amountMl = 80).withSide(FeedSide.RIGHT).amountMl)
    }
}
