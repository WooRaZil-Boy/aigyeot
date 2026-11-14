package kr.aigyeot.app.store

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.ZoneId
import java.util.concurrent.Callable
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.serialization.json.Json
import kr.aigyeot.app.common.rfc3339
import kr.aigyeot.app.link.soundMethod
import kr.aigyeot.app.store.db.AppDatabase
import kr.aigyeot.app.store.db.ControlAct
import kr.aigyeot.app.store.db.ControlLogEntity
import kr.aigyeot.app.store.db.EpisodeEntity
import kr.aigyeot.app.store.db.PlayLogEntity
import kr.aigyeot.app.store.db.StoreDao
import kr.aigyeot.app.store.db.act
import kr.aigyeot.engine.MethodKind
import kr.aigyeot.engine.Params
import kr.aigyeot.engine.RestLogEntry
import kr.aigyeot.engine.SteerWindowRecord
import kr.aigyeot.engine.TurnSignal
import kr.aigyeot.engine.methodLabel
import kr.aigyeot.engine.speaks
import kr.aigyeot.engine.statSince
import kr.aigyeot.engine.toldShown
import kr.aigyeot.shared.CarAction
import kr.aigyeot.shared.CarActionKind
import kr.aigyeot.shared.ControlRecord
import kr.aigyeot.shared.EpisodeResult
import kr.aigyeot.shared.EpisodeTrigger
import kr.aigyeot.shared.ExcludedReason
import kr.aigyeot.shared.Gear
import kr.aigyeot.shared.HandoffKind
import kr.aigyeot.shared.MethodStat
import kr.aigyeot.shared.OutputRoute
import kr.aigyeot.shared.PlayEntry
import kr.aigyeot.shared.PlayPurpose
import kr.aigyeot.shared.PreCryContext
import kr.aigyeot.shared.ReportSummaryBody
import kr.aigyeot.shared.SecondAction
import kr.aigyeot.shared.SecondAttempt
import kr.aigyeot.shared.SoundRef
import kr.aigyeot.shared.Steering
import kr.aigyeot.shared.SteeringUnavailableReason
import kr.aigyeot.shared.StopInfo
import kr.aigyeot.shared.StopReason
import kr.aigyeot.shared.TimelineEpisode

// report_summary를 Room에서 만든다. 차 화면 리포트도 같은 body를 그린다(한 곳에서 만든다).

// 64 KB는 봉투(v·id·seq·type·car·from·ts)를 포함한 요청 본문 기준인데 여기서는 body만 잰다. 봉투 여유를 뺀 값을 한도로 쓴다
private const val MAX_BYTES = 64 * 1024 - 2048
private val SEOUL = ZoneId.of("Asia/Seoul")

/** +09:00 ISO 8601, 초 단위. 형식은 Wire.rfc3339 하나, 시간대만 Asia/Seoul로 고정한다. */
fun iso(ms: Long): String = rfc3339(ms, SEOUL)

/** 인계 결과가 확정됐나. 말하는 종류(`HandoffKind.speaks()`)의 인계 행에 handoff_told_driver가 아직 null이면 확정 전이다. 화면의 '정리 중'과 집계가 이 함수만 쓴다(report_pending은 리포트 보내기 전용). */
fun handoffSettled(rows: List<EpisodeEntity>): Boolean =
    rows.none { r -> r.handoffToldDriver == null && r.handoffKind?.let { kr.aigyeot.engine.HandoffKind.valueOf(it).speaks() } == true }

/** 리포트 화면용: 본문과 '정리 중' 여부(= !handoffSettled)를 한 Room 트랜잭션에서 함께 읽는다. 확정은 에피소드 행으로 정하고 report_pending은 보지 않는다. [build]는 트랜잭션 안에서 돈다. */
fun readReport(db: AppDatabase, sessionId: String, build: (StoreDao) -> ReportSummaryBody?): Pair<ReportSummaryBody?, Boolean> =
    db.runInTransaction(Callable { db.storeDao().let { build(it) to !handoffSettled(it.episodes(sessionId)) } })

