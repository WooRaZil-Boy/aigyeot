package kr.aigyeot.phone.domain.model

import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.TreeSet
import kotlin.math.floor

// 기록 가져오기. 순수 규칙만 둔다: 파일 고르기·읽기는 화면 층, 저장은 RecordCare.importRows.
// 두 앱이 같은 파일에서 같은 결과(같은 id·같은 건너뛴 수)를 내야 한다(표준 예제로 맞춘다).

enum class ImportFormat { AIGYEOT, HUCKLEBERRY, NARA, UNKNOWN }

/** 건너뛴 이유별 수. 줄마다 처음 걸린 이유 하나로 센다. */
data class ImportSkipped(val unsupported: Int = 0, val unreadable: Int = 0, val future: Int = 0, val old: Int = 0, val duplicate: Int = 0) {
    val total get() = unsupported + unreadable + future + old + duplicate
}

/** `profiles`: Nara 아이 이름(파일에 처음 나온 순서). `rows`: 가져올 기록(startAt 오름차순). 고른 이름은 넣지 않는다. */
data class ImportDraft(val format: ImportFormat, val profiles: List<String>, val rows: List<CareEvent>, val skipped: ImportSkipped) {
    /** 이름이 둘 이상이면 아이를 골라야 미리 보기로 간다. */
    val needsChild get() = profiles.size > 1
}

object CareCsv {
    const val MAX_BYTES = 5 * 1024 * 1024 // 설계값
    const val MAX_LINES = 20_000 // 설계값
    const val MAX_COLUMNS = 256 // 설계값: 한 레코드의 칸 상한(Nara 넓은 표도 50칸 안팎). 넘으면 파일째 읽지 않는다
    const val OLD_MS = 14 * DAY // 설계값
    const val DUPLICATE_MS = MINUTE // 설계값
    private const val OZ_ML = 29.57

    /**
     * 5MB 또는 2만 줄을 넘으면 null(import.tooLarge). UTF-8로 읽고 앞 BOM은 버린다. 화면 층은 MAX_BYTES + 1바이트까지만 읽어 넘긴다.
     * UTF-8로 풀 수 없으면 빈 글: 헤더가 없어 [parse]가 UNKNOWN(import.unreadable)으로 끝낸다.
     */
    fun read(bytes: ByteArray): String? {
        if (bytes.size > MAX_BYTES) return null
        val text = runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrDefault("").removePrefix(BOM)
        val lines = text.count { it == '\n' } + if (text.isNotEmpty() && !text.endsWith('\n')) 1 else 0
        return text.takeIf { lines <= MAX_LINES }
    }

    /**
     * 헤더로 형식을 정하고(모르면 UNKNOWN, 줄은 읽지 않는다) 줄마다 기록으로 바꾼다.
     * `profile`은 고른 아이(Nara 이름이 둘 이상일 때만 본다). 둘 이상인데 null이면 아직 고르기 전이라 줄은 비고 건너뛴 수는 0이다.
     */
    fun parse(text: String, existing: CareFile, now: Long, profile: String? = null): ImportDraft =
        try {
            parseRecords(records(text.removePrefix(BOM)), existing, now, profile)
        } catch (_: Malformed) {
            ImportDraft(ImportFormat.UNKNOWN, emptyList(), emptyList(), ImportSkipped()) // import.unreadable
        }

