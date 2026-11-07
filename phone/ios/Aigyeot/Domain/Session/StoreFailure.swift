import Foundation

/// 저장 실패 처리 방침 하나(arch-phone-ios 7장, 차 앱 contracts-car 6.0 범주 방식). 지금 폰의 동작을 그대로 옮긴 것이다.
///
/// - 공통: 모든 쓰기는 저장소의 쓰기 입구 하나(PhoneStateRepository·CareRepository의 `update`, `switchTo`)를 지난다.
///   실패하면 그 자리에서 메모리를 되돌리고(롤백) 띠에 `NoticeRules.saveFailed`를 한 번 올린다(저장소의 `onWriteFailed`).
/// - 그 뒤 할 일만 범주마다 다르고 그 일꾼 한 곳이 한다: R은 `SyncInbox.scheduleRetry`, W는 `SendOutbox.retrySaveLater`. 간격은 RetryPolicy에만 있다.
///   이 enum은 범주 목록(문서)이다. 코드는 범주 이름을 주석으로 단다.
enum StoreFailure: Equatable {
    /// S 시작 복원: 깨진 원본을 옆에 남기고 빈 상태로 시작. 남기지 못하면 그 실행은 쓰기를 거부한다(저장 구현이 한다). 뒷일 없음.
    case start
    /// U 사용자 저장(돌봄 기록·노래·곡 선택·설정·안내 넘김): 공통 처리로 끝. 사용자가 다시 한다.
    case user
    /// R 받기(받은 함·실시간 저장, 24시간 재요청 표시): ack하지 않고 받은 함부터 다시(RetryPolicy.inboxRetry).
    case receive
    /// W 보내기 일꾼(보낼 함 결과·만료·'보내 봄'): 항목을 두고 RetryPolicy.saveRetry 뒤 다시 보낸다.
    case worker
    /// A 부수 일(기기 목록·띄운 표시·상태 요청 시각): 공통 처리로 끝. 다음 계기가 다시 한다.
    case sideEffect
}
