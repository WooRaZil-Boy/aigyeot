package kr.aigyeot.phone.domain.model

import java.time.ZoneId
import kr.aigyeot.shared.CarActionKind
import kr.aigyeot.shared.ControlRecord
import kr.aigyeot.shared.DeviceInfo
import kr.aigyeot.shared.EpisodeTrigger
import kr.aigyeot.shared.ExcludedReason
import kr.aigyeot.shared.HandoffKind
import kr.aigyeot.shared.OutputRoute
import kr.aigyeot.shared.Platform
import kr.aigyeot.shared.PlayEntry
import kr.aigyeot.shared.PlayPurpose
import kr.aigyeot.shared.PreCryContext
import kr.aigyeot.shared.Role
import kr.aigyeot.shared.SecondAction
import kr.aigyeot.shared.SecondAttempt
import kr.aigyeot.shared.SongRejectReason
import kr.aigyeot.shared.SoundMethod
import kr.aigyeot.shared.SoundRef
import kr.aigyeot.shared.StopInfo
import kr.aigyeot.shared.StopReason

// 화면 문구 고르기(규칙 하나 함수 하나). 리포트·노래·차 타일·수유 전송 상태. 화면은 이 함수의 글을 그리기만 한다.

/** car.songs.full·거절 이유 `limit_reached`: 3곡이 찼을 때 목록 아래 안내와 거절 글이 같다(iOS와 같음). */
const val SONGS_FULL = "노래는 3곡까지예요. 한 곡을 지운 뒤 보내 주세요."
// ---- 리포트 상세 ----

/** 조향 비교 불가 사유 문구. */
fun steeringUnavailableText(noCry: Boolean) = if (noCry) "울음이 없어 비교하지 않았어요" else "비교할 구간이 없어요"

fun triggerText(t: EpisodeTrigger) = if (t == EpisodeTrigger.CRY_ALARM) "울음 감지" else "소리 바꾸기 요청"

/** 에피소드 한 건의 이름(띠 읽기 이름 머리 등, 차 앱 `episodeHead`와 같은 이름·낱말): 울음 경보는 "울음", 그 밖은 "소리 바꾸기 요청". */
fun episodeHead(t: EpisodeTrigger) = if (t == EpisodeTrigger.CRY_ALARM) "울음" else "소리 바꾸기 요청"

/**
 * 리포트 상세 에피소드 블록 머리: 모두 울음 경보(또는 없음)면 "울음", 모두 운전자 요청이면 "소리 바꾸기 요청",
 * 섞이면 "울음과 소리 바꾸기 요청". 낱말은 [episodeHead] 그대로다.
 */
fun episodeSectionHead(triggers: List<EpisodeTrigger>): String {
    val cry = episodeHead(EpisodeTrigger.CRY_ALARM)
    val heads = triggers.map(::episodeHead).distinct()
    return when {
        heads.isEmpty() -> cry
        heads.size == 1 -> heads.single()
        else -> "${cry}과 ${heads.first { it != cry }}"
    }
}

/**
 * report.route(iOS와 같음). 폴백은 한 번이라도 앞에서 들렸다는 뜻이라 원인·전부를 말하지 않는다.
 * 재생이 없었으면 출력 경로를 보이지 않는다(null).
 */
fun outputRouteText(r: OutputRoute): String? = when (r) {
    OutputRoute.REAR_ZONE -> "소리 나간 곳 · 뒷좌석"
    OutputRoute.DEFAULT_ZONE_FALLBACK -> "소리 나간 곳 · 앞에서도 들림"
    OutputRoute.NO_PLAYBACK -> null
}

fun purposeText(p: PlayPurpose) = when (p) {
    PlayPurpose.AUTO_FIRST -> "자동 1차"
    PlayPurpose.AUTO_SECOND -> "자동 2차"
    PlayPurpose.REQUEST -> "운전자 요청"
}

/** 울음 한 건의 소리. 우리 노래는 이 폰이 아는 곡 이름, 모르면 차가 보낸 글자. 틀지 않았으면 "재생 없음". */
fun soundText(songs: List<SongEntry>, r: SoundRef?, songId: String? = null): String = when {
    r == null -> "재생 없음"
    r.method == SoundMethod.PARENT_SONG -> songs.find { it.songId == songId }?.name ?: soundLabel(r.method, r.label)
    else -> soundLabel(r.method, r.label)
}

/** 재생 한 줄의 음원 이름. */
fun playName(songs: List<SongEntry>, p: PlayEntry): String =
    if (p.method == SoundMethod.PARENT_SONG) songs.find { it.songId == p.songId }?.name?.let { "우리 노래($it)" } ?: "우리 노래" else methodName(p.method)

