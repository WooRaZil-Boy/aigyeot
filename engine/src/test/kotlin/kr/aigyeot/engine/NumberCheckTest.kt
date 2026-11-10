package kr.aigyeot.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// 명세서 9장 '글 검사'(contracts-link 8.8 '답 처리', 11-07 C194).
class NumberCheckTest {
    // C311: 상태 단정 목록의 모든 낱말은 정규화를 거친 뒤에도 글에서 잡힌다(낱말 그대로·문장 속·끝 부호)
    @Test fun c311_everyStateClaimReachable() {
        for (w in STATE_CLAIMS) for (t in listOf(w, "아기가 $w?", "그럼 $w.")) assertFalse(noStateClaim(t), "$w/$t")
    }

    @Test fun noDigits_plainText_passes() = assertTrue(noDigits("아기가 우는 이유는 알 수 없지만 불편할 수 있어요."))

    @Test fun noDigits_asciiAndUnicodeDigits_fail() {
        listOf("1분 안에 진정될 수 있어요.", "２분째예요.", "٩분째예요.", "²분째예요.", "\uD804\uDC6F분째예요.", "제2경인휴게소예요.").forEach { assertFalse(noDigits(it), it) }
    }

    @Test fun noDigits_thousandAndHour() {   // C219
        listOf("천 초 뒤에는 괜찮아요.", "열 시쯤에는 도착해요.", "만 번이요.", "이천 초예요.").forEach { assertFalse(noDigits(it), it) }
        listOf("한도 안이에요.", "정도예요.").forEach { assertTrue(noDigits(it), it) }
    }

    @Test fun noDigits_sinoTimes_c244() {
        listOf("백 배 클 수 있어요.", "십 배예요.", "이십 배예요.", "백배 커요.", "삼백 배예요.", "두 배예요.").forEach { assertFalse(noDigits(it), it) }
        listOf("아이 배가 고플 수 있어요.", "하나의 배가 아파요.", "아기 배고파 보여요.").forEach { assertTrue(noDigits(it), it) }
    }

    @Test fun noDigits_sinoCompoundDegreeAndZero() {   // C225
        listOf("삼십도예요.", "이십도쯤이에요.", "백이십도예요.", "영 분 지났어요.", "공 시간이에요.", "영 도예요.").forEach { assertFalse(noDigits(it), it) }
        listOf("한도 안이에요.", "정도예요.", "영주에 가요.", "공개했어요.", "공주님이에요.").forEach { assertTrue(noDigits(it), it) }
    }

    @Test fun noDigits_singleSinoDegree() {   // C230
        listOf("십도예요.", "백도예요.", "오도 올랐어요.", "구도가 나빠요.").forEach { assertFalse(noDigits(it), it) }
        listOf("한도 안이에요.", "정도예요.", "도착했어요.").forEach { assertTrue(noDigits(it), it) }
    }

    @Test fun noDigits_approxOrdinalZero() {   // C232
        listOf("서너 분 걸려요.", "한두 번이에요.", "두세 시간이에요.", "너댓 번 울었어요.", "네댓 분이에요.", "대여섯 개예요.", "서너분 남았어요.",
            "첫 번째예요.", "첫번째 시도예요.", "둘째 날이에요.", "셋째 번이에요.", "열째 시간이에요.", "영분 지났어요.", "공시간이에요.").forEach { assertFalse(noDigits(it), it) }
        listOf("한도 안이에요.", "정도예요.", "영주에 가요.", "공개했어요.", "공주님이에요.", "첫째로 중요해요.", "첫눈이 와요.").forEach { assertTrue(noDigits(it), it) }
    }

    @Test fun noDigits_halfAndOrdinalDay() {   // C235
        listOf("반 시간 뒤면 돼요.", "반시간쯤이에요.", "반 분이에요.", "첫째 날에는 힘들어요.", "셋째 주예요.", "둘째 주에는요.").forEach { assertFalse(noDigits(it), it) }
        listOf("반찬을 먹어요.", "반드시 쉬세요.", "반가워요.", "첫째로 중요해요.").forEach { assertTrue(noDigits(it), it) }
    }

