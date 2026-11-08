package kr.aigyeot.engine

// ④ 묻기(설계 3.4 Qa): 듣기 창(AskButton·Q1), Heard·QaReply, 도구 사건 문맥(Q7), 규칙 문장(ruleSay), 돌봄 요약이 바뀐 답 버리기, 질문 창 시간 끝(Q3).
// 주인 칸(설계 3.3, D7): speech.qa. 쓰기는 이 파일의 private setQa() 하나다. Heard·QaReply는 waitingFor로 그 듣기 id의 창만 쓴다(P5).

private val STOP_WORDS = setOf("그만", "그만해", "그만틀어", "내가할게")   // Q4: plainWord로 견준다(C109)

private fun Tx.setQa(w: QaWindow?) { s = s.copy(speech = s.speech.copy(qa = w)) }

/** C196: 질문 창은 두고(도구가 Q7로 버려지지 않게) 옛 돌봄 요약 표시만 한다. 말하지 않은 ④ 답 줄은 뺀다(말하던 맨 앞 줄은 둔다). */
internal fun Tx.dropQa() {
    s.speech.qa?.let { if (it.step == QaStep.THINKING) setQa(it.copy(careStale = true)) }
    dropQueued { it.sid == Sid.QA && it.careUsed }
}

/** Q3: 질문 창 시간이 끝났다(Router.timers). */
internal fun Tx.qaTimer() { s.speech.qa?.let { if (now >= it.until) setQa(null) } }

/** 수명 표 QaTurn 행의 CUT·DROP. */
internal fun Tx.closeQa() = setQa(null)

/** Q5d: 듣는 도중 자장가·부모 노래가 시작됐다(Soothe.started). */
internal fun Tx.songStarted() { s.speech.qa?.let { if (it.step == QaStep.HEARING) setQa(it.copy(songHeard = true)) } }

/** Q5a: ④ 질문의 도구 사건이면 규칙 문장이 답이라 LLM 글을 말하지 않게 표시한다. */
internal fun Tx.markRuleSaid() { if (qaTool) s.speech.qa?.let { setQa(it.copy(ruleSaid = true)) } }

// ---- ④ 묻기(Q행) ----

internal fun Tx.tool(listenId: String?): Boolean {                                                         // Q7
    if (listenId == null) return true
    val qa = s.speech.qa
    if (qa == null || qa.step != QaStep.THINKING || qa.listenId != listenId) return false
    qaTool = true
    return true
}

internal fun Tx.listen() {
    val id = newId()
    setQa(QaWindow(id, QaStep.HEARING, now + p.qaListenMs + 1_000, false, ses?.play?.method.isSong()))
    out += Listen(id, p.qaListenMs)
}

internal fun Tx.heard(e: Heard) {
    val qa = s.waitingFor(e) ?: return                                                          // Q7
    val text = heardText(e.text) ?: return setQa(null) // Q3
    if (plainWord(text) in STOP_WORDS) { setQa(null); return driverStop() } // Q4
    if (qa.songHeard || ses?.play?.method.isSong()) return setQa(null)  // Q5d: 노래 가사를 질문으로 받지 않는다
    setQa(qa.copy(step = QaStep.THINKING, until = now + p.qaReplyWaitMs + 1_000))              // Q5
    out += Qa(e.listenId, text)
}

internal fun Tx.qaReply(e: QaReply) {                                                                      // Q6
    val qa = s.waitingFor(e) ?: return
    setQa(null)
    // C218: 돌봄 요약이 바뀌었으면 옛 상태로 쓴 LLM 글만 버리고, link가 함께 보낸 대체 글을 말한다
    val drop = qa.careStale && e.llm
    val text = if (drop) e.fallback else e.text
    if (!qa.ruleSaid && text.isNotBlank()) say(Line(Sid.QA, mapOf("text" to text), null, qaAnswer = true, careUsed = if (drop) e.fallbackCare else e.careUsed))
}

/** 규칙 문장. ④ 질문의 답이면 qaAnswer로 맨 앞에 넣고 LLM 글을 버리게 표시한다(Q5a). */
internal fun Tx.ruleSay(sid: Sid) {
    markRuleSaid()
    say(Line(sid, emptyMap(), null, qaAnswer = qaTool))
}
