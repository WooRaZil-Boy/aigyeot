package kr.aigyeot.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Pleos 디자인 값(pleos-app-reference 2.2): 회색 토큰 라이트·다크, 글자 위계(본문 26~30, 라벨 20~24), 대비 4.5:1.

/** 서체를 바꿀 자리는 여기 하나다. Asta Sans는 내려받기 허락 전이라 시스템 서체를 쓴다. */
val AppFont: FontFamily = FontFamily.Default

/** 터치 영역(설계값 56dp, 구현 계획 2장). */
val Touch = 56.dp

private val Light = lightColorScheme(
    background = Color(0xFFF7F8FA), surface = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFEDEEF2),
    onBackground = Color(0xFF131417), onSurface = Color(0xFF131417), onSurfaceVariant = Color(0xFF44464E),
    primary = Color(0xFF0064FF), onPrimary = Color(0xFFFFFFFF), outline = Color(0xFFC7C9CE),
    // Pleos 빨강 토큰(#F62E24)은 면 색이다. 밝은 배경 위 글자는 대비 4.5:1이 되게 어둡게 쓴다(#F7F8FA 대비 약 5.2)
    error = Color(0xFFD0190F), onError = Color(0xFFFFFFFF),
)
private val Dark = darkColorScheme(
    background = Color(0xFF1D1E21), surface = Color(0xFF131417), surfaceVariant = Color(0xFF313236),
    onBackground = Color(0xFFFFFFFF), onSurface = Color(0xFFFFFFFF), onSurfaceVariant = Color(0xFFE0E1E6),
    // 다크 파랑(#0082FF) 위 흰 글자는 대비 4.5:1이 안 돼 어두운 글자를 쓴다
    primary = Color(0xFF0082FF), onPrimary = Color(0xFF131417), outline = Color(0xFF686A72),
    error = Color(0xFFFF4339), onError = Color(0xFF131417),
)

private fun style(size: Int, line: Int, w: FontWeight = FontWeight.Normal) = TextStyle(fontFamily = AppFont, fontSize = size.sp, lineHeight = line.sp, fontWeight = w)

private val Type = Typography(
    headlineSmall = style(48, 64, FontWeight.Bold),
    titleLarge = style(40, 52, FontWeight.Bold), titleMedium = style(36, 44, FontWeight.Bold), titleSmall = style(32, 40, FontWeight.Bold),
    bodyLarge = style(30, 38), bodyMedium = style(28, 36), bodySmall = style(26, 34),
    labelLarge = style(24, 28, FontWeight.Medium), labelMedium = style(20, 24), labelSmall = style(20, 24),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = Type) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
    }

/** 한 화면 틀: 제목, 뒤로, 세로 스크롤. portrait 한 창(628~840dp)을 기준으로 가운데 둔다. */
@Composable
fun Page(title: String, onBack: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) =
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 840.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (onBack != null) OutlinedButton(onBack, Modifier.heightIn(min = Touch)) { Text("뒤로", style = MaterialTheme.typography.labelLarge) }
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }

/** 큰 버튼(56dp 이상, 라벨 24). */
@Composable
fun BigButton(text: String, enabled: Boolean = true, primary: Boolean = true, onClick: () -> Unit) {
    val m = Modifier.fillMaxWidth().heightIn(min = Touch)
    val label: @Composable () -> Unit = { Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    if (primary) Button(onClick, m, enabled) { label() } else OutlinedButton(onClick, m, enabled) { label() }
}

/** 한 메시지는 3줄 이하(Pleos 가독성). */
@Composable
fun Body(text: String, color: Color = Color.Unspecified) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 3, overflow = TextOverflow.Ellipsis)

@Composable
fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
