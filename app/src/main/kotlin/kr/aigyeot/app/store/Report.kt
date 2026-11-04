package kr.aigyeot.app.store

import kotlinx.serialization.json.Json
import kr.aigyeot.app.store.db.ContextSampleEntity
import kr.aigyeot.app.store.db.EpisodeEntity
import kr.aigyeot.app.store.db.PlayLogEntity
import kr.aigyeot.app.store.db.SteerWindowEntity
import kr.aigyeot.app.store.db.StoreDao
import kr.aigyeot.engine.Params
import kr.aigyeot.engine.RestLogEntry
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
import kr.aigyeot.shared.SoundMethod
import kr.aigyeot.shared.SoundRef
import kr.aigyeot.shared.Steering
import kr.aigyeot.shared.SteeringUnavailableReason
import kr.aigyeot.shared.StopInfo
import kr.aigyeot.shared.StopReason
import kr.aigyeot.shared.TimelineEpisode
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

// report_summary(contracts-link 3.3)를 Room에서 만든다. 차 화면 리포트도 같은 body를 그린다(한 곳에서 만든다).

// 64 KB는 봉투(v·id·seq·type·car·from·ts)를 포함한 요청 본문 기준(contracts-link 1.1)인데 여기서는 body만 잰다. 봉투 여유를 뺀 값을 한도로 쓴다
private const val MAX_BYTES = 64 * 1024 - 2048
private val SEOUL = ZoneId.of("Asia/Seoul")
private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx")

/** 3.3 '시각': +09:00 ISO 8601, 초 단위(C48). */
fun iso(ms: Long): String = Instant.ofEpochMilli(ms).atZone(SEOUL).format(ISO)

private val B32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
private val rnd = SecureRandom()

/** ULID(대문자 Crockford base32 26자): 앞 48비트 시각 + 뒤 80비트 난수. */
fun ulid(ms: Long): String = buildString(26) {
    for (i in 9 downTo 0) append(B32[((ms ushr (5 * i)) and 31).toInt()])
    repeat(16) { append(B32[rnd.nextInt(32)]) }
}

/** 세션 하나의 report_summary. 세션이 없거나 아직 열려 있으면 null. 64 KB를 넘으면 오래된 에피소드부터 줄인다. */
fun buildReport(dao: StoreDao, sessionId: String, now: Long, p: Params = Params()): ReportSummaryBody? {
    val s = dao.session(sessionId) ?: return null
    val endedAt = s.endedAt ?: return null
    val episodes = dao.episodes(sessionId)
    val plays = dao.plays(sessionId)
    val songName = { id: String? -> id?.let { dao.song(it)?.name } }
    val timeline = episodes.map { timeline(it, plays.filter { pl -> pl.episodeId == it.id }, songName) }
    val context = episodes.filter { it.alarmAt != null }.mapNotNull { e -> preCry(e, dao.context(e.id)) }
    val windows = dao.steerWindows(sessionId)
    val full = ReportSummaryBody(
        sessionId = s.id, startedAt = iso(s.startedAt), endedAt = iso(endedAt), simulated = s.scenarioId != null,
        outputRoute = when (s.outputRoute) { "REAR_ZONE" -> OutputRoute.REAR_ZONE; "DEFAULT_FALLBACK" -> OutputRoute.DEFAULT_ZONE_FALLBACK; else -> OutputRoute.NO_PLAYBACK }, // C85
        timeline = timeline, preCryContext = context,
        steering = steering(windows, dao.baselineWindows(s.driverUserId)),
        carActions = carActions(s.outputRoute, plays, episodes, s.restLog, dao.controls(sessionId).filter { it.ok }),
        controls = dao.controls(sessionId).groupBy { it.api to it.target }.map { (k, rows) ->
            ControlRecord(k.first, k.second, rows.size, rows.count { it.ok }, rows.count { !it.ok }, rows.sumOf { it.retries })
        },
        methodStats = methodStats(dao, now, p, songName),
    )
    return truncate(full)
}

