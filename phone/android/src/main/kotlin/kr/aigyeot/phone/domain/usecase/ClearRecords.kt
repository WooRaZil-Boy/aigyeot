package kr.aigyeot.phone.domain.usecase

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kr.aigyeot.phone.domain.model.clearedRecords
import kr.aigyeot.phone.domain.model.idsToClear
import kr.aigyeot.phone.domain.repository.CareRepository
import kr.aigyeot.phone.domain.repository.Clock
import kr.aigyeot.phone.domain.repository.PhoneStateRepository
import kr.aigyeot.phone.domain.repository.SongFileStore
import kr.aigyeot.phone.domain.state.Notices
import java.util.concurrent.atomic.AtomicInteger

/**
 * '이 휴대폰의 기록 지우기'(7.4·R10): 커밋 → 파일 정리 → 기기 목록 확인과 상태 요청 판단. 돌봄 기록도 지운다.
 * 연결 상태를 먼저 지운다: 돌봄 기록만 지워진 채 죽으면 남은 수유 전송 기록이 다시 옮겨 올 수 있다. 연결이 없으면 돌봄 기록만.
 */
class ClearRecords(
    private val care: CareRepository,
    private val state: PhoneStateRepository,
    private val recordCare: RecordCare,
    private val notices: Notices,
    private val songs: SongFileStore,
    private val connection: KeepConnected,
    private val outbox: SendOutbox,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {
    /** 누른 횟수. 노래 보내기가 시작할 때 보고 커밋할 때 다시 본다(지운 뒤 노래를 되살리지 않게, [RecordSong]). */
    private val gen = AtomicInteger(0)

    fun generation(): Int = gen.get()

    operator fun invoke() {
        val pressedAt = clock.now() // 누른 순간: 잠금을 기다리는 사이 새로 한 녹음은 지우지 않게
        val shown = care.care.value.events.mapTo(HashSet()) { it.id } // 누를 때 보이던 기록은 시각과 상관없이 지운다
        gen.incrementAndGet() // 이 전에 시작한 노래 보내기는 지운 뒤 노래를 되살리지 않는다
        scope.launch {
            val c = state.creds
            // 돌봄 기록은 이 순간의 id만 지운다(R10). 연결이 없거나 자격을 못 읽었어도 이 폰의 상태 파일·녹음은 지운다(7.4).
            // 지울 표시를 저장할 때까지 돌봄 잠금을 쥔다: 그 사이 수유 옮기기(R11)의 저장이 끼어 옛 수유를 넣으면 지울 id에 없어 남는다.
            // 잠금 순서는 돌봄 잠금 → 상태 잠금 하나뿐이다. 연결과 따로 최신 상태에 적용한다: 끊기와 겹쳐 자격이 바뀌어도 지우기가 조용히 취소되지 않게.
            val marked = care.locked {
                val ids = care.care.value.idsToClear(shown, pressedAt)
                state.commitLocal { it.clearedRecords(ids, at = pressedAt, sending = outbox.sending) }
            }
            if (!marked) return@launch
            notices.clearBanner() // 지운 알림 본문·상세로 가는 '보기'를 막는다
            // 지운 기록은 id째 빠져 되돌려도 되살아나지 않으니 되돌리기 띠도 걷는다(U38 11-09 보충, iOS `recordsCleared`와 같음).
            // 지울 표시를 저장한 뒤라 그 사이 맞추기는 보내지 않는다. 띠 시간은 그대로 둬도 [RecordCare]의 끝이 다른 띠로 보고 그냥 끝난다.
            notices.clearUndo()
            notices.pruneFeedingDrop(state.state.value.unseenDrops) // 지운 수유의 버림 안내 줄도 띠에서 걷는다
            state.deleteRecoveryCopy() // 사용자가 기록을 지웠으니 옮겨 둔 상태도 지운다
            songs.clean()
            recordCare.finishClear() // 지울 id를 care.json에서 빼고, 사본·누른 때까지의 임시 녹음·CSV를 지우고, 빈 요약을 보낸다
            if (c != null && state.isCurrent(c)) {
                connection.refreshDevices(c)
                outbox.pump()
            }
        }
    }
}
