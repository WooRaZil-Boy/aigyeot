package kr.aigyeot.phone.domain.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 기록 가져오기(phone-care-plan U64~U69). 표준 예제는 shared의 파일을 경로 그대로 읽는다(복사본 없음, iOS와 같은 파일). */
class CareCsvTest {
    private val dir = File("../../shared/src/test/resources/care/import")
    private val now = 1_793_944_800_000L // 2026-11-06T15:00:00+09:00(U69)
    private val existing = CareJson.decodeFromString(CareFile.serializer(), File(dir, "existing.json").readText())

    private fun csv(name: String) = File(dir, "$name.csv").readText()
    private fun expected(name: String) = Json.parseToJsonElement(File(dir, "$name.expected.json").readText()).jsonObject

    /** U69: 기대 JSON의 `profile`은 결과가 아니라 고른 아이(입력)다. 없으면 null. */
    private fun parse(name: String): ImportDraft =
        CareCsv.parse(csv(name), existing, now, expected(name)["profile"]?.jsonPrimitive?.contentOrNull)

    /** 기대 JSON 꼴: format·profiles·rows(createdAt·updatedAt 뺌)·skipped 넷. */
    private fun ImportDraft.json() = buildJsonObject {
        put("format", format.name)
        put("profiles", JsonArray(profiles.map(::JsonPrimitive)))
        put("rows", JsonArray(rows.map { JsonObject(CareJson.encodeToJsonElement(CareEvent.serializer(), it).jsonObject - "createdAt" - "updatedAt") }))
        put("skipped", buildJsonObject {
            put("unsupported", skipped.unsupported)
            put("unreadable", skipped.unreadable)
            put("future", skipped.future)
            put("old", skipped.old)
            put("duplicate", skipped.duplicate)
        })
    }

    private fun aigyeot(vararg rows: String) = (listOf(CSV_HEADER) + rows).joinToString("\r\n") + "\r\n"

    @Test
    fun `헤더로 형식을 정하고 모르는 헤더는 거부한다`() {
        assertEquals(ImportFormat.AIGYEOT, parse("aigyeot").format)
        assertEquals(ImportFormat.HUCKLEBERRY, parse("huckleberry").format)
        assertEquals(ImportFormat.NARA, parse("nara").format)
        assertEquals(JsonObject(expected("unknown")), parse("unknown").json()) // UNKNOWN, 줄은 읽지 않는다
        // 칸 이름은 앞뒤 공백을 지우고 대소문자 없이 견준다. Nara를 Huckleberry보다 먼저 본다.
        assertEquals(ImportFormat.AIGYEOT, CareCsv.parse(" KIND,Start,end,END_UNKNOWN,side,amount_ml,diaper, Note \n", existing, now).format)
        assertEquals(ImportFormat.NARA, CareCsv.parse("type,start,end,duration, START DATE/TIME \n", existing, now).format)
        assertEquals(ImportFormat.HUCKLEBERRY, CareCsv.parse("TYPE,Start,End,Duration,Notes\n", existing, now).format)
        // 아이곁 칸이 하나 모자라거나 Huckleberry의 Duration이 없으면 추측하지 않는다.
        assertEquals(ImportFormat.UNKNOWN, CareCsv.parse("kind,start,end,end_unknown,side,amount_ml,diaper\n", existing, now).format)
        assertEquals(ImportFormat.UNKNOWN, CareCsv.parse("Type,Start,End,Notes\nFeed,2026-11-06 09:00,,\n", existing, now).format)
        assertEquals(ImportFormat.UNKNOWN, CareCsv.parse("", existing, now).format)
        // U68 ③: 빈 줄·공백뿐인 레코드·빈 칸만 있는 레코드는 건너뛰고, 헤더는 첫 번째로 비지 않은 레코드다(iOS와 같은 단언).
        val lead = CareCsv.parse("\r\n \n,,\n" + aigyeot("FEED,${rfc3339(now - HOUR)},${rfc3339(now - HOUR + 600_000)},false,LEFT,,,"), CareFile(), now)
        assertEquals(ImportFormat.AIGYEOT, lead.format)
        assertEquals(1, lead.rows.size)
    }

