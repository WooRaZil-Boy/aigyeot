package kr.aigyeot.app.exec

import kr.aigyeot.app.link.eul
import kr.aigyeot.app.link.jong
import kr.aigyeot.engine.Answer
import kr.aigyeot.engine.Ask
import kr.aigyeot.engine.heardText
import kr.aigyeot.engine.plainWord
import kr.aigyeot.engine.Event
import kr.aigyeot.engine.Heard
import kr.aigyeot.engine.Listen
import kr.aigyeot.engine.Sid
import kr.aigyeot.engine.Speak
import kr.aigyeot.engine.SpeechDone
import kr.aigyeot.engine.YesNo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 차가 말하기 시작한 문장(화면 표시용, 계약 차 2.5). [at]은 말하기를 시작한 시각. 말하지 못하고 끝난 문장(TTS 실패·포커스 거절·시간 초과)은 거둔다. */
data class Said(val sid: Sid, val text: String, val at: Long)
/** 듣기 창 [listenId]가 받은 글(물어보기 질문 표시용). */
data class HeardText(val listenId: String, val text: String, val at: Long)

/**
 * 운전자 한 문장(Speak·Ask)과 듣기(Ask의 '응/아니', Listen). 판단은 하지 않는다: 문장 간격·통화·큐는 판단 코어 몫(계약 4.1 말하기 규칙).
 * 듣기는 마이크 서비스 PCM을 STT에 그대로 넣는다(명세서 6장). 마이크 서비스가 없으면 바로 '답 없음'이다.
 */
