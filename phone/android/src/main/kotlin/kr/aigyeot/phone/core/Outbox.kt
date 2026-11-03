package kr.aigyeot.phone.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// 보낼 함 결과 처리(계약 7.5). 넣은 순서대로 하나씩 보내고, 다시 보내도 같은 실패는 버린다.

sealed interface SendResult {
    /** 201·200. `body`는 중계가 저장한 값(고친 수유 시각). */
    data class Sent(val body: JsonObject) : SendResult

    /** 노래 PUT 201 또는 409 `already_exists`. 이어서 song_added를 보낸다. */
    data object Uploaded : SendResult

    data class Failed(val status: Int, val code: String?, val retryAfterS: Long? = null) : SendResult

    /** 네트워크 오류. */
    data object Offline : SendResult
}

/**
 * 지금 보낼 차례인 항목. 맨 앞이 기다리는 중이면 뒤도 기다린다(순서 유지).
 * 맨 앞이 7일 지났으면 보내지 않는다(건너뛰지도 않는다): 만료 정리([pruned]) 저장이 먼저 지우고 표시를 남긴다.
 */
fun PhoneState.nextToSend(now: Long): OutboxItem? =
    outbox.firstOrNull()?.takeIf { it.nextTryAt <= now && now - it.createdAt < KEEP_IDS_MS }

/** 맨 앞 항목이 7일 지났다. 전송 일꾼은 고르기 전에 [pruned]를 저장해 치운다(안 그러면 깨움이 0초로 헛돈다). */
fun PhoneState.frontExpired(now: Long): Boolean = outbox.firstOrNull()?.let { now - it.createdAt >= KEEP_IDS_MS } == true

/** 401은 바꾸지 않는다(부르는 쪽이 연결 끊김으로 처리한다). */
fun PhoneState.afterSend(item: OutboxItem, r: SendResult, now: Long): PhoneState {
    val id = item.envelope.id
    fun change(f: (OutboxItem) -> OutboxItem) = copy(outbox = outbox.map { if (it.envelope.id == id) f(it) else it })
    fun retry(waitMs: Long) = change { it.copy(attempts = it.attempts + 1, nextTryAt = now + waitMs) }
    val dropped = copy(outbox = outbox.filter { it.envelope.id != id })

    return when (r) {
        SendResult.Uploaded -> change { it.copy(uploaded = true, attempts = 0, nextTryAt = 0) }
        is SendResult.Sent -> when (item.envelope.type) {
            "feeding_started" -> {
                val at = (r.body["at"] as? JsonPrimitive)?.contentOrNull
                // feeding_state가 POST 응답보다 먼저 왔을 수 있다: 고친 시각을 저장된 최신 수유 상태와도 견준다(3.3).
                val last = instantOf(feeding?.value?.lastFeedingStart)
                dropped.copy(
                    feedings = feedings.map {
                        if (it.msgId != id) return@map it
                        val fixed = at ?: it.at
                        val reflected = it.reflected || (last != null && instantOf(fixed) == last)
                        it.copy(sentAt = now, at = fixed, reflected = reflected, failed = if (reflected) null else it.failed)
                    },
                )
            }
            "song_added" -> dropped.copy(
                songs = songs.map { if (it.songId == item.songId && it.phase == SongPhase.UPLOADING) it.copy(phase = SongPhase.WAITING) else it },
            )
            else -> dropped
        }
        SendResult.Offline -> retry(backoffMs(item.attempts))
        is SendResult.Failed -> when {
            r.status == 401 -> this
            r.status == 429 -> retry(r.retryAfterS?.times(1000) ?: backoffMs(item.attempts))
            r.status >= 500 -> retry(backoffMs(item.attempts))
            // 파일이 먼저 올라가지 않았다: 파일부터 다시 올린다.
            r.code == "song_blob_missing" -> change { it.copy(uploaded = false, nextTryAt = now) }
            // 차가 아직 처리하지 않은 노래 파일이 3개: 차가 켜진 뒤 다시.
            r.code == "song_quota" -> retry(backoffMs(item.attempts))
            // 400·403·409 id_conflict·413·415 등은 다시 보내도 같아서 버리고 화면에 알린다.
            else -> dropped.markFailed(item, r)
        }
    }
}

/** 버려진(영구 실패) 노래 삭제·선택은 목록에 남길 곳이 없어 화면 안내로 알린다. 나머지는 항목 자체에 실패가 표시된다. */
fun dropNotice(type: String): String? = when (type) {
    "song_removed" -> "노래 삭제를 차로 보내지 못했어요. 차에는 그 노래가 남아 있을 수 있어요."
    "song_selected" -> "실험에 쓸 노래 선택을 차로 보내지 못했어요."
    else -> null
}

private fun PhoneState.markFailed(item: OutboxItem, r: SendResult.Failed): PhoneState {
    val why = when (r.status) {
        413 -> "파일이 너무 커요. 3분 안으로 다시 녹음해 주세요."
        415 -> "녹음 파일을 읽지 못했어요. 다시 녹음해 주세요."
        else -> "보내지 못했어요(${r.code ?: r.status})"
    }
    return when (item.envelope.type) {
        "feeding_started" -> copy(feedings = feedings.map { if (it.msgId == item.envelope.id) it.copy(failed = why) else it })
        "song_added" -> copy(songs = songs.map { if (it.songId == item.songId) it.copy(error = why) else it })
        else -> this
    }
}
