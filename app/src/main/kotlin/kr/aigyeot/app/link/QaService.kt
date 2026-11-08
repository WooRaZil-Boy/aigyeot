package kr.aigyeot.app.link

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kr.aigyeot.app.link.db.LinkDao
import kr.aigyeot.app.store.bestEffortSuspend
import kr.aigyeot.app.store.db.ChildProfileEntity
import kr.aigyeot.app.store.db.needsSetup
import kr.aigyeot.engine.EngineState
import kr.aigyeot.engine.Event
import kr.aigyeot.engine.Qa
import kr.aigyeot.engine.QaReply
import kr.aigyeot.shared.LlmRequest
import kr.aigyeot.shared.LlmResponse
import kr.aigyeot.shared.LlmState

private const val TAG = "Link"

/** 상황판(8.8): 질문 하나를 AI에 묻고 3초 안에 답이 없으면 오프라인 문장. 어떤 예외가 나도 QaReply는 하나 낸다. 범주 A(프로필 읽기). */
internal class QaService(
    private val dao: LinkDao,
    private val relay: Relay,
    private val clock: () -> Long,
    private val post: (Event) -> Unit,
    private val state: () -> EngineState?,         // 최신 상태 읽기(상황판 호출에 넣는 상태 JSON, contracts-link 8.8)
    private val creds: Creds,
    private val scope: CoroutineScope,
    private val qaWaitMs: Long,
) {
    suspend fun qa(cmd: Qa) {
        var question = cmd.text
        var st = llmState(null, clock(), 0)
        var tools: List<Event> = emptyList()
        var text: String? = null
        var careUsed = true
        var llm = false; var fb = ""; var fbCare = false; var facts: List<String> = emptyList(); var fbTopics: List<String> = emptyList()
        try {
            val s = state()
            val now = clock()
            st = llmState(s, now)
            var prof: ChildProfileEntity? = null
            bestEffortSuspend("④ 프로필 읽기") { prof = dao.profile() }   // C302 A: 다 실패하면 프로필 없음(needsSetup)으로 AI에 묻지 않고 오프라인 문장
            question = llmQuestion(cmd.text, prof?.nickname)
            val methods = s?.session?.methods ?: s?.methodsNext.orEmpty()
            // 안내를 확인하지 않은 프로필은 AI에 묻지 않는다(C122)
            val call = if (prof.needsSetup()) null else scope.async { ask(question, st) }
            // 3초 안에 답이 없으면 오프라인 문장. 늦은 답과 도구는 버린다(G5)
            val r = call?.let { withTimeoutOrNull(qaWaitMs) { it.await() } }
            if (r == null) call?.cancel()
            // C218: 주제 문장은 답이 도착한 순간 상태로 짠다(LLM에는 질문 때 상태를 보냈다)
            val s2 = state()
            val now2 = clock()
            val st2 = llmState(s2, now2)
            val o = r?.let { qaEventsCare(it, cmd.listenId, now2, st2, question, methods) }
            if (o != null) { tools = o.first; text = o.second; careUsed = o.third; llm = o.llm; fb = o.fallback; fbCare = o.fallbackCare; facts = o.factTopics; fbTopics = o.fallbackTopics }
            else { facts = offlineFactTopics(question); text = if (facts.isEmpty()) offlineReply(question, st2) else ""; careUsed = offlineCareUsed(question) }   // C216
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "상황판 처리 오류", e)
        } finally {   // 어떤 예외가 나도 QaReply는 하나 낸다(8.8)
            tools.forEach(post)
            post(if (text != null) QaReply(clock(), cmd.listenId, text, careUsed, llm, fb, fbCare, facts, fbTopics) else offlineFactTopics(question).let { f -> QaReply(clock(), cmd.listenId, if (f.isEmpty()) offlineReply(question, st) else "", offlineCareUsed(question), factTopics = f) })
        }
    }

    private suspend fun ask(question: String, st: LlmState): LlmResponse? {
        val c = creds.credential() ?: return null
        return when (val r = relay.llm(c.token, LlmRequest(ulid(clock()), question, st))) {
            is Res.Ok -> r.value
            is Res.Err -> null.also {
                Log.w(TAG, "상황판 실패 status=${r.status} code=${r.code}")
                if (r.status == 401) creds.invalidate(c)
            }
            Res.Offline -> null
        }
    }
}
