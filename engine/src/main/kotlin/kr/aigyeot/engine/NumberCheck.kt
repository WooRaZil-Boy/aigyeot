package kr.aigyeot.engine

// 명세서 9장 '글 검사'(contracts-link 8.8 '답 처리'). ④(link)가 LLM 답 글에 부른다.

/**
 * 글 검사의 '숫자 없음'(contracts-link 8.8 '답 처리', 11-07 C194). NFKC로 정규화하고 모든 유니코드 숫자를 아라비아 숫자로 읽은 뒤
 * 숫자가 하나도 없고, 한글 수사 바로 뒤(공백 허용)에 단위(초·분·시간·일·년·개월·살·돌·번·개·명·곳·배·도·킬로·미터·퍼센트 등, 한 목록 [UNIT_SPACED], C249)가 오는 말("세 번"·"두 시간"·"십여 분"·"백 미터")도 없으면 참.
 * 단위 '도'는 띄어 쓴 경우만 본다("한도"·"정도"는 숫자가 아니다. 두 음절 이상 한자어 수 "삼십도"는 붙여도 숫자, C225). 한자어 수("삼 분"·"십분")는 붙여 써도 단위(도는 띄어 쓴 경우만)를 본다(C198·C205). 중계의 시스템 지시가 글에 숫자를 쓰지 않게 한다.
 */
fun noDigits(text: String): Boolean {
    val t = readable(text)
    return t.none(Char::isDigit) && !KOREAN_COUNT.containsMatchIn(t) && !SINO_COUNT.containsMatchIn(t) && !SINO_MULTI_COUNT.containsMatchIn(t) && !SINO_DEGREE.containsMatchIn(t) && !ZERO_COUNT.containsMatchIn(t) && !APPROX_COUNT.containsMatchIn(t) && !ORDINAL_COUNT.containsMatchIn(t) && !HALF_COUNT.containsMatchIn(t) && !PERIOD_WORD.containsMatchIn(t)
}

