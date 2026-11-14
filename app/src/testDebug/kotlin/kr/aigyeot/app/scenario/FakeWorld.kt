package kr.aigyeot.app.scenario

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kr.aigyeot.app.host.HostApi
import kr.aigyeot.app.host.ScenarioSetup
import kr.aigyeot.app.store.db.ControlLogEntity
import kr.aigyeot.engine.AddWaypoint
import kr.aigyeot.engine.Answer
import kr.aigyeot.engine.Area
import kr.aigyeot.engine.Ask
import kr.aigyeot.engine.CareSummary
import kr.aigyeot.engine.Command
import kr.aigyeot.engine.ControlKind
import kr.aigyeot.engine.ControlResult
import kr.aigyeot.engine.CrySegment
import kr.aigyeot.engine.EndReason
import kr.aigyeot.engine.Engine
import kr.aigyeot.engine.EngineState
import kr.aigyeot.engine.Event
import kr.aigyeot.engine.Gear
import kr.aigyeot.engine.GearChanged
import kr.aigyeot.engine.Heard
import kr.aigyeot.engine.Listen
import kr.aigyeot.engine.LowerHvac
import kr.aigyeot.engine.MicPath
import kr.aigyeot.engine.MicService
import kr.aigyeot.engine.PlaybackEnded
import kr.aigyeot.engine.PlaybackStarted
import kr.aigyeot.engine.SetReadingLight
import kr.aigyeot.engine.SetSeatHeat
import kr.aigyeot.engine.SetWindowLock
import kr.aigyeot.engine.Speak
import kr.aigyeot.engine.SpeechDone
import kr.aigyeot.engine.StartPlayback
import kr.aigyeot.engine.Step
import kr.aigyeot.engine.StopPlayback
import kr.aigyeot.engine.Tick
import kr.aigyeot.engine.TurnSignal
import kr.aigyeot.engine.VehicleSample
import kr.aigyeot.engine.WaypointResult
import kr.aigyeot.engine.YesNo
import kr.aigyeot.engine.fresh
import java.time.Instant
import java.util.PriorityQueue

/**
 * 호스트 없이 장면을 끝까지 돌리는 가짜 세상: 진짜 판단 코어(Engine)를 돌리는 호스트,
 * 할 일에 결과 사건을 돌려주는 가짜 실행기, Virtual Console을 기어·주행 사건으로 바꾸는 가짜 차, 녹음 대신 울음 구간을 내는 가짜 감지기.
 * 시간은 가상 시계(50ms씩)로 흐른다. 한 장면 실행에 하나씩 만든다.
 *
 * @param cryLenSec 녹음 파일마다 실제로 우는 길이(초). 없으면 audio.durSec 내내 운다.
 * @param vcFails true면 모든 vc 요청이 실패로 끝난다(차량 입력도 안 들어간다).
 * @param audioDelayMs 녹음을 섞기 시작하기까지의 지연(PCM 읽기 시간). audio가 at + 지연을 돌려준다.
 * @param micAvailable false면 앱 화면이 없는 것처럼 micOn이 실패한다.
 */
