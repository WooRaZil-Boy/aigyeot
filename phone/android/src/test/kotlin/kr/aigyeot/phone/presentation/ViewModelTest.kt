package kr.aigyeot.phone.presentation

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kr.aigyeot.phone.domain.model.CareEvent
import kr.aigyeot.phone.domain.model.CareFile
import kr.aigyeot.phone.domain.model.CareKind
import kr.aigyeot.phone.domain.model.Connection
import kr.aigyeot.phone.domain.model.Credentials
import kr.aigyeot.phone.domain.model.DisconnectRun
import kr.aigyeot.phone.domain.model.OutboxPayload
import kr.aigyeot.phone.domain.model.PAIR_BUSY
import kr.aigyeot.phone.domain.model.PhoneState
import kr.aigyeot.phone.domain.model.SongEntry
import kr.aigyeot.phone.domain.model.SongPhase
import kr.aigyeot.phone.domain.model.SongSend
import kr.aigyeot.phone.domain.model.outboxItem
import kr.aigyeot.phone.domain.model.songRef
import kr.aigyeot.phone.domain.repository.Mark
import kr.aigyeot.phone.domain.repository.SongRecorder
import kr.aigyeot.phone.domain.usecase.Kit
import kr.aigyeot.phone.domain.usecase.NOW
import kr.aigyeot.phone.domain.usecase.OWNER
import kr.aigyeot.phone.domain.usecase.kitTest
import kr.aigyeot.shared.DeviceInfo
import kr.aigyeot.shared.Platform
import kr.aigyeot.shared.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 화면 상태 계산(순수 함수)과 ViewModel(가짜 중계·prefs, 진짜 파일 저장소). ViewModel 범위는 시험 범위의 자식이다. */
class ViewModelTest {
    private val guardian = Credentials("c_1", "d_g", "t", Role.GUARDIAN)

    private fun Kit.vmScope() = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    // ---- UiState 계산(순수 함수) ----

    @Test
    fun `탭 모양과 끊기 끝`() {
        assertEquals(CareTabsUiState(guardian = true, disconnectDone = false), careTabsUi(Connection.Paired(guardian), DisconnectRun.Running))
        assertEquals(CareTabsUiState(guardian = false, disconnectDone = true), careTabsUi(Connection.None, DisconnectRun.Done))
        assertEquals(CareTabsUiState(guardian = false, disconnectDone = false), careTabsUi(Connection.Gone("d"), DisconnectRun.Failed("x")))
    }

    @Test
    fun `타이머는 그 기록과 연결 여부를 본다`() {
        val e = CareEvent("e1", CareKind.FEED, NOW, zone = "Asia/Seoul", createdAt = NOW, updatedAt = NOW)
        val care = CareFile(events = listOf(e))
        assertEquals(TimerUiState(e, care, connected = true, guardian = false), timerUi("e1", care, Connection.Paired(OWNER)))
        assertEquals(TimerUiState(null, care, connected = false, guardian = false), timerUi("x", care, Connection.Gone("d")))
    }

    @Test
    fun `노래 목록은 지운 곡을 빼고 고르는 중인 곡을 표시한다`() {
        val kept = SongEntry("s1", "자장가", phase = SongPhase.ACCEPTED)
        val removed = SongEntry("s2", "옛 노래", phase = SongPhase.REMOVED)
        val s = PhoneState(songs = listOf(kept, removed), outbox = listOf(outboxItem(OutboxPayload.Message("song_selected", songRef("s1")), NOW)))
        assertEquals(SongsUiState(listOf(kept), setOf("s1"), online = false), songsUi(s, online = false))
    }

    @Test
    fun `보호자 목록은 차를 빼고 인계 알림 받기는 없으면 꺼짐이다`() {
        val car = DeviceInfo("d_car", Role.CAR, Platform.ANDROID, "2025-11-05T08:00:00+09:00", null, true)
        val phone = DeviceInfo("d_p", Role.GUARDIAN, Platform.IOS, "2025-11-05T08:00:00+09:00", null, true)
        val ui = guardiansUi(PhoneState(role = Role.OWNER, devices = listOf(car, phone), myDeviceId = "d_x"), online = true, busy = false, error = null, invite = null)
        assertEquals(listOf(phone), ui.phones)
        assertFalse(ui.handoffAlerts)
    }

    // ---- ViewModel ----

    @Test
    fun `시작 화면은 GONE·완료·소개·탭 순으로 정한다`() {
        kitTest(creds = null, marks = mapOf(Mark.GONE_CLEANUP_PENDING to "d_old")) {
            assertEquals(Route.Gone, AppViewModel(state, notices, link, pair, lose, vmScope()).startRoute())
        }
        kitTest(creds = null) {
            assertEquals(Route.Intro, AppViewModel(state, notices, link, pair, lose, vmScope()).startRoute())
            link.chooseRecordsOnly()
            assertEquals(Route.Home, AppViewModel(state, notices, link, pair, lose, vmScope()).startRoute())
        }
        kitTest(marks = mapOf(Mark.PAIR_DONE to OWNER.deviceId)) {
            assertEquals(Route.Done(Role.OWNER.name), AppViewModel(state, notices, link, pair, lose, vmScope()).startRoute())
        }
    }

