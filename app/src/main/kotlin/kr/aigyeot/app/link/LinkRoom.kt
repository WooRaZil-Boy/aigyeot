package kr.aigyeot.app.link

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kr.aigyeot.app.link.db.OutboxEntity
import kr.aigyeot.app.link.db.PhoneLinkMarkEntity
import kr.aigyeot.app.link.db.ProcessedMessageEntity
import kr.aigyeot.app.link.db.SongDownloadEntity
import kr.aigyeot.app.link.db.SongSelectionEntity
import kr.aigyeot.app.store.db.AppDatabase
import kr.aigyeot.app.store.db.PhoneLinkEntity
import kr.aigyeot.app.store.db.SongEntity
import kr.aigyeot.engine.CareView
import kr.aigyeot.engine.Event
import kr.aigyeot.engine.FeedingFrom
import kr.aigyeot.engine.FeedingStarted
import kr.aigyeot.engine.LinkChanged
import kr.aigyeot.engine.Params
import kr.aigyeot.shared.CareSummaryBody
import kr.aigyeot.shared.DevicesResponse
import kr.aigyeot.shared.Envelope
import kr.aigyeot.shared.FeedingStartedBody
import kr.aigyeot.shared.FeedingStateBody
import kr.aigyeot.shared.PairedBody
import kr.aigyeot.shared.PhoneRole
import kr.aigyeot.shared.ProtocolJson
import kr.aigyeot.shared.Role
import kr.aigyeot.shared.SongAddedBody
import kr.aigyeot.shared.SongRefBody
import kr.aigyeot.shared.SongRejectReason
import kr.aigyeot.shared.SongStatusBody
import kr.aigyeot.shared.SongStatusKind
import kr.aigyeot.shared.UnpairedBody
import java.util.concurrent.Callable

// Song.status 값(contracts-car 6.1)
internal const val PENDING = "PENDING"
internal const val ACTIVE = "ACTIVE"
internal const val REJECTED = "REJECTED"
internal const val DELETED = "DELETED"

/** 차가 받아 처리하는 type(8.4). 나머지는 1단계에서 ack만 한다. */
internal val RECEIVED_TYPES = setOf("feeding_started", "song_added", "song_removed", "song_selected", "paired", "unpaired", "state_request", "care_summary")

/**
 * 후보 ③(contracts-link 8.4, 명세서 5장). 고른 곡이 쓸 수 있으면 그 곡, 아니면 쓸 수 있는 곡 중 추가 순번이 가장 큰 곡.
 * store도 `MethodsChanged`를 낼 때 같은 규칙을 쓴다(이 함수를 불러도 된다).
 */
fun candidateSong(songs: List<SongEntity>, selection: SongSelectionEntity?): String? {
    val active = songs.filter { it.status == ACTIVE }
    return active.firstOrNull { it.id == selection?.songId }?.id ?: active.maxByOrNull { it.addedSeq ?: Long.MIN_VALUE }?.id
}

/** 한 메시지 처리의 결과. 커밋 뒤 link가 사건을 내고 파일을 지우고 일꾼을 깨운다(8.3 4·5단계). */
internal class Applied {
    val events = mutableListOf<Event>()
    val deleteFiles = mutableListOf<String>()
    var download = false
    var outbox = false
    var phones = false
    var newOwner = false          // 연결 코드로 붙은 새 owner(G41: 연결 화면이 열려 있으면 새 코드)
    var justPaired: String? = null
    var care: CareView? = null    // care_summary 요약(L60). 지금 값보다 늦은지는 커밋 뒤 link가 판단 코어 마지막 상태로 견준다
}

/** 8.3 3단계·8.4·8.5 넣기를 Room 트랜잭션 안에서 한다. 네트워크는 부르지 않는다. */
/** 폰 시계 오차 허용(3.3 care_summary 받는 쪽). */
private const val CLOCK_SKEW_MS = 5 * 60_000L

internal class LinkRoom(private val db: AppDatabase, private val params: Params = Params()) {
    val dao = db.linkDao()

    fun <T> tx(f: () -> T): T = db.runInTransaction(Callable { f() })

