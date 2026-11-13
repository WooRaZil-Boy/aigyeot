package kr.aigyeot.app.common

import android.content.Context
import java.io.File

/** 파일 위치 약속(고정 경로). */
object CarPaths {
    fun songDownload(ctx: Context, songId: String) = File(ctx.filesDir, "songs/$songId.download")  // link가 받은 원본(검사 전)
    fun songFile(ctx: Context, songId: String) = File(ctx.filesDir, "songs/$songId.m4a")   // −30 LUFS 사본. exec가 튼다
    fun scenarioDir(ctx: Context) = File(ctx.filesDir, "scenario")                          // 디버그로 흘려 넣는 장면·녹음(계약 7.1)
    fun scenarioResults(ctx: Context) = File(ctx.filesDir, "scenario-results")              // 측정 출력(계약 7.4)
}