    @Test fun noDigits_koreanNumeralWithUnit() {
        listOf("세 번 울었어요.", "두 시간 전이에요.", "한 번이요.", "다섯분 지났어요.", "스무 살이에요.", "열 개예요.", "일곱 킬로예요.", "스물 도예요.").forEach { assertFalse(noDigits(it), it) }
        listOf("열이 있을 수 있어요.", "한참 울 수 있어요.", "세상에서 가장 불편할 수 있어요.", "두고 보세요.").forEach { assertTrue(noDigits(it), it) }   // 단위가 바로 뒤에 오지 않으면 통과
    }

    // C376: 낱말 끝의 한 음절 고유어 수("하지만 주변"의 만)는 숫자가 아니다. 진짜 숫자 말은 그대로 걸린다
    @Test fun c376_wordEndingNativeNumeralIsNotNumber() {
        listOf("죄송하지만 주변 소리까지는 알 수 없어요.", "편안한 곳에서 쉬세요.", "필요한 일은 없어요.", "중요한 시선이 아니에요.", "그렇지만 주위를 살펴보세요.",
            "정확한 분은 알 수 없어요.", "안전한 곳을 찾아보세요.").forEach { assertTrue(noDigits(it), it) }
        listOf("세 번 울었어요.", "두 시간 지났어요.", "한 번이요.", "약 두 시간이에요.", "하루에 세 번이에요.", "만 번이요.", "만 주 동안이에요.", "열 시쯤이에요.", "한 곳이에요.",
            "서른세 분 기다려 보세요.", "열한 번 울었어요.", "스물두 시간이에요.", "두 주의 적응 기간이 필요해요.", "생후 이십 주차예요.", "예순다섯 개예요.", "약열두 시간이에요.", "백한 번 정도예요.", "백열 분 기다려요.", "백쉰 번이에요.", "두 주로 잡으면 돼요.", "세 시도 가능해요.").forEach { assertFalse(noDigits(it), it) }
    }

    // C383(C376의 퇴행): 수를 꾸미는 '몇'·'총' 뒤 한 음절 수와 어림수 '세네'·'너덧'은 숫자다. C376이 막은 낱말 끝은 그대로 통과
    @Test fun c383_quantifierPrefixAndApproxAreNumbers() {
        listOf("세네 번 울었어요.", "너덧 번 울었어요.", "몇천 명이 써요.", "몇만 명이 써요.", "몇십 분 걸려요.", "몇백 미터 앞이에요.", "총두 번 울었어요.", "총세 번이에요.")
            .forEach { assertFalse(noDigits(it), it) }
        listOf("죄송하지만 주변에 쉼터가 있어요.", "편안한 곳에서 쉬세요.", "약한 곳을 살펴보세요.", "간단한 곳이에요.", "애매한 곳이에요.").forEach { assertTrue(noDigits(it), it) }
    }

    // C383 덧붙임: 복합 고유어 수 꼴에서 '만한'은 낱말(갈 만한 곳·볼만한)이다. '백한 번' 같은 수는 그대로 숫자
    @Test fun c383_manhanIsWordNotNumber() {
        listOf("쉬어 갈 만한 곳이 있어요.", "볼만한 시간이에요.").forEach { assertTrue(noDigits(it), it) }
        listOf("백한 번이요.", "서른세 번 울었어요.", "열한 시간이에요.", "만 번이요.").forEach { assertFalse(noDigits(it), it) }
    }

    // C375: 군말 검사
    @Test fun c375_leakedEnglishAndToolWords() {
        listOf("answer 도구를 호출해 수유 주제를 고릅니다.", "answer를 호출합니다.", "stop_soothing을 호출합니다.", "Rendering the answer for the feeding question.", "Sleep 주제로 알려 드릴게요.",
            "(function call: answer with topics)", "add_waypoint로 넣을게요.", "도구로 고릅니다.", "호출했어요.", "ｓｗｉｔｃｈ_ｓｏｕｎｄ").forEach { assertFalse(noLeak(it), it) }
        listOf("백색소음이 도움이 될 수 있어요.", "다음 쉼터까지 약 km 정도예요.", "아기가 우는 이유는 알 수 없지만 불편할 수 있어요.", "OK, 알겠어요.").forEach { assertTrue(noLeak(it), it) }
    }

