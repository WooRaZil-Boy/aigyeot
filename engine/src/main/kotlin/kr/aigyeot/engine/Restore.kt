package kr.aigyeot.engine

// 복원 ②(C303 A3~A7): Room 사실(RoomFacts)로 상태를 맞춘다. 공개 입구는 Engine.restoreFromRoom(Engine.kt), 이 파일은 그 차례다.
// 주인 칸 없음(D7): 칸은 그 주인 리듀서의 함수로만 고친다 — 장부 Offer(dropLedger), 세션 닫기 Life(closeFromRoom), 재생 누적·울음 수·에피소드 Soothe(matchRoom),
// 성적 Facts(roomHistory), 말하던 줄·큐 Speech(endSpeaking·dropQueued·spoke). 복원 ③ 비우기는 RESTORE 열(Engine.afterRestore)이다(D6).

// ---- 복원 ②(C303 A3~A7, 호스트 reconcileRoomRows에서 옮김) ----

internal fun Tx.fromRoom(r: RoomFacts) {
    fun roomTold(a: HandoffLedger) = a.episodeId?.let { r.told[it] }   // Room에 이미 확정된 인계(말했는지)
    val x = ses
    // C152: 닫힌 세션(Room에서 끝난 이 세션 포함)의 장부는 Room 확정이 앞선다(flush가 false로 덮고 알림을 또 보내지 않게 지운다). 알림·확정 없음
    s.handoffs.filter { roomTold(it) != null && (it.sessionId != x?.id || r.sessionEnded) }.forEach(::dropLedger)
    if (x != null && r.sessionEnded) {
        // C137·C140·C159: closeSession의 버림만(할 일은 그때 나갔다, 말하던 줄은 ③ C123이 버린다). 이 세션의 장부는 그대로 남아 닫힌 세션의 것이 된다
        // (첫 Step의 flush가 이름 없는 알림·false·리포트를 낸다)
        closeFromRoom()
        roomHistory(r.history)   // 세션 끝과 함께 커밋된 마지막 1차 결과가 성적에서 빠지지 않게 Room에서 다시 센다(5장, C56)
    } else if (x != null) {
        // C277·C287(A3)·r14w·r14x·C133 ②: 재생 누적·울음 수 하한, Room에서 끝난 에피소드는 닫고 성적은 Room에서 다시 센다(두 번 넣지 않게)
        if (matchRoom(r)) roomHistory(r.history)
        // C136: 질문을 내며 Room에 인계 확정을 저장한 뒤 스냅숏 전에 죽었다. Room 값이 앞서니 장부와 큐 줄을 지운다(알림·확정 없음)
        openLedger()?.let { a ->
            val told = roomTold(a) ?: return@let
            dropLedger(a)
            val gone = s.speech.queue.filter { it.isHandoffOf(a.sessionId) }
            // C151·C161: 말하던 맨 앞 줄이어도 Room 값이 있으니 빼고 speaking도 비운다(③ dropSpeaking이 다음 줄을 버리지 않게, 말하기 간격은 이어 간다)
            if (s.speech.queue.firstOrNull()?.isHandoffOf(a.sessionId) == true) endSpeaking()
            dropQueued { it.isHandoffOf(a.sessionId) }   // 맨 앞 인계 줄은 위에서 뺐다(남은 맨 앞 줄은 인계 줄이 아니다)
            if (told) gone.forEach(::spoke)   // 엔진이 그때 했을 처리: 말했으면 speechDone과 같은 변화(C84·C66·C141), 아니면 버린 줄(금지 없음, 결정 ②)
        }
    }
}