    @Test
    fun `너무 큰 파일은 읽지 않는다`() {
        assertNull(CareCsv.read(ByteArray(CareCsv.MAX_BYTES + 1) { 'a'.code.toByte() }))
        assertNotNull(CareCsv.read(ByteArray(CareCsv.MAX_BYTES) { 'a'.code.toByte() }))
        val line = "a\n"
        assertNull(CareCsv.read(line.repeat(CareCsv.MAX_LINES + 1).toByteArray()))
        assertEquals(line.repeat(CareCsv.MAX_LINES), CareCsv.read(line.repeat(CareCsv.MAX_LINES).toByteArray()))
        assertNull(CareCsv.read((line.repeat(CareCsv.MAX_LINES) + "a").toByteArray())) // 끝 줄바꿈이 없는 마지막 줄도 센다
        // UTF-8, 앞 BOM은 버린다. UTF-8로 풀 수 없으면(EUC-KR 등) 빈 글이라 형식을 정하지 못한다(import.unreadable).
        assertEquals(CSV_HEADER, CareCsv.read(("﻿" + CSV_HEADER).toByteArray()))
        val euckr = CareCsv.read("Type,Start,End,Duration\n수유,,,\n".toByteArray(charset("EUC-KR")))
        assertEquals("", euckr)
        assertEquals(ImportFormat.UNKNOWN, CareCsv.parse(euckr!!, existing, now).format)
    }

    @Test
    fun `세 형식 예제를 기대 기록으로 바꾼다`() {
        for (name in listOf("aigyeot", "huckleberry", "nara")) {
            val d = parse(name)
            assertEquals(name, JsonObject(expected(name) - "profile"), d.json())
            assertTrue(name, d.rows.all { it.createdAt == now && it.updatedAt == now })
        }
    }

    @Test
    fun `Nara 아이를 고르면 그 아이 줄만 본다`() {
        val text = csv("nara")
        // 이름이 둘 이상인데 아직 고르지 않았다: 줄은 비고 건너뛴 수도 0이다. 이름은 파일에 처음 나온 순서.
        val none = CareCsv.parse(text, existing, now, null)
        assertEquals(listOf("EXAMPLE-B", "EXAMPLE-A"), none.profiles)
        assertEquals(emptyList<CareEvent>(), none.rows)
        assertEquals(ImportSkipped(), none.skipped)
        // 고르지 않은 아이의 줄은 rows에도 skipped에도 세지 않는다(A 40줄 = rows 26 + skipped 14).
        val a = CareCsv.parse(text, existing, now, "EXAMPLE-A")
        assertEquals(40, a.rows.size + a.skipped.total)
        val b = CareCsv.parse(text, existing, now, "EXAMPLE-B")
        assertEquals(listOf("EXAMPLE B 수유"), b.rows.map { it.note })
        assertEquals(ImportSkipped(unsupported = 1, future = 1, old = 1), b.skipped)
        // 이름이 하나면 고르지 않아도 그 줄을 본다(머리 + B 네 줄).
        val one = text.lines().take(5).joinToString("\n")
        assertEquals(b.rows, CareCsv.parse(one, existing, now, null).rows)
    }

    @Test
    fun `같은 줄은 두 앱에서 같은 id다`() {
        // U66: "imp-" + SHA-256("kind|startAt|endAt|feedSide|amountMl", null은 빈 글자) 16진수 앞 16글자. 값은 shasum으로 따로 구했다.
        assertEquals("imp-47fd89f213c9a86d", importId(CareKind.FEED, 1_792_735_200_000, 1_792_735_920_000, FeedSide.RIGHT, null))
        assertEquals("imp-a1eabd74ef98fbee", importId(CareKind.DIAPER, 1_793_404_800_000, null, null, null))
        // 메모·기저귀 값은 id에 들지 않는다. 다른 형식에서 같은 기록이 오면 같은 id다: 가져온 Nara 기록을 아이곁 CSV로 내보내 다시 읽어도 같다.
        val nara = parse("nara").rows
        val again = CareCsv.parse(CareFile(events = nara).toCsv(APP_ZONE), CareFile(), now)
        assertEquals(nara.map { it.id }, again.rows.map { it.id })
        assertEquals(nara.size, again.rows.size)
    }