private fun truncate(b: ReportSummaryBody): ReportSummaryBody {
    var r = b
    while (encodeReport(r).toByteArray().size > MAX_BYTES && (r.timeline.isNotEmpty() || r.preCryContext.isNotEmpty())) {
        r = r.copy(timeline = r.timeline.drop(1), preCryContext = r.preCryContext.drop(1), truncated = true)
    }
    // 에피소드를 다 줄여도 넘으면 선택 항목(method_stats)을 비우고, 그래도 넘으면 제어 기록을 뒤에서부터, 마지막으로 car_actions를 줄인다(C91)
    fun over() = encodeReport(r).toByteArray().size > MAX_BYTES
    if (over()) r = r.copy(methodStats = emptyList(), truncated = true)
    while (over() && r.controls.isNotEmpty()) r = r.copy(controls = r.controls.dropLast(maxOf(1, r.controls.size / 4)), truncated = true)
    if (over()) r = r.copy(carActions = emptyList(), truncated = true)
    return r
}

private fun sound(method: String, songId: String?, songName: (String?) -> String?): SoundRef = when (method) {
    "WHITE_NOISE" -> SoundRef(SoundMethod.WHITE_NOISE, "백색소음")
    "LULLABY" -> SoundRef(SoundMethod.LULLABY, "자장가")
    else -> SoundRef(SoundMethod.PARENT_SONG, songName(songId) ?: "부모 노래")
}

private fun soundMethod(m: String) = when (m) { "WHITE_NOISE" -> SoundMethod.WHITE_NOISE; "LULLABY" -> SoundMethod.LULLABY; else -> SoundMethod.PARENT_SONG }

private fun result(r: String?) = when (r) { "SUCCESS" -> EpisodeResult.SUCCESS; "FAIL" -> EpisodeResult.FAILURE; "UNJUDGED" -> EpisodeResult.UNDETERMINED; else -> null }

/** 3.3 차 Unjudged → excluded_reason 표. */
private val EXCLUDED = mapOf(
    "DRIVER_STOP" to ExcludedReason.DRIVER_STOP, "CALL" to ExcludedReason.CALL, "GEAR_P" to ExcludedReason.GEAR_TO_PARK,
    "PARKED_ALARM" to ExcludedReason.STARTED_IN_PARK, "SWITCH_SOUND" to ExcludedReason.SWITCH_SOUND,
    "SESSION_CAP" to ExcludedReason.SESSION_CAP, "FEEDING_WINDOW" to ExcludedReason.FEEDING_WINDOW,
    "MIC_ERROR" to ExcludedReason.MIC_ERROR, "DETECTOR_TIMEOUT" to ExcludedReason.DETECTOR_TIMEOUT,
    "SILENCED" to ExcludedReason.INPUT_SILENCED, "FOCUS_LOSS" to ExcludedReason.FOCUS_LOSS,
    "SUSPEND" to ExcludedReason.SUSPEND, "SESSION_END" to ExcludedReason.SESSION_END,
)