    // C377: 말하는 분 값 하나. S06 elapsed·S11 drove(hm)와 ④ 문장이 같은 함수를 지난다
    @Test fun c377_spokenMinute_boundaries() {
        assertEquals(listOf("0분", "59분", "1시간", "1시간 1분", "2시간", "17시간 54분"), listOf(0, 59, 60, 61, 120, 1074).map(::spokenMin))
        assertEquals("0분", spokenMin(-5))
        for (m in listOf(0L, 59L, 60L, 61L, 1074L)) assertEquals(spokenMin(m), hm(m * 60_000 + 59_999), "hm=$m")   // 초는 내림
    }

    // ---- C108 상태 단정 검사 ----
    @Test fun stateClaim_listedExpressions_caught() {
        listOf(
            "아기가 잘 자고 있어요.", "배고파서 울어요.", "괜찮아요.", "숨을 잘 쉬고 있어요.", "잠들었어요.", "깼어요.", "깨어 있어요.",
            "아파서 울어요.", "열이 있는 것 같아요.", "안전해요.", "안전하게 가고 있어요.", "괜찮습니다.", "호흡이 고르세요.", "기저귀 때문에 울어요.", "심심해서 울어요.", "푹 자는 중이에요.", "아픈 것 같아요.", "배고픈 것 같아요.", "졸린가 봐요.", "많이 졸려해요.",
        ).forEach { assertFalse(noStateClaim(it), it) }
    }

    // C271: 주제마다 사실 단정 글이 막힌다(공백이 끼어도)
    @Test fun c271_factClaims_caught() {
        listOf(
            "지금 울고 있어요.", "울지 않아요.", "울음이 들리지 않아요.", "그쳤어요.", "울었어요.",
            "먹었어요.", "방금 먹였어요.", "먹은 기록이 있어요.", "수유했어요.", "잤어요.", "잠들었어요.",
            "기저귀를 갈았어요.", "갈았어요.", "더워요.", "추워요.", "덥습니다.", "춥습니다.", "온도가 높아요.",
            "휴게소가 있어요.", "휴게소가 없어요.", "수유실이 있어요.", "수유실이 없어요.",
            "울음이 들려요.", "울음소리가 들려요.", "먹었습니다.", "먹였습니다.", "먹었고요.", "울었습니다.", "그쳤습니다.", "잤습니다.", "잤고요.",
            "잘 통했어요.", "효과가 있었어요.", "효과가 없었어요.",
        ).forEach { assertFalse(noStateClaim(it), it) }
    }

    // 지나친 차단 확인(보고용): LLM이 흔히 쓸 '왜' 답은 통과한다
    @Test fun c271_typicalWhyAnswers_pass() {
        listOf("낯선 소리에 놀랐을 수 있어요.", "불편할 수 있어요.", "졸릴 수 있어요.", "놀라서 울었을 수 있어요.", "먹었을 수도 있어요.").forEach { assertTrue(noStateClaim(it), it) }
    }

    @Test fun stateClaim_observedFacts_pass() {
        listOf("마지막 수유는 12시 10분이에요.", "백색소음을 틀었어요.", "소리를 바꿀게요.", "")
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
        assertFalse(noStateClaim("기록상 오늘 3번 먹었어요."))   // C271: 수유 사실 단정("먹었어")이라 막힌다
    }

    @Test fun c156_firstSentences() {
        assertEquals("가요. 나요!", firstSentences("가요. 나요! 다요.", 2))
        assertEquals("27.5도예요.", firstSentences("27.5도예요.", 2))
        assertEquals("61분 전 기록 기준으로, 가요. 나요.", firstSentences("61분 전 기록 기준으로, 가요. 나요. 다요.", 2))
    }

    @Test fun noDigits_handoIsNotNumber() {
        listOf("소리는 정해진 재생 한도 안에서만 틀어요.", "그 정도예요.").forEach { assertTrue(noDigits(it), it) }
    }

    @Test fun noDigits_koreanNumeralHoe() = assertFalse(noDigits("한 회 더 달래면 돼요."))

