package kr.aigyeot.phone.presentation

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kr.aigyeot.phone.domain.model.APP_ZONE
import kr.aigyeot.phone.domain.model.CareCsv
import kr.aigyeot.phone.domain.model.CareEvent
import kr.aigyeot.phone.domain.model.CareFile
import kr.aigyeot.phone.domain.model.CareKind
import kr.aigyeot.phone.domain.model.CareUndo
import kr.aigyeot.phone.domain.model.HOUR
import kr.aigyeot.phone.domain.model.FeedSide
import kr.aigyeot.phone.domain.model.MINUTE
import kr.aigyeot.phone.domain.model.onDate
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
        assertEquals(e, timerUi("e1", care, Connection.Paired(OWNER)).event)
        assertEquals(TimerUiState("e1", care, connected = true, guardian = false), timerUi("e1", care, Connection.Paired(OWNER)))
        assertEquals(null, timerUi("x", care, Connection.Gone("d")).event)
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
    fun `시트는 입력·검사·저장 단계를 ViewModel이 갖고 지워진 기록은 되살리지 않는다`() = kitTest {
        val saved = SavedStateHandle()
        val vm = CareSheetViewModel(care, notices, recordCare, clock, saved, vmScope())
        // 새 메모: 글이 없으면 저장하지 않는다. 저장하면 시트 id가 기록 id이고 닫힌다.
        vm.open("01JXSHEET0000000000000000A", CareSheetTarget.New(CareKind.NOTE, day = null))
        vm.save()
        assertEquals(CareSheetViewModel.Phase.Editing, vm.sheet.value?.phase)
        vm.setNote("트림")
        vm.save()
        until("저장") { vm.sheet.value?.phase == CareSheetViewModel.Phase.Closed }
        assertEquals("01JXSHEET0000000000000000A", care.care.value.events.single().id)
        // 화면을 다시 만들면(같은 시트 id) 적던 것을 이어 쓰고, 프로세스가 죽은 뒤에도 화면 저장 상태에서 되살린다.
        vm.close("01JXSHEET0000000000000000A")
        vm.open("s2", CareSheetTarget.New(CareKind.NOTE, day = null))
        vm.setNote("반쯤 적음")
        vm.open("s2", CareSheetTarget.New(CareKind.NOTE, day = null))
        assertEquals("반쯤 적음", vm.sheet.value?.draft?.note)
        assertEquals("반쯤 적음", CareSheetViewModel(care, notices, recordCare, clock, saved, vmScope()).sheet.value?.draft?.note)
        // 저장 중에 닫고 새 시트를 열면 늦게 온 앞 시트의 저장 결과는 새 시트를 닫지 않는다.
        vm.save()
        assertEquals(CareSheetViewModel.Phase.Saving, vm.sheet.value?.phase)
        vm.close("s2")
        vm.open("s3", CareSheetTarget.New(CareKind.NOTE, day = null))
        until("앞 시트 저장") { care.care.value.events.size == 2 }
        settle()
        assertEquals("s3", vm.sheet.value?.sheetId)
        assertEquals(CareSheetViewModel.Phase.Editing, vm.sheet.value?.phase)
        vm.close("s3")
        // 고치던 기록이 그사이 지워졌으면 저장하지 않고 닫는다(되살리지 않는다, R10).
        val e = care.care.value.events.first()
        vm.open("s4", CareSheetTarget.Edit(e.id))
        recordCare.delete(e.id)
        until("지움") { care.care.value.events.first { it.id == e.id }.deletedAt != null }
        vm.setNote("고침")
        vm.save()
        assertEquals(CareSheetViewModel.Phase.Closed, vm.sheet.value?.phase)
        assertTrue(care.care.value.events.first { it.id == e.id }.deletedAt != null)
    }

    @Test
    fun `시트와 타이머는 같은 검사로 미래 시각을 막고 겹침은 확인을 기다린다`() = kitTest {
        val vm = CareSheetViewModel(care, notices, recordCare, clock, SavedStateHandle(), vmScope())
        // 먼저 수유 하나(30분 전~10분 전)
        vm.open("a", CareSheetTarget.New(CareKind.FEED, day = null))
        vm.pick(30)
        vm.setEnd(java.time.Instant.ofEpochMilli(NOW - 10 * 60_000).atZone(APP_ZONE).hour, java.time.Instant.ofEpochMilli(NOW - 10 * 60_000).atZone(APP_ZONE).minute)
        vm.save()
        until("첫 수유") { vm.sheet.value?.phase == CareSheetViewModel.Phase.Closed }
        vm.close("a")
        // 겹치는 새 수유(15분 전 시작, 끝 모름)는 확인을 기다리고, '그래도 저장'이면 저장한다.
        vm.open("b", CareSheetTarget.New(CareKind.FEED, day = null))
        vm.pick(15)
        vm.save()
        assertTrue(vm.sheet.value?.phase is CareSheetViewModel.Phase.ConfirmOverlap)
        vm.keepEditing()
        assertEquals(CareSheetViewModel.Phase.Editing, vm.sheet.value?.phase)
        vm.save()
        vm.save(allowOverlap = true)
        until("겹쳐도 저장") { vm.sheet.value?.phase == CareSheetViewModel.Phase.Closed }
        assertEquals(2, care.care.value.events.size)
        // 타이머: 시작 시각을 앞으로 옮기면 R3 글이고 저장하지 않는다.
        recordCare.start(CareKind.SLEEP)
        until("잠 시작") { care.care.value.events.any { it.kind == CareKind.SLEEP } }
        val sleep = care.care.value.events.first { it.kind == CareKind.SLEEP }
        val timer = TimerViewModel(sleep.id, state, care, recordCare, clock, vmScope())
        val later = java.time.Instant.ofEpochMilli(NOW + 2 * HOUR).atZone(APP_ZONE)
        timer.setTime(TimerViewModel.TimeField.END, later.hour, later.minute)
        assertEquals("앞으로의 시각은 적을 수 없어요", (timer.phase.value as CareSheetViewModel.Phase.Failed).message)
    }

    @Test
    fun `타이머는 쪽과 양을 바로 얹고 시작 시각은 검사해 겹치면 확인 뒤 저장한다`() = kitTest {
        // 끝난 수유 하나(50분 전~40분 전)
        val past = CareEvent("p", CareKind.FEED, NOW - 50 * MINUTE, endAt = NOW - 40 * MINUTE, zone = APP_ZONE.id, createdAt = NOW, updatedAt = NOW)
        assertTrue(recordCare.saveEvent(past))
        recordCare.start(CareKind.FEED)
        until("수유 시작") { care.care.value.events.any { it.id != "p" } }
        val id = care.care.value.events.first { it.id != "p" }.id
        val timer = TimerViewModel(id, state, care, recordCare, clock, vmScope())
        fun e() = care.care.value.events.first { it.id == id }
        timer.setSide(FeedSide.BOTTLE)
        until("젖병") { e().feedSide == FeedSide.BOTTLE }
        timer.setAmount(60)
        until("양") { e().amountMl == 60 }
        // 10분 전으로 옮기면 겹치지 않아 바로 저장
        val ten = java.time.Instant.ofEpochMilli(NOW - 10 * MINUTE).atZone(APP_ZONE)
        timer.setTime(TimerViewModel.TimeField.START, ten.hour, ten.minute)
        until("시작 옮김") { e().startAt == onDate(ten.toLocalDate(), ten.hour, ten.minute, APP_ZONE) }
        // 45분 전으로 옮기면 앞 수유와 겹친다: 확인을 기다리고, '고치기'면 그대로, 다시 골라 '그래도 저장'이면 저장한다.
        val overlap = java.time.Instant.ofEpochMilli(NOW - 45 * MINUTE).atZone(APP_ZONE)
        timer.setTime(TimerViewModel.TimeField.START, overlap.hour, overlap.minute)
        assertTrue(timer.phase.value is CareSheetViewModel.Phase.ConfirmOverlap)
        timer.keepEditing()
        assertEquals(CareSheetViewModel.Phase.Editing, timer.phase.value)
        assertEquals(onDate(ten.toLocalDate(), ten.hour, ten.minute, APP_ZONE), e().startAt)
        timer.setTime(TimerViewModel.TimeField.START, overlap.hour, overlap.minute)
        timer.confirmTime()
        until("겹쳐도 저장") { e().startAt == onDate(overlap.toLocalDate(), overlap.hour, overlap.minute, APP_ZONE) }
        until("단계") { timer.phase.value == CareSheetViewModel.Phase.Editing }
        assertEquals(FeedSide.BOTTLE, e().feedSide)
        assertEquals(60, e().amountMl)
    }

    /** 가져오기 시험용 설정 ViewModel: 문서 주소 → 파일 내용(없으면 열지 못함). 값이 함수라 읽기를 붙잡아 둘 수 있다. */
    private fun Kit.settingsVm(files: Map<String, suspend () -> ByteArray?>) =
        SettingsViewModel(state, care, recordCare, clearRecords, disconnect, clock, { uri -> files[uri]?.invoke() }, vmScope())

    private val wall = { ms: Long -> java.time.Instant.ofEpochMilli(ms).atZone(APP_ZONE).toLocalDateTime().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) }

    private fun naraTwoChildren() = listOf(
        "Type,Profile Name,Start Date/time,Note,[Breastfeed] Left Duration (Seconds),[Breastfeed] Right Duration (Seconds),[Diaper] Type",
        "Diaper,EXAMPLE-X,${wall(NOW - HOUR)},,,,Wet",
        "Breastfeed,EXAMPLE-Y,${wall(NOW - 2 * HOUR)},EXAMPLE,300,300,",
        "Diaper,EXAMPLE-Y,${wall(NOW - 3 * HOUR)},,,,Dirty",
    ).joinToString("\n").toByteArray()

    @Test
    fun `가져오기는 아이 고르기와 미리 보기를 거쳐 저장하고 띠의 되돌리기로 뺀다`() = kitTest {
        val vm = settingsVm(
            mapOf(
                "content://unknown" to { "date,activity\n2025-11-05,feed\n".toByteArray() },
                "content://euckr" to { "Type,Start,End,Duration\n수유,,,\n".toByteArray(charset("EUC-KR")) }, // UTF-8로 풀 수 없다
                "content://big" to { ByteArray(CareCsv.MAX_BYTES + 1) },
                "content://nara" to { naraTwoChildren() },
            ),
        )
        suspend fun ended(message: String) = until(message) { vm.import.value == ImportUi.Ended(message) }
        // 끝내는 안내 셋(7.1 import.unreadable·tooLarge·failed): 글과 '닫기'만.
        val unreadable = "이 파일은 읽을 수 없어요. 아이곁·Huckleberry·Nara에서 내보낸 CSV인지 확인해 주세요"
        vm.pick("content://unknown")
        ended(unreadable)
        vm.closeImport()
        vm.pick("content://euckr")
        ended(unreadable)
        vm.pick("content://big")
        ended("파일이 너무 커요")
        vm.pick("content://gone") // 파일을 열지 못했다
        ended("가져오지 못했어요. 다시 해 주세요")
        vm.closeImport()
        assertEquals(ImportUi.Closed, vm.import.value)
        // Nara 아이 둘: 고르기 → 미리 보기 → 가져오기.
        vm.pick("content://nara")
        until("아이 고르기") { vm.import.value is ImportUi.PickChild }
        assertEquals(listOf("EXAMPLE-X", "EXAMPLE-Y"), (vm.import.value as ImportUi.PickChild).profiles)
        vm.pickChild("EXAMPLE-Y")
        until("미리 보기") { vm.import.value is ImportUi.Preview }
        val rows = (vm.import.value as ImportUi.Preview).draft.rows
        assertEquals(listOf(CareKind.DIAPER, CareKind.FEED), rows.map { it.kind })
        vm.confirmImport()
        until("저장 뒤 닫힘") { vm.import.value == ImportUi.Closed }
        assertEquals(rows.map { it.id }, care.care.value.events.map { it.id })
        // 탭의 띠가 받는 되돌리기(5초 안 '되돌리기')는 넣은 기록을 모두 뺀다.
        val tabs = CareTabsViewModel(state, notices, recordCare, disconnect, vmScope())
        val undo = notices.undo.value as CareUndo.Import
        tabs.undo(undo)
        until("되돌림") { care.care.value.events.isEmpty() }
    }

    @Test
    fun `앱 안 배너는 8초 뒤 그 배너만 닫히고 옛 배너의 시간이 새 배너를 닫지 않는다`() = kitTest {
        assertEquals(8_000L, kr.aigyeot.phone.domain.model.BANNER_MS) // 연결 계약 5.6, iOS bannerNanos와 같음
        AppViewModel(state, notices, link, pair, lose, vmScope(), bannerMs = 1_000) // 시험은 1초로 센다
        val a = kr.aigyeot.phone.domain.model.Banner("가", "01JXALERTA000000000000000A")
        val b = kr.aigyeot.phone.domain.model.Banner("나", "01JXALERTB000000000000000B")
        notices.showBanner(a)
        kotlinx.coroutines.delay(600)
        notices.showBanner(b) // a가 닫히기 전에 새 배너
        kotlinx.coroutines.delay(600) // a가 뜬 지 1.2초: a의 시간은 지났지만 b는 1.6초까지 남는다
        assertEquals(b, notices.banner.value)
        until("b가 저절로 닫힘") { notices.banner.value == null }
        // 닫는 것은 그 id일 때만: 다른 배너 id로 닫으려 하면 지금 배너는 남는다
        notices.showBanner(a)
        notices.clearBanner(b.alertId)
        assertEquals(a, notices.banner.value)
    }

    @Test
    fun `가져오기 읽기는 ViewModel이 쥐고 마지막으로 고른 파일만 미리 본다`() = kitTest {
        val slow = CompletableDeferred<Unit>()
        val one = "kind,start,end,end_unknown,side,amount_ml,diaper,note\nNOTE,${kr.aigyeot.phone.domain.model.rfc3339(NOW - HOUR)},,false,,,,EXAMPLE\n".toByteArray()
        val vm = settingsVm(
            mapOf(
                "content://slow" to { slow.await(); naraTwoChildren() },
                "content://fast" to { one },
            ),
        )
        // 읽는 중에 다시 골랐다: 먼저 고른 파일이 늦게 끝나도 마지막 파일의 미리 보기만 남는다.
        vm.pick("content://slow")
        assertEquals(ImportUi.Reading, vm.import.value)
        vm.pick("content://fast")
        until("새 파일 미리 보기") { vm.import.value is ImportUi.Preview }
        slow.complete(Unit)
        settle()
        assertEquals(listOf(CareKind.NOTE), (vm.import.value as ImportUi.Preview).draft.rows.map { it.kind })
        // 화면이 회전·사라져도(ViewModel은 그대로) 읽기는 이어져 결과가 적용된다. 화면 범위가 아니라 ViewModel 범위다.
        val held = CompletableDeferred<Unit>()
        val vm2 = settingsVm(mapOf("content://held" to { held.await(); naraTwoChildren() }))
        vm2.pick("content://held")
        settle()
        held.complete(Unit)
        until("아이 고르기") { vm2.import.value is ImportUi.PickChild }
        // 읽는 중에 닫으면 늦게 끝난 결과를 띄우지 않는다.
        val late = CompletableDeferred<Unit>()
        val vm3 = settingsVm(mapOf("content://late" to { late.await(); one }))
        vm3.pick("content://late")
        vm3.closeImport()
        late.complete(Unit)
        settle()
        assertEquals(ImportUi.Closed, vm3.import.value)
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
