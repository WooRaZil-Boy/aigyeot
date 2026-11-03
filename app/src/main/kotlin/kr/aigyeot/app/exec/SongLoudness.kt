package kr.aigyeot.app.exec

import java.io.File

/** exec가 채우고 link가 부른다(contracts-link 8.4 '노래 받기 작업'의 '−30 LUFS 사본(실행기)'). */
object SongLoudness {
    /** 받은 원본을 −30 LUFS 사본으로 만든다. 거절이면 reason에 contracts-link 3.3 song_status 사유(peak_limit·silent 등). 처음엔 그대로 복사만 한다. */
    suspend fun makeCopy(input: File, output: File): LoudnessResult =
        if (runCatching { input.copyTo(output, overwrite = true) }.isSuccess) LoudnessResult(true) else LoudnessResult(false, "silent")
}

data class LoudnessResult(val ok: Boolean, val reason: String? = null)
