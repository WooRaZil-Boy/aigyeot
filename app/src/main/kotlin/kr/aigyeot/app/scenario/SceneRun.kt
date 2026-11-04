package kr.aigyeot.app.scenario

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kr.aigyeot.app.host.HostApi
import kr.aigyeot.app.host.ScenarioSetup
import kr.aigyeot.app.store.db.ControlLogEntity
import kr.aigyeot.engine.AdjustPlayback
import kr.aigyeot.engine.Answer
import kr.aigyeot.engine.Area
import kr.aigyeot.engine.Ask
import kr.aigyeot.engine.BabyAboard
import kr.aigyeot.engine.BabyAboardFrom
import kr.aigyeot.engine.Command
import kr.aigyeot.engine.DriverStop
import kr.aigyeot.engine.Event
import kr.aigyeot.engine.FeedingConfirmed
import kr.aigyeot.engine.FeedingFrom
import kr.aigyeot.engine.FeedingStarted
import kr.aigyeot.engine.FirstTry
import kr.aigyeot.engine.Gear
import kr.aigyeot.engine.LogAlarm
import kr.aigyeot.engine.LowerHvac
import kr.aigyeot.engine.Method
import kr.aigyeot.engine.Params
import kr.aigyeot.engine.Phase
import kr.aigyeot.engine.PlayKind
import kr.aigyeot.engine.PlayMode
import kr.aigyeot.engine.SaveEpisode
import kr.aigyeot.engine.SaveSession
import kr.aigyeot.engine.SendToPhone
import kr.aigyeot.engine.SetReadingLight
import kr.aigyeot.engine.SetSeatHeat
import kr.aigyeot.engine.SetWindowLock
import kr.aigyeot.engine.Sid
import kr.aigyeot.engine.Speak
import kr.aigyeot.engine.StartPlayback
import kr.aigyeot.engine.Step
import kr.aigyeot.engine.SwitchSound
import kr.aigyeot.engine.Via
import kr.aigyeot.engine.YesNo
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.random.Random

/** 장면 재생기가 판단 코어 밖으로 내는 입력(안드로이드는 [Scenario], JVM 시험은 가짜 차). 막지 않고 바로 돌아온다. */
interface ScenePorts {
    /** 차량 입력 요청. 막지 않고 돌아오고, 끝나면 [done]을 한 번 부른다(다른 스레드여도 된다). */
    fun vc(method: String, path: String, done: (ok: Boolean, why: String) -> Unit)
    /** 녹음을 마이크 자리에 섞는다. 실제로 섞기 시작한 시각(호스트 시계)을 돌려주고, 녹음을 못 열면 null(그 단계 실패). */
    fun audio(a: Audio, at: Long): Long?
    /** '아기 탔어요': 마이크 서비스(장면 입력원)를 켠다. 앱 화면이 앞에 없어 못 켜면 false(그 단계 실패, 명세서 6장). */
    fun micOn(): Boolean
    /** 휴대폰 봉투 전달. 막지 않고 돌아오고 끝나면 [done]을 한 번 부른다(취소되면 부르지 않아도 된다: finish가 실패로 닫는다). */
    fun phone(envelopeJson: String, done: (ok: Boolean, why: String) -> Unit)
    /** 바로 넣은 입력(녹음·누르기·말·휴대폰·주입)을 '시뮬레이션 입력'으로 화면·로그에 알린다(계약 7.1, 명세서 6장). */
    fun simulated(what: String) {}
    /** 그 음원의 BS.1770 통합 크기(LUFS, 계약 7.4 lufs_violations). 잴 수 없으면 null(미측정). */
    fun lufs(method: Method): Double? = null
}

/** 계약 7.4 칼럼 한 줄. null은 빈 칸(잴 것이 없음·미측정). */
data class RunRow(
    val run: Int, val seed: Long, val completed: Boolean, val failedStep: Double?,
    val alarmLatencyMs: Long?, val alarmToPlayMs: Long?, val routedDevice: String?, val fallback: Boolean,
    val maxAttempts: Int, val maxEpisodePlayMs: Long, val sessionPlayMs: Long, val gainViolations: Int,
    val lufsViolations: Int?, val systemVolumeChanges: Int, val driverOps: Int, val handoffMs: Long?, val controlOkRate: Double?,
    val failures: List<String>,
)

