package kr.aigyeot.app.ui

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kr.aigyeot.app.link.LinkedPhone
import kr.aigyeot.app.scenario.RunRow
import kr.aigyeot.app.scenario.ScenarioProgress
import kr.aigyeot.app.scenario.SceneResults
import kr.aigyeot.app.scenario.SceneInfo
import kr.aigyeot.app.scenario.parseScene
import kr.aigyeot.app.scenario.sceneInfo
import kr.aigyeot.app.ui.car3d.Seat3d
import kr.aigyeot.engine.CarSeat
import kr.aigyeot.engine.CrySegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

// 디자인 v6 설정·연결·시연 화면이 계산하는 값(시안 setup()·connect()·demo())
class SetupLinkDemoTest {
    @Test fun seats_orderLabelsAnd3dAnchors() {
        assertEquals(listOf("뒷좌석 왼쪽", "가운데", "뒷좌석 오른쪽"), SEATS.map { it.second })   // 명세서 11장·시안 순서
        assertEquals(listOf(Seat3d.RL, Seat3d.RC, Seat3d.RR), SEATS.map { seat3d(it.first) })    // 자리 단추가 붙는 3D 좌석
        assertEquals(CarSeat.CENTER, carSeatOf("CENTER"))
        assertEquals(CarSeat.RIGHT, carSeatOf(null))   // 첫 설정 기본값
        assertEquals(CarSeat.RIGHT, carSeatOf("??"))
    }

    @Test fun seatTap_picksNearestAnchor_evenWhereCirclesOverlap() {
        // 위에서 본 차(600×700)에서 자리 간격 약 52px, 원 96: 가운데 원 오른쪽 25px를 눌러도 가운데
        val pts = mapOf(Seat3d.RL to androidx.compose.ui.geometry.Offset(248f, 350f), Seat3d.RC to androidx.compose.ui.geometry.Offset(300f, 350f), Seat3d.RR to androidx.compose.ui.geometry.Offset(352f, 350f))
        assertEquals(Seat3d.RC, nearestSeat(pts, androidx.compose.ui.geometry.Offset(325f, 350f), 48f))
        assertEquals(Seat3d.RR, nearestSeat(pts, androidx.compose.ui.geometry.Offset(330f, 360f), 48f))
        assertNull("원 밖은 고르지 않는다", nearestSeat(pts, androidx.compose.ui.geometry.Offset(300f, 500f), 48f))
    }

    @Test fun qrLogo_coversAtMostSixPercent_andSitsOnModuleGrid() {
        for (n in 21..57 step 4) {   // QR 버전 1~10 한 변
            val l = qrLogoModules(n)
            assertTrue("n=$n l=$l", l >= 3 && l * l <= n * n * QR_LOGO_AREA)
            assertEquals("n=$n", 0, (n - l) % 2)
        }
    }

