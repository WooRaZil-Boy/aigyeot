plugins { id("com.android.application") }
android {
    namespace = "dev.probe.pleos"
    compileSdk = 36
    useLibrary("android.car")
    defaultConfig { applicationId = "dev.probe.pleos"; minSdk = 28; targetSdk = 34; versionCode = 1; versionName = "1" }
}
dependencies { implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("com.google.mediapipe:tasks-audio:0.10.14") }