/** 세션 하나의 report_summary. 세션이 없거나 아직 열려 있으면 null. 64 KB를 넘으면 오래된 에피소드부터 줄인다. */
fun buildReport(dao: StoreDao, sessionId: String, now: Long, p: Params = Params(), finalizing: Boolean = false): ReportSummaryBody? {
    val s = dao.session(sessionId) ?: return null
    val endedAt = s.endedAt ?: return null
    val episodes = dao.episodes(sessionId)
    val settled = finalizing || handoffSettled(episodes) // 확정 전(인계 TTS 도중)에는 toldDriver가 null인 안내를 세지 않는다
    val plays = dao.plays(sessionId)
    val songName = { id: String? -> id?.let { dao.song(it)?.name } }
    val timeline = episodes.map { timeline(it, plays.filter { pl -> pl.episodeId == it.id }, songName) }
    val context = episodes.mapNotNull { e -> e.alarmAt?.let { preCry(e.id, it, dao.context(e.id).map { r -> r.toSample() }) } }
    val windows = dao.steerWindows(sessionId)
    val full = ReportSummaryBody(
        sessionId = s.id, startedAt = iso(s.startedAt), endedAt = iso(endedAt), simulated = s.scenarioId != null,
        outputRoute = when (s.outputRoute) {
            "REAR_ZONE" -> OutputRoute.REAR_ZONE
            "DEFAULT_FALLBACK" -> OutputRoute.DEFAULT_ZONE_FALLBACK
            else -> OutputRoute.NO_PLAYBACK
        },
        timeline = timeline, preCryContext = context,
        steering = steering(
            windows.map { it.toRecord() },
            dao.baselineWindows(s.driverUserId).map { it.toRecord() },
            responseStartAt(episodes.mapNotNull { it.alarmAt } + listOfNotNull(dao.firstAlarmLogAt(sessionId)), plays.mapNotNull { it.startedAt })
        ),
        carActions = carActions(s.outputRoute, plays, episodes, settled, s.restLog, dao.controls(sessionId).filter { it.ok }),
        controls = dao.controls(sessionId).groupBy { it.api to it.target }.map { (k, rows) ->
            ControlRecord(k.first, k.second, rows.size, rows.count { it.ok }, rows.count { !it.ok }, rows.sumOf { it.retries })
        },
        methodStats = methodStats(dao, now, p, songName)
    )
    return truncate(full)
}

/** 대응 시작 = 처음 경보한 시각 이후에 시작한 재생 기록의 가장 이른 시각. 경보 없는 에피소드(소리 바꾸기 요청 등)의 재생·경보 전 재생은 대응이 아니다. 없으면 null */
/** 재생 없이 기록만 한 경보(대기·통화·포커스 거절·상한)는 alarm_log에만 남으므로 그 첫 시각도 함께 본다 */
internal fun responseStartAt(alarmAts: List<Long>, playStarts: List<Long>): Long? {
    val firstAlarm = alarmAts.minOrNull() ?: return null
    return playStarts.filter { it >= firstAlarm }.minOrNull()
}

private fun truncate(b: ReportSummaryBody): ReportSummaryBody {
    var r = b
    while (encodeReport(r).toByteArray().size > MAX_BYTES && (r.timeline.isNotEmpty() || r.preCryContext.isNotEmpty())) {
        r = r.copy(timeline = r.timeline.drop(1), preCryContext = r.preCryContext.drop(1), truncated = true)
    }
    // 에피소드를 다 줄여도 넘으면 선택 항목(method_stats)을 비우고, 그래도 넘으면 제어 기록을 뒤에서부터, 마지막으로 car_actions를 줄인다
    fun over() = encodeReport(r).toByteArray().size > MAX_BYTES
    if (over()) r = r.copy(methodStats = emptyList(), truncated = true)
    while (over() && r.controls.isNotEmpty()) r = r.copy(controls = r.controls.dropLast(maxOf(1, r.controls.size / 4)), truncated = true)
    if (over()) r = r.copy(carActions = emptyList(), truncated = true)
    return r
}

/** Room의 음원 종류 글자 → 전송 값·이름(Wire.soundMethod, 판단 코어 methodLabel). 부모 노래는 곡 이름이 있으면 그 이름. */
private fun sound(method: String, songId: String?, songName: (String?) -> String?): SoundRef {
    val k = kind(method)
    return SoundRef(soundMethod(k), (if (k == MethodKind.SONG) songName(songId) else null) ?: methodLabel(k))
}

/** Room 글자 → 종류. 모르는 글자는 부모 노래로 본다(옛 리포트 규칙 그대로). */
private fun kind(method: String) = MethodKind.entries.firstOrNull { it.name == method } ?: MethodKind.SONG

private fun result(r: String?) = when (r) {
    "SUCCESS" -> EpisodeResult.SUCCESS
    "FAIL" -> EpisodeResult.FAILURE
    "UNJUDGED" -> EpisodeResult.UNDETERMINED
    else -> null
}

