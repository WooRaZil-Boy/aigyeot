import java.util.Properties

// 안드로이드 휴대폰 앱. Firebase 설정 파일(google-services.json)은 공개하지 않으므로 있을 때만 플러그인을 건다.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}
if (file("google-services.json").exists()) apply(plugin = libs.plugins.google.services.get().pluginId)

// 중계 주소. debug는 에뮬레이터에서 맥의 wrangler dev(10.0.2.2:8787), release는 배포한 주소를 -Paigyeot.relayUrl=https://… 로 넣는다.
val relayDebug = providers.gradleProperty("aigyeot.relayUrl.debug").getOrElse("http://10.0.2.2:8787")
val relayRelease = providers.gradleProperty("aigyeot.relayUrl").getOrElse("https://aigyeot-relay.invalid")

// release 서명: 루트 local.properties(공개 안 함)의 aigyeot.release.storeFile·storePassword·keyAlias·keyPassword.
// 넷이 모두 있을 때만 쓰고, 하나라도 없으면 지금처럼 서명 없는 release를 만든다(빌드가 깨지지 않게). 키·비밀번호는 저장소에 두지 않는다.
val releaseKey: Map<String, String>? = Properties().apply {
    // UTF-8로 읽는다(InputStream으로 읽으면 ISO-8859-1이라 '../서명/아이곁.p12' 같은 한글 경로·비밀번호가 깨진다). \uXXXX 이스케이프도 그대로 풀린다.
    rootProject.file("local.properties").takeIf { it.isFile }?.reader(Charsets.UTF_8)?.use { load(it) }
}.let { p ->
    listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
        // 값은 그대로 둔다(비밀번호 앞뒤 공백도 비밀번호다). 다만 Properties.load가 `=` 뒤 앞 공백을 지우므로 공백으로 시작하는 비밀번호는 `\ `로 적는다.
        .associateWith { p.getProperty("aigyeot.release.$it").orEmpty() }
        .takeIf { m -> m.values.all { it.isNotBlank() } }
}

android {
    namespace = "kr.aigyeot.phone"
    compileSdk = 37
    defaultConfig {
        applicationId = "kr.aigyeot.phone"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }
    signingConfigs {
        if (releaseKey != null) create("release") {
            storeFile = rootProject.file(releaseKey.getValue("storeFile")) // 절대 경로 또는 저장소 루트 기준
            storePassword = releaseKey.getValue("storePassword")
            keyAlias = releaseKey.getValue("keyAlias")
            keyPassword = releaseKey.getValue("keyPassword")
        }
    }
    buildTypes {
        debug { buildConfigField("String", "RELAY_URL", "\"$relayDebug\"") }
        release {
            buildConfigField("String", "RELAY_URL", "\"$relayRelease\"")
            if (releaseKey != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose) // 화면마다 ViewModel(이미 navigation-compose가 끌어오는 판)
    implementation(libs.navigation.compose)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.code.scanner)
    implementation(libs.zxing.core) // 초대 QR 만들기
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit4)
}
