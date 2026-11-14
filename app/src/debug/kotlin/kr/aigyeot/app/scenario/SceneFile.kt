package kr.aigyeot.app.scenario

import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kr.aigyeot.engine.CarSeat
import kr.aigyeot.engine.CareSummary
import kr.aigyeot.engine.CareView
import kr.aigyeot.engine.Command
import kr.aigyeot.engine.DEFAULT_METHODS
import kr.aigyeot.engine.Event
import kr.aigyeot.engine.Method
import kr.aigyeot.engine.MethodKind
import kr.aigyeot.engine.Phase
import kr.aigyeot.engine.Profile
import kr.aigyeot.engine.Sid

// 장면 파일. 모르는 동작·필드·값은 읽을 때 거절한다(틀린 이름의 expectNone이 조용히 통과하지 않게).

class SceneError(msg: String) : Exception(msg)

data class Scene(val id: String, val title: String, val repeat: Int, val setup: Setup, val steps: List<Cue>)

data class Setup(
    val profile: Profile?,
    val feedingStartOffsetSec: Long?,
    val methods: List<Method>,
    val history: List<SeedTry>,
    val seed: Long,
    val override: Set<String>,
    val otherGuardians: Int,
    // 폰 돌봄 기록 요약 흉내. 시각 칸은 장면 시작 기준 초. [careView]로 CareView가 된다
    val care: JsonObject? = null
)

data class SeedTry(val method: Method, val success: Boolean, val daysAgo: Int)

/** 시간축 한 단계. [t]는 장면 시작부터 초. */
data class Cue(val t: Double, val action: Action, val note: String?)

sealed interface Action
data class Vc(val method: String, val path: String) : Action
data class Audio(val file: String, val fromSec: Double, val durSec: Double, val gainDb: Double, val cry: Boolean) : Action
data class Tap(val what: String) : Action
data class Say(val intent: String, val method: Method?, val onAsk: Sid?, val bySec: Double) : Action
data class Phone(val type: String, val body: JsonObject) : Action
data class Inject(val event: String, val fields: JsonObject) : Action
data class Expect(
    val state: String?,
    val attempt: Int?,
    val command: String?,
    val type: String?,
    val body: JsonObject?,
    val sid: Sid?,
    val method: Method?,
    val bySec: Double
) : Action
data class ExpectNone(val command: String, val sid: Sid?, val type: String?, val body: JsonObject?, val forSec: Double) : Action

val TAPS = setOf("BABY_ABOARD", "FEEDING_NOW", "FEEDING_CONFIRM_YES")
private val INTENTS = setOf("YES", "NO", "STOP", "SWITCH_SOUND")
val STATES = Phase.entries.map { it.name }.toSet() + "IDLE"

/** 할 일 이름. 직렬화 설명에서 읽어 판단 코어와 늘 같다. */
val COMMANDS: Set<String> = Command.serializer().descriptor.getElementDescriptor(1).elementNames.map { it.substringAfterLast('.') }.toSet()
private val EVENTS: Set<String> = Event.serializer().descriptor.getElementDescriptor(1).elementNames.map { it.substringAfterLast('.') }.toSet()
private val CONTROLS = listOf("speed", "accelerator", "brake", "steering")
private val eventJson = Json { ignoreUnknownKeys = false }

/** 장면 파일의 음원 이름([methodText]의 거꾸로). 곡 없는 종류는 판단 코어 기본 음원 목록에서 읽는다(목록을 따로 적지 않는다). */
fun parseMethod(s: String): Method = when {
    s.startsWith("SONG:") && s.length > 5 -> Method(MethodKind.SONG, s.removePrefix("SONG:"))
    else -> DEFAULT_METHODS.firstOrNull { it.kind.name == s } ?: throw SceneError("음원 이름 '$s'")
}

fun methodText(m: Method) = if (m.kind == MethodKind.SONG) "SONG:${m.songId}" else m.kind.name

/** inject 사건 만들기. 필드는 메시지 형식의 이름 그대로, at은 호스트 시계로 채운다. */
fun injectEvent(name: String, fields: JsonObject, at: Long, base: Long = at): Event = if (name == "CareSummary") {
    CareSummary(at, careView(fields, base)) // 시각 칸은 그 단계 t 기준 초
} else {
    eventJson.decodeFromJsonElement(
        Event.serializer(),
        JsonObject(fields + mapOf("type" to JsonPrimitive("kr.aigyeot.engine.$name"), "at" to JsonPrimitive(at)))
    )
}

/** `setup.care`·`inject CareSummary`의 초 오프셋 칸을 [base](epoch ms) 기준 CareView로. 없는 시각 칸은 null. */
fun careView(f: JsonObject, base: Long): CareView {
    f.only(
        "care", "event", "asOfSec", "sleepDiaperEnabled",
        "lastSleepStartSec", "lastSleepEndSec", "sleepingSinceSec", "todaySleepMin", "nightWakeups", "lastDiaperSec", "todayDiaperCount", "todayFeedCount"
    )
    fun at(k: String) = f.long(k)?.let { base + it * 1000 }
    return CareView(
        base + (f.long("asOfSec") ?: throw SceneError("care.asOfSec")) * 1000,
        f.bool("sleepDiaperEnabled") ?: throw SceneError("care.sleepDiaperEnabled"),
        at("lastSleepStartSec"), at("lastSleepEndSec"), at("sleepingSinceSec"),
        f.int("todaySleepMin"), f.int("nightWakeups"), at("lastDiaperSec"), f.int("todayDiaperCount"),
        f.int("todayFeedCount") ?: throw SceneError("care.todayFeedCount")
    )
}