/** 쉼터 제안 문장(이 문장에 '응'은 운전자 조작에서 뺀다, 15장). */
private val REST_OFFERS = setOf(Sid.S07, Sid.S07_DEPART, Sid.S08, Sid.S10, Sid.S11)
/** exec가 control_log에 남기는 재생 줄의 이름(scenario가 읽는 약속, 보고서 '다른 갈래에 요청'). */
/**
 * 차 제어 결과 줄을 기다리는 가장 긴 시간(계약 7.4): 마지막 차 제어 할 일부터 verifyTimeoutMs × (verifyRetries + 2) + 2초.
 * exec는 바꾸기 전 값 읽기 한 번 + 확인 한 번(+ 재시도 verifyRetries번)을 각각 verifyTimeoutMs까지 기다릴 수 있고, 2초는 기록 여유다.
 */
val CONTROL_WAIT_MS: Long = Params().run { verifyTimeoutMs * (verifyRetries + 2) } + 2_000

/** 차 제어 줄의 target(exec가 target = area.name으로 남긴다). */
private val AREA_NAMES = Area.entries.map { it.name }.toSet()
const val GAIN_API = "AudioTrack.setVolume"
/** 실제 출력 장치는 getRoutedDevice의 actual이다(setPreferredDevice의 actual은 요청한 장치, 명세서 7장·계약 4.1). */
const val ROUTE_API = "getRoutedDevice"
/** 시스템 볼륨을 건드린 호출(exec가 control_log에 이 이름으로 남기라고 요청하는 약속). 호출도 system_volume_changes로 센다(계약 7.4). */
val VOLUME_APIS = setOf("setStreamVolume", "adjustStreamVolume")

/** 결과 파일 이름 고르기: `<id>-<stamp>.csv`, 이미 csv·txt 중 하나가 있으면 `-2`, `-3`… 을 붙인 첫 빈 이름. */
fun freeResultFile(dir: java.io.File, id: String, stamp: String): java.io.File {
    var n = 1
    while (true) {
        val base = "$id-$stamp" + if (n == 1) "" else "-$n"
        val csv = java.io.File(dir, "$base.csv")
        if (!csv.exists() && !java.io.File(dir, "$base.txt").exists()) return csv
        n++
    }
}

/**
 * 장면 한 번(반복 k번째)을 시간축대로 실행하고 기대값을 판정하고 측정 칼럼을 낸다. 시계·스레드를 모른다:
 * 부르는 쪽이 [advance]에 지금 시각을 넣고, 판단 코어 Step·제어 기록을 [onStep]·[onControl]로 흘린다(계약 1.1: Step은 읽기만, 사건은 주입).
 * 여러 스레드(호스트의 Step 흐름, 실행 루프)에서 부르므로 모두 잠근다.
 */
class SceneRun(val scene: Scene, val k: Int, private val host: HostApi, private val ports: ScenePorts, val t0: Long) {
    private class Rec(val at: Long, val step: Step)
    private val log = ArrayList<Rec>()
    private val control = ArrayList<ControlLogEntity>()
    private val order = scene.steps.withIndex().sortedBy { it.value.t }.map { it.value }   // 같은 t는 파일 순서
    private var next = 0
    private val checks = ArrayList<Check>()
    private val failed = ArrayList<Pair<Double, String>>()
    private var cryAt: Long? = null
    private var driverOps = 0
    private var volumeChanges = 0
    private val rnd = Random(scene.setup.seed + k)
    val seed = scene.setup.seed + k
    /** 장면이 끝나야 하는 시각(마지막 기대값 창 끝). 이때까지 안 끝나면 [finish]가 남은 것을 실패로 친다. */
    val endAt: Long = t0 + ms(scene.steps.maxOfOrNull { c -> c.t + ((c.action as? Expect)?.bySec ?: (c.action as? ExpectNone)?.forSec ?: (c.action as? Say)?.bySec ?: (c.action as? Audio)?.durSec ?: 0.0) } ?: 0.0)
    /** 가장 늦은 녹음이 끝나는 시각. 마지막 단계가 녹음이어도 이때까지는 마이크를 켜 둔다. */
    private var audioEnd: Long = t0   // 녹음을 실제로 시작한 시각 + 길이(늦게 시작해도 잘리지 않게 exec가 올린다)
    private var lastNow = t0       // advance·done이 본 가장 늦은 시각(진행 중 재생분 계산용)
    private var ctlExpected = 0   // 판단 코어가 낸 차 제어 할 일의 영역 수(영역마다 결과 줄 하나)
    private var ctlGot = 0
    private var ctlLastAt = 0L
    private var lastExecT = 0.0     // 지금까지 실행한 마지막 단계의 t
    private val pending = HashMap<Any, Pair<Double, String>>()   // 아직 done이 안 온 vc·phone 요청 → (그 단계 t, 종류)
    private val drops0 = host.stepLogDrops.value                 // 반복 시작 때 stepLog 유실 수(startScenario보다 먼저 만든다)

