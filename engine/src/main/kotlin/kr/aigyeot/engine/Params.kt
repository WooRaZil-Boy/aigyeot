package kr.aigyeot.engine

// 판단 코어의 설계값 모음.
data class Params(
    // 판단·시도·말하기에 쓰는 값
    val alarmThreshold: Double = 0.7,
    val alarmSegments: Int = 2,
    val attemptMs: Long = 56_000,
    val gapMs: Long = 8_000,
    val confirmMs: Long = 30_000,
    val fadeMs: Long = 2_000,
    val maxAutoAttempts: Int = 2,
    val resumeQuietMs: Long = 300_000,
    val arrivalNoticeMaxSec: Int = 600,
    val restSearchKm: Double = 30.0,
    val nursingExtraKm: Double = 5.0,
    val longDriveMs: Long = 7_200_000,
    val routeWaitMs: Long = 60_000, // 복원 뒤 경로 응답을 기다리는 시간(설계값)
    val speechGapMs: Long = 8_000,
    val offerRepeatMs: Long = 600_000,
    val comfortTempC: Double = 26.0,
    val comfortDeltaC: Double = -2.0,
    val comfortHvacAreas: List<Area> = listOf(Area.ROW_1_LEFT),
    val hvacMinC: Double = 15.0, // 공조 설정 최솟값을 차에서 못 읽을 때의 하한(에뮬레이터 실측)
    val feedingWindowStart: Double = 0.8,
    val feedingWindowEnd: Double = 2.0,
    val targetLufs: Double = -30.0,
    val lufsTolerance: Double = 1.0,
    val maxGain: Double = 1.0,
    val fallbackGain: Double = 0.3,
    val requestPlayMs: Long = 56_000,
    val episodePlayCapMs: Long = 112_000,
    val sessionPlayCapMs: Long = 1_200_000,
    val earTargetDba: Int = 50, // 코드에서 쓰지 않는다
    val statWindowDays: Int = 28,
    val retentionDays: Int = 90,
    val verifyFirstMs: Long = 100,
    val verifyIntervalMs: Long = 150,
    val verifyTimeoutMs: Long = 5_000,
    val verifyRetries: Int = 1,
    val waypointPrecheckMs: Long = 2_000, // 보내기 전 경유지 목록 응답을 기다리는 시간
    val waypointTimeoutMs: Long = 20_000, // 경유지 성공 판정 한도
    val touchTargetDp: Int = 56,
    val pairCodeDigits: Int = 6,
    val pairCodeTtlMs: Long = 300_000,
    val inviteTtlMs: Long = 86_400_000,
    val relayKeepDays: Int = 7,
    val songMaxCount: Int = 3,
    val songMaxSec: Int = 180,
    val songMaxBytes: Long = 2_000_000,
    // 그 밖의 설계값
    val yesNoListenMs: Long = 6_000,
    val qaListenMs: Long = 5_000,
    val qaReplyWaitMs: Long = 3_000,
    val arrivalMinDriveMs: Long = 300_000,
    val longDriveResetParkMs: Long = 900_000,
    val roundRobinPerMethod: Int = 2,
    val statMinShown: Int = 3,
    val contextHalfMs: Long = 30_000,
    val contextStepMs: Long = 1_000,
    val songPeakMaxDbfs: Double = -1.0,
    val departLeadMin: Int = 20,
    val feedingParkConfirmMs: Long = 900_000,
    val dedupDays: Int = 7,
    val reconnectMaxMs: Long = 60_000,
    val steerLowpassHz: Double = 0.6,
    val steerReversalDeg: Double = 3.0,
    val snapshotIntervalMs: Long = 30_000, // 스냅숏 묶어 저장 간격. 설계값·근거 없음
    val steerDirMinDeg: Double = 0.5, // 첫 방향을 정하는 최소 움직임(잡음 위). 설계값·근거 없음, 첫날 센서 잡음으로 조정
    val steerWindowMs: Long = 60_000,
    val holdStableMs: Long = 3_000, // 바쁨이 끝난 뒤 미룬 문장을 낼 때까지 잠잠해야 하는 시간. 설계값·근거 없음
    val holdMaxMs: Long = 30_000, // 미룰 수 있는 문장을 큐에 둘 수 있는 최대 시간(8초 간격+긴 문장 8초+3초 잠잠=19초 > 15초). 설계값
    val steerHoldDeg: Double = 30.0, // 이 이상이면 조향이 큰 것으로 본다. 설계값
    val yamnetHopMs: Long = 975,
    val ambientDimLevel: Double = 0.2, // 앰비언트: 낮출 밝기 = min + (max − min) × level. 설계값·근거 없음
    // 앰비언트: 차분한 어두운 파랑(알파 0xFF). 설계값·근거 없음
    val ambientDimColor: Int = 0xFF3050A0.toInt()
)