    // ---------------------------------------------------------------- 받기(8.3·8.4)

    /** 1단계를 통과한 봉투 하나. 처리한 id면 null(ack만). 본문이 틀리면 처리한 것으로 적고 무시한다(받은 함이 막히지 않게). */
    fun receive(env: Envelope, now: Long): Applied? = tx {
        if (dao.processed(env.id)) return@tx null
        val out = Applied()
        val seq = env.seq ?: 0
        runCatching { apply(env, seq, now, out) }.onFailure { if (it !is BadBody) throw it }
        dao.insertProcessed(ProcessedMessageEntity(env.id, seq, now))
        out
    }

    private class BadBody : Exception()

    private fun <T> Envelope.decode(s: KSerializer<T>): T = runCatching { ProtocolJson.decodeFromJsonElement(s, body) }.getOrElse { throw BadBody() }
    /** 3.3: 시각을 읽지 못하거나 정수가 음수이거나, 끔인데 잠·기저귀 칸에 값이 있으면 버린다. */
    private fun careView(b: CareSummaryBody, now: Long): CareView {
        fun at(s: String?) = s?.let { instantOf(it) ?: throw BadBody() }
        val ints = listOf(b.todaySleepMin, b.nightWakeups, b.todayDiaperCount, b.todayFeedCount)
        if (ints.any { it != null && it < 0 }) throw BadBody()
        val sd = listOf(b.lastSleepStartAt, b.lastSleepEndAt, b.sleepingSince, b.todaySleepMin, b.nightWakeups, b.lastDiaperAt, b.todayDiaperCount)
        if (!b.sleepDiaperEnabled && sd.any { it != null }) throw BadBody()
        val asOf = at(b.asOf) ?: throw BadBody()
        if (asOf > now + CLOCK_SKEW_MS) throw BadBody()   // 폰 시계가 앞선 요약은 시계를 고친 뒤 새 요약을 막는다
        return CareView(
            asOf, b.sleepDiaperEnabled, at(b.lastSleepStartAt), at(b.lastSleepEndAt), at(b.sleepingSince),
            b.todaySleepMin, b.nightWakeups, at(b.lastDiaperAt), b.todayDiaperCount, b.todayFeedCount,
        )
    }
    private fun songId(id: String) = id.takeIf(::isUlid) ?: throw BadBody()