    private fun ms(sec: Double) = Math.round(sec * 1000)
    private fun dueAt(c: Cue) = t0 + ms(c.t)

    fun setup(): ScenarioSetup {
        val runSeed = seed   // Setup.seed가 아니라 반복 k번째 시드(seed + k). run 블록 안에서는 seed가 Setup.seed를 가리킨다
        return scene.setup.run { ScenarioSetup(
            scene.id, runSeed, profile, methods,
            history.map { FirstTry(t0 - it.daysAgo * 86_400_000L, it.method, it.success) },
            feedingStartOffsetSec?.let { t0 + it * 1000 }, otherGuardians, override,
        ) }
    }

    @Synchronized fun onStep(step: Step) {
        if ((step.state.session?.openedAt ?: Long.MAX_VALUE) < t0) return   // 장면 시작 전에 열린 실제 세션의 Step은 측정에 섞지 않는다
        log += Rec(host.clock(), step)
        val n = step.commands.sumOf { c -> when (c) { is SetSeatHeat -> c.areas.size; is SetWindowLock -> c.values.size; is SetReadingLight -> c.areas.size; is LowerHvac -> c.areas.size; else -> 0 } }
        if (n > 0) { ctlExpected += n; ctlLastAt = log.last().at }
    }
    @Synchronized fun onControl(row: ControlLogEntity) { control += row; if (row.target in AREA_NAMES) ctlGot++ }
    @Synchronized fun onVolumeChange() { volumeChanges++ }

    /**
     * 때가 된 단계를 실행하고 기다리는 판정을 진행한다. 사건은 잠금을 푼 뒤 넣는다(호스트가 같은 스레드로 Step을 돌려보내도 막히지 않게).
     * 단계 하나마다 그 사건을 바로 넣고 다음으로 간다: 같은 advance의 뒤 단계가 앞 사건이 반영된 상태를 본다(동기 호스트일 때. 비동기 호스트에서는 여전히 늦을 수 있다).
     */
    fun advance(now: Long) {
        synchronized(this) { lastNow = maxOf(lastNow, now) }
        while (true) {
            val out = synchronized(this) {
                if (next < order.size && dueAt(order[next]) <= now) { exec(order[next++], now); drain() } else null
            } ?: break
            out.forEach(host::post)
        }
        synchronized(this) { checks.removeAll { it.poll(now) }; drain() }.forEach(host::post)
    }

    private fun drain() = outbox.toList().also { outbox.clear() }

    private val outbox = ArrayList<Event>()
    private fun post(e: Event) { outbox += e }

    @Synchronized fun done(now: Long): Boolean { lastNow = maxOf(lastNow, now); return doneAt(now) }
    private fun doneAt(now: Long) = next >= order.size && checks.isEmpty() && pending.isEmpty() && now >= audioEnd && (ctlGot >= ctlExpected || now >= ctlLastAt + CONTROL_WAIT_MS)

    /** 처음 어긋난 단계의 t(지금까지). */
    @Synchronized fun firstFail(): Double? = failed.minOfOrNull { it.first }

    /** 멈춤·시간 초과: 남은 단계와 판정을 실패로 닫는다. */
    @Synchronized fun finish(reason: String) {
        fail(lastExecT, reason)   // 정상 종료가 아니다: 남은 것이 없어도(녹음만 진행 중이어도) 실패를 하나 남긴다
        order.drop(next).forEach { fail(it.t, "$reason: 실행 안 됨") }
        next = order.size
        checks.forEach { fail(it.cue.t, "$reason: 판정 안 끝남") }
        checks.clear()
        pending.values.forEach { fail(it.first, "$reason: ${it.second} 요청이 끝나지 않음") }
        pending.clear()
    }