/** report.preCry.more(iOS와 같음): 기어 → 방향지시등 → 소리 재생 → 창 순서, 거짓·null은 쓰지 않는다. */
fun preCryDrive(p: PreCryContext) =
    "기어 ${p.gear.name}" + (if (p.turnSignalUsed) ", 방향지시등 씀" else "") + (if (p.playing) ", 소리 재생 중" else "") + (if (p.windowOpen == true) ", 창 열림" else "")

/** 울음 직전 실내 온도: 소수 한 자리 + ℃(iOS "%.1f℃"와 같음). */
fun cabinTempText(c: Double) = String.format(java.util.Locale.ROOT, "%.1f℃", c)

fun secondText(songs: List<SongEntry>, sec: SecondAttempt): String {
    val what = when (sec.action) {
        SecondAction.COMFORT_COOL -> "더운 실내로 판단했어요"
        SecondAction.FEEDING_WINDOW -> "수유 시간대로 판단했어요"
        SecondAction.OTHER_SOUND -> "다른 소리로 바꿨어요" + (sec.sound?.let { " · ${soundText(songs, it)}" } ?: "")
        SecondAction.FOCUS_DENIED -> "소리를 이어서 틀지 못했어요"
    }
    return what + (sec.result?.let { " · ${resultText(it)}" } ?: "")
}

/** 성적에서 뺀 이유(차·두 앱 같은 낱말). 열거 이름을 화면에 보이지 않는다. */
fun excludedText(e: ExcludedReason) = when (e) {
    ExcludedReason.DRIVER_STOP -> "운전자가 멈춤"
    ExcludedReason.CALL -> "통화 중"
    ExcludedReason.GEAR_TO_PARK, ExcludedReason.STARTED_IN_PARK -> "정차 중"
    ExcludedReason.SWITCH_SOUND -> "소리 바꾸기 요청"
    ExcludedReason.FEEDING_WINDOW -> "수유 시간대"
    ExcludedReason.SESSION_CAP -> "달래기 20분 끝남"
    ExcludedReason.SESSION_END -> "탑승이 끝남"
    ExcludedReason.MIC_ERROR -> "마이크 오류"
    ExcludedReason.DETECTOR_TIMEOUT -> "감지 시간 초과"
    ExcludedReason.INPUT_SILENCED -> "마이크 입력이 막힘"
    ExcludedReason.FOCUS_LOSS -> "소리를 이어서 틀지 못함"
    ExcludedReason.SUSPEND -> "차가 절전에 들어감"
}

/** 차가 운전자에게 말하지 못한 인계(toldDriver == false)는 제안했다고 보이지 않는다. */
fun stopText(
    reason: StopReason,
    mode: HandoffKind,
    rest: String?,
    toldDriver: Boolean? = null,
    waypointAdded: Boolean = false,
    guardianNotified: Boolean = false
) = buildString {
    append(
        when (reason) {
            StopReason.ATTEMPT_LIMIT -> "두 번 시도 뒤"
            StopReason.SESSION_CAP -> "달래기 20분 끝남"
            StopReason.FEEDING_WINDOW -> "수유 시간대"
            StopReason.NO_SOUND -> "뒷좌석 출력 없음"
        }
    )
    append(" · ")
    append(
        when (mode) {
            HandoffKind.REST_OFFER -> if (toldDriver == false) "쉼터는 묻지 못했어요" else "${rest ?: "쉼터"}에 들르자고 제안했어요"
            HandoffKind.ARRIVAL_NOTICE -> if (toldDriver == false) "도착 안내는 하지 못했어요" else "도착 안내를 했어요"
            HandoffKind.NO_REST -> if (toldDriver == false) "안전한 곳에 세우도록 안내하지 못했어요" else "경유지로 넣을 수 있는 가까운 쉼터를 찾지 못해 안전한 곳에 세우도록 안내했어요"
            HandoffKind.PARKED -> if (reason == StopReason.NO_SOUND) "차가 선 채로 소리를 틀지 못했어요" else "차가 선 채로 달래기를 멈췄어요"
        }
    )
    if (waypointAdded) append(" · 경유지 추가")
    if (guardianNotified) append(" · 다른 보호자에게 알림")
}

/** 정차 한 줄의 값(뒤붙임 포함). 줄 이름 '정차'는 화면이 붙인다('이름: 값' 콜론 없음). */
fun stopLine(st: StopInfo) = stopText(st.reason, st.mode, st.restName, st.toldDriver, st.waypointAdded, st.guardianNotified)

/** told_driver == false인 정차와 정차 인계(parked, 운전자에게 말하지 않음)는 안내가 아니니 세지 않는다(null은 참). 실제 정차 횟수가 아니다. */
fun stopNoticeCount(stops: List<StopInfo?>) = stops.count { it != null && it.toldDriver != false && it.mode != HandoffKind.PARKED }

