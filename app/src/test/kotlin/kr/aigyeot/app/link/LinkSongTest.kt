package kr.aigyeot.app.link

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kr.aigyeot.app.common.CarPaths
import kr.aigyeot.app.exec.LoudnessResult
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// '노래 받기 작업'.
@RunWith(RobolectricTestRunner::class)
class LinkSongTest : LinkKit() {
    private fun add(id: String = SA, seq: Long = 10) =
        runBlocking { link.receive(env("song_added", seq, """{"song_id":"$id","name":"나비야","duration_s":94.5}""")) }
    private fun run() = runBlocking { link.songsOnce() }
    private fun statuses() = outbox("song_status").map { it.body() }

    @BeforeTest fun linked() = phone()

    @Test fun accepted_copyMadeSongActiveStatusSent_rawDeleted() {
        var copied: Pair<String, String>? = null
        copyResult = { i, o -> copied = i.path to o.path; i.copyTo(o, overwrite = true); LoudnessResult(true) }
        add(); run()
        assertEquals(listOf(SA), relay.songCalls)
        assertEquals(CarPaths.songDownload(ctx, SA).path to CarPaths.songFile(ctx, SA).path, copied)   // SongLoudness.makeCopy(원본, 사본)
        val s = dao.song(SA)!!
        assertEquals(ACTIVE, s.status); assertEquals(CarPaths.songFile(ctx, SA).path, s.normalizedPath)
        val st = statuses().single()
        assertEquals("accepted", st.str("status")); assertEquals("true", st.str("candidate")); assertEquals("94.5", st.str("duration_s"))
        assertTrue(dao.downloads().isEmpty())
        assertFalse(CarPaths.songDownload(ctx, SA).exists())
        assertTrue(CarPaths.songFile(ctx, SA).exists())
    }

    @Test fun notFound_rejectedDownloadFailed() {
        relay.songRes = { _, _ -> Res.Err(404, "not_found", null) }
        add(); run()
        assertEquals(REJECTED, dao.song(SA)!!.status)
        assertEquals("download_failed", statuses().single().str("reason"))
    }

    @Test fun checks_tooLong_badFormat_tooLarge_beforeCopy() {
        var copies = 0
        copyResult = { _, _ -> copies++; LoudnessResult(true) }
        probeResult = SongProbe(180.5, true); add(SA, 10); run()
        probeResult = null; add(SB, 11); run()
        probeResult = SongProbe(30.0, true); relay.songRes = { _, f -> f.writeBytes(ByteArray(10)); Res.Ok(200, 2_000_001) }; add(SC, 12); run()
        assertEquals(mapOf(SA to "too_long", SB to "bad_format", SC to "too_large"), mapOf(SA to dao.song(SA)!!.rejectReason, SB to dao.song(SB)!!.rejectReason, SC to dao.song(SC)!!.rejectReason))
        assertEquals(0, copies)
    }

    @Test fun loudnessReject_reasonPassedThrough_unknownBecomesBadFormat() {
        copyResult = { _, _ -> LoudnessResult(false, "peak_limit") }
        add(SA, 10); run()
        copyResult = { _, _ -> LoudnessResult(false, "weird") }
        add(SB, 11); run()
        assertEquals("peak_limit", dao.song(SA)!!.rejectReason)
        assertEquals("bad_format", dao.song(SB)!!.rejectReason)
    }

    @Test fun deletedWhileCopying_copyDeleted_noAccepted() {
        copyResult = { i, o ->
            i.copyTo(o, overwrite = true)
            link.receive(env("song_removed", 20, """{"song_id":"$SA"}"""))   // 정규화하는 동안 지움이 적용됨
            LoudnessResult(true)
        }
        add(); run()
        assertEquals(DELETED, dao.song(SA)!!.status)
        assertEquals(listOf("removed"), statuses().map { it.str("status") })   // accepted는 넣지 않는다
        assertFalse(CarPaths.songFile(ctx, SA).exists())
    }

    @Test fun deletedBeforeDownload_notFetched() {
        add()
        runBlocking { link.receive(env("song_removed", 11, """{"song_id":"$SA"}""")) }
        run()
        assertTrue(relay.songCalls.isEmpty())
    }

    @Test fun networkFailure_retriesWithBackoff() {
        relay.songRes = { _, _ -> Res.Offline }
        add(); run()
        val d = dao.downloads().single()
        assertEquals(1, d.attempts); assertEquals(now + 1_000, d.nextTryAt)
        assertEquals(PENDING, dao.song(SA)!!.status)
        assertTrue(statuses().isEmpty())   // 받는 중인 곡은 상태 응답에서 뺀다
    }

    @Test fun newestAccepted_movesCandidate_statusForOldOneToo() {
        song(SB, ACTIVE, 5)   // 지금 후보
        add(SA, 10); run()
        val byId = statuses().associateBy { it.str("song_id") }
        assertEquals("true", byId.getValue(SA).str("candidate"))
        assertEquals("false", byId.getValue(SB).str("candidate"))
    }

    @Test fun copyCancelled_rethrown_notRejected_downloadKept() {
        copyResult = { _, _ -> throw CancellationException("취소") }
        add()
        assertFailsWith<CancellationException> { run() }
        assertEquals(PENDING, dao.song(SA)!!.status)          // bad_format으로 거절하지 않는다
        assertEquals(1, dao.downloads().size)                 // 다음에 다시 받는다
        assertTrue(statuses().isEmpty())
    }

    @Test fun longName_cutTo30_probeDurationStored() {
        val long = "가".repeat(40)
        runBlocking { link.receive(env("song_added", 10, """{"song_id":"$SA","name":"$long","duration_s":94.5}""")) }
        assertEquals(30, dao.song(SA)!!.name!!.length)
        probeResult = SongProbe(61.0, true)
        run()
        assertEquals(ACTIVE, dao.song(SA)!!.status)           // 받음 경로를 지났다
        assertEquals("61.0", statuses().single().str("duration_s"))   // 폰이 말한 94.5가 아니라 파일에서 잰 값
    }

    @Test fun longName_emoji_notSplitInSurrogate() {
        val long = "😀".repeat(40)
        runBlocking { link.receive(env("song_added", 10, """{"song_id":"$SA","name":"$long","duration_s":94.5}""")) }
        val n = dao.song(SA)!!.name!!
        assertEquals(30, n.codePointCount(0, n.length))
        assertFalse(n.last().isHighSurrogate())
    }
}
