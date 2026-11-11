package kr.aigyeot.phone.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kr.aigyeot.phone.BuildConfig
import kr.aigyeot.phone.domain.model.CareJson

// 정보·라이선스(phone-care-plan U28·U30). 목록은 `scripts/oss_licenses.py`가 release 런타임 의존성의 POM에서 만든 assets/licenses/oss.json이다.
// 전문이 있는 것(Apache 2.0, protobuf BSD-3-Clause)은 전문을, Google 약관은 이름과 POM의 공식 주소를 보인다.

@Serializable
data class OssLicense(val name: String, val url: String, val file: String? = null)

@Serializable
data class OssEntry(val name: String, val artifact: String, val licenses: List<OssLicense>)

@Composable
private fun rememberOss(): List<OssEntry> {
    val context = LocalContext.current
    return remember {
        runCatching {
            context.assets.open("licenses/oss.json").use { CareJson.decodeFromString(ListSerializer(OssEntry.serializer()), it.readBytes().decodeToString()) }
        }.getOrDefault(emptyList())
    }
}

/** about.version · about.oss: "버전 {버전}"과 "오픈소스 라이선스" 목록. 누르면 그 라이브러리의 라이선스. */
@Composable
fun AboutScreen(onBack: () -> Unit, open: (Int) -> Unit) {
    val list = rememberOss()
    BackPage("정보·라이선스", onBack) {
        Block {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppMark(48.dp, 14.dp)
                Column(Modifier.padding(start = 14.dp)) {
                    Text("아이곁", style = Type.title, color = Tokens.colors.textPrimary)
                    Text("버전 ${BuildConfig.VERSION_NAME}", style = Type.caption, color = Tokens.colors.textTertiary)
                }
            }
        }
        ListBlock("오픈소스 라이선스") {
            list.forEachIndexed { i, e -> ListRow(e.name, e.licenses.joinToString(" · ") { it.name }, onClick = { open(i) }) }
        }
    }
}

/** 한 라이브러리의 라이선스: 전문 파일이 있으면 전문, 없으면(이용 약관) 이름과 주소. */
@Composable
fun LicenseScreen(index: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val e = rememberOss().getOrNull(index)
    BackPage(e?.name ?: "", onBack) {
        if (e == null) return@BackPage
        Block {
            Text(e.artifact, Modifier.padding(bottom = 10.dp), style = Type.caption, color = Tokens.colors.textTertiary)
            for (l in e.licenses) {
                Text(l.name, Modifier.padding(bottom = 6.dp), style = Type.headline, color = Tokens.colors.textPrimary)
                val body = l.file?.let { f -> runCatching { context.assets.open("licenses/$f").use { it.readBytes().decodeToString() } }.getOrNull() }
                Text(body ?: l.url, style = Type.caption, color = Tokens.colors.textSecondary)
            }
        }
    }
}