    @Test
    fun `화면을 다시 만들어도 지난 연결 실패 문구는 남고 새로 연 화면에서만 비운다`() = kitTest(creds = null) {
        relay.pairResult = kr.aigyeot.phone.domain.repository.RelayResult.Err(400, "code_invalid", null)
        pair.start("123456")
        until("실패") { pair.run.value is kr.aigyeot.phone.domain.model.PairRun.Failed }
        val vm = AppViewModel(state, notices, link, pair, lose, vmScope()) // 시스템이 화면만 없앤 뒤 다시 만듦
        settle()
        assertTrue(vm.ui.value.pairRun is kr.aigyeot.phone.domain.model.PairRun.Failed)
        vm.clearStalePairRun() // 새로 연 화면(MainActivity, 저장된 상태 없음)
        until("비움") { vm.ui.value.pairRun == null }
    }

    @Test
    fun `연결 링크는 형식이 틀리면 알리고 맞으면 확인 화면 코드로 둔다`() = kitTest {
        val vm = AppViewModel(state, notices, link, pair, lose, vmScope())
        assertEquals("올바른 연결 링크가 아니에요", vm.onLink("https://example.invalid"))
        assertNull(vm.onLink("aigyeot://pair?code=123456"))
        until("코드") { vm.ui.value.pendingCode == "123456" }
        vm.consumePendingCode()
        until("비움") { vm.ui.value.pendingCode == null }
        vm.onAlert("not-a-ulid")
        settle()
        assertNull(vm.ui.value.openAlert)
        assertTrue(PAIR_BUSY.isNotEmpty())
    }

    @Test
    fun `띠는 안내를 보이고 닫으면 걷는다`() = kitTest {
        val vm = StatusViewModel(connection, notices, outbox, vmScope())
        notices.saveFailed()
        until("띠") { vm.ui.value.notice != null }
        vm.dismissNotice()
        until("닫음") { vm.ui.value.notice == null }
        assertTrue(vm.ui.value.status.online)
    }

    @Test
    fun `시트 저장은 저장·지워짐·실패를 나눈다`() = kitTest {
        val vm = CareSheetViewModel(care, notices, recordCare, vmScope())
        val e = CareEvent("01JXSHEET0000000000000000A", CareKind.NOTE, NOW - 60_000, zone = "Asia/Seoul", note = "트림", createdAt = NOW, updatedAt = NOW)
        assertEquals(SheetSave.Saved, vm.save(e, editing = false))
        recordCare.delete(e.id)
        until("지움") { care.care.value.events.single().deletedAt != null }
        assertEquals(SheetSave.Gone, vm.save(e.copy(note = "고침"), editing = true))
    }

    private class FakeRecorder : SongRecorder {
        var recording = false
        override fun start(file: File, onLimit: () -> Unit) = true.also { recording = true; file.writeText("audio") }
        override fun stop() = recording.also { recording = false }
        override fun play(file: File, onDone: () -> Unit) = file.exists()
        override fun stopPlaying() = Unit
        override fun durationS(file: File) = 12.0
    }

    private fun Kit.songsVm(saved: SavedStateHandle = SavedStateHandle()) =
        SongsViewModel(state, connection, recordSong, selectSong, removeSong, songs, FakeRecorder(), clock, saved, vmScope())

    @Test
    fun `시스템이 화면만 없앤 뒤 다시 만든 녹음 시트는 끝낸 녹음을 이어받는다`() = kitTest {
        val saved = SavedStateHandle()
        val first = songsVm(saved)
        first.openSheet()
        first.toggleRecording()
        now += 5_000
        first.toggleRecording()
        until("녹음 끝") { first.sheetUi.value.take == Take.Recorded(5_000) }
        val file = first.sheetUi.value.file
        // 같은 저장 상태로 새 ViewModel(옛 rememberSaveable과 같다): 같은 파일, 끝낸 녹음.
        val again = songsVm(saved)
        again.openSheet()
        until("이어받음") { again.sheetUi.value.file == file && again.sheetUi.value.take == Take.Recorded(5_000) }
    }

    @Test
    fun `프로세스가 죽은 뒤 되살린 녹음 시트는 앞 프로세스 녹음을 버리고 새 이름으로 처음부터`() {
        kitTest(prepare = { File(it, "cache").mkdirs(); File(it, "cache/recording-old.m4a").writeText("old") }) {
            val old = File(dir, "cache/recording-old.m4a")
            val vm = songsVm(SavedStateHandle(mapOf("song.sheet.file" to old.path, "song.sheet.done" to true, "song.sheet.ms" to 9_000L)))
            until("새 파일") { vm.sheetUi.value.file != null && vm.sheetUi.value.file != old }
            assertEquals(Take.Idle(), vm.sheetUi.value.take)
        }
    }

    @Test
    fun `녹음 시트는 녹음·멈춤·보내기를 거쳐 커밋되면 닫힌다`() = kitTest {
        val vm = songsVm()
        vm.openSheet()
        until("시트 파일") { vm.sheetUi.value.file != null }
        vm.toggleRecording()
        until("녹음 중") { vm.sheetUi.value.take is Take.Recording }
        now += 12_000
        vm.toggleRecording()
        until("녹음 끝") { vm.sheetUi.value.take == Take.Recorded(12_000) }
        vm.send("자장가", replacing = null)
        until("보내기 끝") { vm.sheetUi.value.send == SongSend.Sent }
        assertTrue(vm.takeSendResult())
        assertEquals(listOf("자장가"), state.state.value.songs.map { it.name })
        until("보낼 함이 노래를 올리고 보냄") { relay.sent.any { it.type == "song_added" } }
        vm.closeSheet()
        until("시트 닫음") { vm.sheetUi.value.file == null }
    }
}