/** 차 Unjudged → excluded_reason 표. */
private val EXCLUDED = mapOf(
    "DRIVER_STOP" to ExcludedReason.DRIVER_STOP, "CALL" to ExcludedReason.CALL, "GEAR_P" to ExcludedReason.GEAR_TO_PARK,
    "PARKED_ALARM" to ExcludedReason.STARTED_IN_PARK, "SWITCH_SOUND" to ExcludedReason.SWITCH_SOUND,
    "SESSION_CAP" to ExcludedReason.SESSION_CAP, "FEEDING_WINDOW" to ExcludedReason.FEEDING_WINDOW,
    "MIC_ERROR" to ExcludedReason.MIC_ERROR, "DETECTOR_TIMEOUT" to ExcludedReason.DETECTOR_TIMEOUT,
    "SILENCED" to ExcludedReason.INPUT_SILENCED, "FOCUS_LOSS" to ExcludedReason.FOCUS_LOSS,
    "SUSPEND" to ExcludedReason.SUSPEND, "SESSION_END" to ExcludedReason.SESSION_END
)

/** 울음 횟수의 규칙 하나. 울음 경보로 연 에피소드만 '울음'이다(소리 바꾸기 요청·기록만 한 경보·판정 창의 다시 울음은 아니다). 리포트 timeline의 cry_alarm과 홈 '오늘 하루'가 같이 쓴다. */
internal fun isCryAlarm(e: EpisodeEntity) = e.trigger == "ALARM"

internal fun timeline(e: EpisodeEntity, plays: List<PlayLogEntity>, songName: (String?) -> String?): TimelineEpisode {
    val started = plays.filter { it.startedAt != null }.sortedBy { it.startedAt } // 시작하지 못한 재생은 plays에 넣지 않는다
    val entries = started.map {
        PlayEntry(
            PlayPurpose.valueOf(it.purpose),
            soundMethod(kind(it.method)),
            it.songId,
            iso(it.startedAt!!),
            iso(it.endedAt),
            (it.playedMs / 1000.0).roundToInt()
        )
    }
    val second = e.secondAction?.let { a ->
        val pl = started.firstOrNull { it.purpose == "AUTO_SECOND" }
        SecondAttempt(
            when (a) {
                "COMFORT" -> SecondAction.COMFORT_COOL
                "FEEDING" -> SecondAction.FEEDING_WINDOW
                else -> SecondAction.OTHER_SOUND
            },
            pl?.let { sound(it.method, it.songId, songName) } ?: e.secondMethod?.let { sound(it, e.secondSongId, songName) },
            result(e.secondResult)
        )
    }
    val stop = if (e.handoffCause != null && e.handoffKind != null) {
        StopInfo(
            handoff = true,
            reason = when (e.handoffCause) {
                "SESSION_CAP" -> StopReason.SESSION_CAP
                "NO_SOUND" -> StopReason.NO_SOUND
                else -> StopReason.ATTEMPT_LIMIT
            },
            mode = when (e.handoffKind) {
                "WAYPOINT_OFFER" -> HandoffKind.REST_OFFER
                "ARRIVAL_NOTICE" -> HandoffKind.ARRIVAL_NOTICE
                "NO_REST" -> HandoffKind.NO_REST
                else -> HandoffKind.PARKED
            },
            restName = e.handoffRestName,
            waypointAdded = e.waypointAdded,
            guardianNotified = e.guardianNotified,
            toldDriver = e.handoffToldDriver
        )
    } else {
        null
    }
    return TimelineEpisode(
        episodeId = e.id,
        trigger = if (isCryAlarm(e)) EpisodeTrigger.CRY_ALARM else EpisodeTrigger.SWITCH_SOUND,
        startedAt = iso(e.alarmAt ?: e.startedAt),
        alarmLatencyS = e.alarmDelayMs?.let { it / 1000.0 },
        sound = started.firstOrNull()?.let { sound(it.method, it.songId, songName) },
        playS = (started.sumOf { it.playedMs } / 1000.0).roundToInt(),
        plays = entries,
        result = result(e.firstResult),
        excludedReason = e.firstUnjudged?.let { EXCLUDED[it] },
        recried = e.recried, second = second, stop = stop,
        totalCryS = (e.cryMs / 1000.0).roundToInt()
    )
}

