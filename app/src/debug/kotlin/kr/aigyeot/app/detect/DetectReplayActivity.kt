package kr.aigyeot.app.detect

import android.app.Activity
import android.os.Bundle
import android.util.Log
import kr.aigyeot.engine.CrySegment
import kr.aigyeot.engine.Event
import kr.aigyeot.engine.InputLost
import kr.aigyeot.engine.InputRestored
import java.io.File
import java.util.Locale

// debug 전용(기기 회귀): 앱 files 폴더의 .pcm(16kHz mono s16le)을 감지 파이프라인(실제 YAMNet + 판별층)에 넣고,
// 프로브 SoundProbe와 같은 형식의 로그를 찍는다. 태그 DETECT. 맥 기준값(ref.json)과 소수 둘째 자리까지 같아야 한다.
//   am start -n kr.aigyeot.app/.detect.DetectReplayActivity --user 10 [--es file 이름] [--ez realtime true] [--ei repeat N]   (이름 생략하면 .pcm 전부, realtime은 창마다 0.975초를 기다려 마이크 속도로 흘린다)
class DetectReplayActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repeatN = intent.getIntExtra("repeat", 1)
        realtime = intent.getBooleanExtra("realtime", false)
        val only = intent.getStringExtra("file")
        val files = filesDir.listFiles { f -> f.extension == "pcm" && (only == null || f.nameWithoutExtension == only) }!!.sorted()
        Log.i(TAG, "CMD replay dir=$filesDir files=${files.size}")
        Thread { files.forEach { replay(it) }; Log.i(TAG, "DONE") }.start()
    }

    private var realtime = false
    private var repeatN = 1

    private fun replay(f: File) {
        val head = CryHead.parse(assets.open(MicService.HEAD_ASSET).bufferedReader().use { it.readText() })
        val scorer = MediaPipeScorer(this)
        val segs = mutableListOf<CrySegment>()
        val times = mutableListOf<Long>()
        val timed = WindowScorer { w -> val a = System.nanoTime(); val r = scorer.score(w); synchronized(times) { times += System.nanoTime() - a }; r }
        repeat(repeatN) { replayOnce(f, head, timed, scorer, segs) }
        val ts = synchronized(times) { times.sorted() }
        if (ts.isNotEmpty()) Log.i(TAG, "SCORE ${f.nameWithoutExtension} windows=${ts.size} median=${ts[ts.size / 2] / 1000}us p99=${ts[(ts.size * 0.99).toInt().coerceAtMost(ts.size - 1)] / 1000}us max=${ts.last() / 1000}us")
        scorer.close()
    }

    private fun replayOnce(f: File, head: CryHead, timed: WindowScorer, scorer: MediaPipeScorer, segs: MutableList<CrySegment>) {
        segs.clear()
        val detector = CryDetector(head, timed, System::currentTimeMillis) { e: Event ->
            when (e) {
                is CrySegment -> synchronized(segs) { segs += e }
                is InputLost -> Log.i(TAG, "${f.nameWithoutExtension} InputLost ${e.reason}")
                is InputRestored -> Log.i(TAG, "${f.nameWithoutExtension} InputRestored ${e.reason}")
                else -> {}
            }
        }
        val t = System.nanoTime()
        val runner = DetectorRunner(FilePcmSource(f, realtime = realtime), detector)
        runner.start(); runner.join()
        val ms = (System.nanoTime() - t) / 1_000_000
        val p = synchronized(segs) { segs.toList() }
        Log.i(TAG, "${f.nameWithoutExtension} ${f.length() / 32000.0}s took ${ms}ms")
        Log.i(TAG, "HEAD ${f.nameWithoutExtension} cry=${p.joinToString(", ") { String.format(Locale.ROOT, "%.2f", it.p) }} alarm=${p.any { it.alarm }}")
    }

    private companion object { const val TAG = "DETECT" }
}
