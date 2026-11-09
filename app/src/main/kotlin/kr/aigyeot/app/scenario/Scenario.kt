package kr.aigyeot.app.scenario

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kr.aigyeot.app.common.CarPaths
import kr.aigyeot.app.detect.MicService
import kr.aigyeot.app.host.HostApi
import kr.aigyeot.app.link.Link
import kr.aigyeot.engine.Method
import kr.aigyeot.engine.MicPath
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 장면 재생기(F18, 계약 7장). 장면을 시간축대로 돌리고 기대값을 판정해 측정표를 쓴다.
 * 판단 코어 상태는 [HostApi.stepLog]로 읽기만 하고, 입력은 Virtual Console·녹음·사건 주입으로 넣는다(계약 1.1).
 * 마이크 서비스를 켜므로 장면 화면(앱 화면)이 떠 있을 때만 [run]한다(명세서 6장).
 */
class Scenario(
    private val context: Context,
    private val host: HostApi,
    private val link: Link,
) {
    private val _progress = MutableStateFlow<ScenarioProgress?>(null)
    val progress: StateFlow<ScenarioProgress?> = _progress
    private val _running = MutableStateFlow(false)
    /** 장면이 도는 중. */
    val running: StateFlow<Boolean> = _running
    private val _results = MutableStateFlow<SceneResults?>(null)
    /** 마지막으로 실행한 장면의 끝난 반복 행(측정표 CSV와 같은 값, 읽기 전용). 반복이 끝날 때마다 늘어난다. */
    val results: StateFlow<SceneResults?> = _results
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val vc = VirtualConsole()
    private val loudness = SourceLoudness { androidLufs(context, it) }   // 잰 음원은 반복마다 다시 재지 않는다

    /** 시연·측정 화면의 장면 행(id 순, assets/scenario + 흘려 넣은 장면). 부를 때마다 파일을 다시 본다. 읽지 못한 파일은 제목·녹음 없이 둔다. */
    fun list(): List<SceneInfo> = files().entries.sortedBy { it.key }.map { (id, read) -> sceneInfo(id, runCatching { parseScene(read()) }.getOrNull()) }

    fun run(id: String, repeat: Int? = null) {
        if (job?.isCompleted == false) return   // 취소 중인 이전 실행의 정리가 끝나기 전에는 새로 시작하지 않는다
        val scene = try {
            parseScene(files()[id]?.invoke() ?: throw SceneError("장면 '$id'가 없다"))
        } catch (e: Exception) {
            _progress.value = ScenarioProgress(id, 0, 0, 0.0, null, "장면 파일 오류: ${e.message}")
            return
        }
        _running.value = true
        _results.value = SceneResults(scene.id, emptyList())
        job = scope.launch { try { runAll(scene, repeat ?: scene.repeat) } finally { _running.value = false } }
    }

    fun stop() { job?.cancel() }

    /** 앱 안에서 Virtual Console(127.0.0.1:8088)에 닿는지(계약 7.1 미측정 항목). 세션 신호 뒤 차량 상태를 읽어 본다. */
    suspend fun checkVc(): String = withContext(Dispatchers.IO) {
        val s = vc.send("POST", VirtualConsole.SESSION)
        val st = vc.send("GET", "/vehicle/state")
        "session ${s.code} ${s.body.take(80)} · state ${st.code} ${st.body.take(120)}".also { Log.i(TAG, "VC $it") }
    }

    /** 장면 id → 파일 읽기. 흘려 넣은 파일(CarPaths.scenarioDir)이 같은 id의 assets 파일을 덮는다. 읽지 못한 파일은 파일 이름으로 둔다(실행하면 오류를 보여 준다). */
    private fun files(): Map<String, () -> String> {
        val out = LinkedHashMap<String, () -> String>()
        fun add(name: String, read: () -> String) {
            val id = runCatching { parseScene(read()).id }.getOrElse { name.removeSuffix(".json") }
            out[id] = read
        }
        context.assets.list(ASSET_DIR).orEmpty().filter { it.endsWith(".json") }.sorted()
            .forEach { n -> add(n) { context.assets.open("$ASSET_DIR/$n").bufferedReader().use { it.readText() } } }
        CarPaths.scenarioDir(context).listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }
            .forEach { f -> add(f.name) { f.readText() } }
        return out
    }

    private suspend fun runAll(scene: Scene, n: Int) {
        val dir = CarPaths.scenarioResults(context).also { it.mkdirs() }
        val out = freeResultFile(dir, scene.id, SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date()))   // 같은 분에 다시 돌려도 덮어쓰지 않는다
        val rows = ArrayList<RunRow>()
        fun save() {
            _results.value = SceneResults(scene.id, rows.toList())
            out.writeText(csv(rows))
            File(dir, out.nameWithoutExtension + ".txt").writeText(rows.joinToString("\n") { r -> "run ${r.run}: " + (r.failures.ifEmpty { listOf("통과") }).joinToString(" | ") } + "\n")
        }
        // 원래 마이크 상태는 한 번만 읽고 끝에서 한 번만 되돌린다(반복 사이에는 켜지 않는다). 마이크 서비스는 시작할 때만 입력원을 보므로 장면 동안은 끈다(scenario.md 2)
        val micBefore = MicService.runningPauses() != null
        try {
            var micOff = true
            if (micBefore) {
                MicService.stop(context)
                try { micOff = awaitMicOff() } catch (e: CancellationException) { micOff = false }   // 멈춤: runOnce가 세션 신호 대기에서 바로 취소되어 '멈춤' 행을 돌려준다
            }
            for (k in 0 until n) {
                rows += runOnce(scene, k, n, micOff); save()   // 멈춰도 끝난 반복은 남는다
                if (!currentCoroutineContext().isActive) break
            }
        } finally {
            withContext(NonCancellable) {
                var note = "결과: ${out.path}"
                if (micBefore && !startMicIfForeground()) note += " · 앱 화면이 없어 마이크를 다시 켜지 못함"
                save()
                _progress.value = _progress.value?.copy(note = note)
                Log.i(TAG, "$note\n${csv(rows)}")
            }
        }
    }

    /** 앱 화면이 앞에 있을 때만 마이크 서비스를 켠다(명세서 6장). 켰으면 true. */
    private fun startMicIfForeground(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }
        if (info.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) return false
        MicService.start(context, MicPath.HOME_BUTTON)
        return true
    }

    private class VcReq(val method: String, val path: String, val done: (Boolean, String) -> Unit)

    private suspend fun runOnce(scene: Scene, k: Int, n: Int, micOff: Boolean): RunRow {
        // 세션 신호 확인이 먼저다(t0·장면 전 Step 기준을 대기 뒤에 잡는다): 첫 vc가 세션 신호보다 먼저 나가면 emulator_inactive로 실패한다. 최대 3번
        var waitNote: String? = null
        var stoppedEarly = false   // 대기 중 멈춤이어도 행은 남긴다(계약 7.4 '실행마다 한 줄')
        try {
            withContext(Dispatchers.IO) {
                for (i in 0 until 3) {
                    val r = vc.send("POST", VirtualConsole.SESSION)
                    if (r.ok) return@withContext
                    waitNote = "VC 세션 실패 ${r.code} ${r.body.take(60)}"
                    if (i < 2) delay(500)
                }
            }
        } catch (e: CancellationException) { stoppedEarly = true }
        val t0 = host.clock()
        val mix = MixSource()
        val vcQueue = Channel<VcReq>(Channel.UNLIMITED)
        var note: String? = waitNote
        var micRequested = false
        val jobs = ArrayList<Job>()
        lateinit var run: SceneRun
        val ports = object : ScenePorts {
            override fun vc(method: String, path: String, done: (Boolean, String) -> Unit) { vcQueue.trySend(VcReq(method, path, done)) }
            override fun audio(a: Audio, at: Long): Long? = try {
                val pcm = openRecording(a.file).let { MixSource.load(it, a.fromSec, a.durSec) }
                mix.play(pcm, a.durSec, a.gainDb)
                val started = host.clock()   // 실제로 섞기 시작한 시각
                if (a.cry) {   // C36: 경보 지연 기준점을 감지기에 알린다. 녹음이 끝나면 지운다
                    MicService.running?.injectedCryAt = started
                    scope.launch { delay(Math.round(a.durSec * 1000)); MicService.running?.let { if (it.injectedCryAt == started) it.injectedCryAt = null } }
                }
                started
            } catch (e: Exception) { Log.w(TAG, "녹음 ${a.file}: $e"); null }
            override fun micOn(): Boolean = startMicIfForeground().also { if (it) micRequested = true }
            override fun phone(envelopeJson: String, done: (Boolean, String) -> Unit) {
                jobs += scope.launch {
                    try { link.receiveForScenario(envelopeJson); done(true, "") }
                    catch (e: CancellationException) { throw e }   // 취소되면 done을 부르지 않는다: finish가 실패로 닫는다
                    catch (e: Exception) { done(false, "${e.javaClass.simpleName} ${e.message}") }
                }
            }
            override fun simulated(what: String) { Log.i(TAG, "시뮬레이션 입력 $what") }
            override fun lufs(method: Method): Double? = loudness.lufs(method)
        }
        val volume = object : BroadcastReceiver() {   // 계약 7.4 system_volume_changes: STREAM_MUSIC 볼륨 변화 콜백을 센다
            override fun onReceive(c: Context, i: Intent) {
                if (i.getIntExtra(EXTRA_STREAM, -1) == AudioManager.STREAM_MUSIC) run.onVolumeChange()
            }
        }
        run = SceneRun(scene, k, host, ports, t0)
        if (stoppedEarly) { run.finish("멈춤"); return run.row() }   // startScenario·sourceFactory는 건드리지 않는다
        if (!micOff) { run.finish("마이크 서비스를 끄지 못함"); return run.row() }   // 장면 시작 전: startScenario를 부르지 않는다
        MicService.sourceFactory = { mix }   // BABY_ABOARD가 켜는 마이크 서비스가 장면 입력원을 쓴다
        try {
            ContextCompat.registerReceiver(context, volume, IntentFilter(VOLUME_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            // stepLog는 모든 Step을 버퍼에 담아 순서대로 낸다(느려도 중간 Step을 놓치지 않는다, 계약 2.4). startScenario보다 먼저 붙어(UNDISPATCHED:
            // launch가 돌아올 때 이미 구독) 장면 첫 Step부터 받고, 그 앞에 큐에 들어 있던 실제 사건의 Step은 SceneGate가 버린다.
            val gate = SceneGate(run.seed)
            jobs += scope.launch(Dispatchers.Unconfined, CoroutineStart.UNDISPATCHED) { host.stepLog.collect { if (gate.accept(it)) run.onStep(it) } }
            host.startScenario(run.setup())
            jobs += scope.launch(Dispatchers.Unconfined) { host.controlLog.collect { run.onControl(it) } }
            jobs += scope.launch(Dispatchers.IO) {   // 세션 신호: 실행 중 1초마다(계약 7.1)
                while (isActive) { val r = vc.send("POST", VirtualConsole.SESSION); if (!r.ok) note = "VC 세션 실패 ${r.code} ${r.body.take(60)}"; delay(1_000) }
            }
            jobs += scope.launch(Dispatchers.IO) {   // 차량 입력은 순서대로 하나씩
                for (v in vcQueue) {
                    val r = vc.send(v.method, v.path)
                    Log.i(TAG, "VC ${v.method} ${v.path} → ${r.code} ${r.body.take(80)}")
                    val why = "${r.code} ${r.body.take(60)}"
                    if (!r.ok) note = "VC 실패 ${v.path} $why"
                    v.done(r.ok, why)
                }
            }
            while (true) {
                val now = host.clock()
                if (run.done(now)) break
                if (now > run.endAt + OVERTIME_MS) { run.finish("시간 초과"); break }
                run.advance(now)
                _progress.value = ScenarioProgress(scene.id, k + 1, n, ((now - t0) / 1000).toDouble(), run.firstFail(), note ?: "시뮬레이션 입력 실행 중")   // 정수 초: 같은 값이면 StateFlow가 거른다
                delay(TICK_MS)
            }
        } catch (e: CancellationException) {
            run.finish("멈춤")   // 다시 던지지 않는다: 멈춘 반복의 행도 남기고, 부르는 쪽이 isActive로 멈춘다
        } catch (e: Exception) {
            run.finish("오류: ${e.javaClass.simpleName} ${e.message}")
        } finally {
            withContext(NonCancellable) { try {
                run.checkStepLog()   // 장면 Step이 버려졌으면 이 반복은 통과가 아니다(endScenario 복원의 Step은 세지 않게 먼저)
                jobs.forEach { it.cancel() }
                withTimeoutOrNull(6_000) { jobs.joinAll() }   // HttpURLConnection은 취소로 멈추지 않으니 endScenario 전에 끝나길 기다린다
                vcQueue.close()
                runCatching { context.unregisterReceiver(volume) }
                if (micRequested || MicService.runningPauses() != null) {   // 장면 입력원 끄기. 준비 중인 서비스가 나중에 살아나지 않게 요청했으면 늘 stop을 보낸다
                    MicService.stop(context)
                    if (micRequested) delay(500)
                    if (!awaitMicOff()) run.failEnd("마이크 서비스를 끄지 못함")
                }
                try { host.endScenario() } catch (e: Exception) { run.failEnd("endScenario 실패: ${e.javaClass.simpleName} ${e.message}") }
            } finally { MicService.sourceFactory = null } }
        }
        return run.row()
    }

    /** 서비스가 멈추고 그 MicService(running=false)가 판단 코어에 들어갈 때까지(5초까지) 기다린다. 확인하지 못하면 false. */
    private suspend fun awaitMicOff(): Boolean {
        repeat(50) {
            if (MicService.runningPauses() == null && host.steps.value?.state?.car?.micRunning != true) return true
            delay(100)
        }
        Log.w(TAG, "마이크 서비스 꺼짐을 5초 안에 확인하지 못함")
        return false
    }

    private fun openRecording(name: String) =
        File(CarPaths.scenarioDir(context), name).takeIf { it.isFile }?.inputStream() ?: context.assets.open("$ASSET_DIR/$name")

    private companion object {
        const val TAG = "SCENARIO"
        const val ASSET_DIR = "scenario"
        const val TICK_MS = 50L
        const val OVERTIME_MS = 60_000L
        const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"   // 숨은 시스템 방송(공개 콜백이 없다)
        const val EXTRA_STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE"
    }
}

data class ScenarioProgress(val id: String, val run: Int, val of: Int, val t: Double, val failedStep: Double?, val note: String?)

/** 한 번 실행한 장면의 결과 행([Scenario.results]). */
data class SceneResults(val id: String, val rows: List<RunRow>)

/** 장면 행 하나: id, 제목, 장면이 트는 녹음 파일 이름(경로 빼고, 겹침 없이). */
data class SceneInfo(val id: String, val title: String?, val audio: List<String>)

internal fun sceneInfo(id: String, scene: Scene?) =
    SceneInfo(id, scene?.title, scene?.steps.orEmpty().mapNotNull { (it.action as? Audio)?.file?.substringAfterLast('/') }.distinct())