/** 2. pre_cry_context: 경보 −30초 ~ 0초의 1초 표본 요약. 표본이 없으면 뺀다. */
internal fun preCry(episodeId: String, alarm: Long, rows: List<ContextSample>, p: Params = Params()): PreCryContext? {
    val w = rows.filter { it.at >= alarm - p.contextHalfMs && it.at <= alarm }.sortedBy { it.at }
    val atAlarm = w.lastOrNull() ?: return null
    return PreCryContext(
        episodeId = episodeId, windowS = (p.contextHalfMs / 1000).toInt(),
        speedKmhAvg = round1(w.map { it.speedKmh }.average()),
        speedKmhMax = round1(w.maxOf { it.speedKmh }),
        brakePresses = w.zipWithNext().count { (a, b) -> !a.brake && b.brake },
        steeringReversals = reversals(w.map { it.steeringDeg }, p.steerReversalDeg),
        turnSignalUsed = w.any { it.turnSignal != TurnSignal.NONE },
        gear = Gear.valueOf(atAlarm.gear.name),
        cabinTempC = atAlarm.cabinC, windowOpen = null, // 창 위치를 읽는 API 미확인
        playing = atAlarm.playback != null
    )
}

/** 소수 첫째 자리 HALF_UP. 80.00000381469727 → 80.0, 58.25 → 58.3. */
internal fun round1(v: Double): Double = BigDecimal(v.toString()).setScale(1, RoundingMode.HALF_UP).toDouble()

/** 큰 조향 반전 수: 마지막 극값에서 [deg] 이상 반대로 움직이면 한 번(1초 표본, 문턱만 쓴 근사). */
internal fun reversals(angles: List<Double>, deg: Double): Int {
    var ext = angles.firstOrNull() ?: return 0
    var dir = 0.0 // 0 = 아직 방향 모름
    var n = 0
    for (a in angles.drop(1)) {
        when {
            dir == 0.0 -> if (abs(a - ext) >= deg) {
                dir = Math.signum(a - ext)
                ext = a
            }
            (a - ext) * dir > 0 -> ext = a // 같은 방향으로 더 감: 극값을 옮긴다
            abs(a - ext) >= deg -> {
                n++
                dir = -dir
                ext = a
            } // 극값에서 문턱 이상 되돌아옴
        }
    }
    return n
}

/**
 * 3. steering: 이 세션의 울음 창을 같은 운전자·같은 속도대의 평소 창 중앙값과 견준다.
 * 속도대마다 견준 뒤 울음 창의 속도대 비중으로 합친다.
 */
internal fun steering(session: List<SteerWindowRecord>, baseline: List<SteerWindowRecord>, responseAt: Long?): Steering {
    // 창의 역할을 한 곳에서 가른다. cryPresent는 본 울음만 세우니 관측이 일부여도 울음 창이다(자동 시도 재생이 울음 3초 뒤 시작해 첫 울음 창은 거의 늘 미관측). 조용함은 관측하고 울음이 없을 때만
    fun SteerWindowRecord.role() = when {
        excluded -> SteerRole.SKIP
        cryPresent -> SteerRole.CRY
        observed -> SteerRole.QUIET
        else -> SteerRole.UNKNOWN
    }
    val cry = session.filter { it.role() == SteerRole.CRY }
    // NO_CRY는 창이 있고 모든 창을 관측했고 어느 창에도 울음이 없을 때만(제외는 관측과 별개). 관측 못 한 창이 하나라도 있으면(마이크 꺼짐·나중에 켬), 울음 창이 있었지만 모두 제외됐으면 비교할 창 없음
    if (cry.isEmpty()) {
        return Steering(
            false,
            null,
            null,
            null,
            null,
            if (session.isNotEmpty() && session.all { it.observed && !it.cryPresent }) {
                SteeringUnavailableReason.NO_CRY
            } else {
                SteeringUnavailableReason.NO_COMPARABLE_WINDOW
            }
        )
    }
    // 같은 속도대 비교: 평소 창이 있는 속도대의 울음 창만 쓴다
    val baseBands = baseline.map { it.speedBand }.toSet()
    val comparable = cry.filter { it.speedBand in baseBands }
    if (comparable.isEmpty()) return Steering(false, null, null, null, null, SteeringUnavailableReason.NO_COMPARABLE_WINDOW)
    val bands = comparable.map { it.speedBand }.toSet()
    // 속도대별 (중앙값, MAD×1.4826)
    val stat = baseline.filter { it.speedBand in bands }.groupBy { it.speedBand }.mapValues { (_, w) ->
        val v = w.map { it.reversals.toDouble() }
        val med = median(v)
        med to median(v.map { abs(it - med) }) * 1.4826
    }
    val baselinePerMin = comparable.map { stat.getValue(it.speedBand).first }.average()
    val during = comparable.map { it.reversals.toDouble() }.average()
    // 대응 뒤 = 조용한 창 중 첫 울음 창보다 뒤이고 대응 시작 시각(responseAt, play_log 기준) 이후, 울음 창과 같은 속도대. 대응이 없었으면(null) 없음
    val firstCry = cry.minOf { it.startAt } // 기준선 없는 속도대의 울음 창도 포함(comparable만 보면 첫 울음이 늦어 '대응 뒤'가 줄어든다)
    val after = session.filter {
        responseAt != null &&
            it.role() == SteerRole.QUIET &&
            it.startAt > firstCry &&
            it.startAt >= responseAt &&
            it.speedBand in bands
    }
        // 속도대가 섞이면 음수가 될 수 있어 0 아래로 내리지 않는다
        .map { it.reversals - stat.getValue(it.speedBand).first }.takeIf { it.isNotEmpty() }?.average()?.plus(baselinePerMin)?.coerceAtLeast(0.0)
    val z = comparable.filter { stat.getValue(it.speedBand).second > 0 }
        .map { (it.reversals - stat.getValue(it.speedBand).first) / stat.getValue(it.speedBand).second }.takeIf { it.isNotEmpty() }?.average()
    return Steering(true, baselinePerMin, during, after, z, null)
}