    @Test
    fun `건너뛴 이유를 정해진 순서로 센다`() {
        val h = (now - HOUR) // 1시간 전
        val text = aigyeot(
            "PUMP,,,false,,,,EXAMPLE 가져오지 않는 종류가 빈 시작보다 먼저",
            "FEED,${rfc3339(now + 2 * MINUTE)},,true,BOTTLE,401,,EXAMPLE 부피 오류가 미래보다 먼저",
            "DIAPER,${rfc3339(now + 2 * MINUTE)},,false,,,WET,EXAMPLE 미래",
            "DIAPER,${rfc3339(now + 2 * MINUTE)},,false,,,WET,EXAMPLE 미래가 같은 줄보다 먼저",
            "FEED,${rfc3339(1_792_454_430_000)},,true,LEFT,,,EXAMPLE 오래됨이 손 기록 30초 중복보다 먼저",
            "DIAPER,${rfc3339(h)},,false,,,WET,EXAMPLE 가져옴",
            "DIAPER,${rfc3339(h)},,false,,,DIRTY,EXAMPLE 앞줄과 같은 기록(기저귀 값은 id에 없다)",
            "DIAPER,${rfc3339(h + 60_000)},,false,,,WET,EXAMPLE 가져오기로 정한 앞줄과 60초(파일 안도 같은 규칙)",
            "FEED,${rfc3339(h - HOUR)},,true,BOTTLE,12.5,,EXAMPLE 건너뛴 줄",
            "FEED,${rfc3339(h - HOUR + 30_000)},,true,BOTTLE,120,,EXAMPLE 건너뛴 줄과 30초(건너뛴 줄과는 견주지 않는다)",
            "FEED,${rfc3339(h - HOUR + 61_000)},,true,LEFT,,,EXAMPLE 가져오기로 정한 앞줄과 31초",
        )
        val d = CareCsv.parse(text, existing, now)
        assertEquals(ImportSkipped(unsupported = 1, unreadable = 2, future = 2, old = 1, duplicate = 3), d.skipped)
        assertEquals(listOf("EXAMPLE 건너뛴 줄과 30초(건너뛴 줄과는 견주지 않는다)", "EXAMPLE 가져옴"), d.rows.map { it.note })
    }

    @Test
    fun `시각은 정해진 두 꼴만 엄격하게 읽고 빈 줄은 세지 않는다`() {
        val d = CareCsv.parse(
            aigyeot(
                "NOTE,2026-11-04T12:00:00.9876+09:00,,false,,,,EXAMPLE 소수 초는 ms 아래를 버린다",
                "NOTE,2026-11-04T04:00Z,,false,,,,EXAMPLE 초 없는 Z(서울 오후 1:00)",
                "NOTE,2026-11-04 14:00,,false,,,,EXAMPLE 오프셋 없는 벽시계(서울)",
                "NOTE,2026-11-04T15:00:00,,false,,,,EXAMPLE T인데 오프셋 없음",
                "NOTE,2026-11-04 16:00:00+09:00,,false,,,,EXAMPLE 공백인데 오프셋 있음",
                "NOTE,2026-02-30 09:00,,false,,,,EXAMPLE 없는 날",
                "NOTE,2026-11-04 24:00,,false,,,,EXAMPLE 24시",
                "  ,  , ,,,,,   ",
            ),
            CareFile(), now,
        )
        // 2026-11-04 12:00(서울) = now - 51시간
        val noon = now - 51 * HOUR
        assertEquals(listOf(noon + 987, noon + HOUR, noon + 2 * HOUR), d.rows.map { it.startAt })
        assertEquals(ImportSkipped(unreadable = 4), d.skipped) // 공백뿐인 줄은 세지 않는다
    }

    @Test
    fun `Huckleberry 조각은 공백으로 나눈 조각 전체로 읽는다`() {
        val head = "Type,Start,End,Duration,Start Condition,Start Location,End Condition,Notes"
        val rows = listOf("0:15R", "x0:15R 0:10L", "0:15r", "120mls 0:10L", "120 ML", "0:05L\t0:07R").mapIndexed { i, dur ->
            "Feed,2026-11-0${i + 1} 09:00,2026-11-0${i + 1} 09:15,$dur,,,,"
        }
        val d = CareCsv.parse((listOf(head) + rows).joinToString("\n"), CareFile(), now)
        assertEquals(
            listOf(FeedSide.RIGHT to null, FeedSide.LEFT to null, null to null, FeedSide.LEFT to null, FeedSide.BOTTLE to 120, FeedSide.BOTH to null),
            d.rows.map { it.feedSide to it.amountMl },
        )
    }