    private fun fail(t: Double, why: String) { failed += t to why }

    /** 반복 끝(endScenario 전): 그사이 stepLog가 Step을 버렸으면 판정을 믿을 수 없어 실패로 남긴다(expectNone 창의 Step이 빠졌을 수 있다). */
    @Synchronized fun checkStepLog() {
        val lost = host.stepLogDrops.value - drops0
        if (lost > 0) fail(lastExecT, "stepLog 유실 ${lost}개")
    }

    /** 장면이 끝난 뒤 정리에서 생긴 실패(예: 마이크 서비스가 안 꺼짐)를 마지막 단계 t로 남긴다. */
    @Synchronized fun failEnd(why: String) = fail(scene.steps.maxOfOrNull { it.t } ?: 0.0, why)

    /** 지금 마지막 상태(호스트 Step). */
    private fun state() = log.lastOrNull()?.step?.state

    private fun exec(c: Cue, now: Long) {
        lastExecT = c.t
        if (c.action !is Vc && c.action !is Expect && c.action !is ExpectNone) ports.simulated("t=${c.t} ${c.action}")
        when (val a = c.action) {
            is Vc -> {
                val tok = Any()
                pending[tok] = c.t to "vc"
                ports.vc(a.method, a.path) { ok, why ->
                    synchronized(this) { if (pending.remove(tok) != null && !ok) fail(c.t, "vc ${a.path} 실패: $why") }   // finish가 이미 닫은 요청은 무시
                }
            }
            is Audio -> {
                val started = ports.audio(a, now)   // 파일 읽기가 오래 걸려도 실제 시작 시각으로 잡는다
                if (started == null) fail(c.t, "녹음 ${a.file}을 열지 못함")
                else { audioEnd = maxOf(audioEnd, started + ms(a.durSec)); if (a.cry && cryAt == null) cryAt = started }
            }
            is Tap -> {
                driverOp(false)
                when (a.what) {
                    "BABY_ABOARD" -> { if (!ports.micOn()) fail(c.t, "앱 화면이 없어 마이크를 켜지 못함"); post(BabyAboard(now, BabyAboardFrom.HOME)) }   // 계약 7.3: 마이크 서비스도 켠다
                    "FEEDING_NOW" -> post(FeedingStarted(now, now, FeedingFrom.CAR_SCREEN, null))
                    "FEEDING_CONFIRM_YES" -> post(FeedingConfirmed(now, true))
                }
            }
            is Say -> when (a.intent) {
                "STOP" -> { driverOp(false); post(DriverStop(now, Via.SCENARIO)) }
                "SWITCH_SOUND" -> { driverOp(false); post(SwitchSound(now, a.method!!)) }
                else -> checks += SayCheck(c, a)
            }
            is Phone -> {
                val tok = Any()
                pending[tok] = c.t to "phone"
                ports.phone(envelope(a, now)) { ok, why ->
                    synchronized(this) { if (pending.remove(tok) != null && !ok) fail(c.t, "phone ${a.type} 실패: $why") }
                }
            }
            is Inject -> post(injectEvent(a.event, a.fields, now))
            is Expect -> checks += ExpectCheck(c, a)
            is ExpectNone -> checks += NoneCheck(c, a)
        }
    }

    /** 주행 중 운전자 조작(15장). 기어를 아직 모르면(장면 첫 기어 사건 전) 정차로 본다. */
    private fun driverOp(restYes: Boolean) {
        val g = state()?.car?.gear
        if (g != null && g != Gear.P && !restYes) driverOps++
    }

    // ---- 판정 ----

    private abstract inner class Check(val cue: Cue, sec: Double) {
        val from = dueAt(cue)
        val until = from + ms(sec)
        var i = -1   // 아직 보지 않은 첫 기록
        /** true면 끝(통과·실패 모두). */
        open fun poll(now: Long): Boolean {
            if (i < 0) { i = log.size; while (i > 0 && log[i - 1].at >= from) i-- ; if (seeStart(i)) return true }
            while (i < log.size && log[i].at <= until) if (see(log[i++])) return true
            return if (now > until) { timeout(); true } else false
        }
        /** 창이 열릴 때 그 직전 상태(창 시작 때 이미 맞는 상태)를 본다. */
        open fun seeStart(i: Int) = false
        abstract fun see(r: Rec): Boolean
        abstract fun timeout()
    }