// C249: 단위 목록은 하나다. 모든 수사 꼴(고유어·한자어·어림수·서수·영/공·배)이 같이 쓴다. 띄어 쓴 꼴은 전부, 붙여 쓴 꼴은 낱말과 안 겹치는 것만
// ("이해"·"오시"·"이곳"·"일명"·"세월"은 붙이면 낱말). 단위 '도'는 띄어 쓴 경우만. "배고파"·"배려"는 '배'로 안 본다
private const val UNIT_SPACED = "(?:번째|시간|주일|개월|차례|마리|가지|군데|킬로|키로|미터|센티|밀리|리터|그램|칼로리|퍼센트|프로|초|분|시|일|날|주|달|월|년|해|살|세|돌|번|회|개|명|곳|배(?!고|려))"
// 고유어 수 뒤 붙임: 낱말과 겹치는 일(세일·네일·한일)·월·해(그만해)·세(한세상)·도(한도)만 뺀다
private const val UNIT_ATTACHED = "(?:번째|시간|주일|개월|차례|마리|가지|군데|킬로|키로|미터|센티|밀리|리터|그램|칼로리|퍼센트|프로|초|분|시|날|주|달|년|살|돌|번|회|개|명|곳|배(?!고|려))"
// 한 음절 한자어 수 뒤 붙임: 시·날·세·해·돌·명·곳·마리·가지·군데·차례는 "오시"·"이날"·"이세"·"이해"·"일명"·"이곳" 같은 낱말과 겹쳐 뺀다
private const val UNIT_ATTACHED_SINO = "(?:번째|시간|주일|개월|킬로|키로|미터|센티|밀리|리터|그램|칼로리|퍼센트|프로|초|분|일|월|년|주|달|살|번|회|개|배(?!고|려))"
// 두 음절 이상(·수 붙음) 한자어 수 뒤 붙임: 낱말 겹침이 드물어 더 넓게 본다. 시("오십시오")·해("이사해요")·가지("오이가지")만 뺀다
private const val UNIT_ATTACHED_SINO_MULTI = "(?:번째|시간|주일|개월|차례|마리|군데|킬로|키로|미터|센티|밀리|리터|그램|칼로리|퍼센트|프로|초|분|일|월|년|주|달|날|살|세|돌|번|회|개|명|곳|배(?!고|려))"
// C376: 한 음절 고유어 수는 앞이 한글이면 낱말의 끝이라 안 본다("하지만 주변"의 만 주, "편안한 곳"의 한 곳)
// C383: 단 '몇'·'총'은 수를 꾸미는 말이라 앞에 있어도 본다("몇천 명"·"총두 번"). '약'·'단'·'매'는 더하지 않는다("약한 곳"·"간단한 곳"·"애매한 곳")
// C383 덧붙임: 복합 꼴에서 '만한'은 낱말이라 수로 보지 않는다("갈 만한 곳"·"볼만한", 만+한 = 10001을 고유어로 쓰지 않는다). "백한 번"은 그대로 수
private const val NOT_WORD_END = "(?<![가-힣&&[^몇총]])"
private const val NATIVE = "(?:(?!만한)(?:열|스물|서른|마흔|쉰|예순|일흔|여든|아흔|백|천|만)(?:한|두|세|네|다섯|여섯|일곱|여덟|아홉)|(?:백|천|만)(?:열|쉰)|$NOT_WORD_END(?:열|쉰|둘|셋|넷|한|두|세|네|석|넉|천|만)|스물|서른|마흔|예순|일흔|여든|아흔|하나|다섯|여섯|일곱|여덟|아홉|스무)"
private const val SINO = "[일이삼사오육칠팔구십백천만]"
private val KOREAN_COUNT = Regex("${NATIVE}여?(?:\\s*$UNIT_ATTACHED|\\s+$UNIT_SPACED|\\s+도)")   // C257: "만여 명"·"서른여 명"
// 수사 앞이 한글이면 낱말의 일부라 안 본다("백색소음이 도움"의 '이'·"아이 배"). 수십·수백은 '수'를 허용, 십여 분의 '여'를 허용
// C256: 앞이 한글이면 안 보는 예외는 한 음절 수(이·일·오·만…)에만. 두 음절 이상("약삼십 분"의 삼십)은 앞이 한글이어도 본다
// C383: '몇'·'총' 뒤는 본다(NOT_WORD_END, "몇십 분"·"몇백 미터")
private val SINO_COUNT = Regex("$NOT_WORD_END${SINO}여?(?:\\s*$UNIT_ATTACHED_SINO|\\s+$UNIT_SPACED|\\s+도)")
private val SINO_MULTI_COUNT = Regex("(?:수$SINO|$SINO{2,})여?(?:\\s*$UNIT_ATTACHED_SINO_MULTI|\\s+$UNIT_SPACED|\\s+도)")
// C225·C230: 한자어 수(한 음절 포함)는 '도'를 붙여 써도 숫자("십도"·"오도"). "한도"·"정도"는 한자어 수가 아니라 통과
private val SINO_DEGREE = Regex("$SINO+도")
// C232: 어림수("서너 분", C383 "세네 번"·"너덧 번")와 서수("첫 번째"·"둘째 날"). 서수는 뒤에 단위가 올 때만
private val APPROX_COUNT = Regex("(?:한두|두세|세네|서너|너덧|너댓|네댓|대여섯|예닐곱|일여덟|여닐곱)(?:\\s*$UNIT_SPACED|\\s+도)")
// C235·C265: '반'도 C249 단위 목록을 쓴다. 띄어 쓴 꼴은 전부("반 초"·"반 리터"·"반 배"). 붙여 쓴 꼴은 낱말과 겹치는 단위만 뺀다:
// 시(반시계)·주(반주)·달(반달)·월(반월)·해(반해서)·세(반세기)·분(반분하다)·돌(반돌)·날(반날)·곳·명·살·회(반회전)·프로(반프로)·도. 반찬·반드시·반가워·반대·반응은 단위가 아니라 통과
private const val UNIT_ATTACHED_HALF = "(?:번째|시간|주일|개월|차례|마리|가지|군데|킬로|키로|미터|센티|밀리|리터|그램|칼로리|퍼센트|초|일|년|번|개|배(?!고|려))"
private val HALF_COUNT = Regex("반(?:\\s+(?:$UNIT_SPACED|도)|$UNIT_ATTACHED_HALF)")
private val ORDINAL_COUNT = Regex("(?:첫째|첫|둘째|셋째|넷째|다섯째|여섯째|일곱째|여덟째|아홉째|열째)\\s*$UNIT_SPACED")
// C240: 기간 낱말. "하루하루"·"보름달"은 걸려도 정해진 문장으로 가서 안전 쪽
private val PERIOD_WORD = Regex("하루|이틀|사흘|나흘|닷새|엿새|이레|여드레|아흐레|열흘|보름")
// C225: 영·공은 낱말("공개"·"영주")과 겹쳐 뒤에 단위가 띄어 올 때만 본다("영 분"). 붙여 쓴 꼴은 "공개"·"영주"·"공시"와 안 겹치는 단위만(C232)
// C256: 붙여 쓴 꼴도 C249 단위를 본다. 낱말과 겹치는 단위는 뺀다: 시(공시·영시)·주(공주·영주)·달(영달)·개(공개)·회(공회전)·배(사람 이름 영배)·프로(공프로그램 꼴)
private const val UNIT_ATTACHED_ZERO = "(?:번째|시간|개월|차례|마리|가지|군데|곳|킬로|키로|미터|센티|밀리|리터|그램|칼로리|퍼센트|초|분|년|살|날|번)"
private val ZERO_COUNT = Regex("(?:영|공)(?:\\s+(?:$UNIT_SPACED|도)|$UNIT_ATTACHED_ZERO)")

