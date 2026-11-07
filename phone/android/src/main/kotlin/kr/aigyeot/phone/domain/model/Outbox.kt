package kr.aigyeot.phone.domain.model

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

    /** 중계에 닿지 않은 것이 확실하다(이름 풀기·연결 실패). 다시 보내는 것은 [Offline]과 같다. */
    data object Unreached : SendResult
}

/**
 * 지금 보낼 차례인 항목. 맨 앞이 기다리는 중이면 뒤도 기다린다(순서 유지).
 * 맨 앞이 7일 지났으면 보내지 않는다(건너뛰지도 않는다): 만료 정리([pruned]) 저장이 먼저 지우고 표시를 남긴다.
 */
fun PhoneState.nextToSend(now: Long): OutboxItem? =
    outbox.firstOrNull()?.takeIf { it.nextTryAt <= now && now - it.createdAt < KEEP_IDS_MS }

/** 메시지 429 기다림을 늘린다(더 늦은 끝을 남긴다). */
fun PhoneState.withMessageHold(now: Long, waitMs: Long): PhoneState =
    copy(messageHoldUntil = maxOf(messageHoldUntil?.takeIf { it > now } ?: 0, now + waitMs))

/** 보낼 함 항목의 이번 단계가 메시지 POST인가(노래 파일 PUT은 따로 제한한다, 1.4). */
val OutboxItem.posting: Boolean get() = songFile == null || uploaded

/** 맨 앞 항목이 7일 지났다. 전송 일꾼은 고르기 전에 [pruned]를 저장해 치운다(안 그러면 깨움이 0초로 헛돈다). */
fun PhoneState.frontExpired(now: Long): Boolean = outbox.firstOrNull()?.let { now - it.createdAt >= KEEP_IDS_MS } == true

/** 폰 시계가 중계보다 이만큼 넘게 앞서면, 보낸 요약은 차가 '미래'로 보고 버렸을 수 있다(3.3 '5분 넘게 미래'). */
const val CAR_FUTURE_MS = 5 * MINUTE

/** 401은 바꾸지 않는다(부르는 쪽이 연결 끊김으로 처리한다). `aheadMs`는 폰 시계 − 중계 시계(응답의 `Date`). */
fun PhoneState.afterSend(item: OutboxItem, r: SendResult, now: Long, aheadMs: Long = 0): PhoneState {
    val id = item.envelope.id
    // 보내는 사이 새 요약이 옛것을 바꿨으면(R19): 다시 보낼 기다림(429 Retry-After·백오프)은 새것이 이어받고, 옛것의 실패는 알리지 않는다.
    val wasQueued = outbox.any { it.envelope.id == id }
    val target = if (wasQueued || item.envelope.type != "care_summary") id
    else outbox.firstOrNull { it.envelope.type == "care_summary" }?.envelope?.id ?: id
    fun change(f: (OutboxItem) -> OutboxItem) = copy(outbox = outbox.map { if (it.envelope.id == target) f(it) else it })
    fun retry(waitMs: Long) = change { it.copy(attempts = it.attempts + 1, nextTryAt = maxOf(it.nextTryAt, now + waitMs)) }
    val dropped = copy(outbox = outbox.filter { it.envelope.id != id })

    return when (r) {
        // 시도 횟수는 그대로 둔다: 422 song_blob_missing로 되풀이할 때 기다림이 늘게(7.5, iOS `.reupload`와 같음).
        SendResult.Uploaded -> change { it.copy(uploaded = true, nextTryAt = 0) }
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
            // 차가 미래 요약으로 버렸을 수 있다: 내용이 같아도 다음 계기에 다시 보낸다(iOS와 같음).
            "care_summary" -> if (aheadMs > CAR_FUTURE_MS && dropped.outbox.none { it.envelope.type == "care_summary" }) dropped.copy(lastSentCareSummary = null) else dropped
            else -> dropped
        }
        SendResult.Offline -> retry(backoffMs(item.attempts))
        // 앞서 응답을 잃은 적이 없는 요약이면 중계에 없는 것이 확정이다(늦게 도착할 수 없다): 보내 보지 않은 것으로 되돌린다(U57·R10).
        SendResult.Unreached -> retry(backoffMs(item.attempts)).let { s -> if (item.envelope.type == "care_summary" && !item.sentOnce) s.untried(target) else s }
        is SendResult.Failed -> when {
            r.status == 401 -> this
            // 메시지 POST의 429는 기기당 한도라 다른 메시지도 기다린다(그 항목을 지워도). 노래 PUT의 429는 그 항목만 기다린다.
            r.status == 429 -> (r.retryAfterS?.times(1000) ?: backoffMs(item.attempts)).let { w ->
                val held = retry(w).let { s -> if (item.posting) s.withMessageHold(now, w) else s }
                // 429인 요약은 중계에 없다(같은 id를 한도 검사보다 먼저 본다): 보내 보지 않은 것으로 되돌린다(U57·R10).
                if (item.envelope.type == "care_summary") held.untried(target) else held
            }
            r.status >= 500 -> retry(backoffMs(item.attempts))
            // 파일이 먼저 올라가지 않았다: 파일부터 다시 올린다.
            r.code == "song_blob_missing" -> change { it.copy(uploaded = false, attempts = it.attempts + 1, nextTryAt = now + backoffMs(it.attempts)) }
            // 차가 아직 처리하지 않은 노래 파일이 3개: 차가 켜진 뒤 다시.
            r.code == "song_quota" -> retry(backoffMs(item.attempts))
            // 400·403·409 id_conflict·413·415 등은 다시 보내도 같아서 버리고 화면에 알린다.
            else -> if (!wasQueued) dropped
            // 차에 이미 반영된 수유는 실패로 적지도 띠로 알리지도 않는다(7.5 '차에 반영됨').
            else if (item.envelope.type == "feeding_started" && feedingDone(item)) dropped
            else dropped.markFailed(item, r).copy(
                unseenDrops = unseenDrops.withDrop(id, item.envelope.type),
                droppedFeedingAt = item.feedingAt?.takeIf { item.envelope.type == "feeding_started" }?.let { droppedFeedingAt + (id to it) } ?: droppedFeedingAt,
                // 차에 닿지 못한 요약이다: 내용이 같아도 다음 계기에 다시 보낸다(중계가 그 type을 받게 된 뒤 등).
                // 뒤에 남은 요약이 있으면(U57) 표시는 그것을 가리키므로 둔다.
                lastSentCareSummary = if (item.envelope.type == "care_summary" && dropped.outbox.none { it.envelope.type == "care_summary" }) null else lastSentCareSummary,
            )
        }
    }
}

