package kr.aigyeot.phone.domain.usecase

import kr.aigyeot.phone.domain.model.APP_ZONE
import kr.aigyeot.phone.domain.model.CareFile
import kr.aigyeot.phone.domain.model.CareKind
import kr.aigyeot.phone.domain.model.CareSummaryBody
import kr.aigyeot.phone.domain.model.Credentials
import kr.aigyeot.phone.domain.model.MINUTE
import kr.aigyeot.phone.domain.model.reconciled
import kr.aigyeot.phone.domain.model.reconciledSummary
import kr.aigyeot.phone.domain.model.rfc3339
import kr.aigyeot.phone.domain.repository.CareRepository
import kr.aigyeot.phone.domain.repository.Clock
import kr.aigyeot.phone.domain.repository.PhoneStateRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// 돌봄 요약(기능 C, U45): R18 계산과 R19 보내기. 두 앱이 글자까지 같다.

/**
 * R5·R19: 차로 보낼 수유와 바뀐 돌봄 요약을 보낼 함에 넣고, 보낸 표시를 같은 `state.json` 저장에서 올린다. 저장에 실패하면 아무것도 바뀌지 않는다.
 * 규칙은 [reconciled]·[reconciledSummary](순수 함수)이고, 이 클래스는 그 결과를 저장하고 보낼 함을 깨운다. 연결이 없거나 owner가 아니면 그대로다.
 * 부르는 곳은 정해진 네 계기([RecordCare.syncToCar])와 기록 지우기를 마친 뒤뿐이다(R6).
 */
class ReconcileCareSummary(
    private val state: PhoneStateRepository,
    private val care: CareRepository,
    private val outbox: SendOutbox,
    private val clock: Clock,
) {
    /** 수유와 요약(R5·R19). `c`는 계기가 시작할 때 잡은 자격이다(P5). */
    suspend fun feedingAndSummary(c: Credentials) {
        if (state.commit(c) { it.reconciled(care.care.value, clock.now(), APP_ZONE, outbox.sending) } && state.state.value.outbox.isNotEmpty()) outbox.pump()
    }

    /** 요약만(기록 지우기를 마친 뒤). 지우기를 아직 못 마쳤으면 아무것도 넣지 않는다. */
    suspend fun summaryOnly() {
        val c = state.creds ?: return
        if (state.commit(c) { it.reconciledSummary(care.care.value, clock.now(), APP_ZONE, outbox.sending) } && state.state.value.outbox.isNotEmpty()) outbox.pump()
    }
}

/**
 * R18(U45) 돌봄 요약 계산: 숫자와 시각만. 메모 글·기저귀 종류·젖병 양·수유 쪽·별명은 넣지 않는다. 저장소가 없는 순수 계산이다.
 * '오늘 0시'·'어제 22시'·'오늘 6시'는 `asOf`를 `zone` 벽시계로 본 날 기준이다. 시각은 그 순간의 `zone` 오프셋, 초까지.
 */
object BuildCareSummary {
    operator fun invoke(care: CareFile, asOf: Long, zone: ZoneId): CareSummaryBody {
        val live = care.events.filter { it.deletedAt == null && it.startAt <= asOf }
        val today = Instant.ofEpochMilli(asOf).atZone(zone).toLocalDate()
        fun at(d: LocalDate, h: Int) = d.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()
        val midnight = at(today, 0)
        val feeds = live.count { it.kind == CareKind.FEED && it.startAt >= midnight } // 끝 모름 수유도 센다
        if (!care.sleepDiaperEnabled) return CareSummaryBody(rfc3339(asOf, zone), zone.id, false, null, null, null, null, null, null, null, feeds)
        fun t(ms: Long?) = ms?.let { rfc3339(it, zone) }
        val sleeps = live.filter { it.kind == CareKind.SLEEP && !it.endUnknown } // 끝 모름 잠은 모든 잠 칸에서 뺀다
        val ended = sleeps.filter { it.endAt != null && it.endAt <= asOf }
        val last = ended.maxWithOrNull(compareBy({ it.endAt }, { it.startAt }))
        val sleepMs = sleeps.sumOf { (minOf(it.endAt ?: asOf, asOf) - maxOf(it.startAt, midnight)).coerceAtLeast(0) }
        val diapers = live.filter { it.kind == CareKind.DIAPER }
        return CareSummaryBody(
            asOf = rfc3339(asOf, zone),
            zone = zone.id,
            sleepDiaperEnabled = true,
            lastSleepStartAt = t(last?.startAt),
            lastSleepEndAt = t(last?.endAt),
            sleepingSince = t(sleeps.filter { it.endAt == null }.maxOfOrNull { it.startAt }),
            todaySleepMin = (sleepMs / MINUTE).toInt(), // 합을 낸 뒤 내림
            nightWakeups = ended.count { it.endAt!! >= at(today.minusDays(1), 22) && it.endAt < at(today, 6) },
            lastDiaperAt = t(diapers.maxOfOrNull { it.startAt }),
            todayDiaperCount = diapers.count { it.startAt >= midnight },
            todayFeedCount = feeds,
        )
    }
}