    private inner class ExpectCheck(cue: Cue, val e: Expect) : Check(cue, e.bySec) {
        var stateOk = e.state == null && e.attempt == null
        var cmdOk = e.command == null
        override fun seeStart(i: Int) = i > 0 && see(log[i - 1], commands = false)
        override fun see(r: Rec) = see(r, true)
        fun see(r: Rec, commands: Boolean): Boolean {
            if (!stateOk) stateOk = stateMatches(r.step, e.state, e.attempt)
            if (!cmdOk && commands) cmdOk = r.step.commands.any { cmdMatches(it, e.command!!, e.sid, e.method, e.type, e.body, r.at) }
            return stateOk && cmdOk
        }
        override fun timeout() = fail(cue.t, "expect ${listOfNotNull(e.state, e.attempt?.let { "attempt=$it" }, e.command, e.sid, e.type).joinToString(" ")}: ${e.bySec}초 안에 없음")
    }

    private inner class NoneCheck(cue: Cue, val e: ExpectNone) : Check(cue, e.forSec) {
        override fun see(r: Rec): Boolean {
            val hit = r.step.commands.any { cmdMatches(it, e.command, e.sid, null, e.type, e.body, r.at) }
            if (hit) fail(cue.t, "expectNone ${e.command} ${e.type ?: ""}: ${(r.at - from) / 1000.0}초에 나옴")
            return hit
        }
        override fun timeout() {}   // 끝까지 안 나왔다 = 통과
    }

    /** say YES·NO: onAsk 문장의 Ask가 말을 마치고 듣기를 열면 Answer를 넣는다(계약 7.3). */
    private inner class SayCheck(cue: Cue, val s: Say) : Check(cue, s.bySec) {
        // 지난 기록은 보지 않는다: 이미 닫힌 질문에 답하지 않게 poll 때마다 가장 최근 상태만 본다
        override fun poll(now: Long): Boolean {
            val st = state()
            val ask = st?.session?.pendingAsk
            if (ask != null && ask.sid == s.onAsk && st.speech.speaking != ask.askId && log.last().at <= until) {   // by가 지난 뒤 처음 열린 질문에는 답하지 않는다
                driverOp(restYes = s.intent == "YES" && ask.sid in REST_OFFERS)
                post(Answer(host.clock(), ask.askId, YesNo.valueOf(s.intent)))
                return true
            }
            if (now > until) { timeout(); return true }
            return false
        }
        override fun see(r: Rec) = false
        override fun timeout() = fail(cue.t, "say ${s.intent}: ${s.onAsk} 듣기가 ${s.bySec}초 안에 안 열림")
    }

    // ---- 측정(계약 7.4) ----

