package kr.aigyeot.app.ui

import kr.aigyeot.app.store.db.AI_NOTICE_VERSION
import kr.aigyeot.app.store.db.ChildProfileEntity
import kr.aigyeot.engine.CarSeat
import kr.aigyeot.engine.Method
import kr.aigyeot.engine.MethodKind
import java.time.Instant
import java.time.YearMonth
import java.time.temporal.ChronoUnit

// 아기 설정 양식의 규칙(명세서 10·11장). 화면(SetupScreen)은 이 값·함수로 표시하고 누름만 넘긴다. 오늘은 주입받은 시계로 정한다.

/** 수유 간격 한 칸(분)과 끝(분). 바퀴는 '안 씀'(null, 수유 시계 꺼짐) + 한 칸 ~ 끝. */
internal const val INTERVAL_STEP_MIN = 30
internal const val INTERVAL_MAX_MIN = 600

/** 태어난 해 바퀴가 거슬러 가는 해 수(올해~3년 전, C387: 대상 0~2세 + 여유). */
internal const val BIRTH_YEARS_BACK = 3

/** 주입받은 시계의 이번 달(차 시간대). */
internal fun thisMonth(now: Long): YearMonth = YearMonth.from(Instant.ofEpochMilli(now).atZone(TimeText.zone))

/** 저장된 생년월(없거나 읽을 수 없으면 이번 달). */
internal fun birthMonthOf(saved: String?, now: Long): YearMonth = saved?.let { runCatching { YearMonth.parse(it) }.getOrNull() } ?: thisMonth(now)

/** 해 바퀴(오름차순): 올해−3 ~ 올해. 양식의 해가 그보다 이르면 맨 앞에 더해 지킨다(C387, 저장값을 잃지 않게). */
internal fun birthYears(now: Long, saved: Int? = null): List<Int> {
    val y = thisMonth(now).year
    val base = (y - BIRTH_YEARS_BACK..y).toList()
    return if (saved != null && saved < base.first()) listOf(saved) + base else base
}

/** 달 바퀴: 올해면 1 ~ 이번 달(미래 달 없음), 아니면 1 ~ 12. */
internal fun birthMonths(year: Int, now: Long): List<Int> = thisMonth(now).let { t -> (1..if (year == t.year) t.monthValue else 12).toList() }

/** 해를 바꿨을 때 달이 그 해 목록 밖이면(올해의 이번 달 뒤) 이번 달로. */
internal fun clampBirth(year: Int, month: Int, now: Long): YearMonth = YearMonth.of(year, month.coerceAtMost(birthMonths(year, now).last()))

internal fun birthMonthText(ym: YearMonth) = "${ym.year}년 ${ym.monthValue}월"

/** 간격 바퀴: '안 씀'(null) + 30분 ~ 10시간(30분 단위, 21개). 목록 밖 저장값은 크기 순 자리에 더해 지킨다(C387). */
internal fun intervals(saved: Int?): List<Int?> {
    val base = (INTERVAL_STEP_MIN..INTERVAL_MAX_MIN step INTERVAL_STEP_MIN).toList()
    return listOf<Int?>(null) + if (saved != null && saved !in base) (base + saved).sorted() else base
}

/** 간격 글(U20): '안 씀', '30분', '3시간', '2시간 30분'(빈 단위는 붙이지 않는다). */
internal fun intervalText(v: Int?): String = when {
    v == null -> "안 씀"
    v < 60 -> "${v}분"
    v % 60 == 0 -> "${v / 60}시간"
    else -> "${v / 60}시간 ${v % 60}분"
}

private fun ageMonths(birth: YearMonth, now: Long) = ChronoUnit.MONTHS.between(birth, thisMonth(now))

/** 월령 글(해·달 줄 부제·창 설명). */
internal fun ageText(birth: YearMonth, now: Long) = "생후 ${ageMonths(birth, now)}개월"

internal const val NEWBORN_HELP = "참고로 신생아 모유는 보통 2시간, 첫 몇 주는 2~4시간, 분유는 첫 달 말 3~4시간이에요"
internal const val INTERVAL_OFF_HELP = "'안 씀'이면 수유 시계가 꺼져요"

/**
 * 간격 도움말(명세 10장·C387): 0~1개월이면 참고 범위(먼저), '안 씀'이거나 6개월 이상이면 꺼진다는 것, 그 밖에는 없음. 앱이 간격을 정하지는 않는다.
 * 양식(간격 줄 아래)과 간격 창 설명(굴리는 값)이 같이 쓴다.
 */
internal fun setupHelp(birth: YearMonth, interval: Int?, now: Long): String? {
    val age = ageMonths(birth, now)
    return when {
        age <= 1 -> NEWBORN_HELP
        interval == null || age >= 6 -> INTERVAL_OFF_HELP
        else -> null
    }
}

/** 동의 블록 글(C119·C120 ③·C387): 굵은 줄, 그 아래 요약 두 문장, '자세히 보기' 창 제목과 원문(글자 그대로). */
internal const val CONSENT_TITLE = "아기의 보호자(법정대리인)로서 아이곁 사용에 동의해요"
internal const val CONSENT_SUMMARY = "질문하면 질문 글과 상태 숫자가 AI 서버로 가요. 울음소리와 차 위치 좌표는 보내지 않아요."
internal const val CONSENT_DETAIL_TITLE = "AI 서버로 가는 정보"
internal const val CONSENT_DETAIL =
    "질문하면 질문 글과 지금 상태 숫자(울음·수유·잠·기저귀 기록 요약, 카시트 시간, 실내 온도, 소리별 성적, 다음 쉼터 이름·거리)가 아이곁 중계 서버를 거쳐 AI 서버로 가요. 울음 소리와 차 위치 좌표는 보내지 않아요."

/** 저장할 수 있나(보호자 동의 + 별명). */
internal fun canSave(consent: Boolean, nickname: String) = consent && nickname.isNotBlank()

/**
 * 양식 → 저장 행. 음원 ①②와 자장가는 늘 쓰고(명세서 5장) 부모 노래는 [song]일 때만, 앞에서도 작게 틀기는 뒷좌석 출력이 없을 때만(D12).
 * 동의 시각은 처음 동의한 때를 지킨다.
 */
internal fun profileEntity(
    old: ChildProfileEntity?, nickname: String, birth: YearMonth, seat: CarSeat, interval: Int?,
    sibling: Boolean, song: Boolean, front: Boolean, noRearOutput: Boolean, now: Long,
) = ChildProfileEntity(
    id = old?.id ?: "child", nickname = nickname.trim(), birthYm = birth.toString(), carseat = seat.name,
    feedingIntervalMin = interval, lastFeedingStart = old?.lastFeedingStart,
    methods = listOfNotNull(Method(MethodKind.WHITE_NOISE), Method(MethodKind.WOMB), Method(MethodKind.LULLABY), if (song) Method(MethodKind.SONG) else null),
    hasSibling = sibling, frontFallbackAllowed = noRearOutput && front, consentAt = old?.consentAt ?: now, aiNoticeVersion = AI_NOTICE_VERSION,
)
