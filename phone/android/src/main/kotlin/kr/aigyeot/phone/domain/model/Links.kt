package kr.aigyeot.phone.domain.model

import kr.aigyeot.shared.Role

// 작업 상태(sealed). 화면이 회전·재구성돼도 작업은 앱 수명에서 끝나고, 화면은 이 값만 본다. 불가능한 조합(도는 중인데 오류 등)은 타입으로 막는다.

/**
 * 지금 연결(11-08 조율 결정, 두 앱 같음). 원본은 [kr.aigyeot.phone.domain.repository.PhoneStateRepository]이고 상태 파일과 한 잠금에서 바뀐다.
 * 자격이 있으면서 GONE인 조합은 없다(강제 끊김은 지운 뒤에 GONE이 된다).
 */
sealed interface Connection {
    data object None : Connection

    /** 같은 값이면 같은 연결 세대다. 비동기 결과는 보낼 때 잡은 이 자격으로 처리한다(P5). */
    data class Paired(val creds: Credentials) : Connection

    /** 401·닫기 4001로 끊겼고 GONE 화면("연결이 끊겼어요", 9장)에서 아직 고르지 않았다. */
    data class Gone(val deviceId: String) : Connection
}

val Connection.creds: Credentials? get() = (this as? Connection.Paired)?.creds

/** 코드로 연결하기(1.4·7.1). */
sealed interface PairRun {
    val code: String

    data class Running(override val code: String) : PairRun

    /** `resetOffer`: 연결 정보 읽기가 거듭 실패해 지우고 새로 연결할지 묻는다(11-07 사용자 결정). */
    data class Failed(override val code: String, val error: String, val resetOffer: Boolean = false) : PairRun

    /** 연결됐다. `role`이 null이면 끝난 사이 끊겨(GONE) 완료 화면으로 가지 않는다. */
    data class Done(override val code: String, val role: Role?) : PairRun
}

/** 설정의 '연결 끊기'. */
sealed interface DisconnectRun {
    data object Running : DisconnectRun
    data class Failed(val error: String) : DisconnectRun

    /** 끊었다(U25 b): 탭의 오늘로 간다. */
    data object Done : DisconnectRun
}

/** 녹음 시트의 노래 보내기(녹음 파일 경로별). */
sealed interface SongSend {
    data object Sending : SongSend
    data object Sent : SongSend
    data class Failed(val error: String) : SongSend
}

/** 앱 안 배너(5.6): 앞에 있을 때 온 인계 알림. 글과 알림 id는 한 값이다. */
data class Banner(val text: String, val alertId: String)

/** 연결 표시(9장·7.2). 자격이 바뀌면 처음 값으로 돌아간다. */
data class LinkStatus(
    /** 중계에 닿는가. 거짓이면 오프라인 띠. */
    val online: Boolean = true,
    /** 중계 시계 − 폰 시계(분). 2분 넘게 다르면 경고 띠. */
    val clockSkewMin: Long? = null,
)