    @Synchronized fun row(): RunRow {
        val alarm = cryAt?.let { c -> log.firstOrNull { r -> r.at >= c && r.step.commands.any { (it is StartPlayback && it.kind == PlayKind.AUTO) || it is LogAlarm } } }?.at
        val play = alarm?.let { a -> log.firstOrNull { r -> r.at >= a && r.step.state.session?.play?.let { it.kind == PlayKind.AUTO && it.startedAt != null } == true } }?.at
        val handoff = alarm?.let { a -> log.firstOrNull { r -> r.at >= a && r.step.state.session?.phase == Phase.HANDOFF } }?.at
        val cmds = log.flatMap { r -> r.step.commands.map { r.at to it } }
        val missing = maxOf(0, ctlExpected - ctlGot)   // 17초 안에 오지 않은 차 제어 결과는 실패로 센다(row는 상태를 바꾸지 않는다)
        val ctlFail = if (missing > 0) (ctlLastAt - t0) / 1000.0 to "차 제어 결과 ${missing}개를 받지 못함" else null   // 마지막 차 제어 할 일의 장면 시각
        val gains = control.filter { it.api == GAIN_API }
        val others = control.filter { it.target in AREA_NAMES }   // 차 제어 성공률만: target이 영역 이름인 줄(계약 7.4)
        // 아직 안 끝난 재생분(판단 코어는 끝날 때만 더한다). 마지막 기록은 마지막으로 본 시각까지(호스트가 Tick을 생략할 수 있다)
        val lastRec = log.lastOrNull()
        fun Rec.live() = step.state.session?.play?.startedAt?.let { (maxOf(at, if (this === lastRec) lastNow else at) - it).coerceAtLeast(0) } ?: 0
        return RunRow(
            run = k + 1, seed = seed, completed = failed.isEmpty() && missing == 0, failedStep = (failed + listOfNotNull(ctlFail)).minOfOrNull { it.first },
            alarmLatencyMs = alarm?.let { it - cryAt!! }, alarmToPlayMs = play?.let { it - alarm }, // play는 alarm이 있을 때만 있다
            routedDevice = control.firstOrNull { it.api == ROUTE_API }?.actual,
            fallback = log.any { it.step.state.session?.frontAudible == true },
            maxAttempts = log.maxOfOrNull { it.step.state.session?.attemptNo ?: 0 } ?: 0,
            maxEpisodePlayMs = maxOf(
                log.maxOfOrNull { r -> r.step.state.session?.episode?.let { it.playedMs + r.live() } ?: 0 } ?: 0,
                cmds.maxOfOrNull { (it.second as? SaveEpisode)?.record?.playedMs ?: 0 } ?: 0,
            ),
            sessionPlayMs = maxOf(
                log.maxOfOrNull { r -> r.step.state.session?.let { it.playedMs + r.live() } ?: 0 } ?: 0,
                cmds.maxOfOrNull { (it.second as? SaveSession)?.record?.playedMs ?: 0 } ?: 0,
            ),
            gainViolations = gainViolations(gains.map { GainPoint(it.at, it.target, it.goal.toDoubleOrNull() ?: Double.NaN) },
                cmds.mapNotNull { (at, c) -> (c as? AdjustPlayback)?.let { at to it } },
                cmds.mapNotNull { it.second as? StartPlayback }.associateBy { it.playId }),
            lufsViolations = lufsViolations(cmds.mapNotNull { (it.second as? StartPlayback)?.method }.toSet(), lufs = ports::lufs),
            systemVolumeChanges = volumeChanges + control.count { it.api in VOLUME_APIS }, driverOps = driverOps,
            handoffMs = handoff?.let { it - alarm }, // handoff는 alarm이 있을 때만 있다
            controlOkRate = (others.size + missing).takeIf { it > 0 }?.let { others.count { it.ok }.toDouble() / it },
            failures = (failed + listOfNotNull(ctlFail)).sortedBy { it.first }.map { "t=${it.first}: ${it.second}" },
        )
    }

    private fun envelope(p: Phone, now: Long) = buildJsonObject {
        put("v", 1); put("id", ulid(now, rnd)); put("type", p.type)
        put("from", buildJsonObject { put("role", "owner"); put("device", "d_scenario") })
        put("ts", rfc3339(now))
        val off = p.body["atOffsetSec"]   // 시각은 그 단계 기준 초로 적는다(7.3)
        put("body", if (off == null) p.body else JsonObject(p.body - "atOffsetSec" + ("at" to JsonPrimitive(rfc3339(now + ms((off as JsonPrimitive).content.toDouble()))))))
    }.toString()
}

// ---- 순수 계산(시험하기 쉽게 밖에 둔다) ----

/** 계약 7.4 lufs_violations: 쓴 음원 중 −30 ±1 LUFS(Params) 밖의 수. 하나라도 못 재면 null(미측정, 0으로 쓰지 않는다). */
fun lufsViolations(used: Set<Method>, p: Params = Params(), lufs: (Method) -> Double?): Int? {
    val v = used.map(lufs)
    return if (null in v) null else v.count { abs(it!! - p.targetLufs) > p.lufsTolerance }
}

/**
 * stepLog에서 장면 Step만 넘긴다(계약 2.4·C92). 수집은 startScenario보다 먼저 붙어야 장면 첫 Step을 놓치지 않는데, 그 사이 앞서 큐에 든
 * 실제 사건의 Step이 섞여 온다. 그래서 장면 첫 Step까지 버린다: 호스트가 `EngineState.fresh(seed)`로 만들어 rng = 그 반복 시드, 세션·할 일이 없다.
 * 한 수집자(호스트 스레드)에서만 부른다.
 */