private fun timeline(e: EpisodeEntity, plays: List<PlayLogEntity>, songName: (String?) -> String?): TimelineEpisode {
    val started = plays.filter { it.startedAt != null }.sortedBy { it.startedAt }   // 시작하지 못한 재생은 plays에 넣지 않는다
    val entries = started.map {
        PlayEntry(PlayPurpose.valueOf(it.purpose), soundMethod(it.method), it.songId, iso(it.startedAt!!), iso(it.endedAt), (it.playedMs / 1000.0).roundToInt())
    }
    val second = e.secondAction?.let { a ->
        val pl = started.firstOrNull { it.purpose == "AUTO_SECOND" }
        SecondAttempt(
            when (a) { "COMFORT" -> SecondAction.COMFORT_COOL; "FEEDING" -> SecondAction.FEEDING_WINDOW; else -> SecondAction.OTHER_SOUND },
            pl?.let { sound(it.method, it.songId, songName) } ?: e.secondMethod?.let { sound(it, e.secondSongId, songName) },
            result(e.secondResult),
        )
    }
    val stop = if (e.handoffCause != null && e.handoffKind != null) StopInfo(
        handoff = true,
        reason = when (e.handoffCause) { "SESSION_CAP" -> StopReason.SESSION_CAP; "NO_SOUND" -> StopReason.NO_SOUND; else -> StopReason.ATTEMPT_LIMIT },
        mode = when (e.handoffKind) { "WAYPOINT_OFFER" -> HandoffKind.REST_OFFER; "ARRIVAL_NOTICE" -> HandoffKind.ARRIVAL_NOTICE; "NO_REST" -> HandoffKind.NO_REST; else -> HandoffKind.PARKED },
        restName = null,   // 에피소드 행에 쉼터 이름이 없다(6.1). 폰은 handoff_alert의 rest_name을 쓴다
        waypointAdded = e.waypointAdded, guardianNotified = e.guardianNotified,
    ) else null
    return TimelineEpisode(
        episodeId = e.id,
        trigger = if (e.trigger == "ALARM") EpisodeTrigger.CRY_ALARM else EpisodeTrigger.SWITCH_SOUND,
        startedAt = iso(e.alarmAt ?: e.startedAt),
        alarmLatencyS = e.alarmDelayMs?.let { it / 1000.0 },
        sound = started.firstOrNull()?.let { sound(it.method, it.songId, songName) },
        playS = (started.sumOf { it.playedMs } / 1000.0).roundToInt(),
        plays = entries,
        result = result(e.firstResult),
        excludedReason = e.firstUnjudged?.let { EXCLUDED[it] },
        recried = e.recried, second = second, stop = stop,
        totalCryS = (e.cryMs / 1000.0).roundToInt(),
    )
}

/** 2. pre_cry_context: 경보 −30초 ~ 0초의 1초 표본 요약. 표본이 없으면 뺀다. */
internal fun preCry(e: EpisodeEntity, rows: List<ContextSampleEntity>, p: Params = Params()): PreCryContext? {
    val alarm = e.alarmAt ?: return null
    val w = rows.filter { it.at >= alarm - p.contextHalfMs && it.at <= alarm }.sortedBy { it.at }
    val atAlarm = w.lastOrNull() ?: return null
    return PreCryContext(
        episodeId = e.id, windowS = (p.contextHalfMs / 1000).toInt(),
        speedKmhAvg = (w.map { it.speedKmh }.average() * 10).roundToInt() / 10.0,
        speedKmhMax = w.maxOf { it.speedKmh },
        brakePresses = w.zipWithNext().count { (a, b) -> !a.brake && b.brake },
        steeringReversals = reversals(w.map { it.steeringDeg }, p.steerReversalDeg),
        turnSignalUsed = w.any { it.turnSignal != "NONE" },
        gear = Gear.valueOf(atAlarm.gear),
        cabinTempC = atAlarm.cabinC, windowOpen = null,   // 창 위치를 읽는 API 미확인(G39)
        playing = atAlarm.playback != null,
    )
}

/** 큰 조향 반전 수: 마지막 극값에서 [deg] 이상 반대로 움직이면 한 번(1초 표본, 기준 문서 3.4의 문턱만 쓴 근사). */
internal fun reversals(angles: List<Double>, deg: Double): Int {
    var ext = angles.firstOrNull() ?: return 0
    var dir = 0.0   // 0 = 아직 방향 모름
    var n = 0
    for (a in angles.drop(1)) when {
        dir == 0.0 -> if (abs(a - ext) >= deg) { dir = Math.signum(a - ext); ext = a }
        (a - ext) * dir > 0 -> ext = a                              // 같은 방향으로 더 감: 극값을 옮긴다
        abs(a - ext) >= deg -> { n++; dir = -dir; ext = a }         // 극값에서 문턱 이상 되돌아옴
    }
    return n
}

/**
 * 3. steering: 이 세션의 울음 창을 같은 운전자·같은 속도대의 평소 창 중앙값과 견준다(G38).
 * 속도대마다 견준 뒤 울음 창의 속도대 비중으로 합친다(명세서 17장 C97).
 */
