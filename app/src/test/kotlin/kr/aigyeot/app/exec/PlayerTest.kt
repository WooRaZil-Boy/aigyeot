package kr.aigyeot.app.exec

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kr.aigyeot.engine.AdjustPlayback
import kr.aigyeot.engine.EndReason
import kr.aigyeot.engine.Event
import kr.aigyeot.engine.FocusChange
import kr.aigyeot.engine.FocusChanged
import kr.aigyeot.engine.Method
import kr.aigyeot.engine.MethodKind
import kr.aigyeot.engine.Params
import kr.aigyeot.engine.PlayKind
import kr.aigyeot.engine.PlayMode
import kr.aigyeot.engine.PlaybackEnded
import kr.aigyeot.engine.PlaybackRejected
import kr.aigyeot.engine.PlaybackStarted
import kr.aigyeot.engine.RejectReason
import kr.aigyeot.engine.StartPlayback
import kr.aigyeot.engine.StopPlayback

// 실행기가 지킬 재생 규칙(존·폴백·이득·끝 시각·포커스·ReleaseAudio)과 볼륨 상한
class PlayerTest {
    private val t = FakeTime()
    private val r = Rec()
    private val audio = FakeAudio()
    private var front = false
    private val pl = Player(audio, t, { t.now }, { r.events += it }, logger(t, r), { front }, Params())
    private val noise = Method(MethodKind.WHITE_NOISE)
    private fun start(id: String = "s1-1", lenMs: Long = 56_000) = pl.start(StartPlayback(id, PlayKind.AUTO, noise, t.now + lenMs, 2_000))
    private val track get() = audio.tracks.last()
    private fun volumeRows() = r.rows.filter { it.api == "AudioTrack.setVolume" && it.target == "s1-1" }.map { it.goal }

    @Test fun `뒷좌석 출력이 있으면 뒷좌석 존으로 이득 1_0에 튼다`() {
        start()
        assertEquals(listOf<Event>(PlaybackStarted(t.now, "s1-1", REAR_ZONE, fallback = false)), r.events)
        assertTrue(track.rear && track.started && audio.focusHeld)
        assertEquals(1.0f, track.gains.first(), "첫 이득이 1.0")
        assertFalse(track.gainBeforeStart, "시작 전에는 gain을 부르지 않는다")
        assertTrue(
            r.rows.any {
                it.api == "requestAudioFocus" && it.ok && it.goal.contains("USAGE_MEDIA") && it.goal.contains("GAIN_TRANSIENT")
            },
            "포커스 결과와 요청 context·gain 종류를 ControlLog에"
        )
        assertTrue(r.rows.any { it.api == "getRoutedDevice" && it.actual == REAR_ZONE && it.ok }, "출력 장치를 ControlLog에")
    }

    @Test fun `뒷좌석 출력이 없고 앞 틀기를 허락하지 않으면 틀지 않고 NO_REAR_OUTPUT`() {
        audio.rear = false
        start()
        assertEquals(listOf<Event>(PlaybackRejected(t.now, "s1-1", RejectReason.NO_REAR_OUTPUT)), r.events)
        assertEquals(0, audio.focusRequests, "포커스도 요청하지 않는다")
        assertTrue(audio.tracks.isEmpty())
    }

    @Test fun `뒷좌석 출력이 없고 앞 틀기를 허락하면 기본 존에 이득 0_3으로 튼다`() {
        audio.rear = false
        front = true
        start()
        val s = r.of<PlaybackStarted>().single()
        assertTrue(s.fallback)
        assertFalse(track.rear, "setPreferredDevice를 하지 않는다")
        assertEquals(0.3f, track.gains.last())
    }

    @Test fun `뒷좌석 장치는 있으나 실제 경로가 다른 곳이고 앞 틀기를 허락하면 첫 이득부터 0_3`() {
        audio.routed = "bus0_media_out"
        front = true
        start()
        assertTrue(audio.rear && track.rear, "뒷좌석 장치가 있어 뒷좌석으로 열었다")
        assertEquals(listOf(0.3f), track.gains, "1.0을 거치지 않는다")
        assertFalse(track.gainBeforeStart)
    }