class SceneGate(private val seed: Long) {
    private var begun = false
    fun accept(step: Step): Boolean {
        if (!begun) begun = step.state.rng == seed && step.state.session == null && step.commands.isEmpty()
        return begun
    }
}

fun stateMatches(step: Step, state: String?, attempt: Int?): Boolean {
    val s = step.state.session
    if (state != null && (s?.phase?.name ?: "IDLE") != state) return false
    return attempt == null || s?.attemptNo == attempt
}

/** 계약 7.3: command는 이름, sid·method는 그 할 일의 문장·음원, type·body는 SendToPhone(body는 적은 필드만, 배열은 통째, 시각은 ± 2초). */
fun cmdMatches(c: Command, name: String, sid: Sid?, method: kr.aigyeot.engine.Method?, type: String?, body: JsonObject?, at: Long): Boolean {
    if (c::class.simpleName != name) return false
    if (sid != null && sid != when (c) { is Speak -> c.sid; is Ask -> c.sid; else -> null }) return false
    if (method != null && method != (c as? StartPlayback)?.method) return false
    if (type != null && type != (c as? SendToPhone)?.type) return false
    if (body != null) {
        val b = (c as? SendToPhone)?.body ?: return false
        if (!body.all { (k, v) -> b[k]?.let { same(v, it) } == true }) return false
    }
    return true
}

/** 기대값이 RFC 3339 문자열이면 실제 값이 문자열이든 숫자(epoch ms, 판단 코어의 occurred_at)든 ±2초로 비교한다. 그 밖은 같음 비교. */
private fun same(want: JsonElement, got: JsonElement): Boolean {
    val w = (want as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::parseTime)
    val gp = got as? JsonPrimitive
    val g = gp?.let { if (it.isString) parseTime(it.content) else it.content.toDoubleOrNull()?.toLong() }
    return if (w != null && g != null) abs(w - g) <= 2000 else want == got
}

private fun parseTime(s: String): Long? = runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()

