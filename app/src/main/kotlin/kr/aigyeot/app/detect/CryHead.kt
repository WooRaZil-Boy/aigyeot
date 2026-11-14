package kr.aigyeot.app.detect

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kr.aigyeot.engine.Params
import kotlin.math.exp

// 감지 상수. YAMNet 창은 0.975초 = 15600표본이다.
object Detect {
    const val SAMPLE_RATE = 16_000
    const val WINDOW_SAMPLES = 15_600
    /** 창 한 걸음은 판단 코어 Params.yamnetHopMs 하나(15600표본 ÷ 16 kHz). */
    val WINDOW_MS: Long = Params().yamnetHopMs
    const val SEGMENT_WINDOWS = 3
    const val SILENCE_SAMPLES = 3 * SAMPLE_RATE // rms 0.0이 3초
    const val TIMEOUT_MS = 3_000L // 구간이 3초 넘게 안 오면 판별기 무응답
}

// 울음 판별층: 고른 24개 YAMNet 클래스 점수의 3창 평균(24) + 최대(24) → 로지스틱(rt_train.py와 같은 식).
// 경보 문턱은 판단 코어 Params.alarmThreshold 하나다(SegmentTracker). 판별층 JSON의 "threshold"는 학습 기록이며 시험이 그 값과 같은지 본다
class CryHead(val classes: IntArray, val w: DoubleArray, val bias: Double) {
    init { require(w.size == classes.size * 2) { "가중치는 클래스 수의 2배여야 한다" } }

    /** [seg]는 3창(각 521 점수). 확률(0~1)을 돌려준다. */
    fun prob(seg: List<FloatArray>): Double {
        require(seg.size == Detect.SEGMENT_WINDOWS)
        var z = bias
        for (k in classes.indices) {
            var sum = 0.0
            var max = Double.NEGATIVE_INFINITY
            for (s in seg) { val v = s[classes[k]].toDouble(); sum += v; if (v > max) max = v }
            z += w[k] * sum / seg.size + w[classes.size + k] * max
        }
        return 1 / (1 + exp(-z))
    }

    companion object {
        // org.json은 JVM 시험에서 안 돌아 kotlinx.serialization으로 읽는다
        fun parse(json: String): CryHead {
            val o = Json.parseToJsonElement(json).jsonObject
            val cl = o.getValue("classes").jsonArray
            val w = o.getValue("w").jsonArray
            return CryHead(
                IntArray(cl.size) { cl[it].jsonPrimitive.content.toInt() },
                DoubleArray(w.size) { w[it].jsonPrimitive.content.toDouble() },
                o.getValue("bias").jsonPrimitive.content.toDouble(),
            )
        }

        /** 판별층 JSON에 학습 때 적은 문턱(없으면 null). 판정에는 쓰지 않는다: Params.alarmThreshold와 다르면 시험이 실패한다. */
        fun trainedThreshold(json: String): Double? = Json.parseToJsonElement(json).jsonObject["threshold"]?.jsonPrimitive?.doubleOrNull
    }
}