    private fun apply(env: Envelope, seq: Long, now: Long, out: Applied) {
        when (env.type) {
            "feeding_started" -> {
                val at = instantOf(env.decode(FeedingStartedBody.serializer()).at) ?: throw BadBody()
                val cur = dao.profile()?.lastFeedingStart
                if (cur != null && at <= cur) return                                   // 같거나 이르면 무시
                dao.setLastFeedingStart(at)
                enqueue("feeding_state", feedingState(), now, out)
                out.events += FeedingStarted(now, at, FeedingFrom.PHONE, env.id)
            }
            "song_added" -> {
                val b = env.decode(SongAddedBody.serializer())
                val id = songId(b.songId)
                if (dao.song(id) != null) return                                       // 지운 곡은 되살리지 않는다. 같은 곡을 다시 추가해도 바꿀 것이 없다
                // 3곡 판정(G40): 추가 순번이 더 작고 아직 지워지지 않은(지움 순번이 없거나 더 큰) 곡
                val holders = dao.songs().count { s -> s.addedSeq.let { it != null && it < seq } && (s.status != DELETED || s.lastSeq > seq) }
                if (holders >= params.songMaxCount) {
                    dao.putSong(SongEntity(id, b.name.let { it.substring(0, it.offsetByCodePoints(0, minOf(30, it.codePointCount(0, it.length)))) }, null, "AAC", null, null, null, REJECTED, "limit_reached", seq, seq))
                    statuses(listOf(id), now, out)
                } else {
                    dao.putSong(SongEntity(id, b.name.let { it.substring(0, it.offsetByCodePoints(0, minOf(30, it.codePointCount(0, it.length)))) }, null, "AAC", b.durationS, null, null, PENDING, null, seq, seq))
                    dao.putDownload(SongDownloadEntity(id, seq, 0, 0))
                    out.download = true
                }
            }
            "song_removed" -> {
                val id = songId(env.decode(SongRefBody.serializer()).songId)
                val old = dao.song(id)
                if (old != null && seq <= old.lastSeq) return
                val before = candidate()
                dao.putSong(
                    old?.copy(status = DELETED, lastSeq = seq, normalizedPath = null)
                        ?: SongEntity(id, null, null, "AAC", null, null, null, DELETED, null, null, seq),
                )
                dao.deleteDownload(id)
                old?.normalizedPath?.let { out.deleteFiles += it }
                statuses(listOf(id) + moved(before), now, out)
            }
            "care_summary" -> out.care = careView(env.decode(CareSummaryBody.serializer()), now)   // Room에는 두지 않는다(C119)
            "song_selected" -> select(songId(env.decode(SongRefBody.serializer()).songId), seq, now, out)
            "paired" -> {
                val b = env.decode(PairedBody.serializer())
                val mark = dao.mark(b.deviceId)
                if (mark?.unpaired == true || (mark != null && seq <= mark.lastSeq)) return
                val old = dao.phoneLink(b.deviceId)
                dao.putPhoneLink(
                    PhoneLinkEntity(
                        b.deviceId, b.platform.name.lowercase(), b.role.name.lowercase(),
                        instantOf(b.pairedAt) ?: now, old?.lastSeenAt, b.handoffAlerts,
                    ),
                )
                dao.putMark(PhoneLinkMarkEntity(b.deviceId, seq, false, now))
                if (old == null) {
                    out.justPaired = b.deviceId                                        // 새 행이면 "연결됐어요"
                    if (b.role == PhoneRole.OWNER) { out.newOwner = true; fullState(null, now, out) }   // G23
                }
                linkChanged(now, out)
            }
            "unpaired" -> {
                val b = env.decode(UnpairedBody.serializer())
                unpair(b.deviceId, seq, now, out)
            }
            "state_request" -> {
                val from = env.from
                if (from?.role == Role.OWNER && from.device != null) fullState(from.device, now, out)   // guardian은 답하지 않는다
            }
        }
    }

    /** 끊김(받은 unpaired, 차 화면 끊기). 기기 id는 다시 쓰지 않으므로 끊김 표시는 늦은 paired를 모두 막는다(8.4). */
    fun unpair(deviceId: String, seq: Long, now: Long, out: Applied) {
        dao.deletePhoneLink(deviceId)
        dao.putMark(PhoneLinkMarkEntity(deviceId, maxOf(seq, dao.mark(deviceId)?.lastSeq ?: 0), true, now))
        linkChanged(now, out)
    }

    /** song_selected(폰이 보낸 것, 차가 보내고 받은 seq): 기록된 seq보다 클 때만(8.4). ③이 바뀌면 두 곡의 song_status. */
    fun select(songId: String, seq: Long, now: Long, out: Applied) {
        val cur = dao.selection()
        if (cur != null && seq <= cur.seq) return
        val before = candidate()
        dao.clearSelection()
        dao.insertSelection(SongSelectionEntity(songId, seq))
        statuses(moved(before), now, out)
    }

    /** ③이 [before]에서 바뀌었으면 두 곡(8.4 '바뀌면 두 곡의 song_status'). */
    private fun moved(before: String?): List<String?> = candidate().let { after -> if (after == before) emptyList() else listOf(before, after) }

    private fun linkChanged(now: Long, out: Applied) {
        out.phones = true
        out.events.removeAll { it is LinkChanged }
        out.events += LinkChanged(now, dao.otherGuardians())
    }

    // ---------------------------------------------------------------- 보낼 함(8.5)

    fun candidate(): String? = candidateSong(dao.songs(), dao.selection())

