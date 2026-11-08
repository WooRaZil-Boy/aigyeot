package kr.aigyeot.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// 명세서 9장 '숫자 검사'. 상태값은 contracts-link 1.4 POST /v1/llm 요청의 state 예시다.
class NumberCheckTest {
    private val state: JsonElement = Json.parseToJsonElement(
        """
        { "cry": { "current_min": 2, "count_this_drive": 3 },
          "feeding": { "elapsed_min": 170, "interval_min": 180 },
          "carseat_min": 52, "cabin_temp": 27.5,
          "next_rest": { "id": "r1", "name": "제2경인○○휴게소", "distance_km": 7.2, "nursing_room": true },
          "method_stats": [ { "method": "lullaby", "label": "자장가", "successes": 6, "attempts": 7 } ],
          "last_night_wakeups": null }
        """,
    )

    @Test fun numberCheck_contractExample_passes() {
        assertTrue(numbersGrounded("자장가를 틀었을 때 7번 중 6번 그쳤어요. 소리 덕분인지는 알 수 없어요.", state))
    }

    @Test fun numberCheck_noNumbers_passes() = assertTrue(numbersGrounded("원인은 알 수 없어요.", state))

    @Test fun numberCheck_derivedNumber_fails() {
        // 170분을 2시간 50분으로 바꿔 말하면 상태값에 없는 숫자다(명세서 9장: 버리고 오프라인 문장)
        assertFalse(numbersGrounded("수유한 지 2시간 50분 지났어요.", state))
        assertFalse(numbersGrounded("1분 안에 진정될 수 있어요.", state))   // 1은 id "r1"에만 있다
    }

    @Test fun numberCheck_decimalsAndNames() {
        assertTrue(numbersGrounded("실내가 27.5도예요. 7.2km 앞에 수유실이 있어요.", state))
        assertTrue(numbersGrounded("제2경인○○휴게소까지 7.20km예요.", state))   // 이름 안 숫자, 끝 0
        assertFalse(numbersGrounded("실내가 27도예요.", state))                  // 반올림한 값은 상태값에 없다
    }

    @Test fun numberCheck_signAndThousands() {
        val cold = Json.parseToJsonElement("""{ "cabin_temp": -3.5, "carseat_min": 1180 }""")
        assertTrue(numbersGrounded("실내가 영하 3.5도예요. 카시트에 1,180분째예요.", cold))
        assertFalse(numbersGrounded("카시트에 1,181분째예요.", cold))
    }

    @Test fun numberCheck_unicodeDigits() {
        assertFalse(numbersGrounded("９분째 울고 있어요.", state))    // 전각 9: 상태값에 없다
        assertTrue(numbersGrounded("２분째 울고 있어요.", state))     // 전각 2는 2와 같다
        assertFalse(numbersGrounded("٩분째 울고 있어요.", state))     // 아랍 인도 숫자 9
        assertTrue(numbersGrounded("²분째 울고 있어요.", state))      // NFKC로 2
        assertFalse(numbersGrounded("⁹분째 울고 있어요.", state))     // NFKC로 9
    }

    @Test fun numberCheck_nonBmpDigit_fails() {
        // 브라미 숫자 9(U+1106F)는 상태값에 없다. 6은 U+1106C
        assertFalse(numbersGrounded("\uD804\uDC6F분째 울어요.", state))
        assertTrue(numbersGrounded("\uD804\uDC6C분째 울어요.", state))   // 6은 상태값에 있다
    }

    // ---- C108 상태 단정 검사 ----
    @Test fun stateClaim_listedExpressions_caught() {
        listOf(
            "아기가 잘 자고 있어요.", "배고파서 울어요.", "괜찮아요.", "숨을 잘 쉬고 있어요.", "잠들었어요.", "깼어요.", "깨어 있어요.",
            "아파서 울어요.", "열이 있는 것 같아요.", "안전해요.", "안전하게 가고 있어요.", "괜찮습니다.", "호흡이 고르세요.", "기저귀 때문에 울어요.", "심심해서 울어요.", "푹 자는 중이에요.", "아픈 것 같아요.", "배고픈 것 같아요.", "졸린가 봐요.", "많이 졸려해요.",
        ).forEach { assertFalse(noStateClaim(it), it) }
    }

    @Test fun stateClaim_observedFacts_pass() {
        listOf("울음이 들리지 않아요.", "마지막 수유는 12시 10분이에요.", "백색소음을 틀었어요.", "지금 2분째 울고 있어요.", "소리를 바꿀게요.", "")
            .forEach { assertTrue(noStateClaim(it), it) }
    }

    @Test fun stateClaim_spaceAndFullwidthVariants_caught() {
        assertFalse(noStateClaim("아기가 잘  자고   있어요"))
        assertFalse(noStateClaim("잘\n자고\t있어요"))
        assertFalse(noStateClaim("괜\u3000찮\u00A0아 요"))                                  // 전각·줄 바꿈 없는 공백
        assertFalse(noStateClaim(java.text.Normalizer.normalize("괜찮아요", java.text.Normalizer.Form.NFD)))  // 풀어 쓴 자모
        assertFalse(noStateClaim("숨 을 쉬어요"))
    }

