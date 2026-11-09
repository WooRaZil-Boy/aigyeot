package kr.aigyeot.app.exec

import kr.aigyeot.app.store.db.ControlLogEntity
import kr.aigyeot.engine.AdjustPlayback
import kr.aigyeot.engine.EndReason
import kr.aigyeot.engine.Event
import kr.aigyeot.engine.FocusChange
import kr.aigyeot.engine.FocusChanged
import kr.aigyeot.engine.Params
import kr.aigyeot.engine.PlayMode
import kr.aigyeot.engine.PlaybackEnded
import kr.aigyeot.engine.PlaybackRejected
import kr.aigyeot.engine.PlaybackStarted
import kr.aigyeot.engine.RejectReason
import kr.aigyeot.engine.StartPlayback
import kr.aigyeot.engine.StopPlayback
import kr.aigyeot.engine.sessionIdOf
import java.util.Locale

internal const val REAR_ZONE = "bus100_audio_zone_1"

/** 포커스 요청 줄의 goal: 원한 결과와 요청 context·gain 종류(Audio.kt의 요청과 같게, C321). */
internal const val FOCUS_GOAL = "GRANTED(USAGE_MEDIA, GAIN_TRANSIENT)"

/**
 * control_log 한 줄(계약 6.1). session_id·at은 여기서 채운다.
 * V13(P5): session_id는 결과가 온 때의 상태가 아니라 명령을 낸 세션이다. [ref]는 그 명령의 cmdId·playId(`{세션 id}-{seq}`)이고
 * 세션 id는 [sessionIdOf] 하나로 얻는다. 명령 없이 나는 줄(Exec start 등)만 ref가 null이라 지금 세션([session])을 쓴다.
 */
internal class ControlLogger(private val clock: () -> Long, private val session: () -> String?, private val sink: (ControlLogEntity) -> Unit) {
    /** 저장 쪽이 던져도 삼킨다: 기록 실패가 재생 예약·제어 결과를 끊으면 안 된다(로그는 부수 효과). */
    fun row(ref: String?, api: String, target: String, goal: String, actual: String?, ok: Boolean, ackMs: Long? = null, appliedMs: Long? = null, retries: Int = 0) {
        try {
            sink(ControlLogEntity(sessionId = if (ref != null) sessionIdOf(ref) else session(), at = clock(), api = api, target = target, goal = goal, actual = actual,
                ackMs = ackMs, appliedMs = appliedMs, ok = ok, retries = retries, cmdId = ref))
        } catch (e: Exception) { /* 무시 */ }
    }
}

/**
 * 뒷좌석 재생(계약 4.1 '실행기가 지킬 재생 규칙', 명세서 7·14장).
 * 이득 = 기본(뒷좌석 1.0, 앞 폴백 0.3) × 페이드 × 감쇄. 기본은 내려가기만 하고 페이드는 시간이 갈수록 작아지므로,
 * 감쇄가 풀릴 때 감쇄 직전 이득(그사이 페이드에 들어갔으면 페이드 값)보다 커지지 않는다(14장 볼륨 상한 ①).
 */