    /** 헤더(첫 레코드)만 읽고 형식을 정한다. 모르는 형식이면 나머지는 읽지 않는다. 줄은 한 레코드씩 본다(파일 전체를 칸으로 펼치지 않는다). */
    private fun parseRecords(records: Sequence<List<String>>, existing: CareFile, now: Long, profile: String?): ImportDraft {
        val header = records.firstOrNull()?.map { it.trim().lowercase() }.orEmpty()
        val format = formatOf(header)
        if (format == ImportFormat.UNKNOWN) return ImportDraft(format, emptyList(), emptyList(), ImportSkipped())
        val index = header.withIndex().reversed().associate { (i, name) -> name to i } // 같은 이름이면 앞 칸
        val lines = records.drop(1).map { Line(index, it) }
        val profiles = if (format == ImportFormat.NARA) lines.map { it["profile name"] }.filter(String::isNotEmpty).distinct().toList() else emptyList()
        val chosen = when {
            profiles.size < 2 -> lines
            profile == null -> return ImportDraft(format, profiles, emptyList(), ImportSkipped())
            else -> lines.filter { it["profile name"] == profile }
        }
        val seen = Seen(existing.events) // 이 파일에서 이미 가져오기로 정한 줄도 같은 규칙으로 본다(건너뛴 줄은 아니다)
        val rows = mutableListOf<CareEvent>()
        val skips = IntArray(Skip.entries.size)
        for (line in chosen) {
            val read = when (format) {
                ImportFormat.AIGYEOT -> aigyeot(line)
                ImportFormat.HUCKLEBERRY -> huckleberry(line)
                else -> nara(line)
            }
            val e = (read as? Got)?.event(now)
            val skip = when {
                read is Skip -> read
                e!!.startAt > now + FUTURE_SLACK_MS -> Skip.FUTURE
                e.startAt < now - OLD_MS -> Skip.OLD
                seen.duplicate(e) -> Skip.DUPLICATE
                else -> null
            }
            if (skip != null) skips[skip.ordinal]++ else { rows += e!!; seen.take(e) }
        }
        val skipped = ImportSkipped(
            skips[Skip.UNSUPPORTED.ordinal],
            skips[Skip.UNREADABLE.ordinal],
            skips[Skip.FUTURE.ordinal],
            skips[Skip.OLD.ordinal],
            skips[Skip.DUPLICATE.ordinal],
        )
        return ImportDraft(format, profiles, rows.sortedBy { it.startAt }, skipped)
    }

    private const val BOM = "﻿"

    private fun formatOf(header: List<String>): ImportFormat = when {
        header.isNotEmpty() && header == CSV_HEADER.split(',') -> ImportFormat.AIGYEOT
        "type" in header && "start date/time" in header -> ImportFormat.NARA
        header.containsAll(listOf("type", "start", "end", "duration")) -> ImportFormat.HUCKLEBERRY
        else -> ImportFormat.UNKNOWN
    }

    /** 파일째 읽을 수 없다(import.unreadable): 닫히지 않은 따옴표로 끝났거나 한 레코드의 칸이 [MAX_COLUMNS]를 넘는다. */
    private class Malformed : Exception()