    @Test fun `뒷좌석 장치는 있으나 실제 경로가 다른 곳이고 허락하지 않으면 소리를 내지 않는다`() {
        audio.routed = "bus0_media_out"
        start()
        assertTrue(track.gains.isEmpty(), "이득을 한 번도 정하지 않는다: ${track.gains}")
    }

    @Test fun `뒷좌석 경로가 아직 null이면 무음으로 알리고 REAR 콜백이 오면 그때 처음 1_0`() {
        audio.routed = null
        start()
        assertEquals(PlaybackStarted(t.now, "s1-1", null, fallback = false), r.of<PlaybackStarted>().single())
        assertTrue(track.started && track.gains.isEmpty(), "확인 전에는 이득을 정하지 않는다")
        pl.adjust(AdjustPlayback("s1-1", PlayMode.DUCK))
        pl.adjust(AdjustPlayback("s1-1", PlayMode.NORMAL))
        assertTrue(track.gains.isEmpty(), "그사이 모드 변경도 소리를 열지 않는다")
        audio.onRouted!!(null)
        assertTrue(track.gains.isEmpty(), "null 콜백은 확정이 아니다")
        audio.onRouted!!(REAR_ZONE)
        assertEquals(listOf(1.0f), track.gains)
        assertTrue(r.rows.any { it.api == "getRoutedDevice" && it.actual == REAR_ZONE && it.ok })
        t.advance(Player.ROUTE_CONFIRM_MS + 1)
        assertTrue(r.of<PlaybackEnded>().isEmpty(), "확정 뒤 확인 타이머가 끝내지 않는다")
        assertEquals(listOf(1.0f), track.gains, "이득은 한 번만 정해졌다")
    }

    @Test fun `확인 타이머가 REAR로 먼저 정하고 뒤늦은 콜백이 와도 이득은 한 번뿐이고 끝나지 않는다`() {
        audio.routed = null
        start()
        track.routed = REAR_ZONE
        t.advance(Player.ROUTE_CONFIRM_MS)
        assertEquals(listOf(1.0f), track.gains)
        audio.onRouted!!(REAR_ZONE)
        t.advance(Player.ROUTE_CONFIRM_MS)
        assertEquals(listOf(1.0f), track.gains, "콜백이 이득을 다시 정하지 않는다")
        assertTrue(r.of<PlaybackEnded>().isEmpty())
    }

    @Test fun `경로가 끝내 정해지지 않고 남은 시간이 확인 시간보다 짧으면 DEADLINE이 아니라 ERROR`() {
        audio.routed = null
        start(lenMs = Player.ROUTE_CONFIRM_MS - 1_000)
        t.advance(Player.ROUTE_CONFIRM_MS)
        assertTrue(track.gains.isEmpty(), "한 번도 소리를 열지 않았다: ${track.gains}")
        assertEquals(EndReason.ERROR, r.of<PlaybackEnded>().single().reason)
        assertTrue(r.rows.any { it.api == "playback" && it.actual == "routing unknown" })
        assertTrue(track.released && !audio.focusHeld)
    }

    // 에뮬레이터 장면 C: 설치 직후 부하에서 MediaPlayer는 start() 1초 뒤에도 경로가 없었다(디코더 준비만 +0.55초). 1초 확인이면 첫 자장가가 ERROR → WAIT
    @Test fun `경로가 1_5초 늦게 와도 끝내지 않고 그때 처음 1_0`() {
        audio.routed = null
        start()
        t.advance(1_500)
        assertTrue(r.of<PlaybackEnded>().isEmpty() && track.gains.isEmpty(), "확인 전: 끝나지 않고 무음")
        audio.onRouted!!(REAR_ZONE)
        assertEquals(listOf(1.0f), track.gains)
        assertTrue(r.of<PlaybackEnded>().isEmpty())
    }

    @Test fun `경로 null 뒤 다른 곳이고 허락하지 않으면 소리 없이 ERROR로 끝난다`() {
        audio.routed = null
        start()
        assertTrue(track.gains.isEmpty() && r.of<PlaybackEnded>().isEmpty(), "조건")
        audio.onRouted!!("bus0_media_out")
        assertTrue(track.gains.isEmpty(), "한 번도 소리를 열지 않았다: ${track.gains}")
        assertEquals(EndReason.ERROR, r.of<PlaybackEnded>().single().reason)
        assertTrue(track.released && !audio.focusHeld)
    }