    @Test
    fun `부피와 모유 초는 소수를 읽고 범위는 반올림 전에 본다`() {
        // U66 11-09 보충: Nara 모유 초는 소수 허용(음수·숫자 아님은 그 쪽 없음), 끝 = 시작 + (합 × 1000을 ms 아래 버림).
        val nara = listOf(
            "Type,Profile Name,Start Date/time,Note,[Breastfeed] Left Duration (Seconds),[Breastfeed] Right Duration (Seconds),[Bottle Feed] Volume,[Bottle Feed] Volume Unit",
            "Breastfeed,,2026-11-01 09:00:00,,600.0,,,",
            "Breastfeed,,2026-11-01 12:00:00,,600.5,0,,",
            "Breastfeed,,2026-11-01 15:00:00,,-5,300,,",
            "Breastfeed,,2026-11-01 18:00:00,,abc,,,",
            "Bottle Feed,,2026-11-02 01:00:00,,,,400.4,ml",
            "Bottle Feed,,2026-11-02 02:00:00,,,,-0.4,ml",
            "Bottle Feed,,2026-11-02 03:00:00,,,,400,ml",
            "Bottle Feed,,2026-11-02 04:00:00,,,,0,ml",
            "Bottle Feed,,2026-11-02 05:00:00,,,,13.53,oz",
            "Bottle Feed,,2026-11-02 06:00:00,,,,13.52,oz",
            "Bottle Feed,,2026-11-02 07:00:00,,,,0.5,ml",
            "Bottle Feed,,2026-11-02 08:00:00,,,,120.49,ml",
        ).joinToString("\n")
        val d = CareCsv.parse(nara, CareFile(), now)
        val feeds = d.rows.filter { it.feedSide != FeedSide.BOTTLE }
        assertEquals(listOf(FeedSide.LEFT, FeedSide.LEFT, FeedSide.RIGHT, null), feeds.map { it.feedSide })
        assertEquals(listOf(600_000L, 600_500L, 300_000L, null), feeds.map { e -> e.endAt?.let { it - e.startAt } })
        // 부피: 범위(0 이상 400 이하)는 반올림 전 값(oz는 ×29.57 한 값), 0.5에서 올림. 13.53oz ≈ 400.08ml는 범위 밖.
        assertEquals(listOf(400, 0, 400, 1, 120), d.rows.filter { it.feedSide == FeedSide.BOTTLE }.map { it.amountMl })
        assertEquals(ImportSkipped(unreadable = 3), d.skipped)
        // 같은 함수: Huckleberry Duration의 부피 조각, 아이곁 amount_ml(정수만)
        val huck = CareCsv.parse(
            "Type,Start,End,Duration,Notes\nFeed,2026-11-03 09:00,,400.4ml,\nFeed,2026-11-03 10:00,,399.5ml,\n" +
                "Feed,2026-11-03 11:00,,-0.4ml 0:10R,\n", // 부호도 숫자다: 범위 밖이라 쪽 조각으로 살리지 않는다(U68 보충 ⑤)
            CareFile(), now,
        )
        assertEquals(listOf(400), huck.rows.map { it.amountMl })
        assertEquals(ImportSkipped(unreadable = 2), huck.skipped)
        val mine = CareCsv.parse(aigyeot("FEED,2026-11-04T09:00:00+09:00,,true,BOTTLE,400,,", "FEED,2026-11-04T10:00:00+09:00,,true,BOTTLE,400.0,,"), CareFile(), now)
        assertEquals(listOf(400), mine.rows.map { it.amountMl })
        assertEquals(ImportSkipped(unreadable = 1), mine.skipped)
    }