internal class Voice(
    private val tts: TtsPort,
    private val stt: SttPort,
    private val tap: MicTap,
    private val sched: Sched,
    private val clock: () -> Long,
    private val post: (Event) -> Unit,
    private val micRunning: () -> Boolean,
) {
    private var speaking: String? = null
    private val _said = MutableStateFlow<Said?>(null)
    private val _heard = MutableStateFlow<HeardText?>(null)
    /** 화면용 읽기 투영(디자인 v6 홈 '차가 방금 말한 문장'·물어보기 질문, 계약 차 2.5). 판단에는 쓰지 않는다. */
    val said: StateFlow<Said?> = _said
    val heard: StateFlow<HeardText?> = _heard
    private var lastDone: Triple<String, Boolean, Long>? = null   // 마지막으로 끝난 발화·spoken·실제 완료 시각(C208·C212)
    private var watchdog: () -> Unit = {}
    private var hearing: Hearing? = null

    private inner class Hearing(val deliver: (String?) -> Unit) {
        var text: String? = null
        var closed = false
        var gotFinal = false                       // STT 최종 결과(오류 포함)를 받았다
        val finals = mutableListOf<String>()       // 비지 않은 최종 결과(온 순서, C111: 한 창의 최종 결과를 이어 하나로 낸다)
        val timers = mutableListOf<() -> Unit>()
    }

    /** 지금 말하는 문장의 화면 표시를 거두는 일(끝나면 비운다). 끊김(cancel·cancelIfSpeaking)은 완료 콜백을 버리므로 여기서 거둔다. */
    private var unshow: (() -> Unit)? = null

    /** 말하기를 시작하며 화면에 문장을 낸다(말하는 동안 ④ 답·안내가 보이게). 말하지 못하고 끝나거나 끊기면 앞 값으로 되돌린다. */
    private fun show(sid: Sid, text: String): (Boolean) -> Unit {
        val prev = _said.value
        val mine = Said(sid, text, clock()).also { _said.value = it }
        val undo = { if (_said.value === mine) _said.value = prev }
        unshow = undo
        return { spoken -> if (unshow === undo) unshow = null; if (!spoken) undo() }
    }

    fun speak(c: Speak) { val t = Lines.text(c.sid, c.vars); say(c.utteranceId, t, show(c.sid, t)) }

    fun ask(c: Ask) {
        val t = Lines.text(c.sid, c.vars)
        val shown = show(c.sid, t)
        say(c.askId, t) { spoken ->
            shown(spoken)
            if (!spoken || !micRunning()) post(Answer(clock(), c.askId, YesNo.NONE))
            else hear(c.listenMs) { post(Answer(clock(), c.askId, yesNo(it))) }
        }
    }

    fun listen(c: Listen) {
        if (!micRunning()) return post(Heard(clock(), c.listenId, null))
        hear(c.ms) { text -> val h = heardText(text); h?.let { _heard.value = HeardText(c.listenId, it, clock()) }; post(Heard(clock(), c.listenId, h)) }   // C303 A22: Q3 두 자 미만은 판단 코어와 같은 함수. 화면과 사건이 같은 값
    }

    /** CancelSpeech: 말하기와 듣기를 끊는다. 돌려줄 사건은 없다(계약 4.1). */
    fun cancel() {
        if (speaking != null) { speaking = null; watchdog(); tts.stop(); unshow?.invoke(); unshow = null }
        hearing?.let { h -> hearing = null; h.timers.forEach { it() }; tap.close(); stt.cancel() }
    }

    /** C208: [id]가 아직 말하는 중이면 끊고 null('끊음'), 이미 끝났으면 그 spoken(SpeechDone이 호스트 큐에 있다). done 콜백과 같은 잠금 안에서 부른다. */
    fun cancelIfSpeaking(id: String): Pair<Boolean, Long>? {
        if (speaking == id) { cancel(); return null }
        return lastDone?.takeIf { it.first == id }?.let { it.second to it.third }   // C212: 완료 시각도 돌려준다
    }

    private fun say(id: String, text: String, then: (Boolean) -> Unit) {
        if (speaking != null) { watchdog(); tts.stop() }          // 판단 코어는 하나씩 내지만, 남은 문장이 있으면 끊고 새 문장을 말한다
        speaking = id
        fun done(spoken: Boolean) {
            if (speaking != id) return
            speaking = null
            val at = clock()
            lastDone = Triple(id, spoken, at)
            watchdog()
            post(SpeechDone(at, id, spoken))
            then(spoken)
        }
        watchdog = sched.after(SPEECH_TIMEOUT_MS) { if (speaking == id) { tts.stop(); done(false) } }
        try { tts.speak(text) { done(it) } } catch (e: Exception) { done(false) }
    }

    /**
     * [ms] 동안 PCM을 넣고 끝을 알린 뒤, 창이 닫혀도 바로 넘기지 않고 [STT_FINAL_GRACE_MS] 유예가 끝날 때 한 번 넘긴다.
     * 넘기는 글은 비지 않은 최종 결과를 온 순서대로 띄어 이은 것이고, 최종이 없으면 마지막 부분 인식이다.
     */
    private fun hear(ms: Long, deliver: (String?) -> Unit) {
        hearing?.let { old -> hearing = null; old.timers.forEach { it() }; tap.close(); stt.cancel(); old.deliver(null) }
        val h = Hearing(deliver).also { hearing = it }
        fun finish() {
            if (hearing !== h) return
            hearing = null
            h.timers.forEach { it() }
            tap.close()
            deliver(h.text)
        }
        try {
            stt.begin { text, final ->
                if (hearing !== h) return@begin
                if (final) {   // 최종이 하나라도 왔으면 부분 인식은 버린다(4.3 '답이 없으면 아무것도 안 한다'). 빈 최종은 앞의 최종을 지우지 않는다
                    h.gotFinal = true
                    if (text.isNotBlank()) h.finals += text.trim()
                    h.text = h.finals.joinToString(" ").takeIf { it.isNotEmpty() }
                }
                else if (text.isNotBlank() && !h.gotFinal) h.text = text
                // 창이 닫힌 뒤에도 바로 끝내지 않는다: 유예 중 더 올 최종 결과까지 모아 유예가 끝날 때 한 번 낸다(C111)
            }
            tap.open { pcm -> if (hearing === h && !h.closed) stt.feed(pcm) }
        } catch (e: Exception) {                                   // 시작에서 던지면 그 듣기를 빈 결과로 끝낸다
            hearing = null
            runCatching { tap.close() }; runCatching { stt.cancel() }
            return deliver(null)
        }
        h.timers += sched.after(ms) {
            if (hearing !== h) return@after
            h.closed = true
            tap.close()
            stt.finish()
            h.timers += sched.after(STT_FINAL_GRACE_MS) {
                if (hearing === h && !h.gotFinal) stt.cancel()     // 최종 없이 끝나면 마지막 부분 인식을 쓰되 묶음은 닫는다
                finish()
            }
        }
    }

    companion object {
        /** TTS 끝 신호가 오지 않을 때 문장 큐가 영원히 막히지 않게. 설계값, 근거 없음(가장 긴 S07_DEPART도 10초 안팎). */
        const val SPEECH_TIMEOUT_MS = 20_000L
        /** 듣기 창이 끝난 뒤 STT 최종 결과를 기다리는 시간. 판단 코어가 듣기 창 끝에 1초 여유를 두므로(Q3, 계약 Q행) 그 안에 Heard가 닿게. */
        const val STT_FINAL_GRACE_MS = 800L

        private val NO = listOf("아니", "아뇨", "싫", "됐어", "괜찮", "안들", "안넣", "안가", "안갈", "안해", "안돼", "지마", "말고", "그냥")
        private val YES = setOf("응", "네", "예", "그래", "좋아", "들러", "들르자", "들러줘", "넣어", "넣어줘", "오케이", "콜", "그러자", "좋지", "주세", "줘")

        /**
         * '응/아니'(4.3). (a) 공백·부호를 뺀 전체 글에 부정·망설임 조각이 하나라도 있으면 NO.
         * (b) 아니면 낱말마다 부호와 끝의 '요'를 떼어 모든 낱말이 긍정 낱말일 때만 YES. (c) 그 밖은 NONE.
         * 잘못 YES는 경유지를 넣고 잘못 NO·NONE은 아무것도 안 하므로 망설이면 NO·NONE 쪽이다. 말 목록은 설계값이다(명세서에 '응/아니'만 있다).
         */
        fun yesNo(text: String?): YesNo {
            if (text == null) return YesNo.NONE
            val all = text.filter { it.isLetterOrDigit() }
            if (NO.any { all.contains(it) }) return YesNo.NO
            val words = text.split(Regex("\\s+")).map(::plainWord).filter { it.isNotEmpty() }   // C303 B11: Q4 '그만'과 같은 정규화
            return if (words.isNotEmpty() && words.all { it in YES }) YesNo.YES else YesNo.NONE
        }
    }
}