    /**
     * RFC 4180: 쉼표로 나누고, 따옴표 안의 쉼표·줄바꿈은 칸 글자, 따옴표 둘은 따옴표 하나. 줄바꿈은 CRLF·LF. 모든 칸이 빈 줄은 뺀다.
     * 한 레코드씩 내준다(다시 돌면 처음부터 다시 읽는다). 이럴 때는 [Malformed]로 파일째 읽지 않는다:
     * 따옴표가 닫히지 않은 채 끝나면(뒤 기록들을 한 칸(메모)으로 삼키지 않게), 한 레코드의 칸이 상한을 넘으면(5MB 안 한 줄 수백만 칸으로 메모리를 다 쓰지 않게).
     */
    private fun records(text: String): Sequence<List<String>> = sequence {
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        fun endCell() {
            row += cell.toString()
            cell.clear()
            if (row.size > MAX_COLUMNS) throw Malformed()
        }
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                quoted && c == '"' -> quoted = false
                quoted -> cell.append(c)
                c == '"' -> quoted = true
                c == ',' -> endCell()
                c == '\n' -> {
                    endCell()
                    if (row.any(String::isNotBlank)) yield(row)
                    row = mutableListOf()
                }
                c != '\r' -> cell.append(c)
            }
            i++
        }
        if (quoted) throw Malformed()
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            endCell()
            if (row.any(String::isNotBlank)) yield(row)
        }
    }

    /** 한 줄: 칸 이름(소문자)으로 읽는다. 없는 칸은 빈 글자. 메모 말고는 앞뒤 공백을 지운다(공백뿐이면 값 없음). */
    private class Line(private val index: Map<String, Int>, private val cells: List<String>) {
        fun raw(name: String) = index[name]?.let { cells.getOrNull(it) }.orEmpty()
        operator fun get(name: String) = raw(name).trim()
    }

    /** 줄을 읽은 결과: 기록 재료이거나 건너뛸 이유(①·②). ③~⑤는 [parse]가 본다. */
    private sealed interface Read

    private enum class Skip : Read { UNSUPPORTED, UNREADABLE, FUTURE, OLD, DUPLICATE }

    private class Got(
        val kind: CareKind,
        val start: Long,
        val end: Long?,
        val side: FeedSide?,
        val amount: Int?,
        val diaper: Diaper?,
        val note: String,
    ) : Read {
        /**
         * 끝이 비었거나 시작보다 이르거나 최대 길이를 넘거나 지금 + 1분보다 늦으면(미래 시각 금지) 끝 모름.
         * 진행 중은 만들지 않는다. 수유·잠 말고는 끝이 없다.
         */
        fun event(now: Long): CareEvent {
            val max = kind.maxMs
            val endAt = end?.takeIf { max != null && it >= start && it - start <= max && it <= now + FUTURE_SLACK_MS }
            return CareEvent(
                id = importId(kind, start, endAt, side, amount),
                kind = kind,
                startAt = start,
                endAt = endAt,
                endUnknown = max != null && endAt == null,
                zone = APP_ZONE.id,
                feedSide = side,
                amountMl = amount,
                diaper = diaper,
                note = note.takeIf { it.isNotBlank() }?.let(::clipNote),
                source = CareSource.IMPORT,
                createdAt = now,
                updatedAt = now,
            )
        }
    }

    /** 아이곁(내보내기의 역): 시각은 오프셋 있는 ISO 8601, kind·side·diaper는 enum 글자. 모르는 side·diaper는 null, amount_ml이 정수가 아니면 읽을 수 없는 줄. */
    private fun aigyeot(l: Line): Read {
        val kind = CareKind.entries.find { it.name == l["kind"] } ?: return Skip.UNSUPPORTED
        val start = time(l["start"]) ?: return Skip.UNREADABLE
        val amount = l["amount_ml"].ifEmpty { null }?.let { if (it.toIntOrNull() == null) return Skip.UNREADABLE else ml(it, "ml") ?: return Skip.UNREADABLE }
        // 값은 종류에 맞는 칸에만(두 앱 같은 id): 쪽·양은 FEED만, 기저귀는 DIAPER만. 양 검사는 위에서 모든 줄에 했다.
        val feed = kind == CareKind.FEED
        val side = FeedSide.entries.find { it.name == l["side"] }?.takeIf { feed }
        val diaper = Diaper.entries.find { it.name == l["diaper"] }?.takeIf { kind == CareKind.DIAPER }
        return Got(kind, start, time(l["end"]), side, amount?.takeIf { feed }, diaper, l.raw("note"))
    }

    /**
     * 부피 조각: 숫자 + ml·oz(단위 대소문자 없이, 사이 공백 허용). 앞뒤는 칸 끝이거나 공백이다("120mls"는 아니다).
     * 숫자는 부호를 포함해 읽어 범위 검사로 보낸다("-0.4ml"는 읽을 수 없는 줄).
     */
    private val VOLUME = Regex("""(?:^|\s)([+-]?\d+(?:\.\d+)?)\s*(ml|oz)(?=\s|$)""", RegexOption.IGNORE_CASE)

    /** 쪽 조각: 공백으로 나눈 조각 전체가 H:MM + 대문자 R·L. */
    private val SIDE = Regex("""\d+:\d{2}([RL])""")

    /** Huckleberry: Duration 조각에서 부피(젖병이 우선)나 쪽(H:MM + R·L)을 읽는다. 기저귀는 End Condition·Duration, Start Condition은 읽지 않는다. */
    private fun huckleberry(l: Line): Read {
        val kind = when (l["type"].lowercase()) {
            "sleep" -> CareKind.SLEEP
            "feed" -> CareKind.FEED
            "diaper" -> CareKind.DIAPER
            else -> return Skip.UNSUPPORTED
        }
        val start = time(l["start"]) ?: return Skip.UNREADABLE
        val duration = l["duration"]
        var side: FeedSide? = null
        var amount: Int? = null
        if (kind == CareKind.FEED) {
            val volume = VOLUME.find(duration)
            if (volume != null) {
                amount = ml(volume.groupValues[1], volume.groupValues[2]) ?: return Skip.UNREADABLE
                side = FeedSide.BOTTLE
            } else {
                val sides = duration.split(Regex("""\s+""")).mapNotNull { SIDE.matchEntire(it)?.groupValues?.get(1) }.toSet()
                side = when {
                    "R" in sides && "L" in sides -> FeedSide.BOTH
                    "R" in sides -> FeedSide.RIGHT
                    "L" in sides -> FeedSide.LEFT
                    else -> null
                }
            }
        }
        val diaper = if (kind == CareKind.DIAPER) diaperOf(l["end condition"] + " " + duration) else null
        return Got(kind, start, time(l["end"]), side, amount, diaper, l.raw("notes"))
    }

    /** Nara: 넓은 표 한 줄. 모유는 좌우 초로 쪽·끝, 젖병은 부피·단위(끝 모름), 잠은 끝 칸, 기저귀는 [Diaper] Type. */
    private fun nara(l: Line): Read {
        val type = l["type"].lowercase()
        val kind = when (type) {
            "breastfeed", "bottle feed" -> CareKind.FEED
            "sleep" -> CareKind.SLEEP
            "diaper" -> CareKind.DIAPER
            else -> return Skip.UNSUPPORTED
        }
        val start = time(l["start date/time"]) ?: return Skip.UNREADABLE
        val note = l.raw("note")
        return when (type) {
            "breastfeed" -> {
                val left = seconds(l["[breastfeed] left duration (seconds)"])
                val right = seconds(l["[breastfeed] right duration (seconds)"])
                val side = when {
                    left > 0 && right > 0 -> FeedSide.BOTH
                    left > 0 -> FeedSide.LEFT
                    right > 0 -> FeedSide.RIGHT
                    else -> null
                }
                Got(kind, start, side?.let { start + floor((left + right) * 1000).toLong() }, side, null, null, note) // ms 아래 버림
            }
            "bottle feed" -> {
                val amount = ml(l["[bottle feed] volume"], l["[bottle feed] volume unit"]) ?: return Skip.UNREADABLE
                Got(kind, start, null, FeedSide.BOTTLE, amount, null, note)
            }
            "sleep" -> Got(kind, start, time(l["[sleep] end date/time"]), null, null, null, note)
            else -> Got(kind, start, null, null, null, diaperOf(l["[diaper] type"]), note)
        }
    }

    /** Nara 모유 초: 소수 허용. 숫자가 아니거나 음수면 그 쪽은 없음(0). */
    private fun seconds(s: String): Double = s.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: 0.0

    /**
     * 부피(세 형식 공통): 소수 허용, ml면 그대로, oz면 ×29.57. 범위(0 이상 400 이하)는 반올림 **전** 값으로 보고
     * 0.5에서 올린다. 단위가 없거나 범위 밖이면 null(그 줄은 ②).
     */
    private fun ml(value: String, unit: String): Int? {
        val v = value.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null // "NaN"도 숫자로 읽히지만 반올림할 수 없다
        val ml = when (unit.trim().lowercase()) {
            "ml" -> v
            "oz" -> v * OZ_ML
            else -> return null
        }
        return if (ml >= AMOUNT_ML.first && ml <= AMOUNT_ML.last) floor(ml + 0.5).toInt() else null
    }

    /** 글자에 wet·pee → WET, dirty·poo → DIRTY, mixed·both → BOTH, dry → DRY(대소문자 없이). 둘 이상 맞으면 BOTH, 못 정하면 null. */
    private fun diaperOf(text: String): Diaper? {
        val t = text.lowercase()
        val hits = buildSet {
            if ("wet" in t || "pee" in t) add(Diaper.WET)
            if ("dirty" in t || "poo" in t) add(Diaper.DIRTY)
            if ("mixed" in t || "both" in t) add(Diaper.BOTH)
            if ("dry" in t) add(Diaper.DRY)
        }
        return if (hits.size > 1) Diaper.BOTH else hits.firstOrNull()
    }

    private val WALL = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm[:ss]").withResolverStyle(ResolverStyle.STRICT)

    /** 오프셋 있는 ISO 8601(`Z`·`±HH:MM`)이면 그대로, 없으면 `YYYY-MM-DD HH:MM[:SS]`를 Asia/Seoul 벽시계로. 못 읽으면 null. */
    private fun time(s: String): Long? {
        if (s.isEmpty()) return null
        return runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(s, WALL).atZone(APP_ZONE).toInstant().toEpochMilli() }.getOrNull()
    }

    /** 메모 200자(코드 포인트)에서 자른다. */
    private fun clipNote(s: String): String =
        if (s.codePointCount(0, s.length) <= NOTE_MAX) s else s.substring(0, s.offsetByCodePoints(0, NOTE_MAX))
}

