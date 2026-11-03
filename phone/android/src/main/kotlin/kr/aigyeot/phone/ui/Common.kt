package kr.aigyeot.phone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kr.aigyeot.phone.core.instantOf
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val hm = DateTimeFormatter.ofPattern("HH:mm")
private val mdhm = DateTimeFormatter.ofPattern("M월 d일 HH:mm")

/** RFC 3339 → 이 폰 시간대의 "08:12". */
fun clock(s: String?): String = instantOf(s)?.let { clock(it) } ?: "—"
fun clock(ms: Long): String = hm.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
fun dateTime(s: String?): String = instantOf(s)?.let { mdhm.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) } ?: "—"
fun minutesAgo(ms: Long, now: Long = System.currentTimeMillis()) = ((now - ms) / 60_000).coerceAtLeast(0)
fun seconds(s: Int) = if (s >= 60) "${s / 60}분 ${s % 60}초" else "${s}초"

/** 제목과 '뒤로'가 있는 한 화면. 내용은 세로로 스크롤한다. */
@Composable
fun Page(title: String, onBack: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                TextButton(onClick = onBack) { Text("뒤로") }
                Spacer(Modifier.width(4.dp))
            }
            Text(title, style = MaterialTheme.typography.headlineSmall)
        }
        content()
    }
}

/** 위쪽 띠(오프라인·경고, 7.2). */
@Composable
fun Band(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (action != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
fun Muted(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
