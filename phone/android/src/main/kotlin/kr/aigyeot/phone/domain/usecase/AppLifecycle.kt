package kr.aigyeot.phone.domain.usecase

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import kr.aigyeot.phone.domain.model.FeedTrigger
import kr.aigyeot.phone.domain.repository.CareRepository
import kr.aigyeot.phone.domain.repository.LinkMarks
import kr.aigyeot.phone.domain.repository.Mark
import kr.aigyeot.phone.domain.repository.MarkEdit
import kr.aigyeot.phone.domain.repository.OngoingNotice
import kr.aigyeot.phone.domain.repository.PhoneStateRepository
import kr.aigyeot.phone.domain.repository.SongFileStore
import kr.aigyeot.phone.domain.state.AppVisibility
import kr.aigyeot.phone.domain.state.LinkSwitch

/** 앱 시작 정리 순서와 앞·뒤(7.4·R8·R10·R11, 계약 2장). 순서를 바꾸지 않는다. */
class AppLifecycle(
    private val state: PhoneStateRepository,
    private val care: CareRepository,
    private val songs: SongFileStore,
    private val link: LinkSwitch,
    private val marks: LinkMarks,
    private val visibility: AppVisibility,
    private val connection: KeepConnected,
    private val outbox: SendOutbox,
    private val recordCare: RecordCare,
    private val disconnect: Disconnect,
    private val ongoing: OngoingNotice,
    private val scope: CoroutineScope,
) {
    /** 앱을 만든 뒤 한 번. 시작 화면을 정하기 전에 앞 프로세스의 끊기를 마무리한다(즉시 부분). 나머지는 메인 흐름의 다음 차례에 돈다. */
    fun start() {
        scope.launch {
            yield() // 앱을 다 만든 뒤에 돈다
            // 복구한 상태(요청 필요 표시 포함)를 바로 저장한다.
            state.saveRecoveredStart()
            // 연결이 없다고 확정되면(못 읽음이 아님) 연결 캐시는 쓸모가 없다: 끊기 정리가 중간에 죽었어도 손상 사본·녹음을 마저 지운다(7.4).
            // 새 연결(같은 잠금)과 겹쳐 새 상태 파일을 지우지 않게 잠금 안에서 다시 본다. 지우다 만 기록 지우기 표시는 남긴다.
            if (!state.credsUnreadableAtStart) link.transitionLock.withLock { if (state.creds == null) songs.purge() }
            songs.deleteLeftovers() // 앞 프로세스가 남긴 임시 녹음
            songs.clean()
            // 앱을 열 때: 지우다 만 기록을 먼저 마친다(옮기기·요약보다 먼저, R10). 수유 기록 한 번 옮기기(R11), 30일 지난 지운 기록 정리(R8).
            // U22: 옮기기 전에 state.json의 보낸 표시를 먼저 채운다. 그 뒤 차로 보낼지 본다(R6).
            recordCare.finishClear()
            recordCare.migrateIfNeeded()
            recordCare.purgeOld()
            recordCare.syncAtStart()
        }
        // 진행 중 기록이 바뀔 때만 잠금 화면 알림을 고친다(R15: 매초 갱신 없음).
        scope.launch { care.care.collect { ongoing.show(it) } }
        disconnect.recoverAtStart()
    }

    /** 화면이 앞에 왔다. */
    fun onForeground() {
        visibility.set(true)
        outbox.hurryNetworkWaits() // 앞으로 올 때 망 실패 기다림 없이 보낸다(7.5, iOS `didBecomeActive`와 같음)
        connection.start()
        // 지우다 만 기록(R10)을 먼저 마친 뒤 수유·요약을 본다(시작·iOS `didBecomeActive`와 같은 순서): 지우는 사이 적은 수유도 이번에 간다.
        scope.launch {
            recordCare.finishClear()
            recordCare.syncToCar(FeedTrigger.FOREGROUND)
        }
        scope.launch { recordCare.purgeOld() } // R8 '앱을 열 때': 프로세스가 살아 있는 채 다시 열어도 정리한다
        refreshOngoingNotice() // 시스템 설정에서 알림을 켜고 돌아왔을 수 있다(기록은 그대로라 흐름으로는 다시 뜨지 않는다)
    }

    /** 화면이 뒤로 갔다. 끊고 멈춘다. */
    fun onBackground() {
        visibility.set(false)
        outbox.cancelWake()
        connection.stop()
    }

    /** 권한을 받은 뒤 진행 중 알림을 다시 그린다. */
    fun refreshOngoingNotice() = ongoing.show(care.care.value)

    /** 알림 권한은 처음 타이머를 시작할 때 한 번만 묻는다(9장). 처음이면 참을 돌려주고 물었다고 적는다. */
    fun takeNotificationAsk(): Boolean {
        if (marks.flag(Mark.ASKED_NOTIFICATIONS)) return false
        marks.apply(MarkEdit.Flag(Mark.ASKED_NOTIFICATIONS, true))
        return true
    }
}
