import java.util.Properties

// 차 앱. 여러 갈래가 함께 쓰는 공용 파일이라 조율 담당만 고친다.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// release 서명: 루트 local.properties(공개 안 함)의 aigyeot.release.storeFile·storePassword·keyAlias·keyPassword.
// 넷이 모두 있을 때만 쓰고, 하나라도 없으면 지금처럼 서명 없는 release를 만든다(빌드가 깨지지 않게). 키·비밀번호는 저장소에 두지 않는다.
val releaseKey: Map<String, String>? = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.reader(Charsets.UTF_8)?.use { load(it) }
}.let { p ->
    listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
        .associateWith { p.getProperty("aigyeot.release.$it").orEmpty() }   // 비밀번호 앞뒤 공백도 비밀번호라 trim하지 않는다. 단 Properties 형식은 `=` 뒤 앞 공백을 지우므로 공백으로 시작하는 비밀번호는 `\ `로 적는다
        .takeIf { m -> m.values.all { it.isNotEmpty() } }   // 공백만으로 된 비밀번호도 쓴다(isNotBlank면 서명이 조용히 빠짐)
}

android {
    namespace = "kr.aigyeot.app"
    compileSdk = 37
    useLibrary("android.car")
    defaultConfig {
        applicationId = "kr.aigyeot.app"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }   // Pleos 에뮬레이터(arm64·x86_64)와 실차(arm64)만. APK 크기를 줄인다
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
        release { if (releaseKey != null) signingConfig = signingConfigs.getByName("release") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true } // Robolectric(Room in-memory 시험)
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":shared"))
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.navi)
    implementation(libs.pleos.stt)
    implementation(libs.pleos.tts)
    implementation(libs.mediapipe.tasks.audio)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.core)
    implementation(libs.filament.android)   // 홈 무대 3D(디자인 v6): GLB 그리기
    implementation(libs.filament.gltfio)
    implementation(libs.filament.utils)     // 환경광(IBL) 미리 거르기·KTX
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit4)
    testImplementation(kotlin("test-junit")) // 갈래 ① 감지 시험(assertNotNull 등). JUnit4 위에서 돈다
    testImplementation(libs.robolectric) // store·link Room 시험을 JVM에서
    testImplementation(libs.androidx.test.core)
}