fun actionText(a: CarActionKind) = when (a) {
    CarActionKind.REAR_PLAYBACK -> "뒷좌석 재생"
    CarActionKind.DEFAULT_ZONE_PLAYBACK -> "앞에서도 들리게 재생"
    CarActionKind.SEAT_HEATER_OFF -> "뒷좌석 열선 끔"
    CarActionKind.REAR_WINDOW_LOCK -> "뒷창 잠금"
    CarActionKind.CABIN_COOL -> "실내 온도 낮춤"
    CarActionKind.READING_LIGHT_OFF -> "독서등 끔"
    CarActionKind.AMBIENT_DIM -> "조명 낮춤" // iOS와 같은 글자
    CarActionKind.WAYPOINT_ADDED -> "경유지 추가"
    CarActionKind.ARRIVAL_NOTICE -> "도착 안내"
    CarActionKind.GUARDIAN_ALERT -> "다른 보호자 알림"
}

/** 보호자 '연결된 휴대폰' 줄 부제(iOS와 같음): 'Android'·'iPhone', 이 휴대폰이면 뒤에 ' · 이 휴대폰'. 열거 이름을 보이지 않는다. */
fun phoneSub(d: DeviceInfo, myDeviceId: String?) =
    listOfNotNull(if (d.platform == Platform.IOS) "iPhone" else "Android", "이 휴대폰".takeIf { d.deviceId == myDeviceId }).joinToString(" · ")

/** 리포트 '제어 기록' 한 줄의 첫 줄(API 이름 · 대상, iOS ReportViews와 같은 글). */
fun controlTitle(c: ControlRecord) = "${c.api} · ${c.target}"

/** 리포트 '제어 기록' 한 줄의 둘째 줄(iOS ReportViews와 같은 글). */
fun controlStatsText(c: ControlRecord) = "시도 ${c.attempts} · 성공 ${c.succeeded} · 실패 ${c.failed} · 다시 ${c.retries}"

// ---- 우리 노래 ----

/** 거절 이유 문구. */
fun rejectText(r: SongRejectReason?) = when (r) {
    SongRejectReason.PEAK_LIMIT -> "조금 더 크게 다시 녹음해 주세요"
    SongRejectReason.SILENT -> "소리가 거의 들리지 않아요. 다시 녹음해 주세요."
    SongRejectReason.BAD_FORMAT -> "파일을 읽지 못했어요. 다시 녹음해 주세요."
    SongRejectReason.TOO_LONG -> "3분이 넘어요. 3분 안으로 다시 녹음해 주세요."
    SongRejectReason.TOO_LARGE -> "파일이 너무 커요. 다시 녹음해 주세요."
    SongRejectReason.LIMIT_REACHED -> SONGS_FULL
    SongRejectReason.DOWNLOAD_FAILED -> "차가 파일을 받지 못했어요. 다시 보내 주세요."
    null -> "차가 받지 않았어요."
}

/** 곡 상태 한 줄. '실험 후보'는 최신 song_status의 candidate로만 보이고, 고른 뒤 아직 못 보냈으면 그것만 덧붙인다. */
fun songStatusText(e: SongEntry, online: Boolean, selecting: Boolean): String = (
    e.error ?: when (e.phase) {
        SongPhase.UPLOADING -> if (online) "올리고 있어요" else "연결되면 보내요" // 우리 노래·오프라인: 보낼 함에서 기다린다
        SongPhase.WAITING -> "차가 받기를 기다려요"
        SongPhase.ACCEPTED -> if (e.candidate) "다음 주행부터 써요 · 실험 후보" else "다음 주행부터 써요"
        SongPhase.REJECTED -> "거절됨: ${rejectText(e.reason)}"
        SongPhase.REMOVED -> "지운 노래"
    }
    ) + if (selecting && !e.candidate) " · 고르는 중" else ""

// ---- 차 타일·수유 전송 상태 ----

/**
 * 홈 차 타일: 오프라인이면 알 수 없음, 아니면 연결됨 또는 "차 꺼짐 · 마지막 연락 {경과} 전".
 * {경과}는 경과 형식([agoText]): 1분 미만 "마지막 연락 방금", "5분 전"·"1시간 전"·"1시간 15분 전"(차 타일 값).
 */
fun carTileText(devices: List<DeviceInfo>, online: Boolean, now: Long): String {
    val car = devices.find { it.role == Role.CAR }
    return when {
        !online -> "알 수 없음"
        car == null -> "차를 기다리는 중"
        car.online -> "연결됨"
        else -> "차 꺼짐" + (instantOf(car.lastSeenAt)?.let { " · 마지막 연락 ${agoText(it, now)}" } ?: "")
    }
}

/** 오늘 카드의 수유 전송 상태. */
fun feedingSendText(r: FeedingRecord, zone: ZoneId): String = when {
    r.failed != null -> r.failed
    r.reflected -> "차에 반영됨"
    r.sentAt != null -> "보냄 ${koClock(r.sentAt, zone)}"
    else -> "보내는 중"
}
