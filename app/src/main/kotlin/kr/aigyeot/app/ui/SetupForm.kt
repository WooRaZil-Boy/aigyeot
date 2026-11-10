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

/** 수유 간격 한 칸(분)과 끝(분). 0 아래로 내리면 '비움'(수유 시계 꺼짐). */
internal const val INTERVAL_STEP_MIN = 30
internal const val INTERVAL_MAX_MIN = 600

/** 주입받은 시계의 이번 달(차 시간대). */
internal fun thisMonth(now: Long): YearMonth = YearMonth.from(Instant.ofEpochMilli(now).atZone(TimeText.zone))

/** 저장된 생년월(없거나 읽을 수 없으면 이번 달). */
internal fun birthMonthOf(saved: String?, now: Long): YearMonth = saved?.let { runCatching { YearMonth.parse(it) }.getOrNull() } ?: thisMonth(now)

/** 생년월 늘리기: 이번 달을 넘지 않는다. 넘으면 null(단추 끔). */
internal fun nextBirthMonth(ym: YearMonth, now: Long): YearMonth? = ym.plusMonths(1).takeUnless { it.isAfter(thisMonth(now)) }

internal fun birthMonthText(ym: YearMonth) = "${ym.year}년 ${ym.monthValue}월"

/** 수유 간격 줄이기: 한 칸 아래, 한 칸 이하면 비움(null). 비어 있으면 그대로(단추 끔). */
internal fun intervalDown(v: Int?): Int? = v?.let { if (it <= INTERVAL_STEP_MIN) null else it - INTERVAL_STEP_MIN }

/** 수유 간격 늘리기: 비어 있으면 한 칸부터, 끝에서 멈춘다. */
internal fun intervalUp(v: Int?): Int = ((v ?: 0) + INTERVAL_STEP_MIN).coerceAtMost(INTERVAL_MAX_MIN)

internal fun intervalStepText(v: Int?) = v?.let { "${it / 60}시간 ${it % 60}분" } ?: "비움(수유 시계 꺼짐)"

/** 월령 안내(명세서 10장): 0~1개월이면 참고 범위, 6개월 이상이면 비워 둘 수 있다는 것만. 앱이 간격을 정하지는 않는다. */
internal fun ageNote(birth: YearMonth, now: Long): String? {
    val age = ChronoUnit.MONTHS.between(birth, thisMonth(now))
    return when {
        age <= 1 -> "참고: 신생아 모유는 보통 2시간, 첫 몇 주는 2~4시간, 분유는 첫 달 말 3~4시간이에요"
        age >= 6 -> "비워 두면 수유 시계가 꺼져요"
        else -> null
    }
}

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