    @Test fun noDigits_sinoNumeralWithUnit() {
        listOf("삼 분 동안 울었어요.", "이십 분 지났어요.", "오 번이에요.").forEach { assertFalse(noDigits(it), it) }
    }

    @Test fun noDigits_secondsUnitAndSinoNoSpace() {
        listOf("열 초 정도 걸릴 수 있어요.", "십분 뒤면 진정될 수 있어요.", "삼분쯤 걸려요.", "두 주 뒤예요.", "세 달 됐어요.", "이십초예요.").forEach { assertFalse(noDigits(it), it) }
        listOf("한도 안이에요.", "그 정도예요.", "초조할 수 있어요.").forEach { assertTrue(noDigits(it), it) }
    }

    @Test fun noDigits_periodWordsAndTimes() {
        listOf("하루 정도 지났어요.", "이틀째예요.", "사흘 전이에요.", "나흘", "닷새", "엿새", "이레", "여드레", "아흐레", "열흘", "보름 됐어요.", "두 배로 커요.", "세 배예요.", "열 배", "한 배 더")
            .forEach { assertFalse(noDigits(it), it) }
        listOf("배고파 보여요.", "배려해 주세요.", "한 배고픔", "배가 아파요.").forEach { assertTrue(noDigits(it), it) }
    }

    // C249: 수사 꼴마다 단위 목록이 달라 새던 말
    @Test fun noDigits_c249_oneUnitList() {
        listOf("십여 분 동안 울 수 있어요.", "삼 일쯤 걸려요.", "일 년이 지나요.", "백 미터 앞이에요.", "백 밀리리터예요.", "삼십 퍼센트예요.", "두 돌 무렵이에요.",
            "서너 가지예요.", "수십 분 걸려요.", "한 곳이에요.", "세 명이에요.", "오 개월이에요.").forEach { assertFalse(noDigits(it), it) }
    }

    @Test fun noDigits_c249_wordsStillPass() {
        listOf("백색소음이 도움이 될 수 있어요.", "이 소리가 익숙해질 수 있어요.", "천천히 달래 주세요.", "만약 계속 울면 쉬어 가세요.", "한동안 낯설 수 있어요.", "세상 소리가 낯설 수 있어요.",
            "상황을 이해해 주세요.", "아이 배가 고플 수 있어요.").forEach { assertTrue(noDigits(it), it) }
    }

    // C256: 영·공 붙여 쓴 꼴도 C249 단위를 보고, 두 음절 이상 한자어 수는 앞이 한글이어도 본다
    @Test fun noDigits_c256_zeroAttachedUnits() {
        listOf("영퍼센트예요.", "공퍼센트예요.", "영미터예요.", "공킬로예요.", "영개월이에요.").forEach { assertFalse(noDigits(it), it) }
        listOf("공개했어요.", "영주에 가요.", "공시가 났어요.", "공주님이에요.", "영월에 가요.", "공일이에요.", "영달을 누려요.").forEach { assertTrue(noDigits(it), it) }
    }

    @Test fun noDigits_c256_multiSyllableSinoAfterHangul() {
        listOf("약삼십 분 정도 쉬어 가도 좋아요.", "약이십 분 걸려요.", "약 삼십 분이에요.").forEach { assertFalse(noDigits(it), it) }
        listOf("백색소음이 도움이 될 수 있어요.", "이 소리가 익숙해질 수 있어요.", "천천히 달래 주세요.", "만약 계속 울면 쉬어 가세요.", "상황을 이해해 주세요.", "이곳에서 쉬어요.",
            "오시면 돼요.", "아이 배가 고플 수 있어요.").forEach { assertTrue(noDigits(it), it) }
    }

    // ---- C257: 수사 꼴 × 단위 표. 띄어 쓴 꼴은 모든 단위가 막히고, 붙여 쓴 꼴은 낱말과 겹치는 단위만 뺀다 ----
    private val units = listOf("번째", "시간", "주일", "개월", "차례", "마리", "가지", "군데", "킬로", "키로", "미터", "센티", "밀리", "리터", "그램", "칼로리", "퍼센트", "프로",
        "초", "분", "시", "일", "날", "주", "달", "월", "년", "해", "살", "세", "돌", "번", "회", "개", "명", "곳", "배")