    @Test fun `경로 null 뒤 다른 곳이고 허락하면 첫 이득이 0_3이다`() {
        audio.routed = null
        front = true
        start()
        assertTrue(track.gains.isEmpty(), "조건")
        audio.onRouted!!("bus0_media_out")
        assertEquals(listOf(0.3f), track.gains)
        assertTrue(r.of<PlaybackEnded>().isEmpty())
    }

    @Test fun `콜백 없이 확인 시간이 지나면 다시 읽어 REAR면 1_0, 아직 null이면 ERROR`() {
        audio.routed = null
        start()
        track.routed = REAR_ZONE
        t.advance(Player.ROUTE_CONFIRM_MS - 1)
        assertTrue(track.gains.isEmpty(), "확인 시간 전에는 그대로")
        t.advance(1)
        assertEquals(listOf(1.0f), track.gains)
        // 다시 읽어도 null
        r.events.clear()
        start("s1-2")
        t.advance(Player.ROUTE_CONFIRM_MS)
        assertTrue(track.gains.isEmpty())
        assertEquals(EndReason.ERROR, r.of<PlaybackEnded>().last().reason)
        assertTrue(r.rows.any { it.api == "playback" && it.actual == "routing unknown" })
    }

    @Test fun `시작 때 이득 설정이 던지면 트랙·포커스를 놓고 PlaybackRejected(ERROR) 하나만 낸다`() {
        audio.throwGain = true
        start()
        assertEquals(listOf<Event>(PlaybackRejected(t.now, "s1-1", RejectReason.ERROR)), r.events)
        assertTrue(track.released && !audio.focusHeld)
        assertEquals(0, t.pending())
        t.advance(60_000)
        assertEquals(1, r.events.size)
    }

    @Test fun `재생 중 이득 설정이 던지면 PlaybackEnded(ERROR) 하나만 낸다`() {
        start()
        assertTrue(r.of<PlaybackStarted>().size == 1 && !track.released, "조건: 재생 중")
        track.throwGain = true
        pl.adjust(AdjustPlayback("s1-1", PlayMode.DUCK))
        assertEquals(EndReason.ERROR, r.of<PlaybackEnded>().single().reason)
        assertTrue(track.released && !audio.focusHeld)
        t.advance(60_000)
        assertEquals(1, r.of<PlaybackEnded>().size)
    }

    @Test fun `포커스가 거절되면 FOCUS_DENIED이고 트랙을 열지 않는다`() {
        audio.focusOk = false
        start()
        assertEquals(listOf<Event>(PlaybackRejected(t.now, "s1-1", RejectReason.FOCUS_DENIED)), r.events)
        assertTrue(audio.tracks.isEmpty())
        assertTrue(r.rows.any { it.api == "requestAudioFocus" && !it.ok })
    }

    @Test fun `음원을 열지 못하면 ERROR이고 포커스를 돌려준다`() {
        audio.openOk = false
        start()
        assertEquals(listOf<Event>(PlaybackRejected(t.now, "s1-1", RejectReason.ERROR)), r.events)
        assertFalse(audio.focusHeld)
        assertTrue(r.rows.any { it.api == "playback" && !it.ok }, "오류를 ControlLog에(4j·42f·18n)")
    }

    @Test fun `시작 뒤 다른 곳으로 나가고 앞 틀기를 허락하지 않으면 바로 멈추고 ERROR`() {
        audio.routed = "bus0_media_out"
        start()
        assertEquals(listOf("started", "ended"), r.events.map { if (it is PlaybackStarted) "started" else "ended" })
        assertEquals(EndReason.ERROR, r.of<PlaybackEnded>().single().reason)
        assertTrue(track.released && !audio.focusHeld)
    }

    @Test fun `시작하자마자 다른 곳으로 나가고 앞 틀기를 허락하면 폴백으로 알리고 이득 0_3`() {
        audio.routed = "bus0_media_out"
        front = true
        start()
        val s = r.of<PlaybackStarted>().single()
        assertTrue(s.fallback, "D12: fallback = true로 알린다")
        assertEquals("bus0_media_out", s.routedDevice)
        assertEquals(0.3f, track.gains.last())
        assertTrue(r.of<PlaybackEnded>().isEmpty() && !track.released)
    }