fun rfc3339(ms: Long): String =
    OffsetDateTime.ofInstant(Instant.ofEpochMilli(ms).truncatedTo(ChronoUnit.SECONDS), ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

private const val B32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
private fun ulid(ms: Long, rnd: Random) = buildString(26) {
    for (i in 9 downTo 0) append(B32[((ms shr (5 * i)) and 31).toInt()])
    repeat(16) { append(B32[rnd.nextInt(32)]) }
}

data class GainPoint(val at: Long, val playId: String, val gain: Double)

/**
 * 계약 7.4 gain_violations: 이득 > 1.0·NaN, 또는 재생 중 이득을 올림. 허용(위반 아님)은 감쇄(DUCK)·멈춤(PAUSE)이 NORMAL로 풀린 뒤
 * 올라가는 한 번뿐이다. 감쇄가 처음 시작될 때(이미 감쇄 중이 아니면) 그 직전 이득이 기준 ref이고, NORMAL이 오면 ref까지 오를 수 있다
 * (DUCK→PAUSE→NORMAL도 처음 직전 값). ref에 닿으면 허용을 닫는다. 마지막 페이드 구간([endAt − fadeMs, endAt])에 풀렸으면 페이드 값까지만:
 * base × (endAt − (at − 250)) / fadeMs (base = 감쇄·페이드 전의 마지막 이득) (페이드는 선형으로 보고, 기록 시각 오차 0.25초를 봐준다). 낮추기(페이드·폴백 0.3)는 위반이 아니다.
 * 같은 시각이면 조정이 이득 점보다 먼저다(감쇄 줄은 명령 뒤에 찍힌다).
 */
fun gainViolations(points: List<GainPoint>, adjusts: List<Pair<Long, AdjustPlayback>>, starts: Map<String, StartPlayback> = emptyMap(), maxGain: Double = 1.0): Int {
    var n = 0
    for ((id, ps) in points.groupBy { it.playId }) {
        val adj = adjusts.filter { it.second.playId == id }
        // (시각, 순서(조정 0·점 1), 조정 or 점)
        val ev = (adj.map { Triple(it.first, 0, it.second as Any) } + ps.map { Triple(it.at, 1, it as Any) }).sortedWith(compareBy({ it.first }, { it.second }))
        val st = starts[id]
        var last: Double? = null
        var base = maxGain   // 감쇄·페이드가 없을 때의 이득: 감쇄 중이 아니고 페이드 구간 전의 마지막 점
        var ref = maxGain
        var ducked = false
        var allow: Double? = null
        var rose = false   // 허용 아래에서 올라간 적이 있다(복귀 시작). 그 뒤 내려가면 복귀 끝
        for ((at, _, x) in ev) {
            if (x is AdjustPlayback) {
                if (x.mode != PlayMode.NORMAL) {
                    if (!ducked) {
                        val first = if (last == null) ps.filter { it.at <= at }.minByOrNull { it.at } else null   // 같은 ms의 첫 점도 감쇄 직전 값
                        ref = last ?: first?.gain ?: maxGain
                        if (first != null && !(st != null && st.fadeMs > 0 && first.at >= st.endAt - st.fadeMs)) base = first.gain
                        ducked = true; allow = null; rose = false
                    }
                } else if (ducked) { ducked = false; allow = ref; rose = false }
                continue
            }
            val g = (x as GainPoint).gain
            if (g.isNaN() || g > maxGain + 1e-9) n++
            else {
                val inFade = st != null && st.fadeMs > 0 && at >= st.endAt - st.fadeMs && at <= st.endAt
                if (last != null && g > last + 1e-9) {
                    var cap = allow
                    if (cap != null && inFade) cap = minOf(cap, base * maxOf(0.0, (st!!.endAt - (at - 250)).toDouble() / st.fadeMs))
                    if (cap == null || g > cap + 1e-9) n++
                    else if (g >= cap - 1e-9) allow = null
                    else rose = true
                } else if (last != null && g < last - 1e-9 && rose) allow = null   // 복귀가 시작된 뒤 내려갔다: 복귀 끝

                if (!ducked && !(st != null && st.fadeMs > 0 && at >= st.endAt - st.fadeMs)) base = g
                last = g
            }
        }
    }
    return n
}

val CSV_HEADER = listOf(
    "run", "seed", "completed", "failed_step", "alarm_latency_ms", "alarm_to_play_ms", "routed_device", "fallback",
    "max_attempts", "max_episode_play_ms", "session_play_ms", "gain_violations", "lufs_violations", "system_volume_changes",
    "driver_ops", "handoff_ms", "control_ok_rate",
)

/** 계약 7.4 CSV. 반복마다 한 줄, 끝에 요약 줄(completed는 통과/전체, 상한을 보는 칸은 최댓값, 성공률은 최솟값). */
fun csv(rows: List<RunRow>): String {
    fun f(v: Any?) = v?.toString() ?: ""
    fun rate(v: Double?) = v?.let { "%.3f".format(java.util.Locale.ROOT, it) }
    fun line(r: RunRow) = listOf(r.run, r.seed, r.completed, r.failedStep, r.alarmLatencyMs, r.alarmToPlayMs, r.routedDevice?.replace(',', ';'),
        r.fallback, r.maxAttempts, r.maxEpisodePlayMs, r.sessionPlayMs, r.gainViolations, r.lufsViolations, r.systemVolumeChanges,
        r.driverOps, r.handoffMs, rate(r.controlOkRate)).joinToString(",") { f(it) }
    val sum = if (rows.isEmpty()) "" else listOf(
        "summary", "", "${rows.count { it.completed }}/${rows.size}", rows.mapNotNull { it.failedStep }.minOrNull(),
        rows.mapNotNull { it.alarmLatencyMs }.maxOrNull(), rows.mapNotNull { it.alarmToPlayMs }.maxOrNull(), "",
        rows.any { it.fallback }, rows.maxOf { it.maxAttempts }, rows.maxOf { it.maxEpisodePlayMs }, rows.maxOf { it.sessionPlayMs },
        rows.maxOf { it.gainViolations }, if (rows.any { it.lufsViolations == null }) null else rows.maxOf { it.lufsViolations!! }, rows.maxOf { it.systemVolumeChanges },
        rows.maxOf { it.driverOps }, rows.mapNotNull { it.handoffMs }.maxOrNull(), rate(rows.mapNotNull { it.controlOkRate }.minOrNull()),
    ).joinToString(",") { f(it) }
    return (listOf(CSV_HEADER.joinToString(",")) + rows.map(::line) + sum).joinToString("\n", postfix = "\n")
}
