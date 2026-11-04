package kr.aigyeot.app.exec

import kr.aigyeot.engine.Area
import kr.aigyeot.engine.ControlKind
import kr.aigyeot.engine.FocusChange
import kr.aigyeot.engine.Method
import kr.aigyeot.engine.RestCandidate

// Pleos·안드로이드 API를 감싼 얇은 경계. 실제는 Android.kt, 시험은 가짜. 콜백은 모두 Exec의 잠금 안에서 불린다.

/** 시간 예약. 돌려준 함수를 부르면 취소된다. */
internal fun interface Sched { fun after(ms: Long, task: () -> Unit): () -> Unit }

/** 재생(AudioManager·AudioTrack·MediaPlayer). 재생은 한 번에 하나다. */
internal interface AudioPort {
    /** C74: getDevices(OUTPUTS)에 bus100_audio_zone_1이 있는가. */
    fun hasRearOutput(): Boolean
    /** USAGE_MEDIA 포커스 요청(동기). 거절이면 false. 바뀌면 [onChange]. */
    fun requestFocus(onChange: (FocusChange) -> Unit): Boolean
    fun abandonFocus()
    /** 음원을 연다. 못 열면 null. [rear]면 뒷좌석 존에 setPreferredDevice. 재생 중 경로가 바뀌면 [onRouted], 오류면 [onError]. */
    fun open(method: Method, rear: Boolean, onRouted: (String?) -> Unit, onError: () -> Unit): Track?
}

internal interface Track {
    /** 소리 없이 시작해 경로를 정하고 getRoutedDevice 주소를 돌려준다(모르면 null). 이득은 그 뒤 [gain]으로 처음 정한다. */
    fun start(): String?
    /** getRoutedDevice 주소를 한 번 읽는다(아직 모르면 null). */
    fun routed(): String?
    /** 트랙 이득(0~1). 시스템 볼륨은 건드리지 않는다. [start] 뒤에 처음 불린다. */
    fun gain(g: Float)
    fun pause()
    fun resume()
    fun release()
}

/** Pleos TTS. 운전자 한 문장(C72). [done]은 말했으면 true. */
internal interface TtsPort {
    fun speak(text: String, done: (Boolean) -> Unit)
    fun stop()
}

/** Pleos STT ON_DEVICE, PCM 직접 넣기(sendAudio). 11-03 실측 경로(stt_listen_1103). */
internal interface SttPort {
    /** [onText]의 두 번째 값은 최종 결과인지(ResultListener.onUpdated의 completed). */
    fun begin(onText: (String, Boolean) -> Unit)
    fun feed(pcm: ByteArray)
    fun finish()
    fun cancel()
}

/** 마이크 서비스 PCM 받기(MicService.pcmTap, 16kHz mono s16le). */
internal interface MicTap {
    fun open(sink: (ByteArray) -> Unit)
    fun close()
}

/** 차 제어 넷의 쓰기·다시 읽기. 값은 불리언 0.0·1.0, 열선 단계, 설정 온도 ℃. */
internal interface ControlPort {
    fun read(kind: ControlKind, area: Area, cb: (Double?) -> Unit)
    fun write(kind: ControlKind, area: Area, value: Double, ack: (Boolean) -> Unit)
    /** 공조 설정 온도 최솟값(차가 알려 주는 범위, C101). 못 읽으면 null — [Controls]가 `Params.hvacMinC`를 쓴다. */
    fun minTemp(area: Area, cb: (Double?) -> Unit) = cb(null)
}

/** NaviHelper addWaypoint. 결과 콜백(경로 변경)이나 오류만 [done]으로 준다(C75). [note]는 ControlLog actual. */
internal interface NaviPort {
    /** [rest]는 판단 코어 경로 상태의 그 쉼터(이름·남은 거리, 좌표 찾기에 쓴다). 모르면 null. */
    fun addWaypoint(restId: String, rest: RestCandidate?, done: (ok: Boolean, note: String) -> Unit)
    /** 요청을 닫았다(성공·실패·시간 초과·대체). 위치 대기·결과 대기·대조 대상을 모두 비워 늦은 응답이 경유지를 더하거나 결과가 되지 않게 한다. */
    fun cancelWaypoint()
}
