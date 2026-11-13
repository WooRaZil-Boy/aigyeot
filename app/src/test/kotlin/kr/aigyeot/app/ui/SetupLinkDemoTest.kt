package kr.aigyeot.app.ui

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kr.aigyeot.app.link.LinkedPhone
import kr.aigyeot.app.ui.car3d.Seat3d
import kr.aigyeot.engine.CarSeat
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
        assertEquals(listOf("뒷좌석 왼쪽", "뒷좌석 가운데", "뒷좌석 오른쪽"), SEATS.map { it.second })   // 명세서 11장·시안 순서
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

    // 설정 양식 규칙(SetupForm.kt): 오늘은 주입받은 시계로. 바퀴 값·글은 디자인 v8d-10 ⑩·명세 C387
    private val nov10 = java.time.ZonedDateTime.of(2026, 11, 10, 9, 0, 0, 0, ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli()
    private fun ym(y: Int, m: Int) = java.time.YearMonth.of(y, m)

    @Test fun setupForm_rulesUseInjectedClock() {
        val now = nov10
        assertEquals(ym(2026, 11), thisMonth(now))
        assertEquals(ym(2026, 11), birthMonthOf(null, now)); assertEquals(ym(2026, 2), birthMonthOf("2026-02", now))
        assertEquals("2026년 10월", birthMonthText(ym(2026, 10)))
        val e = profileEntity(null, " 하늘 ", ym(2026, 8), CarSeat.CENTER, 180, sibling = false, song = false, front = true, noRearOutput = false, now = now)
        assertEquals("하늘", e.nickname); assertEquals("2026-08", e.birthYm); assertEquals(180, e.feedingIntervalMin)
        assertEquals("뒷좌석 출력이 있으면 앞 재생 허용은 저장하지 않는다", false, e.frontFallbackAllowed)
        assertEquals(listOf(kr.aigyeot.engine.MethodKind.WHITE_NOISE, kr.aigyeot.engine.MethodKind.WOMB, kr.aigyeot.engine.MethodKind.LULLABY), e.methods.map { it.kind }); assertEquals(now, e.consentAt)
        assertNull("'안 씀'은 저장 null(수유 시계 꺼짐, 저장 형식 그대로)", profileEntity(null, "하늘", ym(2026, 8), CarSeat.RIGHT, null, false, true, false, false, now).feedingIntervalMin)
    }

    @Test fun birthYears_thisYearBackThree_keepsOlderSaved() {
        assertEquals(listOf(2023, 2024, 2025, 2026), birthYears(nov10))
        assertEquals(listOf(2023, 2024, 2025, 2026), birthYears(nov10, saved = 2024))
        assertEquals(listOf(2021, 2023, 2024, 2025, 2026), birthYears(nov10, saved = 2021))   // 저장 2021-05: 목록에 더해 지킨다
    }

    @Test fun birthMonths_thisYearStopsAtThisMonth() {
        assertEquals((1..11).toList(), birthMonths(2026, nov10))
        assertEquals((1..12).toList(), birthMonths(2025, nov10))
    }

    @Test fun clampBirth_futureMonthMovesToThisMonth() {
        assertEquals(ym(2026, 11), clampBirth(2026, 12, nov10))   // 2025-12에서 해만 2026으로
        assertEquals(ym(2026, 3), clampBirth(2026, 3, nov10))
        assertEquals(ym(2025, 12), clampBirth(2025, 12, nov10))
    }

    @Test fun intervals_offThen30To600_keepsOddSaved() {
        val l = intervals(null)
        assertEquals(21, l.size); assertNull(l.first()); assertEquals(30, l[1]); assertEquals(600, l.last())
        assertTrue(l.drop(1).zipWithNext { a, b -> b!! - a!! }.all { it == 30 })
        assertEquals(l, intervals(180))   // 목록 안 저장값은 그대로
        val odd = intervals(200)
        assertEquals(22, odd.size); assertEquals(listOf(180, 200, 210), odd.subList(odd.indexOf(180), odd.indexOf(180) + 3))
    }

    @Test fun intervalText_offMinutesHours() {
        assertEquals("안 씀", intervalText(null)); assertEquals("30분", intervalText(30)); assertEquals("2시간 30분", intervalText(150))
        assertEquals("3시간", intervalText(180)); assertEquals("10시간", intervalText(600)); assertEquals("3시간 20분", intervalText(200))
    }

    @Test fun ageText_monthsFromBirth() {
        assertEquals("생후 1개월", ageText(ym(2026, 10), nov10)); assertEquals("생후 0개월", ageText(ym(2026, 11), nov10))
        assertEquals("생후 14개월", ageText(ym(2025, 9), nov10))
    }

    @Test fun setupHelp_newbornFirst_thenOff_elseNone() {
        for (iv in listOf(null, 180)) {   // 0·1개월은 간격과 관계없이 참고 범위(먼저)
            assertEquals(NEWBORN_HELP, setupHelp(ym(2026, 11), iv, nov10)); assertEquals(NEWBORN_HELP, setupHelp(ym(2026, 10), iv, nov10))
        }
        assertNull(setupHelp(ym(2026, 8), 180, nov10))                          // 3개월 + 3시간
        assertEquals(INTERVAL_OFF_HELP, setupHelp(ym(2026, 8), null, nov10))     // 3개월 + '안 씀'
        assertEquals(INTERVAL_OFF_HELP, setupHelp(ym(2026, 3), 180, nov10))      // 8개월
        assertEquals("'안 씀'이면 수유 시계가 꺼져요", INTERVAL_OFF_HELP)
    }

    @Test fun consentTexts_summaryAndOriginal() {
        assertEquals("아기의 보호자(법정대리인)로서 아이곁 사용에 동의해요", CONSENT_TITLE)
        assertEquals("질문하면 질문 글과 상태 숫자가 AI 서버로 가요. 울음소리와 차 위치 좌표는 보내지 않아요.", CONSENT_SUMMARY)
        assertEquals("AI 서버로 가는 정보", CONSENT_DETAIL_TITLE)
        // 창 본문 = 명세 C120 ③ 원문 글자 그대로(로컬에 명세가 있으면 거기서도 찾는다)
        val original = "질문하면 질문 글과 지금 상태 숫자(울음·수유·잠·기저귀 기록 요약, 카시트 시간, 실내 온도, 소리별 성적, 다음 쉼터 이름·거리)가 아이곁 중계 서버를 거쳐 AI 서버로 가요. 울음 소리와 차 위치 좌표는 보내지 않아요."
        assertEquals(original, CONSENT_DETAIL)
        val spec = listOf(File("../docs/eyes-forward-spec.md"), File("docs/eyes-forward-spec.md")).firstOrNull { it.exists() }
        if (spec != null) assertTrue("명세 C120 ③에 같은 글이 있다", spec.readText().contains("\"$CONSENT_DETAIL\""))
    }
}
