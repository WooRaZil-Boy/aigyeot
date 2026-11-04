package kr.aigyeot.app.scenario

import kr.aigyeot.engine.CabinTemp
import kr.aigyeot.engine.MethodKind
import kr.aigyeot.engine.RouteState
import kr.aigyeot.engine.Sid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertFailsWith

class SceneFileTest {
    private fun scene(steps: String, override: String = "\"CabinTemp\"", extra: String = "") = """
        {"format":1,"id":"T","title":"시험","repeat":2$extra,
         "setup":{"profile":{"birthYm":"2026-02","carseat":"RIGHT","feedingIntervalMin":null,"hasSibling":false},
                  "methods":["WHITE_NOISE","SONG:s1"],"history":[{"method":"LULLABY","result":"FAIL","daysAgo":3}],
                  "seed":7,"override":[$override],"otherGuardians":1},
         "steps":[$steps]}""".trimIndent()

    private fun bad(steps: String, override: String = "\"CabinTemp\"", extra: String = "") =
        assertFailsWith<SceneError> { parseScene(scene(steps, override, extra)) }.message!!

    @Test fun `assets의 장면 파일은 모두 읽힌다`() {
        val files = File("src/main/assets/scenario").listFiles { f -> f.extension == "json" }!!
        assertEquals(26, files.size)   // A~E, D2, F14 20개
        files.forEach { f -> assertEquals(f.nameWithoutExtension, parseScene(f.readText()).id) }
    }

    @Test fun `모든 동작과 setup을 읽는다`() {
        val s = parseScene(scene("""
            {"t":0,"vc":"PUT /vehicle/gear?gear=Drive"},
            {"t":1,"inject":{"event":"CabinTemp","celsius":27}},
            {"t":2,"audio":{"file":"cry/a.pcm","fromSec":1,"durSec":40,"gainDb":-6,"cry":true}},
            {"t":3,"tap":"BABY_ABOARD"},
            {"t":4,"say":{"intent":"YES","onAsk":"S07","by":45}},
            {"t":4,"say":{"intent":"SWITCH_SOUND","method":"LULLABY"}},
            {"t":5,"phone":{"type":"feeding_started","body":{"atOffsetSec":-9}}},
            {"t":6,"expect":{"command":"SendToPhone","type":"handoff_alert","body":{"kind":"x"},"by":14}},
            {"t":7,"expectNone":{"command":"Speak","sid":"S12","for":10},"note":"설명"}
        """))
        assertEquals(2, s.repeat)
        assertEquals(MethodKind.SONG, s.setup.methods[1].kind)
        assertEquals("s1", s.setup.methods[1].songId)
        assertEquals(listOf(false), s.setup.history.map { it.success })
        assertEquals(Vc("PUT", "/vehicle/gear?gear=Drive"), s.steps[0].action)
        assertEquals(Audio("cry/a.pcm", 1.0, 40.0, -6.0, true), s.steps[2].action)
        assertEquals(Sid.S07, (s.steps[4].action as Say).onAsk)
        assertEquals(30.0, (s.steps[5].action as Say).bySec, 0.0)   // by 기본값
        assertEquals("설명", s.steps[8].note)
        assertTrue(injectEvent("CabinTemp", (s.steps[1].action as Inject).fields, 5) == CabinTemp(5, 27.0))
    }

    @Test fun `RouteState 주입은 쉼터 목록까지 사건으로 바뀐다`() {
        val s = parseScene(scene("""{"t":1,"inject":{"event":"RouteState","hasDestination":true,"remainingSec":360,"rests":[
            {"id":"r1","name":"휴게소","kind":"REST_AREA","distanceKm":7,"hasNursingRoom":true}]}}""", "\"RouteState\""))
        val e = injectEvent("RouteState", (s.steps[0].action as Inject).fields, 9) as RouteState
        assertEquals(7.0, e.rests.single().distanceKm, 0.0)
    }