fun parseScene(text: String): Scene {
    val o = try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (e: Exception) {
        null
    } ?: throw SceneError("JSON 객체가 아니다")
    o.only("최상위", "format", "id", "title", "repeat", "setup", "steps", "note")
    if (o.int("format") != 1) throw SceneError("format은 1이어야 한다")
    val setup = setup(o.obj("setup") ?: throw SceneError("setup이 없다"))
    val steps = (o["steps"] as? JsonArray ?: throw SceneError("steps가 없다")).mapIndexed { i, e ->
        val s = e as? JsonObject ?: throw SceneError("steps[$i]가 객체가 아니다")
        try {
            cue(s, setup)
        } catch (x: SceneError) {
            throw SceneError("steps[$i]: ${x.message}")
        }
    }
    val repeat = o.int("repeat") ?: 1
    if (repeat < 1) throw SceneError("repeat ≥ 1")
    return Scene(o.str("id") ?: throw SceneError("id가 없다"), o.str("title") ?: "", repeat, setup, steps)
}

private fun setup(s: JsonObject): Setup {
    s.only("setup", "profile", "feedingStartOffsetSec", "methods", "history", "seed", "override", "otherGuardians", "care")
    val profile = s.obj("profile")?.let { p ->
        p.only("profile", "birthYm", "carseat", "feedingIntervalMin", "hasSibling")
        Profile(
            p.str("birthYm") ?: throw SceneError("profile.birthYm"),
            enumOf<CarSeat>(p.str("carseat"), "profile.carseat"),
            p.int("feedingIntervalMin"),
            p.bool("hasSibling") ?: throw SceneError("profile.hasSibling")
        )
    }
    val history = (s["history"] as? JsonArray ?: JsonArray(emptyList())).map { h ->
        val x = h as? JsonObject ?: throw SceneError("history 항목")
        x.only("history", "method", "result", "daysAgo")
        val r = x.str("result")
        if (r != "SUCCESS" && r != "FAIL") throw SceneError("history.result는 SUCCESS·FAIL") // 성적 씨앗은 판정된 1차만(FirstTry)
        SeedTry(parseMethod(x.str("method") ?: ""), r == "SUCCESS", x.int("daysAgo") ?: throw SceneError("history.daysAgo"))
    }
    val override = s.strings("override").toSet()
    override.forEach { if (it !in EVENTS) throw SceneError("override '$it'는 사건 이름이 아니다") }
    val care = s.obj("care")?.also {
        careView(it, 0)
        if ("CareSummary" !in override) throw SceneError("setup.care를 쓰려면 override에 CareSummary가 있어야 한다")
    }
    return Setup(
        profile,
        s.long("feedingStartOffsetSec"),
        s.strings("methods").map(::parseMethod),
        history,
        s.long("seed") ?: throw SceneError("setup.seed"),
        override,
        s.int("otherGuardians") ?: 0,
        care
    )
}

