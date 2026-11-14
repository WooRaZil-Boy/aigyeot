package kr.aigyeot.app.link

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kr.aigyeot.app.common.CarPaths
import kr.aigyeot.app.exec.LoudnessResult
import kr.aigyeot.app.link.db.SongDownloadEntity
import kr.aigyeot.app.store.WORKER_RETRY_MS
import kr.aigyeot.app.store.bestEffortSuspend
import kr.aigyeot.engine.Params
import java.io.File

private val MAX_SONG_BYTES = Params().songMaxBytes

/** 노래 받기 작업: 받아 검사하고 −30 LUFS 사본을 만든다. 한 바퀴 실패 뒤 다시 깨운다. */
internal class SongWorker(
    private val context: Context,
    private val room: LinkRoom,
    private val relay: Relay,
    private val clock: () -> Long,
    private val creds: Creds,
    private val rx: Mutex,
    private val probe: (File) -> SongProbe?,
    private val makeCopy: suspend (File, File) -> LoudnessResult,
    private val scope: CoroutineScope,
    private val after: (Applied) -> Unit,
) {
    private val dao = room.dao
    private val songSignal = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var songWake: Job? = null

    fun signal() { songSignal.trySend(Unit) }

    fun startConsumer(): Job = scope.launch { for (x in songSignal) if (!bestEffortSuspend("노래 받기") { songsOnce() }) wakeSongsAt(clock() + WORKER_RETRY_MS) }

    fun cancelWake() { songWake?.cancel() }

    suspend fun songsOnce() {
        while (true) {
            val now = clock()
            val d = dao.nextDownload(now) ?: return dao.nextDownloadAt()?.let(::wakeSongsAt) ?: Unit
            if (!room.stillWanted(d)) { dao.deleteDownload(d.songId); continue }   // 그사이 지움이 왔다
            val c = creds.credential() ?: return wakeSongsAt(now + 60_000)
            val raw = CarPaths.songDownload(context, d.songId).apply { parentFile?.mkdirs() }
            when (val r = relay.song(c.token, d.songId, raw, MAX_SONG_BYTES)) {
                is Res.Ok -> finishSong(d, raw, r.value, null)
                is Res.Err -> when {
                    r.status == 401 -> { raw.delete(); return creds.invalidate(c) }
                    r.status == 429 -> dao.retryDownload(d.songId, now + (r.retryAfterS?.times(1000)?.coerceAtLeast(1000) ?: backoffMs(d.attempts)))
                    r.status >= 500 -> dao.retryDownload(d.songId, now + backoffMs(d.attempts))
                    else -> finishSong(d, raw, null, "download_failed")   // 404: 중계에 파일이 없다(이미 지움·7일 지남)
                }
                Res.Offline -> dao.retryDownload(d.songId, now + backoffMs(d.attempts))
            }
            raw.delete()
        }
    }

    /** 크기·길이·형식 검사 → −30 LUFS 사본(실행기) → 마지막 트랜잭션. */
    private suspend fun finishSong(d: SongDownloadEntity, raw: File, size: Long?, failed: String?) {
        val p = if (failed == null) probe(raw) else null
        val checked = failed ?: when {
            size!! > MAX_SONG_BYTES -> "too_large"
            p == null || !p.aac -> "bad_format"
            p.durationS > Params().songMaxSec -> "too_long"
            else -> null
        }
        val copy = CarPaths.songFile(context, d.songId)
        val made = if (checked == null) {
            try { makeCopy(raw, copy) } catch (e: CancellationException) { throw e }   // 취소는 거절이 아니다. 다음에 다시 받는다
            catch (e: Exception) { LoudnessResult(false, "bad_format") }
        } else null
        // 실행기의 거절 이유는 그대로 싣는다. 열거에 없는 글자면 bad_format으로 둔다(폰이 읽지 못하는 값을 보내지 않게)
        val reason = checked ?: made?.takeIf { !it.ok }?.let { m -> m.reason?.takeIf { LinkRoom.rejectReason(it) != null } ?: "bad_format" }
        val out = Applied()
        rx.withLock {
            if (!room.finishDownload(d, reason, copy.path, p?.durationS, clock(), out)) copy.delete()
            after(out)
        }
    }

    private fun wakeSongsAt(at: Long) {
        songWake?.cancel()
        songWake = scope.launch { delay((at - clock()).coerceAtLeast(0)); signal() }
    }
}