/** 명세서 8장 문장 표. 채울 값(vars)은 판단 코어가 C29 형식으로 넣는다. 조사는 받침에 맞춘다. */
internal object Lines {
    fun text(sid: Sid, v: Map<String, String>): String {
        fun g(k: String) = v[k].orEmpty()
        val km = "${g("distanceKm")}킬로미터"
        val rest = g("restName")
        return when (sid) {
            Sid.S02 -> "뒤에서 울어요. 뒷자리에 ${g("method")} 틀었어요."
            Sid.S03 -> "아직 울어요. ${g("method")}${ro(g("method"))} 바꿨어요."
            Sid.S04 -> "실내가 ${g("tempC")}도예요. ${g("loweredC")}도 낮췄어요."
            Sid.S06 -> "수유한 지 ${g("elapsed")}이에요. 배고플 수 있어요."
            Sid.S07 -> "$km 앞 ${rest}에 수유실이 있어요. 들를까요?"
            Sid.S07_DEPART -> "${clock(g("hungryAt"))}쯤 배고플 시간이라, $km 앞 수유실 있는 $rest${eul(rest)} 경유지로 넣을까요?"
            Sid.S08 -> "$km 앞 ${rest}에서 쉬어 갈까요?"
            Sid.S09 -> "$rest${eul(rest)} 경유지로 넣었어요."
            Sid.S10 -> "경유지로 넣을 수 있는 가까운 쉼터를 못 찾았어요. 안전한 곳에 세워서 돌봐 주세요."
            Sid.S11 -> "${g("drove")}째 달리고 있어요. $km 앞 ${rest}에서 쉬어 갈까요?"
            Sid.S12 -> "도착했어요. 자고 있어도 카시트에서 꺼내 눕혀 주세요."
            Sid.S15 -> "${g("minutes")}분 뒤 도착해요. 도착해서 돌봐 주세요."
            Sid.S16 -> "뒤에 아기가 탔나요? 화면에서 '아기 탔어요'를 눌러 주세요."
            Sid.S17 -> "아직 듣지 못하고 있어요. 다음에 세웠을 때 눌러 주세요."
            Sid.S18 -> "더 틀 수 없어요. 안전한 곳에 세워서 돌봐 주세요."
            Sid.S19 -> "경유지를 넣지 못했어요."
            Sid.S20 -> "지금은 소리를 틀 수 없어요."
            Sid.QA -> g("text")
        }
    }

    /** epoch ms 문자열을 기기 시간대의 "오후 3시 10분"(분이 0이면 "오후 3시")으로. 숫자가 아니면 그대로. */
    internal fun clock(ms: String): String {
        val t = ms.toLongOrNull() ?: return ms
        val c = java.util.Calendar.getInstance().apply { timeInMillis = t }
        val h = c.get(java.util.Calendar.HOUR_OF_DAY); val m = c.get(java.util.Calendar.MINUTE)
        return "${if (h < 12) "오전" else "오후"} ${if (h % 12 == 0) 12 else h % 12}시" + if (m == 0) "" else " ${m}분"
    }

    private fun ro(w: String) = jong(w).let { if (it == 0 || it == 8) "로" else "으로" }   // 받침·을/를은 Wire 한 곳(C303 A24)
}
