package kr.aigyeot.app.scenario

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kr.aigyeot.app.Golden
import kr.aigyeot.engine.AddWaypoint
import kr.aigyeot.engine.AdjustPlayback
import kr.aigyeot.engine.Ask
import kr.aigyeot.engine.CancelSpeech
import kr.aigyeot.engine.Command
import kr.aigyeot.engine.DimAmbient
import kr.aigyeot.engine.HideNotice
import kr.aigyeot.engine.Listen
import kr.aigyeot.engine.LogAlarm
import kr.aigyeot.engine.LowerHvac
import kr.aigyeot.engine.ReleaseAudio
import kr.aigyeot.engine.RestoreAmbient
import kr.aigyeot.engine.SendToPhone
import kr.aigyeot.engine.SetReadingLight
import kr.aigyeot.engine.SetSeatHeat
import kr.aigyeot.engine.SetWindowLock
import kr.aigyeot.engine.ShowNotice
import kr.aigyeot.engine.Speak
import kr.aigyeot.engine.StartPlayback
import kr.aigyeot.engine.StopPlayback

// 장면을 가짜 세상에서 돌려 나온 할 일(시각·종류·핵심 값)을 장면마다 글 파일로 고정한다. 리팩터링 회귀망이다.
class ScenesGoldenTest {
    private val dir = File("src/debug/assets/scenario")
    private val cryLen = mapOf("cry/stop_40s.pcm" to 20.0, "cry/stop_50s.pcm" to 20.0)

    /** 같은 종류의 저장 할 일은 반복이 많아 종류만 센다. 값이 중요한 할 일만 핵심 값을 적는다. */
    private fun describe(c: Command): String? = when (c) {
        is Speak -> "Speak ${c.sid} ${c.vars.toSortedMap()}"
        is Ask -> "Ask ${c.sid} ${c.vars.toSortedMap()} listenMs=${c.listenMs}"
        is Listen -> "Listen ms=${c.ms}"
        is StartPlayback -> "StartPlayback ${c.kind} ${c.method} endAtMs=${c.endAt - FakeWorld.T0} fadeMs=${c.fadeMs}"
        is StopPlayback -> "StopPlayback fade=${c.fade}"
        is AdjustPlayback -> "AdjustPlayback ${c.mode}"
        is SetSeatHeat -> "SetSeatHeat ${c.areas} level=${c.level}"
        is SetWindowLock -> "SetWindowLock ${c.values}"
        is SetReadingLight -> "SetReadingLight ${c.areas} on=${c.on}"
        is LowerHvac -> "LowerHvac ${c.areas} deltaC=${c.deltaC}"
        is DimAmbient -> "DimAmbient level=${c.level} color=${c.color}"
        is RestoreAmbient -> "RestoreAmbient"
        is AddWaypoint -> "AddWaypoint ${c.restId}"
        is ShowNotice -> "ShowNotice ${c.kind}"
        is HideNotice -> "HideNotice ${c.kind}"
        is SendToPhone -> "SendToPhone ${c.type}"
        is LogAlarm -> "LogAlarm ${c.reason}"
        ReleaseAudio -> "ReleaseAudio"
        CancelSpeech -> "CancelSpeech"
        else -> null // 저장·스냅숏 할 일은 값이 길고 반복이라 뺀다
    }

    private fun record(id: String): String {
        val world = FakeWorld(cryLen)
        val row = world.play(parseScene(File(dir, "$id.json").readText()))
        return buildString {
            append("completed=${row.completed} attempts=${row.maxAttempts}\n")
            world.commands.forEach { (at, c) -> describe(c)?.let { append("+${at - FakeWorld.T0}ms $it\n") } }
        }
    }

    @Test fun `장면 일곱 개의 할 일 기록이 저장된 글과 같다`() {
        for (id in listOf("A", "B", "C", "C2", "D", "D2", "E")) {
            val first = record(id)
            assertEquals(first, record(id), "$id: 같은 장면을 두 번 돌리면 기록이 같아야 한다")
            Golden.check("src/testDebug/resources/golden", "scene-$id.txt", first)
        }
    }
}