internal fun steering(session: List<SteerWindowEntity>, baseline: List<SteerWindowEntity>): Steering {
    val usable = session.filter { !it.excluded }
    val cry = usable.filter { it.cryPresent }
    if (cry.isEmpty()) return Steering(false, null, null, null, null, SteeringUnavailableReason.NO_CRY)
    // 같은 속도대 비교(G38): 평소 창이 있는 속도대의 울음 창만 쓴다
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
    // 대응 뒤 창: 첫 울음 창보다 뒤이고, 울음 창과 같은 속도대에서 재생 중인데 울음이 없는 창만
    val firstCry = comparable.minOf { it.startAt }
    val after = usable.filter { it.startAt > firstCry && it.speedBand in bands && it.playing && !it.cryPresent }
        .map { it.reversals - stat.getValue(it.speedBand).first }.takeIf { it.isNotEmpty() }?.average()?.plus(baselinePerMin)
    val z = comparable.filter { stat.getValue(it.speedBand).second > 0 }
        .map { (it.reversals - stat.getValue(it.speedBand).first) / stat.getValue(it.speedBand).second }.takeIf { it.isNotEmpty() }?.average()
    return Steering(true, baselinePerMin, during, after, z, null)
}

private fun median(v: List<Double>): Double = v.sorted().let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }

/**
 * 4. car_actions. 제어는 control_log의 성공 줄을 api 이름으로 가른다.
 * shortcut: api 이름의 낱말(Seat·WindowLock·ReadingLight·Hvac)로 가른다. exec가 이름을 바꾸면 여기를 맞춘다(보고서에 적음)
 */
private fun carActions(route: String?, plays: List<PlayLogEntity>, eps: List<EpisodeEntity>, restLog: String, controls: List<kr.aigyeot.app.store.db.ControlLogEntity>): List<CarAction> {
    val played = plays.filter { it.startedAt != null }
    val waypoints = runCatching { Json.decodeFromString<List<RestLogEntry>>(restLog) }.getOrDefault(emptyList()).count { it.waypointOk == true }
    fun ctl(word: String, exclude: String? = null) = controls.count { it.api.contains(word, true) && (exclude == null || !it.api.contains(exclude, true)) }
    return listOf(
        CarAction(if (route == "DEFAULT_FALLBACK") CarActionKind.DEFAULT_ZONE_PLAYBACK else CarActionKind.REAR_PLAYBACK, played.size, (played.sumOf { it.playedMs } / 1000.0).roundToInt()),
        CarAction(CarActionKind.SEAT_HEATER_OFF, ctl("SeatTemperature"), null),
        CarAction(CarActionKind.REAR_WINDOW_LOCK, controls.count { it.api.contains("WindowLock", true) && it.goal.lowercase() in setOf("true", "1", "1.0") }, null),
        CarAction(CarActionKind.CABIN_COOL, ctl("Hvac", "Seat"), null),
        CarAction(CarActionKind.READING_LIGHT_OFF, ctl("ReadingLight"), null),
        CarAction(CarActionKind.WAYPOINT_ADDED, waypoints, null),
        CarAction(CarActionKind.ARRIVAL_NOTICE, eps.count { it.handoffKind == "ARRIVAL_NOTICE" }, null),
        CarAction(CarActionKind.GUARDIAN_ALERT, eps.count { it.guardianNotified }, null),
    ).filter { it.count > 0 }
}

/** 선택. method_stats: 28일 1차 시도(장면 제외, 6.1 MethodStat). */
private fun methodStats(dao: StoreDao, now: Long, p: Params, songName: (String?) -> String?): List<MethodStat> =
    dao.firstTries(now - p.statWindowDays * Store.DAY).groupBy { it.method to it.songId }.map { (k, rows) ->
        val s = sound(k.first, k.second, songName)
        MethodStat(s.method, s.label, rows.count { it.result == "SUCCESS" }, rows.size)
    }