/** id: "imp-" + SHA-256("kind|startAt|endAt|feedSide|amountMl", null은 빈 글자, 시각은 epoch ms) 16진수 앞 16글자. 다시 가져와도 겹치지 않는다. */
fun importId(kind: CareKind, startAt: Long, endAt: Long?, side: FeedSide?, amountMl: Int?): String {
    val key = "${kind.name}|$startAt|${endAt ?: ""}|${side?.name ?: ""}|${amountMl ?: ""}"
    return "imp-" + MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
}

/**
 * 중복 판정 한 곳: 같은 id(지운 기록 포함), 또는 같은 종류·시작 60초 이하인 지우지 않은 기록.
 * 미리 보기([CareCsv.parse])와 저장 순간([imported])이 같이 쓴다. 받아들인 줄은 [take]로 더해 그 뒤 줄과도 견준다.
 */
private class Seen(events: List<CareEvent>) {
    private val ids = events.mapTo(HashSet()) { it.id }

    /** 종류별 지우지 않은 기록의 시작 시각. 60초 이웃을 정렬된 집합에서 찾는다(줄마다 전체 기록을 훑지 않게). */
    private val starts = HashMap<CareKind, TreeSet<Long>>()

    init {
        for (e in events) if (e.deletedAt == null) addStart(e)
    }

