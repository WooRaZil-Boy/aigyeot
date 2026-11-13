package kr.aigyeot.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kr.aigyeot.phone.domain.model.APP_ZONE
import kr.aigyeot.phone.domain.model.CareEvent
import kr.aigyeot.phone.domain.model.CareFile
import kr.aigyeot.phone.domain.model.CareKind
import kr.aigyeot.phone.domain.model.Diaper
import kr.aigyeot.phone.domain.model.FeedSide
import kr.aigyeot.phone.domain.model.MINUTE
import kr.aigyeot.phone.domain.model.onDate
import kr.aigyeot.phone.domain.model.ulid
import java.time.Instant
import java.time.ZoneId

// debug 빌드만: 시연 시드(phone-care-plan U50). release에는 들어가지 않는다.
// 넣기: adb shell am broadcast -n kr.aigyeot.phone/.DebugSeedReceiver (앱이 꺼져 있어도 된다)

/**
 * 지난 6일은 날마다 같은 목록(현지 "시:분"). iOS도 이 목록을 따른다. 잠은 그날 안에서 끝나 오늘 칸에 섞이지 않는다.
 * 잠 끝도 벽시계로 정한다. 서머타임 바뀌는 날 길이를 더하면 밤잠 끝이 한 시간 밀려 다음 잠과 겹친다.
 * 그날은 밤잠(00:10~06:40)이 한 시간 짧거나 길어, 합을 13~14시간(U50)에 두게 보정한다(iOS `DemoSeed`와 같은 방법).
 */
private val PAST_FEEDS = listOf("06:50", "09:50", "12:50", "15:50", "18:50", "21:30", "23:40") // 20분씩, 왼쪽·오른쪽 번갈아
private val PAST_SLEEPS = listOf("00:10" to "06:40", "07:20" to "09:40", "10:20" to "12:40", "16:20" to "18:40") // 합 13시간 30분
private val PAST_DIAPERS = listOf(
    "06:45" to Diaper.WET, "09:45" to Diaper.DIRTY, "12:45" to Diaper.WET,
    "15:45" to Diaper.WET, "18:45" to Diaper.BOTH, "23:35" to Diaper.WET,
)

/**
 * U50: 기준은 시드를 넣는 순간(분 내림). 오늘 값은 차 시연 장면 C2와 같다:
 * 마지막 수유 120분 전(양쪽, 끝 100분 전)·다른 수유 6시간 전, 마지막 잠 210~150분 전(진행 중 없음), 지난밤 끝난 잠 1번(01:00~02:00),
 * 오늘 잠 합 120분, 마지막 기저귀 60분 전(소변)·오늘 기저귀 2번. 별명은 그대로 두고 잠·기저귀는 켠다.
 */
fun demoSeed(nowMs: Long, zone: ZoneId, keep: CareFile = CareFile()): CareFile {
    val t = nowMs / MINUTE * MINUTE
    val today = Instant.ofEpochMilli(t).atZone(zone).toLocalDate()
    val out = mutableListOf<CareEvent>()
    fun add(kind: CareKind, start: Long, end: Long? = null, side: FeedSide? = null, diaper: Diaper? = null) {
        out += CareEvent(ulid(start), kind, start, end, zone = zone.id, feedSide = side, diaper = diaper, createdAt = end ?: start, updatedAt = end ?: start)
    }
    fun hm(day: java.time.LocalDate, s: String) = onDate(day, s.take(2).toInt(), s.takeLast(2).toInt(), zone)
    for (d in 6 downTo 1) {
        val day = today.minusDays(d.toLong())
        PAST_FEEDS.forEachIndexed { i, s -> hm(day, s).let { add(CareKind.FEED, it, it + 20 * MINUTE, side = if (i % 2 == 0) FeedSide.LEFT else FeedSide.RIGHT) } }
        // 짧으면 낮잠 셋의 시작을 10분까지 당기고(앞 수유 끝과 닿을 뿐 겹치지 않는다), 길면 밤잠을 끝에서 7시간 전(실제 시각)에 시작한다.
        val night = hm(day, PAST_SLEEPS[0].second) - hm(day, PAST_SLEEPS[0].first)
        val pull = minOf(10 * MINUTE, maxOf(0L, 390 * MINUTE - night) / 3)
        PAST_SLEEPS.forEachIndexed { i, (s, e) ->
            val start = if (i == 0) (if (night > 420 * MINUTE) hm(day, e) - 420 * MINUTE else hm(day, s)) else hm(day, s) - pull
            add(CareKind.SLEEP, start, hm(day, e))
        }
        PAST_DIAPERS.forEach { (s, k) -> add(CareKind.DIAPER, hm(day, s), diaper = k) }
    }
    // 시드 시각이 이르면(오전 6시 40분 전) 오늘 장면이 어제로 넘어간다. 겹치지 않게 그 구간의 어제 기록을 뺀다.
    out.removeAll { (it.endAt ?: it.startAt) > t - 400 * MINUTE } // 끝으로 본다: 넘어오는 잠도 뺀다. 경계에 닿기만 하면 남긴다(iOS와 같음)
    val night = hm(today, "01:00")
    val nightEnd = night + 60 * MINUTE
    // 오늘 장면의 첫 기록(6시간 전 수유)보다 앞설 때만 넣는다(이른 시드에서는 뺀다). 마지막 잠(T−210)만 보면 07~08시 시드에서
    // 6시간 전 수유와 겹친다. U50 숫자를 지키는 08:30 이후에는 늘 넣는다.
    if (nightEnd <= t - 360 * MINUTE) add(CareKind.SLEEP, night, nightEnd)
    fun ago(min: Int) = t - min * MINUTE
    add(CareKind.FEED, ago(360), ago(340), side = FeedSide.LEFT)
    add(CareKind.DIAPER, ago(330), diaper = Diaper.DIRTY)
    add(CareKind.SLEEP, ago(210), ago(150))
    add(CareKind.FEED, ago(120), ago(100), side = FeedSide.BOTH)
    add(CareKind.DIAPER, ago(60), diaper = Diaper.WET)
    return keep.copy(events = out.sortedBy { it.startAt }, sleepDiaperEnabled = true, migratedFeedings = true)
}

/** 시드를 넣는다. 사용자 저장과 같은 길이라 화면이 바로 바뀌고, owner로 연결돼 있으면 저장 뒤 계기로 차에 보낸다. */
class DebugSeedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val recordCare = (context.applicationContext as AigyeotApp).container.recordCare
        recordCare.replaceForDebug { demoSeed(System.currentTimeMillis(), APP_ZONE, it) }.invokeOnCompletion { pending.finish() }
    }
}