/**
 * 글 정규화 하나(C275): NFKC → 모든 유니코드 숫자를 ASCII로(`\d`는 ASCII만 본다. 아랍 소수점·천 단위 기호도) →
 * 서식 문자(Cf: U+200B·U+2060·U+FEFF 등)와 모든 공백·줄 구분자(Zs·Zl·Zp·탭·개행)를 공백 하나로.
 * Cf를 지우지 않고 공백으로 바꾼다: 지우면 "잠\u200B시간"이 "잠시간"이 되어 일반 공백 "잠 시간"과 달라진다.
 */
fun readable(s: String): String =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC)
        .codePoints().toArray().joinToString("") { cp ->
            when {
                Character.isDigit(cp) -> "${Character.digit(cp, 10)}"
                cp == 0x066B -> "."
                cp == 0x066C -> ","
                Character.getType(cp) == Character.FORMAT.toInt() || Character.isWhitespace(cp) || Character.isSpaceChar(cp) -> " "
                else -> String(Character.toChars(cp))
            }
        }

/**
 * 상태 단정 검사(명세서 9장, 17장 C108, contracts-link 8.8). 답 글이 관찰하지 않은 아이 상태(잠·깸·배고픔·아픔·안전·숨)나
 * 울음 원인을 단정하는 표현을 담으면 거짓이다. NFKC로 정규화하고 공백을 모두 지운 글에서 [STATE_CLAIMS]를 찾는다.
 * 지나치게 걸러도 오프라인 문장이 나갈 뿐이라 안전 쪽이다.
 */
fun noStateClaim(text: String): Boolean {
    val t = readable(text).filterNot { it.isWhitespace() }
    return STATE_CLAIMS.none { it in t }
}

/**
 * 군말 검사(C375·C386, contracts-link 8.8). 모델이 도구 이름·주제 값·영어 추론·"…를 고르겠습니다" 같은 추론 글을 글 칸에 흘리는 것을 막는다.
 * 정규화한 글에 ① 영문 낱말(영문자 2자 이상 연속. 식별자 목록 없이 길이로 거른다) ② 한글·영문 밖 글자(힌디·키릴·한자 등, 1자도) ③ 괄호류
 * ④ [LEAK_WORDS](공백 지운 글)가 있으면 거짓이다. 걸러도 정해 둔 문장이 나갈 뿐이라 안전 쪽이다. 낱말은 실측 자료에서 뽑았고 말투 변형을 하나씩 더하지 않는다.
 */