    @Test fun `형식 오류는 거절한다`() {
        assertTrue(bad("""{"t":1,"jump":"x"}""").contains("모르는 동작"))
        assertTrue(bad("""{"t":1,"tap":"BABY_ABOARD","vc":"PUT /x"}""").contains("하나"))
        assertTrue(bad("""{"t":1,"inject":{"event":"RearDoor","side":"LEFT","open":true}}""").contains("override"))   // override에 없는 사건
        assertTrue(bad("""{"t":1,"inject":{"event":"CabinTemp","celsius":"따뜻"}}""").contains("CabinTemp"))
        assertTrue(bad("""{"t":1,"inject":{"event":"CabinTemp","celsius":20,"humid":1}}""").contains("CabinTemp"))
        assertTrue(bad("""{"t":1,"vc":"PUT /vehicle/controls?speed=50&brake=0"}""").contains("네 값"))       // 네 값을 늘 함께(7.3)
        assertTrue(bad("""{"t":1,"vc":"PUT /vehicle/controls?speed=5.5&accelerator=0&brake=0&steering=0"}""").contains("정수"))
        assertTrue(bad("""{"t":1,"vc":"DELETE /vehicle/gear"}""").contains("vc"))
        assertTrue(bad("""{"t":1,"tap":"HONK"}""").contains("tap"))
        assertTrue(bad("""{"t":1,"say":{"intent":"YES"}}""").contains("onAsk"))
        assertTrue(bad("""{"t":1,"say":{"intent":"SWITCH_SOUND"}}""").contains("method"))
        assertTrue(bad("""{"t":1,"expect":{"state":"SLEEPING","by":3}}""").contains("state"))
        assertTrue(bad("""{"t":1,"expect":{"command":"StartPlay","by":3}}""").contains("할 일"))   // 틀린 이름의 expectNone이 조용히 통과하지 않게
        assertTrue(bad("""{"t":1,"expectNone":{"command":"Speek","for":3}}""").contains("할 일"))
        assertTrue(bad("""{"t":1,"expect":{"state":"IDLE"}}""").contains("by"))
        assertTrue(bad("""{"t":1,"expect":{"by":3}}""").contains("하나는"))
        assertTrue(bad("""{"t":1,"expect":{"state":"IDLE","by":3,"within":2}}""").contains("모르는 필드"))
        assertTrue(bad("""{"t":-1,"tap":"BABY_ABOARD"}""").contains("t"))
        assertTrue(bad("""{"t":1,"audio":{"file":"a.pcm","durSec":0}}""").contains("durSec"))
        assertTrue(bad("""{"t":1,"phone":{"type":"x","body":{"atOffsetSec":"어제"}}}""").contains("atOffsetSec"))
        assertTrue(bad("", "\"Banana\"").contains("override"))
        assertTrue(bad("", extra = ",\"loop\":true").contains("모르는 필드"))
        assertFailsWith<SceneError> { parseScene("[]") }
        assertFailsWith<SceneError> { parseScene(scene("").replace("\"format\":1", "\"format\":2")) }
        assertFailsWith<SceneError> { parseScene(scene("").replace("\"SUCCESS\"", "\"x\"").replace("\"FAIL\"", "\"UNJUDGED\"")) }
    }

    @Test fun `P1 장면 phone은 feeding_started만 받는다(곡·선택·연결 메시지는 실제 Room을 바꾸므로 거절)`() {
        parseScene(scene("""{"t":1,"phone":{"type":"feeding_started","body":{"atOffsetSec":-9}}}"""))
        listOf("song_selected", "song_added", "song_removed", "paired", "unpaired", "state_request").forEach {
            assertTrue(it, bad("""{"t":1,"phone":{"type":"$it","body":{}}}""").contains("feeding_started"))
        }
    }

    @Test fun `F9 타입이 틀린 값은 조용히 넘기지 않고 거절한다`() {
        assertTrue(bad("""{"t":1,"expect":{"command":"Speak","sid":123,"by":3}}""").contains("sid"))
        assertTrue(bad("""{"t":1,"expect":{"state":1,"by":3}}""").contains("state"))
        val m = assertFailsWith<SceneError> { parseScene(scene("").replace("[\"WHITE_NOISE\",\"SONG:s1\"]", "\"WHITE_NOISE\"")) }.message!!
        assertTrue(m, m.contains("methods"))
    }
}
