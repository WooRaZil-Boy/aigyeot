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
        assertEquals(EditCheck.Problem(CareProblem.FUTURE), file.check(a.copy(id = "b", startAt = now + HOUR, endAt = null), now))
        // 타이머의 시작 수정은 지금 시작에 가장 가까운 그 시각 중 지금 이전, 쪽을 바꾸면 젖병이 아닐 때 양을 비운다.
        assertEquals(onDate(today, 9, 0, zone), a.withStartPicked(9, 0, now + MINUTE).startAt)
        assertNull(a.copy(feedSide = FeedSide.BOTTLE, amountMl = 80).withSide(FeedSide.RIGHT).amountMl)
    }
}
