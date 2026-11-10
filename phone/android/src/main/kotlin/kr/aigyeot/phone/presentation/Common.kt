package kr.aigyeot.phone.presentation

import androidx.compose.runtime.Composable
import kr.aigyeot.phone.domain.model.APP_ZONE
import kr.aigyeot.phone.domain.model.instantOf
import kr.aigyeot.phone.domain.model.koClock
import java.time.Instant
import java.time.format.DateTimeFormatter

private val md = DateTimeFormatter.ofPattern("M월 d일")

/** RFC 3339 → 이 폰 시간대의 "오전 8:12"(U20·7.1 시각 형식, 기기 언어와 무관). */
fun clock(s: String?): String = instantOf(s)?.let { clock(it) } ?: "—"
fun clock(ms: Long): String = koClock(ms, APP_ZONE)
fun dateTime(s: String?): String = instantOf(s)?.let { "${md.format(Instant.ofEpochMilli(it).atZone(APP_ZONE))} ${clock(it)}" } ?: "—"
fun seconds(s: Int) = if (s >= 60) "${s / 60}분 ${s % 60}초" else "${s}초"

/** 위쪽 띠(오프라인·경고, 7.2). 화면 읽기 알림은 [BandCard]가 맡는다. */
@Composable
fun Band(text: String, action: String? = null, onAction: () -> Unit = {}) = BandCard(text, action, onAction)

/** 화면의 액티비티(감싼 Context를 벗겨 찾는다). */
internal tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