/** 띠로 알릴 버림이면 더한다(메시지 id마다 하나, 띠 글은 [withDropNotices]가 같은 글을 한 번만 보인다). */
fun Map<String, String>.withDrop(id: String, type: String): Map<String, String> = if (dropNotice(type) == null) this else this + (id to type)

/** 7.1 outbox.dropped(U54): 버린(영구 실패) 메시지의 띠 안내. 수유는 기록 줄의 실패 표시와 함께 띄운다. 노래 추가는 항목에만 표시된다. */
fun dropNotice(type: String): String? = when (type) {
    "feeding_started" -> "수유 기록을 차로 보내지 못했어요."
    "song_removed" -> "노래 삭제를 차로 보내지 못했어요. 차에는 그 노래가 남아 있을 수 있어요."
    "song_selected" -> "실험에 쓸 노래 선택을 차로 보내지 못했어요."
    "care_summary" -> "돌봄 기록 요약을 차로 보내지 못했어요."
    else -> null
}

/**
 * U54: 띠 글에 한 줄을 더한다. 띠가 하나라 떠 있는 안내(다른 버림·저장 실패)를 덮지 않고 줄을 바꿔 함께 보인다.
 * 같은 글은 한 번. 사용자가 띠를 닫으면 다음 안내부터 새로 모은다.
 */
fun withNoticeLine(notice: String?, line: String): String =
    if (notice == null) line else if (line in notice.lines()) notice else "$notice\n$line"

/** U54: 버린 메시지들의 안내를 띠 글에 더한다. */
fun withDropNotices(notice: String?, types: List<String>): String? =
    types.mapNotNull(::dropNotice).fold(notice) { n, line -> withNoticeLine(n, line) }

private fun PhoneState.markFailed(item: OutboxItem, r: SendResult.Failed): PhoneState {
    // 화면 문구는 앱이 정한다(1.1): 중계의 코드를 보이지 않는다(iOS와 같은 글).
    val why = when (r.status) {
        413 -> "파일이 너무 커요. 3분 안으로 다시 녹음해 주세요."
        415 -> "녹음 파일을 읽지 못했어요. 다시 녹음해 주세요."
        else -> "노래를 보내지 못했어요. 다시 녹음해 주세요."
    }
    return when (item.envelope.type) {
        "feeding_started" -> copy(feedings = feedings.map { if (it.msgId == item.envelope.id) it.copy(failed = "차로 보내지 못했어요") else it })
        "song_added" -> copy(songs = songs.map { if (it.songId == item.songId) it.copy(error = why) else it })
        else -> this
    }
}