class FakeWorld(
    private val cryLenSec: Map<String, Double> = emptyMap(), private val speechMs: Long = 3_000,
    private val vcFails: Boolean = false, private val micAvailable: Boolean = true, private val audioDelayMs: Long = 0,
) : HostApi, ScenePorts {
    var now = T0
    private var state = EngineState.fresh(0, null, emptyList(), emptyList(), null, 0)
    private val _steps = MutableStateFlow<Step?>(null)
    override val steps: StateFlow<Step?> = _steps
    override val controlLog = MutableSharedFlow<ControlLogEntity>()
    override val stepLog = MutableSharedFlow<Step>()   // 가짜 세상은 run.onStep에 바로 넣는다
    override val stepLogDrops = MutableStateFlow(0L)
    private var run: SceneRun? = null
    val commands = ArrayList<Pair<Long, Command>>()
    val events = ArrayList<Event>()
    var setup: ScenarioSetup? = null
    var ended = false
    var vcSent = ArrayList<String>()
    var simulated = 0
    private var hvacC = 22.0

    private class Job(val at: Long, val seq: Int, val f: () -> Unit)
    private val queue = PriorityQueue<Job>(compareBy<Job>({ it.at }, { it.seq }))
    private var seq = 0
    private fun at(t: Long, f: () -> Unit) { queue += Job(t, seq++, f) }

    // ---- 호스트 ----
    override fun clock() = now
    override fun post(event: Event) {
        events += event
        val st = Engine().step(state, event)
        state = st.state
        _steps.value = st
        run?.onStep(st)
        st.commands.forEach { commands += now to it; exec(it) }
    }
    override fun startScenario(setup: ScenarioSetup) {
        this.setup = setup
        state = EngineState.fresh(setup.seed, setup.profile, setup.methods, setup.history, setup.feedingStart, setup.otherGuardians)
        setup.care?.let { post(CareSummary(now, it)) }   // 호스트와 같게 첫 Step 뒤 요약 흉내
    }
    override fun endScenario() { ended = true }

    // ---- 장면 입력 ----
    override fun vc(method: String, path: String, done: (Boolean, String) -> Unit) {
        vcSent += "$method $path"
        if (vcFails) { done(false, "모의 실패"); return }
        done(true, "")
        val q = path.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate { it.substringBefore('=') to it.substringAfter('=') }
        at(now + 100) {
            when {
                path.startsWith("/vehicle/gear") -> post(GearChanged(now, if (q["gear"] == "Drive") Gear.D else Gear.P))
                path.startsWith("/vehicle/controls") ->
                    post(VehicleSample(now, q.getValue("speed").toDouble(), q.getValue("brake").toInt() > 0, q.getValue("steering").toDouble(), TurnSignal.NONE))
            }
        }
    }

    private val cries = ArrayList<LongRange>()
    private var mic = false
    private var prevLoud = false
    override fun audio(a: Audio, at: Long): Long? {
        val s = at + audioDelayMs
        if (a.cry) cries += s until s + Math.round((cryLenSec[a.file] ?: a.durSec) * 1000)
        return s
    }
    override fun micOn(): Boolean {
        if (!micAvailable) return false
        at(now + 300) { mic = true; post(MicService(now, true, MicPath.HOME_BUTTON)); detect(now + 3 * WINDOW) }
        return true
    }
    override fun phone(envelopeJson: String, done: (Boolean, String) -> Unit) { done(true, "") }
    override fun simulated(what: String) { simulated++ }

    /** 가짜 감지기: 0.975초마다 3창(2.925초) 구간. 울음이 구간의 절반 이상이면 p = 0.95, 경보는 이 구간과 앞 구간이 모두 큼. */
    private fun detect(t: Long): Unit = at(t) {
        if (!mic) return@at
        val start = now - 3 * WINDOW
        val cried = cries.sumOf { r -> (minOf(r.last + 1, now) - maxOf(r.first, start)).coerceAtLeast(0) }
        val loud = cried * 2 >= 3 * WINDOW
        post(CrySegment(now, start, now, if (loud) 0.95 else 0.05, loud && prevLoud, cries.lastOrNull { now in it }?.first))
        prevLoud = loud
        detect(t + WINDOW)
    }

    // ---- 가짜 실행기: 할 일 → 결과 사건 ----
    private fun exec(c: Command) {
        when (c) {
            is StartPlayback -> {
                control(ROUTE_API, c.playId, ZONE)
                control(GAIN_API, c.playId, "1.0")
                at(now + 200) { post(PlaybackStarted(now, c.playId, ZONE, false)) }
                at(c.endAt) { post(PlaybackEnded(now, c.playId, EndReason.DEADLINE)) }
            }
            is StopPlayback -> at(now + 100) { post(PlaybackEnded(now, c.playId, EndReason.STOPPED)) }
            is Speak -> at(now + speechMs) { post(SpeechDone(now, c.utteranceId, true)) }
            is Ask -> {
                at(now + speechMs) { post(SpeechDone(now, c.askId, true)) }
                at(now + speechMs + c.listenMs) { post(Answer(now, c.askId, YesNo.NONE)) }   // 답이 이미 왔으면 판단 코어가 버린다
            }
            is Listen -> at(now + c.ms) { post(Heard(now, c.listenId, null)) }
            is SetSeatHeat -> result(c.cmdId, ControlKind.SEAT_HEAT, c.areas.associateWith { c.level.toDouble() })
            is SetReadingLight -> result(c.cmdId, ControlKind.READING_LIGHT, c.areas.associateWith { if (c.on) 1.0 else 0.0 })
            is LowerHvac -> at(now + 300) {   // 22℃에서 시작해 deltaC만큼 내린 실제 before·after(최솟값 15℃)
                val before = hvacC
                hvacC = maxOf(15.0, before + c.deltaC)
                c.areas.forEach { area ->
                    control(ControlKind.HVAC_TEMP.name, area.name, "$hvacC")
                    post(ControlResult(now, c.cmdId, ControlKind.HVAC_TEMP, area, true, before, hvacC))
                }
            }
            is SetWindowLock -> result(c.cmdId, ControlKind.WINDOW_LOCK, c.values.mapValues { if (it.value) 1.0 else 0.0 })
            is AddWaypoint -> at(now + 500) { post(WaypointResult(now, c.cmdId, c.restId, true)) }
            else -> {}
        }
    }

    private fun result(cmdId: String, kind: ControlKind, goal: Map<Area, Double>) = at(now + 300) {
        goal.forEach { (area, v) ->
            control(kind.name, area.name, "$v")
            post(ControlResult(now, cmdId, kind, area, true, 0.0, v))
        }
    }

    private fun control(api: String, target: String, goal: String) =
        run?.onControl(ControlLogEntity(0, state.session?.id, now, api, target, goal, goal, 50, 100, true, 0))

    /** 장면 한 번을 끝까지(또는 끝나야 할 때 + 60초까지) 돌린다. */
    fun play(scene: Scene, k: Int = 0): RunRow {
        val r = SceneRun(scene, k, this, this, now)
        run = r
        startScenario(r.setup())
        tick(now + 1_000)
        while (!r.done(now)) {
            if (now > r.endAt + 60_000) { r.finish("시간 초과"); break }
            r.advance(now)
            val next = now + SLICE
            while (queue.isNotEmpty() && queue.peek()!!.at <= next) { val j = queue.poll()!!; now = maxOf(now, j.at); j.f() }
            now = next
        }
        endScenario()
        return r.row()
    }

    private fun tick(t: Long): Unit = at(t) { post(Tick(now)); tick(t + 1_000) }

    companion object {
        val T0: Long = Instant.parse("2026-11-05T00:00:00Z").toEpochMilli()
        const val WINDOW = 975L
        const val SLICE = 50L
        const val ZONE = "bus100_audio_zone_1"
    }
}