    private fun addStart(e: CareEvent) {
        starts.getOrPut(e.kind) { TreeSet() } += e.startAt
    }

    fun duplicate(e: CareEvent): Boolean {
        if (e.id in ids) return true
        val near = starts[e.kind]?.ceiling(e.startAt - CareCsv.DUPLICATE_MS) ?: return false // 시작 - 60초 이상인 가장 이른 것
        return near <= e.startAt + CareCsv.DUPLICATE_MS
    }

    fun take(e: CareEvent) {
        ids += e.id
        addStart(e)
    }
}

/**
 * 가져온 줄을 한 번에 넣는다. 저장하는 순간의 기록으로 중복을 다시 본다:
 * 미리 보기 뒤 손으로 넣은 기록과 겹치는 줄, 같은 미리 보기를 두 번 저장한 줄은 넣지 않는다.
 */
fun CareFile.imported(rows: List<CareEvent>): CareFile {
    val seen = Seen(events)
    val fresh = rows.filter { r -> if (seen.duplicate(r)) false else true.also { seen.take(r) } }
    return copy(events = events + fresh)
}

/** 되돌리기: 이번에 넣은 id를 완전히 뺀다(지운 표시가 아니다. 다시 가져올 수 있게). */
fun CareFile.withoutIds(ids: Collection<String>): CareFile {
    val drop = ids.toHashSet() // 기록마다 id 목록을 훑지 않게
    return copy(events = events.filter { it.id !in drop })
}

// ---- 미리 보기 글(import.*) ----

/** 화면은 이것을 그리기만 한다. 가져올 기록이 0이면 [title]이 import.nothing이고 버튼은 '닫기'만. */
data class ImportPreview(
    val title: String,
    val lines: List<String>,
    val skipTitle: String?,
    val skips: List<String>,
    val note: String?,
    val buttons: List<String>,
)

fun importPreview(d: ImportDraft, now: Long, zone: ZoneId): ImportPreview {
    if (d.rows.isEmpty()) return ImportPreview("가져올 기록이 없어요", emptyList(), null, emptyList(), null, listOf("닫기"))
    fun count(k: CareKind) = d.rows.count { it.kind == k }
    val kinds = listOf(CareKind.FEED to "수유", CareKind.SLEEP to "잠", CareKind.DIAPER to "기저귀", CareKind.NOTE to "메모")
        .filter { (k, _) -> count(k) > 0 }.joinToString(" · ") { (k, name) -> "$name ${count(k)}" }
    val latestFeed = d.rows.lastFeedStart()?.let { "가장 늦은 수유 ${koDayClock(it, now, zone)}" }
    val s = d.skipped
    val skips = listOf(
        s.duplicate to "이미 있는 기록",
        s.old to "14일보다 오래된 기록",
        s.future to "앞으로의 시각",
        s.unreadable to "읽을 수 없는 줄",
        s.unsupported to "가져오지 않는 종류",
    ).filter { it.first > 0 }.map { (n, what) -> "$what ${n}건" }
    return ImportPreview(
        title = "기록 ${d.rows.size}건을 가져올게요",
        lines = listOfNotNull(kinds, latestFeed),
        skipTitle = "가져오지 않는 것".takeIf { skips.isNotEmpty() },
        skips = skips,
        note = "시각은 한국 시간으로 읽어요",
        buttons = listOf("가져오기", "취소"),
    )
}

/** 끝내는 안내(import.unreadable·tooLarge·failed). 화면은 글과 '닫기'만 보인다. */
const val IMPORT_UNREADABLE = "이 파일은 읽을 수 없어요. 아이곁·Huckleberry·Nara에서 내보낸 CSV인지 확인해 주세요."
const val IMPORT_TOO_LARGE = "파일이 너무 커요"
const val IMPORT_FAILED = "가져오지 못했어요. 다시 해 주세요."