internal class Player(
    private val audio: AudioPort,
    private val sched: Sched,
    private val clock: () -> Long,
    private val post: (Event) -> Unit,
    private val log: ControlLogger,
    private val frontAllowed: () -> Boolean,        // 프로필 frontFallbackAllowed(D12)
    private val p: Params,
) {
    private inner class Cur(val id: String, val track: Track, val rear: Boolean, val endAt: Long, val fadeMs: Long, var base: Float) {
        var paused = false                           // PAUSE 중(감쇄 여부와 따로 둔다: 감쇄 중 정지해도 감쇄는 NORMAL 전까지 유지)
        var ducked = false
        var stopFadeAt: Long? = null                 // StopPlayback(fade = true)를 받은 시각
        val timers = mutableListOf<() -> Unit>()
        var gain = -1f
        var announced = false                        // PlaybackStarted를 냈다
        var pendingRoute = false                     // 뒷좌석인데 경로가 아직 미정: 확정 전에는 이득을 정하지 않는다(무음)
    }

    private var cur: Cur? = null

    fun start(c: StartPlayback) {
        cur?.let { end(it, EndReason.STOPPED) }   // 앞 재생이 남아 있으면 먼저 끝낸다(한 번에 하나)
        // 포트가 던져도 상태를 남기지 않고 ERROR로 거절한다(결과를 두 번 내지 않게 Exec의 catch까지 가지 않는다)
        val rear = try { audio.hasRearOutput() } catch (e: Exception) { return reject(c.playId, "getDevices ${e.message}") }
        if (!rear && !frontAllowed()) {
            log.row(c.playId, "getDevices", REAR_ZONE, "present", "absent", false)
            return post(PlaybackRejected(clock(), c.playId, RejectReason.NO_REAR_OUTPUT))
        }
        val granted = try {
            audio.requestFocus { change ->
                // 끝난 재생의 영구 상실도 알린다(5.5 14c: GAP·CONFIRM에서 앞 재생의 LOSS는 WAIT로). 그 밖은 현재 재생일 때만
                if (change == FocusChange.LOSS || cur?.id == c.playId) post(FocusChanged(clock(), c.playId, change))
            }
        } catch (e: Exception) { return reject(c.playId, "requestAudioFocus ${e.message}") }
        if (!granted) {
            log.row(c.playId, "requestAudioFocus", c.playId, FOCUS_GOAL, "FAILED", false)
            return post(PlaybackRejected(clock(), c.playId, RejectReason.FOCUS_DENIED))
        }
        log.row(c.playId, "requestAudioFocus", c.playId, FOCUS_GOAL, "GRANTED", true)
        val track = try { audio.open(c.method, rear, onRouted = { routed(c.playId, it) }, onError = { failed(c.playId, "playback error") }) }
            catch (e: Exception) { return reject(c.playId, "open ${e.message}") }
        if (track == null) return reject(c.playId, "open ${c.method.kind}")
        val x = Cur(c.playId, track, rear, c.endAt, c.fadeMs, (if (rear) p.maxGain else p.fallbackGain).toFloat())
        cur = x
        // Track.start()는 소리 없이 시작해 경로를 정한다. 이득은 경로를 안 뒤에 처음 정한다(폴백이면 처음부터 0.3)
        val routed = try { track.start() } catch (e: Exception) { cur = null; track.release(); return reject(c.playId, "start ${e.message}") }
        // 시작하자마자 다른 곳으로 나가면(D12): 허락했으면 이득 0.3의 폴백으로 알리고, 아니면 알린 뒤 바로 멈춘다(PlaybackEnded(ERROR))
        val elsewhere = rear && routed != null && routed != REAR_ZONE
        val front = elsewhere && frontAllowed()
        if (front) x.base = p.fallbackGain.toFloat()
        // 뒷좌석인데 경로가 아직 null이면 무음으로 알리고(2.4 '재생 시작 결과 먼저'), 첫 onRouted나 확인 타이머가 정한다
        x.pendingRoute = rear && routed == null
        if (!elsewhere && !x.pendingRoute || front) {
            apply(x, if (front) "fallback" else "start")
            log.row(c.playId, "getRoutedDevice", c.playId, if (rear) REAR_ZONE else "default", routed, !elsewhere)
        } else if (elsewhere) log.row(c.playId, "getRoutedDevice", c.playId, REAR_ZONE, routed, false)
        if (cur !== x) return                                                  // 이득 설정이 던져 이미 거절로 닫혔다
        x.announced = true
        post(PlaybackStarted(clock(), c.playId, routed, fallback = !rear || front))
        if (elsewhere && !front) return failed(c.playId, "routed $routed")
        if (x.pendingRoute) x.timers += sched.after(ROUTE_CONFIRM_MS) { confirmRoute(x) }
        x.timers += sched.after(c.endAt - c.fadeMs - clock()) { ramp(x) }
        // 마지막 페이드 중 StopPlayback(fade)을 받았으면 DEADLINE이 아니라 STOPPED로 끝낸다(4.1)
        // 끝 시각까지 경로가 한 번도 안 정해졌으면(소리 없는 재생) DEADLINE(끝까지 틀었다)이 아니라 확인 실패와 같은 ERROR로 끝낸다
        x.timers += sched.after(c.endAt - clock()) {
            if (x.pendingRoute && x.stopFadeAt == null) resolve(x, null, true)
            else end(x, if (x.stopFadeAt != null) EndReason.STOPPED else EndReason.DEADLINE)
        }
    }

    fun stop(c: StopPlayback) {
        val x = cur?.takeIf { it.id == c.playId } ?: return
        if (!c.fade) return end(x, EndReason.STOPPED)
        if (x.stopFadeAt != null) return
        x.stopFadeAt = clock()
        ramp(x)
        x.timers += sched.after(x.fadeMs) { end(x, EndReason.STOPPED) }
    }

    fun adjust(c: AdjustPlayback) {
        val x = cur?.takeIf { it.id == c.playId } ?: return
        if (c.mode == PlayMode.PAUSE) { x.paused = true; x.track.pause(); return }   // 감쇄(ducked)는 그대로 둔다
        val wasPaused = x.paused
        x.paused = false
        x.ducked = c.mode == PlayMode.DUCK
        apply(x, if (wasPaused) "resume" else c.mode.name)
        if (cur !== x) return
        if (wasPaused && clock() < x.endAt) x.track.resume()                 // 남은 시간 안에서 이어 간다(endAt 그대로)
    }

    /** ReleaseAudio·Suspend(3-14 ⑤): 사건 없이(계약 4.1 '돌아오는 사건 없음') 타이머·트랙·포커스를 놓고 control_log 한 줄만 남긴다. */
    fun release() {
        cur?.let { x ->
            cur = null
            x.timers.forEach { it() }
            runCatching { x.track.release() }
            log.row(x.id, "stopPlayback", x.id, "RELEASE", "released", true)
        }
        runCatching { audio.abandonFocus() }
    }

    /** 경로 미정이던 뒷좌석 재생의 확정: REAR면 그때 처음 이득, 다른 곳이면 [routed]와 같은 규칙, 끝내 null이면 ERROR. */
    private fun resolve(x: Cur, addr: String?, final: Boolean) {
        if (!x.pendingRoute || cur !== x) return
        when {
            addr == REAR_ZONE -> { x.pendingRoute = false; apply(x, "start"); log.row(x.id, "getRoutedDevice", x.id, REAR_ZONE, addr, true) }
            addr != null -> {
                x.pendingRoute = false
                if (frontAllowed()) { x.base = p.fallbackGain.toFloat(); apply(x, "fallback"); log.row(x.id, "getRoutedDevice", x.id, REAR_ZONE, addr, false) }
                else { log.row(x.id, "getRoutedDevice", x.id, REAR_ZONE, addr, false); failed(x.id, "routed $addr") }
            }
            final -> failed(x.id, "routing unknown")
        }
    }

    private fun confirmRoute(x: Cur) {
        if (!x.pendingRoute || cur !== x) return
        resolve(x, try { x.track.routed() } catch (e: Exception) { null }, true)
    }

    private fun routed(id: String, addr: String?) {
        val x = cur?.takeIf { it.id == id && it.rear } ?: return
        if (x.pendingRoute) return resolve(x, addr, false)
        if (addr == null || addr == REAR_ZONE) return
        if (frontAllowed()) {                                          // D12: 다른 곳으로 나가면 이득 0.3으로만
            if (x.base > p.fallbackGain) { x.base = p.fallbackGain.toFloat(); apply(x, "fallback") }
            log.row(id, "getRoutedDevice", id, REAR_ZONE, addr, false)
        } else failed(id, "routed $addr")
    }

    private fun failed(id: String, why: String) {
        val x = cur?.takeIf { it.id == id } ?: return
        log.row(id, "playback", id, "playing", why, false)
        end(x, EndReason.ERROR)
    }

    private fun reject(id: String, why: String) {
        audio.abandonFocus()
        log.row(id, "playback", id, "playing", why, false)
        post(PlaybackRejected(clock(), id, RejectReason.ERROR))
    }

    private fun end(x: Cur, reason: EndReason) {
        if (cur !== x) return
        cur = null
        x.timers.forEach { it() }
        x.track.release()
        audio.abandonFocus()
        log.row(x.id, "stopPlayback", x.id, reason.name, "released", reason != EndReason.ERROR)
        post(PlaybackEnded(clock(), x.id, reason))
    }

    /** 페이드 동안 RAMP_MS마다 이득을 다시 정한다. 페이드 밖에서는 타이머를 걸지 않는다. */
    private fun ramp(x: Cur) {
        if (cur !== x) return
        apply(x, "fade")
        if (clock() < fadeEnd(x)) x.timers += sched.after(RAMP_MS) { ramp(x) }
    }

    private fun fadeEnd(x: Cur) = minOf(x.endAt, x.stopFadeAt?.plus(x.fadeMs) ?: Long.MAX_VALUE)

    private fun gainOf(x: Cur): Float {
        val t = clock()
        fun fade(endAt: Long) = if (x.fadeMs <= 0) (if (t < endAt) 1f else 0f) else ((endAt - t).toFloat() / x.fadeMs).coerceIn(0f, 1f)
        var g = x.base * fade(x.endAt)
        x.stopFadeAt?.let { g = minOf(g, x.base * fade(it + x.fadeMs)) }
        if (x.ducked) g *= DUCK_GAIN
        return g.coerceAtMost(p.maxGain.toFloat())
    }

    /** 이득 설정이 던졌다: 시작 중이면 트랙·포커스를 놓고 거절, 시작을 알렸으면 ERROR로 끝낸다(결과는 한 번). */
    private fun gainFailed(x: Cur, e: Exception) {
        if (x.announced) return failed(x.id, "setVolume ${e.message}")
        cur = null
        x.timers.forEach { it() }
        runCatching { x.track.release() }
        reject(x.id, "setVolume ${e.message}")
    }

    /** 이득을 바꿀 때마다(페이드 단계 포함) control_log 한 줄(C96: api AudioTrack.setVolume, goal 이득, actual 이유). 경로 미정이면 정하지 않는다. */
    private fun apply(x: Cur, why: String) {
        if (x.pendingRoute) return
        val g = gainOf(x)
        if (g == x.gain) return
        x.gain = g
        try { x.track.gain(g) } catch (e: Exception) { return gainFailed(x, e) }
        log.row(x.id, "AudioTrack.setVolume", x.id, String.format(Locale.ROOT, "%.3f", g), why, true)
    }

    companion object {
        const val RAMP_MS = 50L
        /**
         * 경로 미정(null)인 뒷좌석 재생이 확정을 기다리는 시간(그동안 무음). 설계값. 11-04 에뮬레이터 실측(자장가 MediaPlayer, 포커스 → 경로):
         * 보통 0.08~0.24초, 설치 직후 첫 재생 0.72초, 한 번은 1초 안에 정해지지 않아 ERROR로 끝났다(디코더 준비만 start() +0.55초). 그래서 3배 여유.
         */
        const val ROUTE_CONFIRM_MS = 3_000L
        /** CAN_DUCK 때 줄이는 배수. 설계값, 근거 없음(14장에 감쇄 크기가 없다). 첫날 들어 보고 정한다. */
        const val DUCK_GAIN = 0.5f
    }
}