    @Test
    fun `아이곁 값은 종류에 맞는 칸에만 넣고 양 검사는 모든 줄에 한다`() {
        // U66 11-09 보충(두 앱 같은 id): 쪽·양은 FEED만, 기저귀는 DIAPER만. 다른 종류의 값은 null이고 id 해시도 null로 센다.
        val sleepAt = now - 6 * HOUR
        val d = CareCsv.parse(
            aigyeot(
                "SLEEP,${rfc3339(sleepAt)},${rfc3339(sleepAt + HOUR)},false,LEFT,120,WET,",
                "FEED,${rfc3339(now - 4 * HOUR)},${rfc3339(now - 4 * HOUR + 600_000)},false,BOTTLE,120,WET,",
                "DIAPER,${rfc3339(now - 2 * HOUR)},,false,LEFT,120,WET,",
                "NOTE,${rfc3339(now - HOUR)},,false,LEFT,120,DIRTY,EXAMPLE",
                "SLEEP,${rfc3339(now - 10 * HOUR)},,true,,401,,EXAMPLE 양 검사는 모든 종류에", // 범위 밖 → 읽을 수 없는 줄
                "DIAPER,${rfc3339(now - 9 * HOUR)},,false,,12.5,DRY,EXAMPLE 정수 아님", // 정수 아님 → 읽을 수 없는 줄
            ),
            CareFile(), now,
        )
        val by = d.rows.associateBy { it.kind }
        val sleep = by.getValue(CareKind.SLEEP)
        assertEquals(Triple(null, null, null), Triple(sleep.feedSide, sleep.amountMl, sleep.diaper))
        assertEquals(importId(CareKind.SLEEP, sleepAt, sleepAt + HOUR, null, null), sleep.id)
        val feed = by.getValue(CareKind.FEED)
        assertEquals(Triple(FeedSide.BOTTLE, 120, null), Triple(feed.feedSide, feed.amountMl, feed.diaper))
        val diaper = by.getValue(CareKind.DIAPER)
        assertEquals(Triple(null, null, Diaper.WET), Triple(diaper.feedSide, diaper.amountMl, diaper.diaper))
        assertEquals(importId(CareKind.DIAPER, now - 2 * HOUR, null, null, null), diaper.id)
        val note = by.getValue(CareKind.NOTE)
        assertEquals(Triple(null, null, null), Triple(note.feedSide, note.amountMl, note.diaper))
        assertEquals(ImportSkipped(unreadable = 2), d.skipped)
    }

    @Test
    fun `닫히지 않은 따옴표가 있는 파일은 읽지 않는다`() {
        // 메모의 닫는 따옴표가 빠지면 뒤 기록들이 그 메모 한 칸으로 삼켜진다(기저귀가 빠지고 건너뛴 수에도 안 잡힘). 파일째 읽을 수 없음(import.unreadable).
        val broken = aigyeot(
            "NOTE,${rfc3339(now - 2 * HOUR)},,false,,,,\"EXAMPLE 메모",
            "DIAPER,${rfc3339(now - HOUR)},,false,,,WET,",
        )
        assertEquals(ImportDraft(ImportFormat.UNKNOWN, emptyList(), emptyList(), ImportSkipped()), CareCsv.parse(broken, CareFile(), now))
        // 닫힌 따옴표 안의 줄바꿈은 그대로 칸 글자다(뒤 기록도 들어온다).
        val ok = aigyeot(
            "NOTE,${rfc3339(now - 2 * HOUR)},,false,,,,\"EXAMPLE 첫 줄\n둘째 줄\"",
            "DIAPER,${rfc3339(now - HOUR)},,false,,,WET,",
        )
        assertEquals(listOf("EXAMPLE 첫 줄\n둘째 줄", null), CareCsv.parse(ok, CareFile(), now).rows.map { it.note })
    }

    @Test
    fun `칸이 너무 많은 레코드가 있는 파일은 읽지 않는다`() {
        val unknown = ImportDraft(ImportFormat.UNKNOWN, emptyList(), emptyList(), ImportSkipped())
        fun nara(cols: Int) = (listOf("Type", "Start Date/time") + List(cols - 2) { "EXAMPLE$it" }).joinToString(",")
        val row = "Diaper,2026-11-06 09:00:00" + ",".repeat(CareCsv.MAX_COLUMNS - 2)
        // 상한(256칸)까지는 읽는다.
        assertEquals(ImportFormat.NARA, CareCsv.parse("${nara(CareCsv.MAX_COLUMNS)}\n$row\n", CareFile(), now).format)
        // 헤더가 상한을 넘으면(5MB 안 한 줄 수백만 칸) 형식을 보기 전에 끝낸다.
        assertEquals(unknown, CareCsv.parse("${nara(CareCsv.MAX_COLUMNS + 1)}\n$row\n", CareFile(), now))
        // 헤더는 맞아도 한 줄이라도 넘으면 파일째 읽지 않는다.
        assertEquals(unknown, CareCsv.parse("${nara(CareCsv.MAX_COLUMNS)}\n$row\n$row,\n", CareFile(), now))
    }

