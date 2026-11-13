package kr.aigyeot.app.scenario

import android.content.Context
import android.util.Log
import kr.aigyeot.app.common.CarPaths
import kr.aigyeot.app.exec.android.AndroidAudio
import kr.aigyeot.app.exec.Loudness
import kr.aigyeot.app.exec.SongLoudness
import kr.aigyeot.app.exec.WhiteNoise
import kr.aigyeot.engine.Method
import kr.aigyeot.engine.MethodKind

/** 음원 ① 백색소음을 exec 생성기로 [sec]초 만들어 exec [Loudness](BS.1770)로 잰다. */
fun whiteNoiseLufs(sec: Int = 10): Double? {
    val m = Loudness(WhiteNoise.RATE, 1)
    val noise = WhiteNoise(seed = 1)
    val buf = ShortArray(WhiteNoise.RATE)
    repeat(sec) { noise.fill(buf); m.addPcm16(buf, buf.size) }
    return m.integrated()
}

/** 장면에서 쓴 음원의 통합 크기 캐시(계약 7.4 lufs_violations). 잰 값만 남기고, 못 잰 것(null)은 다음에 다시 잰다. */
internal class SourceLoudness(private val measure: (Method) -> Double?) {
    private val cache = HashMap<Method, Double>()

    @Synchronized fun lufs(m: Method): Double? = cache[m] ?: measure(m)?.also { cache[m] = it }
}

/**
 * 음원 하나를 exec [Loudness](BS.1770)로 잰다. 음원은 exec가 트는 것과 같은 곳에서 연다(자장가 assets, 부모 노래 CarPaths.songFile). 못 재면 null.
 * 디코더는 부모 노래 사본과 같은 하나(SongLoudness.lufs: PCM 16비트 검사·멈춤 감지 같음).
 */
internal fun androidLufs(ctx: Context, m: Method): Double? = try {
    when (m.kind) {
        MethodKind.WHITE_NOISE -> whiteNoiseLufs()
        MethodKind.WOMB, MethodKind.LULLABY -> ctx.assets.openFd(if (m.kind == MethodKind.WOMB) AndroidAudio.WOMB_ASSET else AndroidAudio.LULLABY_ASSET).use { fd -> SongLoudness.lufs { it.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) } }
        MethodKind.SONG -> m.songId?.let { CarPaths.songFile(ctx, it) }?.takeIf { it.isFile }?.let { f -> SongLoudness.lufs { it.setDataSource(f.path) } }
    }
} catch (e: Exception) { Log.w("SCENARIO", "음원 크기를 재지 못함 $m", e); null }