    /** 보낼 함에 넣는다. 받을 상대가 없으면 만들지 않는다(8.5 '만들지 않는 때'). [to]는 state_request의 답. */
    fun enqueue(type: String, body: JsonObject?, now: Long, out: Applied?, to: String? = null) {
        val links = dao.phoneLinks()
        val make = when {
            type == "song_selected" || type == "records_clear" || to != null -> true
            type == "handoff_alert" -> links.any { it.role == "guardian" && it.handoffAlerts }
            else -> links.isNotEmpty()
        }
        if (!make) return
        dao.insertOutbox(OutboxEntity(ulid(now), type, to, body?.toString() ?: "", now, 0, 0))
        out?.outbox = true
    }

    /** feeding_state body는 넣는 이 트랜잭션에서 저장된 최신 값으로 만든다(3.3 '경합 방지'). */
    fun feedingState(): JsonObject {
        val p = dao.profile()
        val start = p?.lastFeedingStart
        val iv = p?.feedingIntervalMin
        val expected = if (start != null && iv != null) start + (iv * MINUTE * params.feedingWindowStart).toLong() else null
        return ProtocolJson.encodeToJsonElement(FeedingStateBody.serializer(), FeedingStateBody(start?.let(::rfc3339), expected?.let(::rfc3339))).jsonObject
    }

    /** 곡들의 지금 song_status. PENDING(받는 중)은 빼고, 같은 곡은 한 번만(3.3 `song_status`). */
    private fun statuses(ids: List<String?>, now: Long, out: Applied, to: String? = null) {
        val cand = candidate()
        ids.filterNotNull().distinct().forEach { id -> dao.song(id)?.let { s -> statusBody(s, cand)?.let { enqueue("song_status", it, now, out, to) } } }
    }

    /** G23·state_request: 지금 feeding_state와 곡마다 song_status(PENDING 제외). */
    private fun fullState(to: String?, now: Long, out: Applied) {
        enqueue("feeding_state", feedingState(), now, out, to)
        statuses(dao.songs().sortedBy { it.addedSeq ?: Long.MIN_VALUE }.map { it.id }, now, out, to)
    }

    private fun statusBody(s: SongEntity, candidate: String?): JsonObject? {
        val kind = when (s.status) {
            ACTIVE -> SongStatusKind.ACCEPTED
            REJECTED -> SongStatusKind.REJECTED
            DELETED -> SongStatusKind.REMOVED
            else -> return null
        }
        val reason = if (kind == SongStatusKind.REJECTED) s.rejectReason?.let(::rejectReason) ?: SongRejectReason.BAD_FORMAT else null
        val body = SongStatusBody(s.id, s.name, kind, reason, if (kind == SongStatusKind.ACCEPTED) s.durationS else null, s.id == candidate)
        return ProtocolJson.encodeToJsonElement(SongStatusBody.serializer(), body).jsonObject
    }

    // ---------------------------------------------------------------- 노래 받기 작업의 마지막 트랜잭션(8.4)

    /** 아직 받을 곡인가(받기 전 확인). 더 큰 seq의 지움이 와 있으면 아니다. */
    fun stillWanted(d: SongDownloadEntity): Boolean = dao.song(d.songId)?.let { it.status == PENDING && it.lastSeq <= d.addedSeq } == true

    /**
     * 그 곡의 상태와 last_seq를 다시 읽고 Song을 고친다. 그사이 지움이 왔으면 거짓(사본을 지우고 accepted를 보내지 않는다).
     * [reason]이 null이면 받음. 거절 이유는 3.3 열거의 글자(`peak_limit` 등)다. 받음의 길이는 파일에서 잰 [durationS](없으면 폰이 말한 값).
     */
    fun finishDownload(d: SongDownloadEntity, reason: String?, path: String?, durationS: Double?, now: Long, out: Applied): Boolean = tx {
        dao.deleteDownload(d.songId)
        val s = dao.song(d.songId)
        if (s == null || s.status != PENDING || s.lastSeq > d.addedSeq) return@tx false
        val before = candidate()
        dao.putSong(
            if (reason == null) s.copy(status = ACTIVE, normalizedPath = path, durationS = durationS ?: s.durationS, receivedAt = now)
            else s.copy(status = REJECTED, rejectReason = reason, receivedAt = now),
        )
        statuses(listOf(d.songId) + moved(before), now, out)
        reason == null
    }