    @Test fun `재생 중 경로가 바뀌고 앞 틀기를 허락하면 이득만 0_3으로 낮춘다`() {
        front = true
        start()
        audio.onRouted!!("bus0_media_out")
        assertEquals(0.3f, track.gains.last())
        assertTrue(r.of<PlaybackEnded>().isEmpty())
    }

    @Test fun `마지막 페이드 중 StopPlayback(fade=true)는 DEADLINE이 아니라 STOPPED로 끝난다`() {
        start()
        t.advance(55_000)
        pl.stop(StopPlayback("s1-1", true))
        assertTrue(r.of<PlaybackEnded>().isEmpty() && t.now < 1_056_000, "끝 시각 1초 전, 아직 안 끝남")
        t.advance(1_000) // 56초: 끝 시각 타이머가 먼저 선다
        assertEquals(PlaybackEnded(t.now, "s1-1", EndReason.STOPPED), r.events.last())
        t.advance(5_000)
        assertEquals(1, r.of<PlaybackEnded>().size)
    }

    @Test fun `끝 시각 2초 전부터 줄여 endAt에 멈추고 DEADLINE을 낸다`() {
        start()
        t.advance(53_990)
        assertEquals(1.0f, track.gains.last(), "54초 전에는 줄이지 않는다")
        t.advance(1_010) // 55.0초: 절반쯤
        assertTrue(track.gains.last() in 0.45f..0.55f, "가운데 ${track.gains.last()}")
        assertTrue(r.of<PlaybackEnded>().isEmpty())
        t.advance(1_000)
        assertEquals(PlaybackEnded(t.now, "s1-1", EndReason.DEADLINE), r.events.last())
        assertTrue(track.released && !audio.focusHeld, "포커스 반납")
        assertEquals(0, t.pending(), "끝난 뒤 남은 타이머 없음")
        assertTrue(r.rows.none { it.goal.startsWith("fade") }, "'fade 2000ms' 줄은 없다")
        assertEquals(track.gains.map { "%.3f".format(Locale.ROOT, it) }, volumeRows(), "트랙에 준 이득 기록 = AudioTrack.setVolume 줄의 goal")
        assertTrue(track.gains.size > 10, "페이드 단계도 줄로 남는다: ${track.gains.size}")
    }

    @Test fun `페이드 밖에서는 타이머를 돌리지 않는다`() {
        start()
        t.advance(10_000)
        assertEquals(2, t.pending(), "페이드 시작과 끝 두 개뿐(폴링 없음)")
    }

    @Test fun `StopPlayback(fade=false)는 바로 멈추고 STOPPED`() {
        start()
        t.advance(5_000)
        pl.stop(StopPlayback("s1-1", false))
        assertEquals(PlaybackEnded(t.now, "s1-1", EndReason.STOPPED), r.events.last())
        assertTrue(track.released)
        pl.stop(StopPlayback("s1-1", false))
        assertEquals(1, r.of<PlaybackEnded>().size, "두 번 멈춰도 한 번만")
    }

    @Test fun `StopPlayback(fade=true)는 2초 줄인 뒤 STOPPED`() {
        start()
        t.advance(5_000)
        pl.stop(StopPlayback("s1-1", true))
        t.advance(1_000)
        assertTrue(track.gains.last() in 0.45f..0.55f)
        t.advance(1_000)
        assertEquals(EndReason.STOPPED, r.of<PlaybackEnded>().single().reason)
    }

    @Test fun `모르는 재생 id의 StopPlayback은 무시한다`() {
        start()
        pl.stop(StopPlayback("s1-9", false))
        assertTrue(r.of<PlaybackEnded>().isEmpty() && !track.released)
    }

    @Test fun `감쇄가 풀리면 감쇄 직전 이득까지만 되돌리고 페이드에 들어갔으면 페이드 값`() {
        start()
        pl.adjust(AdjustPlayback("s1-1", PlayMode.DUCK))
        assertEquals(Player.DUCK_GAIN, track.gains.last())
        pl.adjust(AdjustPlayback("s1-1", PlayMode.NORMAL))
        assertEquals(1.0f, track.gains.last(), "감쇄 직전 1.0까지만")
        pl.adjust(AdjustPlayback("s1-1", PlayMode.DUCK))
        t.advance(55_000) // 감쇄 중 페이드 절반
        pl.adjust(AdjustPlayback("s1-1", PlayMode.NORMAL))
        assertTrue(track.gains.last() in 0.45f..0.55f, "페이드 값 ${track.gains.last()}")
    }