private enum class SteerRole { SKIP, CRY, QUIET, UNKNOWN }

private fun median(v: List<Double>): Double = v.sorted().let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }

/** 4. car_actions. 제어는 control_log의 성공 줄을 그 뜻(controlAct, 홈 3D 칩과 같은 함수)으로 가른다. */
private fun carActions(
    route: String?,
    plays: List<PlayLogEntity>,
    eps: List<EpisodeEntity>,
    settled: Boolean,
    restLog: String,
    controls: List<ControlLogEntity>
): List<CarAction> {
    val played = plays.filter { it.startedAt != null }
    val waypoints = runCatching { Json.decodeFromString<List<RestLogEntry>>(restLog) }.getOrDefault(emptyList()).count { it.waypointOk == true }
    fun ctl(a: ControlAct) = controls.count { it.act == a }
    return listOf(
        CarAction(
            if (route == "DEFAULT_FALLBACK") CarActionKind.DEFAULT_ZONE_PLAYBACK else CarActionKind.REAR_PLAYBACK,
            played.size,
            (played.sumOf { it.playedMs } / 1000.0).roundToInt()
        ),
        CarAction(CarActionKind.SEAT_HEATER_OFF, ctl(ControlAct.SEAT_HEAT_OFF), null),
        // 잠금에 성공한 뒷창 수. 같은 창의 되돌림·다시 잠금 줄이 두 번째 '목표 참' 줄이라 영역으로 중복 없이 센다
        CarAction(
            CarActionKind.REAR_WINDOW_LOCK,
            controls.filter { it.act == ControlAct.WINDOW_LOCK }.map { it.target }.distinct().size,
            null
        ),
        CarAction(CarActionKind.CABIN_COOL, ctl(ControlAct.CABIN_COOL), null),
        CarAction(CarActionKind.READING_LIGHT_OFF, ctl(ControlAct.READING_LIGHT_OFF), null),
        // 조명 낮춤: 낮춤에 성공한 DimAmbient 수. 한 명령의 밝기·색 두 줄은 cmd_id로 한 번, 되돌림(restore:) 줄은 세지 않는다
        CarAction(
            CarActionKind.AMBIENT_DIM,
            controls.filter { it.act == ControlAct.AMBIENT_DIM }.mapNotNull { it.cmdId }.distinct().size,
            null
        ),
        CarAction(CarActionKind.WAYPOINT_ADDED, waypoints, null),
        CarAction(CarActionKind.ARRIVAL_NOTICE, eps.count { it.handoffKind == "ARRIVAL_NOTICE" && toldShown(it.handoffToldDriver, settled) }, null),
        CarAction(CarActionKind.GUARDIAN_ALERT, eps.count { it.guardianNotified && settled }, null) // 확정 전에는 알림이 나갔는지 모른다
    ).filter { it.count > 0 }
}

/** 선택. method_stats: 28일 1차 시도(장면 제외, MethodStat). */
private fun methodStats(dao: StoreDao, now: Long, p: Params, songName: (String?) -> String?): List<MethodStat> =
    dao.firstTries(p.statSince(now)).groupBy { it.method to it.songId }.map { (k, rows) ->
        val s = sound(k.first, k.second, songName)
        MethodStat(s.method, s.label, rows.count { it.result == "SUCCESS" }, rows.size)
    }