    /** 로고 칸을 비운 실제 연결 QR이 오류 정정 H로 그대로 읽힌다(흰 판 여백 20을 더한 그림). */
    @Test fun pairQr_withLogoHole_stillDecodes() {
        val text = "aigyeot://pair?code=105237"
        val qr = pairQr(text)!!
        val m = qr.matrix
        val c = m.width / 2
        assertFalse(m[c, c])   // 가운데는 비었다
        val scale = 8; val pad = 40
        val w = m.width * scale + pad * 2
        val px = IntArray(w * w) { i ->
            val x = (i % w - pad) / scale; val y = (i / w - pad) / scale
            if (i % w >= pad && i / w >= pad && x < m.width && y < m.height && m[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val read = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, w, px))))
        assertEquals(text, read.text)
    }

    @Test fun phoneLines_roleAndLastSeenAsText() {
        val z = TimeText.zone
        TimeText.zone = ZoneId.of("Asia/Seoul")
        try {
            assertEquals("다른 보호자" to "마지막 연락 11월 9일(월) 오후 2:05", phoneLines(LinkedPhone("d", "guardian", 1_794_200_700_000L)))   // 2026-11-09 14:05 KST
            assertEquals("함께 타는 보호자" to null, phoneLines(LinkedPhone("d", "owner", null)))
        } finally { TimeText.zone = z }
    }

    @Test fun demoTexts_keepCurrentWording() {
        assertEquals("A 2/5회 · 37초", progressText(ScenarioProgress("A", 2, 5, 37.9, null, null)))
        assertEquals("C 1/1회 · 3초 · 어긋남 t=12.0", progressText(ScenarioProgress("C", 1, 1, 3.0, 12.0, "x")))
        assertEquals("반복 장면 기본", repeatText(null))
        assertEquals("반복 3", repeatText(3))
    }

    @Test fun sceneInfo_titleAndAudioFileNames() {
        val a = sceneInfo("A", parseScene(File("src/main/assets/scenario/A.json").readText()))
        assertEquals(SceneInfo("A", "등원 20분: 출발 5분 뒤 울음, 1차에 그침", listOf("stop_40s.pcm"), 5), a)
        // 반복 단추는 그 장면 파일의 repeat에서 한 칸(기본 1인 장면에서 줄이면 1, 늘리면 2)
        assertEquals(1, repeatDown(null, 1)); assertEquals(2, repeatUp(null, 1)); assertEquals(4, repeatDown(null, 5)); assertEquals(3, repeatDown(4, 1))
        assertEquals(SceneInfo("X", null, emptyList()), sceneInfo("X", null))   // 읽지 못한 파일
    }

    private fun row(latMs: Long?) = RunRow(1, 1, true, null, latMs, null, null, false, 0, 0, 0, 0, null, 0, 0, null, null, emptyList())

    @Test fun median_oddEvenEmpty() {
        assertEquals(2_400.0, median(listOf(3_100L, 2_400L, 1_900L))!!, 0.0)
        assertEquals(2_250.0, median(listOf(2_600L, 1_900L))!!, 0.0)
        assertNull(median(emptyList()))
    }

    /** /proc/self/stat: 이름에 빈칸·괄호가 있어도 utime(14)+stime(15)을 센다. 증가분 → 코어 하나 기준 %. */
    @Test fun cpu_ticksFromStat_andPercentOfOneCore() {
        assertEquals(325L, cpuTicks("1234 (kr.aigyeot (x) app) S 1 1234 0 0 -1 4194624 100 0 0 0 250 75 0 0 20 0 30 0 99"))
        assertNull(cpuTicks("garbage"))
        assertEquals(5.0, cpuPercent(ProcSample(100, 0, null), ProcSample(110, 2_000, null), hz = 100)!!, 1e-9)   // 2초에 0.1초 CPU
        assertNull(cpuPercent(ProcSample(null, 0, null), ProcSample(110, 2_000, null), 100))
        assertNull(cpuPercent(ProcSample(100, 2_000, null), ProcSample(110, 2_000, null), 100))   // 같은 시각
    }

    @Test fun metrics_realValuesOrDash() {
        val none = metrics(null, null, null)
        assertEquals(listOf("감지 지연 중앙값", "경보 지연 목표", "CPU", "메모리"), none.map { it.name })
        assertEquals(listOf("—", "3초 이하", "—", "—"), none.map { it.value })
        assertEquals(listOf("", "명세 15장", "예산 5% 미만", "예산 400MB 이하"), none.map { it.sub })
        val m = metrics(SceneResults("A", listOf(row(2_400), row(null), row(1_800))), 3.14, 217_600)
        assertEquals("2.1초", m[0].value)   // 경보가 난 회차 둘의 중앙값
        assertEquals("A 2/3회", m[0].sub)
        assertEquals("3.1%", m[2].value)
        assertEquals("213MB", m[3].value)   // 217600KB / 1024 반올림
        assertEquals("—", metrics(SceneResults("E", listOf(row(null))), null, null)[0].value)   // 울음 없는 장면
    }

    @Test fun prob_lastMinuteOnly_rightEdgeIsNewest() {
        fun seg(endS: Long, p: Double) = CrySegment(endS * 1000, endS * 1000 - 2_925, endS * 1000, p, false)
        val segs = recentSegments(listOf(seg(0, 0.1), seg(30, 0.9), seg(90, 0.4)))
        assertEquals(listOf(30_000L, 90_000L), segs.map { it.end })   // 90초 기준 60초 밖(0초)은 버린다
        val pts = probPoints(segs)
        assertEquals(listOf(0f, 1f), pts.map { it.x })
        assertEquals(listOf(0.9f, 0.4f), pts.map { it.p })
        assertEquals(emptyList<ProbPoint>(), probPoints(emptyList()))
    }

    // 설정 양식 규칙(SetupForm.kt): 오늘은 주입받은 시계로, 생년월은 이번 달을 넘지 않고, 수유 간격은 30분 칸·10시간 끝·한 칸 아래면 비움
    @Test fun setupForm_rulesUseInjectedClock() {
        val now = java.time.ZonedDateTime.of(2026, 11, 10, 9, 0, 0, 0, java.time.ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli()
        assertEquals(java.time.YearMonth.of(2026, 11), thisMonth(now))
        assertNull(nextBirthMonth(java.time.YearMonth.of(2026, 11), now))
        assertEquals(java.time.YearMonth.of(2026, 11), nextBirthMonth(java.time.YearMonth.of(2026, 10), now))
        assertEquals(java.time.YearMonth.of(2026, 11), birthMonthOf(null, now)); assertEquals(java.time.YearMonth.of(2026, 2), birthMonthOf("2026-02", now))
        assertNull(intervalDown(30)); assertEquals(150, intervalDown(180)); assertNull(intervalDown(null))
        assertEquals(30, intervalUp(null)); assertEquals(600, intervalUp(590)); assertEquals("비움(수유 시계 꺼짐)", intervalStepText(null)); assertEquals("3시간 0분", intervalStepText(180))
        assertTrue(ageNote(java.time.YearMonth.of(2026, 10), now)!!.startsWith("참고")); assertNull(ageNote(java.time.YearMonth.of(2026, 7), now)); assertEquals("비워 두면 수유 시계가 꺼져요", ageNote(java.time.YearMonth.of(2026, 5), now))
        val e = profileEntity(null, " 하늘 ", java.time.YearMonth.of(2026, 8), CarSeat.CENTER, 180, sibling = false, song = false, front = true, noRearOutput = false, now = now)
        assertEquals("하늘", e.nickname); assertEquals("2026-08", e.birthYm); assertEquals("뒷좌석 출력이 있으면 앞 재생 허용은 저장하지 않는다", false, e.frontFallbackAllowed)
        assertEquals(listOf(kr.aigyeot.engine.MethodKind.WHITE_NOISE, kr.aigyeot.engine.MethodKind.WOMB, kr.aigyeot.engine.MethodKind.LULLABY), e.methods.map { it.kind }); assertEquals(now, e.consentAt)
    }
}
