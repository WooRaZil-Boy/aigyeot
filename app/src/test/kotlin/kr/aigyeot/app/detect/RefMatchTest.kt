package kr.aigyeot.app.detect

import java.io.File
import kotlin.math.round
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume

/**
 * 맥 파이썬 기준값(ref.json)과 소수 둘째 자리까지 대조한다. 녹음·기준값은 저장소 밖 로컬 데이터 폴더에 있다(CC BY-NC 등 데이터셋 라이선스).
 * 환경 변수 AIGYEOT_DATA_DIR = mk_pcm.py가 쓴 데이터 폴더. 안 주어지거나 필요한 파일이 하나라도 없으면 Assume으로 건너뛴다. 기준 파일 전체를 대조했을 때만 성공한다.
 * 필요한 것: <폴더>/assets/rt/ref.json, <폴더>/assets/rt/scores/<이름>.json (YAMNet 창 점수 [[521개]...]. 파이썬 rt_extract.scores 결과를 tolist()로 저장)
 * MediaPipe는 JVM에서 안 돌아 창 점수는 파일로 받는다. 기기에서 YAMNet까지 포함한 대조는 DetectReplayActivity(debug)가 한다.
 */
class RefMatchTest {
    @Test fun `기준값과 소수 둘째 자리까지 같다`() {
        val dir = System.getenv("AIGYEOT_DATA_DIR")?.let { File(it, "assets/rt") }
        // 자료가 없으면 통과가 아니라 '건너뜀'이다
        Assume.assumeTrue("AIGYEOT_DATA_DIR/assets/rt/ref.json 없음", dir != null && File(dir, "ref.json").exists())
        val ref = Json.parseToJsonElement(File(dir!!, "ref.json").readText()).jsonObject
        val missing = ref.keys.filter { !File(dir, "scores/$it.json").exists() }
        Assume.assumeTrue("scores/<이름>.json 없음: $missing", missing.isEmpty())
        val head = CryHead.parse(File("src/main/assets/cry_head24_rt.json").readText())
        for ((name, expected) in ref) {
            val windows = Json.parseToJsonElement(File(dir, "scores/$name.json").readText()).jsonArray
                .map { w -> (w as JsonArray).let { a -> FloatArray(a.size) { a[it].jsonPrimitive.content.toFloat() } } }
            val tracker = SegmentTracker(head)
            val segs = windows.mapIndexedNotNull { i, w -> tracker.push(w, (i + 1) * Detect.WINDOW_MS, 0) }.ifEmpty { listOfNotNull(tracker.flushShort(0)) }
            assertEquals(expected.jsonArray.map { it.jsonPrimitive.content.toDouble() }, segs.map { round(it.p * 100) / 100 }, "$name: 구간 확률")
        }
    }
}
