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
}