private fun cue(s: JsonObject, setup: Setup): Cue {
    val t = s.num("t") ?: throw SceneError("t가 없다")
    if (t < 0) throw SceneError("t ≥ 0")
    val keys = s.keys - "t" - "note"
    val name = keys.singleOrNull() ?: throw SceneError("동작은 하나여야 한다: $keys")
    val v = s.getValue(name)
    val action = when (name) {
        "vc" -> vc(v.str() ?: throw SceneError("vc는 문자열"))
        "audio" -> v.objOr("audio").run {
            only("audio", "file", "fromSec", "durSec", "gainDb", "cry")
            val dur = num("durSec") ?: throw SceneError("audio.durSec")
            if (dur <= 0) throw SceneError("audio.durSec > 0")
            Audio(str("file") ?: throw SceneError("audio.file"), num("fromSec") ?: 0.0, dur, num("gainDb") ?: 0.0, bool("cry") ?: false)
        }
        "tap" -> Tap(v.str()?.takeIf { it in TAPS } ?: throw SceneError("tap '$v'"))
        "say" -> v.objOr("say").run {
            only("say", "intent", "method", "onAsk", "by")
            val intent = str("intent")?.takeIf { it in INTENTS } ?: throw SceneError("say.intent")
            val method = str("method")?.let(::parseMethod)
            val onAsk = str("onAsk")?.let { enumOf<Sid>(it, "say.onAsk") }
            if ((intent == "YES" || intent == "NO") && onAsk == null) throw SceneError("say ${intent}에는 onAsk가 있어야 한다")
            if (intent == "SWITCH_SOUND" && method == null) throw SceneError("say SWITCH_SOUND에는 method가 있어야 한다")
            Say(intent, method, onAsk, positive("by") ?: 30.0)
        }
        "phone" -> v.objOr("phone").run {
            only("phone", "type", "body")
            val body = obj("body") ?: JsonObject(emptyMap())
            body.num("atOffsetSec")
            val type = str("type") ?: throw SceneError("phone.type")
            // 장면 phone은 feeding_started만: 곡·선택·연결 메시지는 실제 Room과 seq 기록을 바꾼다
            if (type != "feeding_started") throw SceneError("phone.type '$type': 장면은 feeding_started만 받는다")
            Phone(type, body)
        }
        "inject" -> v.objOr("inject").run {
            val ev = str("event") ?: throw SceneError("inject.event")
            if (ev !in setup.override) throw SceneError("inject '$ev'는 setup.override에 없다")
            val fields = JsonObject(this - "event")
            try {
                injectEvent(ev, fields, 0)
            } catch (e: Exception) {
                throw SceneError("inject $ev 필드: ${e.message?.lineSequence()?.first()}")
            }
            Inject(ev, fields)
        }
        "expect" -> v.objOr("expect").run {
            only("expect", "state", "attempt", "command", "type", "body", "sid", "method", "by")
            val state = str("state")?.also { if (it !in STATES) throw SceneError("expect.state '$it'") }
            val cmd = command()
            if (state == null && cmd == null && get("attempt") == null) throw SceneError("expect에 state·attempt·command 중 하나는 있어야 한다")
            Expect(
                state,
                int("attempt"),
                cmd,
                str("type"),
                obj("body"),
                str("sid")?.let { enumOf<Sid>(it, "expect.sid") },
                str("method")?.let(::parseMethod),
                positive("by") ?: throw SceneError("expect.by")
            )
        }
        "expectNone" -> v.objOr("expectNone").run {
            only("expectNone", "command", "sid", "type", "body", "for")
            ExpectNone(
                command() ?: throw SceneError("expectNone.command"),
                str("sid")?.let { enumOf<Sid>(it, "expectNone.sid") },
                str("type"),
                obj("body"),
                positive("for") ?: throw SceneError("expectNone.for")
            )
        }
        else -> throw SceneError("모르는 동작 '$name'")
    }
    return Cue(t, action, s.str("note"))
}

private fun JsonObject.command(): String? = str("command")?.also { if (it !in COMMANDS) throw SceneError("할 일 이름 '$it'") }

// "PUT /vehicle/gear?gear=Drive"처럼 메서드와 경로. 주행 값은 네 값을 정수로 늘 함께 보낸다(빠진 값의 처리는 미측정).
private fun vc(s: String): Vc {
    val m = Regex("""^(GET|PUT|POST) (/\S*)$""").find(s) ?: throw SceneError("vc '$s'")
    val (method, path) = m.destructured
    if (path.startsWith("/vehicle/controls")) {
        val q = path.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate { it.substringBefore('=') to it.substringAfter('=') }
        if (q.keys != CONTROLS.toSet() || q.values.any { it.toIntOrNull() == null }) throw SceneError("vc controls는 ${CONTROLS.joinToString()} 네 값(정수)")
    }
    return Vc(method, path)
}

private inline fun <reified E : Enum<E>> enumOf(s: String?, what: String): E = enumValues<E>().firstOrNull { it.name == s } ?: throw SceneError("$what '$s'")

private fun JsonObject.only(what: String, vararg allowed: String) {
    val extra = keys - allowed.toSet()
    if (extra.isNotEmpty()) throw SceneError("$what: 모르는 필드 $extra")
}
private fun JsonElement.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonElement.objOr(what: String): JsonObject = this as? JsonObject ?: throw SceneError("${what}는 객체")
private fun JsonObject.str(k: String) = get(k)?.takeIf { it !is JsonNull }?.let { it.str() ?: throw SceneError("${k}는 문자열") }
private fun JsonObject.obj(k: String) = get(k)?.takeIf { it !is JsonNull }?.let { it as? JsonObject ?: throw SceneError("${k}는 객체") }
private fun JsonObject.prim(k: String) = get(k)?.takeIf { it !is JsonNull }?.let { it as? JsonPrimitive ?: throw SceneError("${k}는 값") }
private fun JsonObject.num(k: String) = prim(k)?.let { it.doubleOrNull ?: throw SceneError("${k}는 수") }
private fun JsonObject.int(k: String) = prim(k)?.let { it.intOrNull ?: throw SceneError("${k}는 정수") }
private fun JsonObject.long(k: String) = prim(k)?.let { it.longOrNull ?: throw SceneError("${k}는 정수") }
private fun JsonObject.bool(k: String) = prim(k)?.let { it.booleanOrNull ?: throw SceneError("${k}는 참·거짓") }
private fun JsonObject.positive(k: String) = num(k)?.also { if (it <= 0) throw SceneError("$k > 0") }
private fun JsonObject.strings(k: String): List<String> = get(k)?.takeIf { it !is JsonNull }
    ?.let { v -> (v as? JsonArray ?: throw SceneError("${k}는 문자열 목록")).map { it.str() ?: throw SceneError("${k}는 문자열 목록") } }
    ?: emptyList()