    @Test
    fun `끝이 미래인 수유·잠은 끝 모름으로 살린다`() {
        val d = CareCsv.parse(
            aigyeot(
                "FEED,${rfc3339(now - 10 * MINUTE)},${rfc3339(now + MINUTE)},false,LEFT,,,EXAMPLE 끝 지금 + 1분",
                "SLEEP,${rfc3339(now - HOUR)},${rfc3339(now + MINUTE + 1_000)},false,,,,EXAMPLE 끝 지금 + 1분 1초",
            ),
            CareFile(), now,
        )
        assertEquals(listOf(now + MINUTE to false, null to true), d.rows.sortedBy { it.kind }.map { it.endAt to it.endUnknown })
        // 끝 모름이면 id의 endAt도 빈 글자다(두 앱 같은 id).
        assertEquals(importId(CareKind.SLEEP, now - HOUR, null, null, null), d.rows.single { it.kind == CareKind.SLEEP }.id)
    }

    @Test
    fun `가장 늦은 수유 시각은 오늘·어제·그 전을 나눠 쓴다`() {
        // now = 11월 6일(금) 오후 3:00(서울)
        assertEquals("오전 12:00", koDayClock(now - 15 * HOUR, now, APP_ZONE))
        assertEquals("어제 오후 11:59", koDayClock(now - 15 * HOUR - MINUTE, now, APP_ZONE))
        assertEquals("어제 오전 12:00", koDayClock(now - 39 * HOUR, now, APP_ZONE))
        assertEquals("11월 4일 오후 11:59", koDayClock(now - 39 * HOUR - MINUTE, now, APP_ZONE))
        val last = CareCsv.parse(aigyeot("FEED,2026-11-03T11:00:00+09:00,,true,LEFT,,,"), CareFile(), now)
        assertEquals(listOf("수유 1", "가장 늦은 수유 11월 3일 오전 11:00"), importPreview(last, now, APP_ZONE).lines)
    }

    @Test
    fun `손으로 지운 가져온 기록은 다시 가져오지 않는다`() {
        val first = CareCsv.parse(csv("aigyeot"), CareFile(), now)
        val gone = first.rows.first { it.kind == CareKind.FEED }
        // 가져온 뒤 손으로 지웠다(R8: 지운 표시만). 같은 파일을 다시 가져오면 그 줄은 같은 id라 건너뛴다.
        val care = CareFile(events = first.rows).delete(gone.id, now)
        val again = CareCsv.parse(csv("aigyeot"), care, now)
        assertEquals(emptyList<CareEvent>(), again.rows)
        assertEquals(first.rows.size + first.skipped.duplicate, again.skipped.duplicate)
        // 지운 손 기록은 60초 중복으로 보지 않는다(예제의 '지운 손 기록 근처' 줄은 들어온다).
        assertTrue(parse("nara").rows.any { it.note == "EXAMPLE 지운 손 기록 근처" })
    }

    @Test
    fun `가져올 것이 없으면 닫기만 보인다`() {
        val none = CareCsv.parse(csv("aigyeot"), existing.copy(events = existing.events + parse("aigyeot").rows), now)
        assertEquals(emptyList<CareEvent>(), none.rows)
        assertEquals(ImportPreview("가져올 기록이 없어요", emptyList(), null, emptyList(), null, listOf("닫기")), importPreview(none, now, APP_ZONE))
        // 있으면: 제목, 종류별 수(0인 종류는 뺀다), 가장 늦은 수유, 건너뛴 이유(0이면 감춤), 시간대 안내, 가져오기·취소.
        assertEquals(
            ImportPreview(
                title = "기록 26건을 가져올게요",
                lines = listOf("수유 13 · 잠 5 · 기저귀 8", "가장 늦은 수유 오전 11:00"),
                skipTitle = "가져오지 않는 것",
                skips = listOf("이미 있는 기록 4건", "14일보다 오래된 기록 2건", "앞으로의 시각 1건", "읽을 수 없는 줄 5건", "가져오지 않는 종류 2건"),
                note = "시각은 한국 시간으로 읽어요",
                buttons = listOf("가져오기", "취소"),
            ),
            importPreview(parse("nara"), now, APP_ZONE),
        )
        val memo = CareCsv.parse(aigyeot("NOTE,${rfc3339(now - HOUR)},,false,,,,EXAMPLE"), CareFile(), now)
        assertEquals(ImportPreview("기록 1건을 가져올게요", listOf("메모 1"), null, emptyList(), "시각은 한국 시간으로 읽어요", listOf("가져오기", "취소")), importPreview(memo, now, APP_ZONE))
    }
}