    // ---------------------------------------------------------------- 기기 목록 대조(8.4 G37)

    /** 1~3단계. 바뀐 것(연결·역할·알림)이 있으면 LinkChanged를 out에 넣는다. 마지막 연락 시각은 늘 고친다(8.7 ⑥). */
    fun reconcile(r: DevicesResponse, now: Long, out: Applied) = tx {
        val snap = r.snapshotSeq
        val phones = r.devices.filter { it.role == Role.OWNER || it.role == Role.GUARDIAN }
        var changed = false
        for (d in phones) {
            val mark = dao.mark(d.deviceId)
            if (mark?.unpaired == true) continue
            val old = dao.phoneLink(d.deviceId)
            val seen = instantOf(d.lastSeenAt) ?: old?.lastSeenAt
            if (mark == null || mark.lastSeq < snap) {
                val e = PhoneLinkEntity(
                    d.deviceId, d.platform.name.lowercase(), d.role.name.lowercase(),
                    instantOf(d.pairedAt) ?: now, seen, d.handoffAlerts ?: false,
                )
                if (old == null || old.copy(lastSeenAt = seen) != e) changed = true
                dao.putPhoneLink(e)
                dao.putMark(PhoneLinkMarkEntity(d.deviceId, snap, false, now))
                // G23. "연결됐어요"는 띄우지 않는다. 대조가 실시간 paired보다 먼저 새 owner를 만들면 그 paired는 낡은 것이 되므로
                // 연결 화면이 열려 있으면 여기서도 새 코드를 받는다(G41, 8.7 ③)
                if (old == null && d.role == Role.OWNER) { fullState(null, now, out); out.newOwner = true }
            } else if (old != null && seen != null) {
                dao.setLastSeen(d.deviceId, seen)
            }
        }
        for (p in dao.phoneLinks()) {
            if (phones.any { it.deviceId == p.id }) continue
            val mark = dao.mark(p.id)
            if ((mark?.lastSeq ?: 0) < snap) {
                dao.deletePhoneLink(p.id)
                dao.putMark(PhoneLinkMarkEntity(p.id, snap, true, now))
                changed = true
            }
        }
        if (changed) linkChanged(now, out)
        out.phones = true
    }

    // ---------------------------------------------------------------- 차 토큰 무효(8.6)

    /**
     * 자격·PhoneLink를 지우고 옛 방의 seq를 모두 버린다('재등록 때 순번 옮기기' 1~3). 지운 곡 id를 돌려준다(파일 정리용).
     * 보낼 함은 남긴다(새 방으로 보내진다, 받는 쪽이 없으면 중계가 비운다).
     */
    fun resetForNewRoom(now: Long, out: Applied): List<String> = tx {
        dao.clearCredential()
        dao.clearPhoneLinks()
        dao.clearMarks()
        dao.clearProcessed()
        dao.clearDownloads()
        val songs = dao.songs()
        dao.deleteInactiveSongs()
        val active = songs.filter { it.status == ACTIVE }.sortedBy { it.addedSeq ?: Long.MIN_VALUE }
        active.forEachIndexed { i, s -> val n = (i - active.size).toLong(); dao.putSong(s.copy(addedSeq = n, lastSeq = n)) }
        dao.selection()?.let { dao.clearSelection(); dao.insertSelection(it.copy(seq = 0)) }
        linkChanged(now, out)
        songs.filter { it.status != ACTIVE }.map { it.id }
    }

    /** 7일 지난 처리 id·끊김 표시·보낼 함(8.2, 8.5). */
    fun expire(now: Long) = tx {
        dao.expireProcessed(now - KEEP_MS)
        dao.expireMarks(now - KEEP_MS)
        dao.expireOutbox(now - KEEP_MS)
    }

    companion object {
        fun rejectReason(s: String): SongRejectReason? =
            runCatching { ProtocolJson.decodeFromJsonElement(SongRejectReason.serializer(), JsonPrimitive(s)) }.getOrNull()
    }
}
