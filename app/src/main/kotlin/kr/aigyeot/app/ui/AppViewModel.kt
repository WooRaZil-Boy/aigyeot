package kr.aigyeot.app.ui

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.aigyeot.app.host.EventSink
import kr.aigyeot.app.store.Store
import kr.aigyeot.app.store.bestEffortSuspend
import kr.aigyeot.app.store.db.ChildProfileEntity
import kr.aigyeot.app.store.db.needsSetup
import kr.aigyeot.app.store.toProfile
import kr.aigyeot.engine.BabyAboard
import kr.aigyeot.engine.BabyAboardFrom
import kr.aigyeot.engine.ClearRecords
import kr.aigyeot.engine.FeedingConfirmed
import kr.aigyeot.engine.Notice
import kr.aigyeot.engine.ProfileChanged

/** 프로필을 읽는 중이면 [Loading]. [Loaded.profile]이 null이면 아직 저장된 적이 없다. */
sealed interface ProfileState {
    data object Loading : ProfileState
    data class Loaded(val profile: ChildProfileEntity?, val noRearOutput: Boolean) : ProfileState {
        val needsSetup get() = profile.needsSetup()
    }
}

/** 앱 전체 화면이 같이 쓰는 것: 프로필(첫 설정 여부)과, 화면 밖(알림·액티비티)에서도 보내는 사건. 사건은 [events](실제 입구) 하나로 보낸다. */
class AppViewModel(private val store: Store, private val events: EventSink, private val appContext: Context, noRearOutput: () -> Boolean) : ViewModel() {
    val profile: StateFlow<ProfileState> = store.profileFlow().map<ChildProfileEntity?, ProfileState> { ProfileState.Loaded(it, noRearOutput()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileState.Loading)

    /** 보호자 동의(첫 설정 저장) 전에는 어느 경로로도 마이크를 켜지 않는다. 프로필은 동의해야 저장된다 */
    suspend fun hasConsent(): Boolean = store.profileFlow().first() != null // 읽기가 실패해도 다시 읽어 마이크 시작이 빠지지 않게(Store.profileFlow)

    fun saveProfile(p: ChildProfileEntity) {
        viewModelScope.launch {
            // 크래시 없이 다시 하고, 다 실패하면 로그만 남기고 사건을 보내지 않는다
            // Room 먼저: 장면 중이면 장면 끝 복원 ⑤가 맞춘다
            if (bestEffortSuspend("프로필 저장") { withContext(Dispatchers.IO) { store.saveProfile(p) } }) events.post(ProfileChanged(0, p.toProfile()))
        }
    }

    fun babyAboard(from: BabyAboardFrom) = events.post(BabyAboard(0, from))

    /** 정차 화면(아기 설정) '모든 기록 지우기'. 장면 중이면 호스트가 받지 않는다. */
    fun clearRecords() = events.post(ClearRecords(0))

    fun feedingConfirm(yes: Boolean) {
        events.post(FeedingConfirmed(0, yes))
        Notices.cancel(appContext, Notice.FEEDING_CONFIRM)
    }
}

/** 뒷좌석 출력(BUS 100 zone 1)이 없는지. */
fun noRearOutput(ctx: Context): Boolean = ctx.getSystemService(AudioManager::class.java)
    .getDevices(AudioManager.GET_DEVICES_OUTPUTS).none { it.type == AudioDeviceInfo.TYPE_BUS && it.address == "bus100_audio_zone_1" }
