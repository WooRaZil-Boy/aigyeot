package kr.aigyeot.engine

// 한 Step의 작업 공간. 라우터·리듀서·규칙은 모두 이 클래스의 확장 함수다(Router.kt·*Reducer.kt·Restore.kt·Rules.kt).
// 주인 칸: now(clock)·seq(newId·newUlid)·rng(pick). Step 지역 표시(offered·qaTool·snapshot)는 상태에 넣지 않는다.
// 다른 칸의 쓰기 통로(car·ign·speech·qa·ep·offers·controls …)는 그 칸 주인 파일의 private 함수다. OwnershipTest가 지킨다.

/** step 한 번의 작업 공간. 상태는 불변 객체라 copy로 바꿔 끼운다. */
internal class Tx(val p: Params, var s: EngineState) {
    val out = mutableListOf<Command>()
    var offered = false // 이 Step에 인계·쉼터 제안을 했다
    var qaTool = false // 질문 답의 도구 사건(또는 그 결과)을 처리하는 중
    var snapshot = false // Suspend·기록 지우기: 마지막에 SaveSnapshot

    val now get() = s.now
    val ses get() = s.session

    /** 세션 잎 칸을 고치는 통로. 어느 잎 칸을 고칠 수 있는지는 소유 표가 정하고 OwnershipTest가 본다. 세션이 없으면 그대로. */
    fun ses(f: SessionState.() -> SessionState) {
        s.session?.let { s = s.copy(session = it.f()) }
    }
    val gearP get() = s.car.gear == Gear.P
    val suspended get() = s.ign.suspendedAt != null

    /** now를 쓰는 유일한 함수. 라우터 advance(시간 흐름)와 Engine 입구(사건 밖 값 맞추기)가 부른다. */
    fun clock(t: Long) {
        s = s.copy(now = t)
    }

    /** [owner]는 이 명령을 내는 세션(지난 세션 몫의 명령은 그 세션 id, control_log가 id 앞부분으로 기록 세션을 정한다). 기본은 지금 세션. */
    fun newId(owner: String? = ses?.id): String {
        val id = "${owner ?: "idle"}-${s.seq}"
        s = s.copy(seq = s.seq + 1)
        return id
    }

    /** 세션·에피소드 id(ulid). newId와 같은 seq를 하나 쓴다. */
    fun newUlid(): String {
        val id = ulid(now, s.seq)
        s = s.copy(seq = s.seq + 1)
        return id
    }

    /** 난수를 쓰는 유일한 함수(톰슨 고르기). 고른 뒤 다음 rng를 남긴다. */
    fun pick(f: (rng: Long) -> Pick): Method = f(s.rng).also { s = s.copy(rng = it.rng) }.method
}
