package kr.aigyeot.engine

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// 기록: 기록 할 일(SaveEpisode·SaveSession·LogAlarm·CaptureContext, recordsCleared 문 rec 하나), 기록 지우기, 조향 창(VehicleSample), 휴식 기록 줄.
// 주인 칸: steer, session.restLog·recordsCleared. 다른 리듀서는 아래 함수(logRest·setLog·steerCry·dropSteerWindow·clearRecords)를 부른다.

/** 기록 지움 뒤 이 세션의 기록 할 일은 내지 않는다. */
internal fun Tx.rec(c: Command) {
    if (ses?.recordsCleared != true) out += c
}

/** 기록 지우기의 기록 몫. 이 세션은 더 기록하지 않고 Room·중계 기록을 지운 뒤 스냅숏을 남긴다. */
internal fun Tx.clearRecords() {
    ses { copy(recordsCleared = true) }
    out += DeleteRecords
    out += DeleteRelayRecords
    snapshot = true
}

internal fun Tx.logAlarm(r: AlarmOnly) = rec(LogAlarm(ses!!.id, now, r))

internal fun Tx.captureContext() {
    ses?.episode?.let { e -> e.alarmAt?.let { rec(CaptureContext(e.id, it)) } }
}

/** 에피소드 행(인계 확정 칸·guardian_notified 빼고, store가 지금 행 값을 지킨다). */
internal fun Tx.saveEpisode(endedAt: Long? = null) {
    val x = ses ?: return
    val e = x.episode ?: return
    val f = e.first
    val sc = e.second
    val h = e.handoff
    rec(
        SaveEpisode(
            EpisodeRecord(
                e.id, x.id, e.startedAt, endedAt, e.trigger, e.alarmAt, e.alarmDelayMs,
                f?.method?.kind, f?.method?.songId, f?.result, f?.unjudged,
                sc?.action, sc?.method?.kind, sc?.method?.songId, sc?.result,
                h?.cause, h?.kind, e.waypointAdded,
                e.cryMs, e.recried, e.playedMs, e.laterReasons
            )
        )
    )
}

internal fun Tx.saveSession(endedAt: Long? = null, reportPending: Boolean = false) {
    val x = ses ?: return
    rec(
        SaveSession(
            SessionRecord(
                x.id, x.openedAt, endedAt, x.trigger, x.micPath,
                x.output,
                x.driveFrom?.let { ((now - it) / 60_000).toInt() } ?: 0,
                x.playedMs, x.capReached(p), Json.encodeToString(x.restLog), x.windowLockBefore?.let { Json.encodeToString(it) },
                null, reportPending, null, s.ign.driverUserId
            )
        )
    )
}

/** 휴식 기록 i번째 줄만 바꾼다(i가 없거나 범위 밖이면 그대로). */
internal fun Tx.setLog(i: Int?, f: RestLogEntry.() -> RestLogEntry) = ses {
    if (i == null || i !in restLog.indices) this else copy(restLog = restLog.toMutableList().also { it[i] = it[i].f() })
}

/** 휴식 기록 줄을 더하고 그 위치를 돌려준다(제안 문장을 낼 때·질문 답의 경유지 요청). 답·결과는 이 위치에만 붙인다. 세션이 없으면 null. */
internal fun Tx.logRest(sid: Sid, restId: String?): Int? {
    val i = ses?.restLog?.size ?: return null
    ses { copy(restLog = restLog + RestLogEntry(now, sid, restId)) }
    return i
}

internal fun Tx.vehicle(e: VehicleSample) {
    val x = ses ?: return
    val o = steer(s.steer, e, false, x.play?.startedAt != null, x.id, p, observed = s.hears()) // 창의 observed는 sees/hears 하나로(cry()에서도 같은 기준)
    // 바쁨 시각. steer 안에 두어 스냅숏 저장 판단(묶어 저장)에는 영향이 없다 — 저장 횟수를 늘리지 않는다
    val busy = e.brake || e.turnSignal != TurnSignal.NONE || kotlin.math.abs(e.steeringDeg) >= p.steerHoldDeg
    s = s.copy(steer = if (busy) o.state.copy(driveBusyAt = e.at) else o.state)
    o.closed?.let { rec(SaveSteerWindow(it)) }
}

/** 울음 구간이 닿은 조향 창(Soothe.cry). 보지 못한 구간이 닿은 창은 '울음 없음'이 아니다. 본 울음이면 cry_present. */
internal fun Tx.steerCry(seen: Boolean, loud: Boolean) {
    val w = s.steer.window ?: return
    if (!seen) {
        s = s.copy(steer = s.steer.copy(window = w.copy(observed = false)))
    } else if (loud) {
        s = s.copy(steer = s.steer.copy(window = w.copy(cryPresent = true)))
    }
}

/** 수명 표 SteerWindow 행의 DROP(세션 닫힘·복원 ③). */
internal fun Tx.dropSteerWindow() {
    s = s.copy(steer = s.steer.copy(window = null))
}