    @Test fun grid_native_oneSyllable() {   // 한·두·세·네: 일(세일·네일·한일)·월·해("그만해"는 만)·세(한세상)는 낱말과 겹쳐 붙임에서 뺀다
        listOf("한", "두", "세", "네").forEach { n -> units.forEach { u ->
            assertFalse(noDigits("$n $u 됐어요."), "띄어 씀 $n $u")
            if (u !in setOf("일", "월", "해", "세")) assertFalse(noDigits("$n$u 됐어요."), "붙여 씀 $n$u")
        } }
        listOf("세일 중이에요.", "네일 아트예요.", "한일 관계예요.", "한세상 걸려요.", "그만해요.", "한도 안이에요.").forEach { assertTrue(noDigits(it), it) }
    }

    @Test fun grid_native_multiSyllable() {
        listOf("다섯", "여섯", "스무", "서른").forEach { n -> units.forEach { u ->
            assertFalse(noDigits("$n $u 됐어요."), "띄어 씀 $n $u")
            if (u !in setOf("일", "월", "해", "세")) assertFalse(noDigits("$n$u 됐어요."), "붙여 씀 $n$u")
        } }
        listOf("한참 울 수 있어요.", "열이 있을 수 있어요.", "두고 보세요.", "세상에서 가장 불편할 수 있어요.", "한동안 낯설 수 있어요.").forEach { assertTrue(noDigits(it), it) }
    }

    @Test fun grid_native_yeoAndSu() {   // '여'(만여 명)·'수'(수천 명, 수만 개)
        listOf("만여 명이에요.", "만여명이에요.", "서른여 개예요.", "열여 분이에요.", "수천 명이에요.", "수천명이에요.", "수만 곳이에요.", "수만곳이에요.").forEach { assertFalse(noDigits(it), it) }
    }

    @Test fun grid_sino_oneSyllable() {   // 한 음절 한자어 수는 앞이 한글이면 낱말의 일부. 붙임에서 시·날·세·해·돌·명·곳·마리·가지·군데·차례는 뺀다
        val excluded = setOf("시", "날", "세", "해", "돌", "명", "곳", "마리", "가지", "군데", "차례")
        listOf("삼", "오", "십", "백").forEach { n -> units.forEach { u ->
            assertFalse(noDigits("$n $u 됐어요."), "띄어 씀 $n $u")
            if (u !in excluded) assertFalse(noDigits("$n$u 됐어요."), "붙여 씀 $n$u")
        } }
        listOf("오시면 돼요.", "이날 쉬어요.", "이해해 주세요.", "이곳에서 쉬어요.", "일명 소리예요.", "이세 장난감이에요.", "오시오.", "아이 배가 고플 수 있어요.", "백색소음이 좋아요.", "이 소리가 익숙해요.", "천천히 쉬세요.", "만약 울면 쉬어요.")
            .forEach { assertTrue(noDigits(it), it) }
    }

    @Test fun grid_sino_multiSyllable() {   // 두 음절 이상(·수): 시·해·가지만 뺀다. 앞이 한글이어도 본다
        val excluded = setOf("시", "해", "가지")
        listOf("십이", "삼십", "이십오", "수십", "수백", "수천").forEach { n -> units.forEach { u ->
            assertFalse(noDigits("$n $u 됐어요."), "띄어 씀 $n $u")
            if (u !in excluded) { assertFalse(noDigits("$n$u 됐어요."), "붙여 씀 $n$u"); assertFalse(noDigits("약$n$u 됐어요."), "앞이 한글 약$n$u") }
        } }
        listOf("십이명이에요.", "삼십곳이에요.", "수십여 명이에요.", "약삼십 분 걸려요.", "약 삼십 분 걸려요.").forEach { assertFalse(noDigits(it), it) }
        listOf("오십시오.", "이사해요.", "오이가지 무침이에요.").forEach { assertTrue(noDigits(it), it) }
    }