fun noLeak(text: String): Boolean {
    val t = readable(text)
    return !LEAK_CHARS.containsMatchIn(t) && t.filterNot { it.isWhitespace() }.let { s -> LEAK_WORDS.none { it in s } }
}

// 한글은 \p{IsHangul} 대신 코드 범위로 쓴다: 그 표기는 Android 10부터라 minSdk 28(Android 9)에서 정규식 만들기가 실패한다.
// 범위 = 한글 음절·자모·호환 자모·확장 A·B·반각 자모(유니코드 Hangul 문자 집합)
private val LEAK_CHARS = Regex("[A-Za-z]{2,}|[\\p{L}&&[^A-Za-z\\uAC00-\\uD7A3\\u1100-\\u11FF\\u3130-\\u318F\\uA960-\\uA97F\\uD7B0-\\uD7FF\\uFFA0-\\uFFDC]]|[\\[\\](){}<>]")

/** 설계값(공백 없는 꼴). 프롬프트의 내부 낱말(주제·도구·상태값·정해진 문장)과 추론 투 낱말. 바꾸면 contracts-link 8.8과 NumberCheckTest를 함께 고친다. */
internal val LEAK_WORDS = listOf("주제", "도구", "호출", "상태값", "문장", "고르겠", "고를게", "고릅니", "답하겠", "답할게", "답합니", "질문으로보", "말로보여")

/** 설계값(공백 없는 꼴). 바꾸면 contracts-link 8.8과 NumberCheckTest를 함께 고친다. */
internal val STATE_CLAIMS = listOf(
    "잠들", "자고있", "자는중", "잘자", "푹자",         // 잠
    "깼", "깨어",                                      // 깸
    "배고프", "배고파", "배고픈", "졸려", "졸린",                            // 배고픔
    "아파", "아픈", "열이",                            // 아픔
    "안전해", "안전합", "안전하게", "괜찮아", "괜찮습",   // 안전·괜찮음
    "숨을", "숨쉬", "호흡",                            // 숨
    "때문에울", "해서울어요",                          // 원인 단정
    // 사실 주제 단정(C271): 분류가 사실 질문을 놓쳐도 LLM 글이 사실을 말하지 못하게 하는 안전망('잠들'은 위에 있다)
    "울고있", "울지않", "울음이들려", "울음이들리", "울음소리가들", "그쳤어", "그쳤습", "그쳤고", "울었어", "울었습", "울었고",   // 울음
    "먹었어", "먹었습", "먹었고", "먹였어", "먹였습", "먹였고", "먹은기록", "수유했",             // 수유
    "잤어", "잤습", "잤고", "자요", "자네요",                                            // 잠
    "갈았", "기저귀를갈",                               // 기저귀
    "더워요", "추워요", "덥습", "춥습", "온도가",         // 온도
    "휴게소가있", "휴게소가없", "수유실이있", "수유실이없", // 쉼터
    "잘통했", "효과가있었", "효과가없었",                 // 소리 성적
)

// 소수점(숫자 사이 마침표)은 문장 끝이 아니다
/** 앞에서부터 [n]문장까지(문장 끝 부호 포함). 문장 끝 부호로 나눈다(C156). */
fun firstSentences(text: String, n: Int): String {
    var count = 0
    var pos = 0
    for (m in SENTENCE_END.findAll(text)) {
        if (text.substring(pos, m.range.first).isNotBlank() && ++count == n) return text.substring(0, m.range.last + 1).trim()
        pos = m.range.last + 1
    }
    return text.trim()
}

private val SENTENCE_END = Regex("""(?<!\d)\.|\.(?!\d)|[?!\r\n\u0085\u2028\u2029。！？]""")