    @Test fun `이득은 1_0을 넘지 않고 감쇄 해제 말고는 재생 중 올리지 않는다`() {
        start()
        t.advance(20_000)
        pl.adjust(AdjustPlayback("s1-1", PlayMode.DUCK))
        t.advance(10_000)
        pl.adjust(AdjustPlayback("s1-1", PlayMode.PAUSE))
        t.advance(10_000)
        pl.adjust(AdjustPlayback("s1-1", PlayMode.NORMAL))
        t.advance(15_000)
        pl.adjust(AdjustPlayback("s1-1", PlayMode.DUCK))
        t.advance(10_000)
        val g = track.gains
        assertTrue(g.all { it <= 1.0f })
        val ups = g.zipWithNext().count { (a, b) -> b > a }
        assertEquals(1, ups, "PAUSE 뒤 NORMAL로 감쇄를 푼 한 번만 오른다: $g")
    }

    @Test fun `감쇄 중 일시 정지하면 NORMAL 없이 페이드에 들어가도 감쇄가 풀리지 않는다`() {
        start()
        t.advance(20_000)
        pl.adjust(AdjustPlayback("s1-1", PlayMode.DUCK))
        assertEquals(Player.DUCK_GAIN, track.gains.last())
        val from = track.gains.size - 1
        t.advance(30_000)
        pl.adjust(AdjustPlayback("s1-1", PlayMode.PAUSE))
        assertTrue(track.paused && t.now < 1_054_000, "일시 정지 중이고 페이드 전")
        t.advance(5_500) // 55.5초: 페이드 안, 끝 시각 전
        assertTrue(r.of<PlaybackEnded>().isEmpty())
        val after = track.gains.drop(from)
        assertTrue(after.size > 3, "페이드 단계가 기록됐다: $after")
        assertTrue(after.all { it <= Player.DUCK_GAIN }, "감쇄가 풀리면 안 된다: $after")
        assertEquals(0, after.zipWithNext().count { (a, b) -> b > a }, "오름 없음: $after")
    }

    @Test fun `일시 정지 뒤 DUCK이나 NORMAL이 오면 이어 틀고 감쇄는 그 모드를 따른다`() {
        start()
        pl.adjust(AdjustPlayback("s1-1", PlayMode.DUCK))
        pl.adjust(AdjustPlayback("s1-1", PlayMode.PAUSE))
        pl.adjust(AdjustPlayback("s1-1", PlayMode.DUCK))
        assertFalse(track.paused)
        assertEquals(Player.DUCK_GAIN, track.gains.last())
        pl.adjust(AdjustPlayback("s1-1", PlayMode.PAUSE))
        pl.adjust(AdjustPlayback("s1-1", PlayMode.NORMAL))
        assertFalse(track.paused)
        assertEquals(1.0f, track.gains.last())
    }

    @Test fun `일시 정지 동안에도 endAt은 그대로다`() {
        start()
        t.advance(10_000)
        pl.adjust(AdjustPlayback("s1-1", PlayMode.PAUSE))
        assertTrue(track.paused)
        t.advance(46_000)
        assertEquals(EndReason.DEADLINE, r.of<PlaybackEnded>().single().reason)
    }

    @Test fun `포커스 변화를 그 재생 id로 알린다`() {
        start()
        audio.onFocus!!(FocusChange.LOSS_TRANSIENT_CAN_DUCK)
        assertEquals(FocusChanged(t.now, "s1-1", FocusChange.LOSS_TRANSIENT_CAN_DUCK), r.events.last())
        pl.stop(StopPlayback("s1-1", false))
        r.events.clear()
        audio.onFocus!!(FocusChange.GAIN)
        assertTrue(r.events.isEmpty(), "끝난 재생의 포커스 변화는 내지 않는다")
    }