    @Test fun grid_zero() {   // 영·공: 띄어 쓴 꼴은 모든 단위, 붙인 꼴은 시·주·달·개·회·배·프로·일·월·세·해·돌·명(공시·공주·영달·공개·공회전·영배·공명·공세·공해·공돌·영명)을 뺀다
        val excluded = setOf("시", "주", "달", "개", "회", "배", "프로", "일", "월", "세", "해", "돌", "명", "주일")
        listOf("영", "공").forEach { n -> units.forEach { u ->
            assertFalse(noDigits("$n $u 됐어요."), "띄어 씀 $n $u")
            if (u !in excluded) assertFalse(noDigits("$n$u 됐어요."), "붙여 씀 $n$u")
        } }
        listOf("영퍼센트예요.", "영 도예요.", "공시가 났어요.", "영주에 가요.", "공개했어요.", "영월에 가요.", "공해가 심해요.", "공명이 커요.").let { l ->
            l.take(2).forEach { assertFalse(noDigits(it), it) }; l.drop(2).forEach { assertTrue(noDigits(it), it) }
        }
    }

    @Test fun grid_approxOrdinalHalf() {
        listOf("서너", "한두", "두세", "대여섯", "예닐곱").forEach { n -> units.forEach { u -> assertFalse(noDigits("$n $u 됐어요."), "$n $u"); assertFalse(noDigits("$n$u 됐어요."), "$n$u") } }
        listOf("첫째", "둘째", "셋째", "열째").forEach { n -> listOf("번째", "시간", "날", "주", "달", "해", "번", "회").forEach { u -> assertFalse(noDigits("$n $u 됐어요."), "$n $u") } }
        listOf("반 시간", "반시간", "반 년", "반년", "반 개월", "반 주일", "반 일").forEach { assertFalse(noDigits("$it 걸려요."), it) }
        listOf("반찬을 먹어요.", "반드시 쉬세요.", "반가워요.", "첫째로 중요해요.", "첫눈이 와요.").forEach { assertTrue(noDigits(it), it) }
    }

    // C265: 반도 C249 단위 목록 전부(띄어 쓴 꼴), 붙여 쓴 꼴은 낱말과 겹치는 단위만 뺀다
    @Test fun c265_half_usesCommonUnits() {
        val spaced = listOf("번째", "시간", "주일", "개월", "차례", "마리", "가지", "군데", "킬로", "키로", "미터", "센티", "밀리", "리터", "그램", "칼로리", "퍼센트", "프로", "초", "분", "시", "일", "날", "주", "달", "월", "년", "해", "살", "세", "돌", "번", "회", "개", "명", "곳", "배", "도")
        spaced.forEach { assertFalse(noDigits("반 $it 안에 달라질 수 있어요."), "반 $it") }
        val attached = listOf("번째", "시간", "주일", "개월", "차례", "마리", "가지", "군데", "킬로", "키로", "미터", "센티", "밀리", "리터", "그램", "칼로리", "퍼센트", "초", "일", "년", "번", "개", "배")
        attached.forEach { assertFalse(noDigits("반$it 안에 달라질 수 있어요."), "반$it") }
        listOf("반찬을 먹어요.", "반드시 쉬세요.", "반가워요.", "반대예요.", "반응이 있어요.", "반시계 방향이에요.", "반주를 해요.", "반달이 떠요.", "반배고파요.").forEach { assertTrue(noDigits(it), it) }
    }

    // C275: 공백 꼴이 달라도 일반 공백과 같은 결과
    private val spaces = listOf(" ", "\u00A0", "\u2028", "\u2029", "\u3000", "\t", "\n", "\u200B", "\u2060", "\uFEFF", "\u200C\u200D")

    @Test fun c275_noDigits_sameForAllSpaceForms() = spaces.forEach { sp ->
        listOf("세${sp}번", "십${sp}분", "반${sp}시간").forEach { assertFalse(noDigits("${it}만에 달라질 수 있어요."), it.replace("\n", "\\n")) }
    }

    @Test fun c275_noStateClaim_sameForAllSpaceForms() = spaces.forEach { sp ->
        assertFalse(noStateClaim("지금 울고${sp}있어요."), "울고${sp.length}")
    }
}