    @Test fun c108_recordWordsStillFail() {
        assertFalse(noStateClaim("기록상 40분 잤고 방금 깼어요."))
        assertFalse(noStateClaim("기록상 잠들었어요."))
    }

    @Test fun c108_presentSleepStateFailsEvenWithRecord() {
        assertFalse(noStateClaim("기록상 지금 잘 자고 있어요."))
        assertFalse(noStateClaim("기록상\n깼어요."))
        assertTrue(noStateClaim("기록상 오늘 3번 먹었어요."))
    }

    // ---- C129 숫자-항목 검사 ----
    private val care: JsonElement = Json.parseToJsonElement(
        """
        { "cry": { "current_min": 2, "count_this_drive": 3 },
          "feeding": { "elapsed_min": 170, "interval_min": 180 },
          "carseat_min": 52, "cabin_temp": 27.5, "feeds_today": 3, "care_as_of_min_ago": 10,
          "next_rest": { "id": "r1", "name": "제2경인○○휴게소", "distance_km": 7.2, "nursing_room": true },
          "method_stats": [ { "method": "lullaby", "label": "자장가", "successes": 6, "attempts": 7 } ],
          "last_night_wakeups": 2,
          "sleep": { "sleeping_since_min": null, "woke_min_ago": 40, "last_sleep_min": 35, "today_sleep_min": 430 } }
        """,
    )

    @Test fun c129_otherCellNumber_fails() = assertFalse(numbersMatchCells("마지막 수유는 40분 전이에요", care))   // 40은 잠 값

    @Test fun c129_rightCellNumber_passes() {
        assertTrue(numbersMatchCells("마지막 수유는 170분 전이에요.", care))
        assertTrue(numbersMatchCells("40분 전에 깼어요. 카시트에 52분째예요.", care))
        assertTrue(numbersMatchCells("실내 온도는 27.5도예요.", care))   // 소수점은 문장 끝이 아니다
        assertTrue(numbersMatchCells("자장가를 틀었을 때 7번 중 6번 그쳤어요.", care))
    }

    @Test fun c129_sentenceScoped() = assertFalse(numbersMatchCells("수유는 170분 전이에요. 40분이에요.", care))   // 낱말은 같은 문장에서만

    @Test fun c129_sentenceScoped_cr_nel_ls_ps() {
        listOf("\r", "\r\n", "\u0085", "\u2028", "\u2029").forEach { sep ->
            assertFalse(numbersMatchCells("마지막 수유는 40분 전이에요${sep}잠 기록도 있어요", care), "sep=" + sep.map { it.code })
            assertTrue(numbersMatchCells("마지막 수유는 170분 전이에요${sep}40분 전에 깼어요", care))
        }
    }

    @Test fun c129_restNameDigits_pass() = assertTrue(numbersMatchCells("제2경인○○휴게소까지 7.2km예요.", care))

    @Test fun c129_restNameDigits_withoutName_fails() = assertFalse(numbersMatchCells("2번이에요.", care))   // 2는 울음·쉼터 이름 칸인데 낱말이 없다

    @Test fun c129_valueInTwoCells_anyWordPasses() {
        val two = Json.parseToJsonElement("""{ "carseat_min": 40, "sleep": { "woke_min_ago": 40 } }""")
        assertTrue(numbersMatchCells("카시트에 40분째예요.", two))
        assertTrue(numbersMatchCells("40분 전에 깼어요.", two))
        assertFalse(numbersMatchCells("수유한 지 40분이에요.", two))
    }

    private val c130 = Json.parseToJsonElement(
        """{ "feeding": { "elapsed_min": 7 }, "carseat_min": 2, "cabin_temp": 23.5, "next_rest": { "distance_km": 7.2 } }""",
    )

    @Test fun c130_arabicDecimal_wrongCell_fails() {
        assertFalse(numbersMatchCells("수유 후 ٧.٢분째, 카시트에 있어요.", c130))   // 7.2는 쉼터 거리
        assertFalse(numbersMatchCells("수유 후 ７．２분째예요.", c130))
        assertFalse(numbersMatchCells("수유 후 ٧٫٢분째예요.", c130))
    }

    @Test fun c130_decimalVariants_rightCell_pass() {
        assertTrue(numbersMatchCells("쉼터까지 7.2km예요.", c130))
        assertTrue(numbersMatchCells("쉼터까지 ７．２km예요.", c130))
        assertTrue(numbersMatchCells("쉼터까지 ٧٫٢km예요.", c130))
    }

    @Test fun c130_doWordMustFollowNumber() {
        assertFalse(numbersMatchCells("카시트에도 23.5분 있었어요.", c130))
        assertFalse(numbersMatchCells("쉼터에 도착, 23.5분이에요.", c130))
        assertTrue(numbersMatchCells("실내는 23.5도예요.", c130))
        assertTrue(numbersMatchCells("실내는 23.5 도예요.", c130))
        assertTrue(numbersMatchCells("좀 덥네요, 23.5예요.", c130))
        assertTrue(numbersMatchCells("실내는 23.5℃예요.", c130))
        assertTrue(numbersMatchCells("실내는 23.5°C예요.", c130))
        assertFalse(numbersMatchCells("카시트에도 23.5℃ 아닌 23.5분 있었어요.", c130))
    }
}