    @Test fun `끝난 재생의 영구 포커스 상실은 그 재생 id로 알린다`() {
        start()
        pl.stop(StopPlayback("s1-1", false))
        r.events.clear()
        assertTrue(r.of<PlaybackEnded>().isEmpty())
        audio.onFocus!!(FocusChange.LOSS)
        assertEquals(listOf<Event>(FocusChanged(t.now, "s1-1", FocusChange.LOSS)), r.events, "5.5 14c: GAIN·일시 상실만 버린다")
    }

    @Test fun `오디오 포트가 던지면 상태를 남기지 않고 ERROR로 거절하고 포커스를 돌려준다`() {
        audio.throwRear = true
        start()
        assertEquals(listOf<Event>(PlaybackRejected(t.now, "s1-1", RejectReason.ERROR)), r.events)
        assertEquals(0, t.pending())
        audio.throwRear = false
        audio.throwOpen = true
        r.events.clear()
        start("s1-2")
        assertEquals(listOf<Event>(PlaybackRejected(t.now, "s1-2", RejectReason.ERROR)), r.events)
        assertFalse(audio.focusHeld, "포커스를 얻었으면 돌려준다")
        audio.throwOpen = false
        audio.throwFocus = true
        r.events.clear()
        start("s1-3")
        assertEquals(listOf<Event>(PlaybackRejected(t.now, "s1-3", RejectReason.ERROR)), r.events)
        audio.throwFocus = false
        r.events.clear()
        start("s1-4")
        assertTrue(r.events.single() is PlaybackStarted, "앞 실패가 상태를 남기지 않았다")
    }

    @Test fun `ReleaseAudio는 사건 없이 트랙과 포커스를 놓고 타이머를 지운다`() {
        start()
        val before = r.events.size
        assertTrue(audio.focusHeld && t.pending() > 0, "놓기 전 조건")
        pl.release()
        assertTrue(track.released && !audio.focusHeld)
        assertTrue(r.of<PlaybackEnded>().isEmpty(), "돌아오는 사건 없음")
        assertEquals(before, r.events.size)
        assertEquals(0, t.pending(), "남은 타이머 0")
        assertTrue(r.rows.any { it.api == "stopPlayback" })
        t.advance(60_000)
        assertEquals(before, r.events.size, "끝 시각이 지나도 사건이 없다")
        pl.release() // 남은 재생이 없어도 포커스만 안전하게
    }

    @Test fun `재생 중 새 StartPlayback이 오면 앞 재생을 먼저 끝낸다`() {
        start("s1-1")
        start("s1-2")
        assertEquals(
            listOf("s1-1" to "started", "s1-1" to "ended", "s1-2" to "started"),
            r.events.map {
                when (it) {
                    is PlaybackStarted -> it.playId to "started"
                    is PlaybackEnded -> it.playId to "ended"
                    else -> "" to ""
                }
            }
        )
        assertTrue(audio.tracks[0].released && !audio.tracks[1].released)
    }

    @Test fun `재생 오류면 ERROR로 끝내고 ControlLog에 남긴다`() {
        start()
        audio.onError!!()
        assertEquals(EndReason.ERROR, r.of<PlaybackEnded>().single().reason)
        assertTrue(r.rows.any { it.api == "playback" && !it.ok })
    }

    @Test fun `ControlLog 줄에는 세션 id와 시각이 붙는다`() {
        start()
        assertTrue(r.rows.all { it.sessionId == "s1" && it.at == t.now })
    }

    @Test fun `재생 기록은 지금 세션이 없어도(세션 닫힘 뒤) 재생을 낸 세션 id`() {
        val p2 = Player(audio, t, { t.now }, { r.events += it }, ControlLogger({ t.now }, { null }, { r.rows += it }), { front }, Params())
        p2.start(StartPlayback("S9-1", PlayKind.AUTO, noise, t.now + 56_000, 2_000))
        p2.release()
        assertTrue(r.rows.size >= 3 && r.rows.all { it.sessionId == "S9" }, r.rows.map { it.api to it.sessionId }.toString())
        audio.rear = false
        p2.start(StartPlayback("S9-2", PlayKind.AUTO, noise, t.now + 56_000, 2_000)) // 뒷좌석 없음: getDevices 줄
        assertTrue(r.rows.last().api == "getDevices" && r.rows.last().sessionId == "S9")
    }
}
