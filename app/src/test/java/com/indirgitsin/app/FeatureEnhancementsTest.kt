package com.indirgitsin.app

import com.indirgitsin.app.data.downloader.DownloadQueueCoordinator
import com.indirgitsin.app.ui.screen.VideoPlaybackModel
import com.indirgitsin.app.util.YoutubeLinkHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class FeatureEnhancementsTest {

    @Test
    fun testPlatformDetection() {
        assertEquals("YouTube", YoutubeLinkHelper.detectPlatform("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("YouTube", YoutubeLinkHelper.detectPlatform("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("SoundCloud", YoutubeLinkHelper.detectPlatform("https://soundcloud.com/artist-name/track-name"))
        assertEquals("SoundCloud", YoutubeLinkHelper.detectPlatform("https://m.soundcloud.com/artist-name/track-name"))
        assertEquals("Bandcamp", YoutubeLinkHelper.detectPlatform("https://artist.bandcamp.com/track/track-name"))
        assertEquals("MediaCCC", YoutubeLinkHelper.detectPlatform("https://media.ccc.de/v/37c3-1234"))
        assertNull(YoutubeLinkHelper.detectPlatform("https://example.com/not-supported"))
    }

    @Test
    fun testSupportedMediaUrl() {
        assertTrue(YoutubeLinkHelper.isSupportedMediaUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertTrue(YoutubeLinkHelper.isSupportedMediaUrl("https://soundcloud.com/artist/song"))
        assertTrue(YoutubeLinkHelper.isSupportedMediaUrl("https://test.bandcamp.com/album/demo"))
        assertTrue(YoutubeLinkHelper.isSupportedMediaUrl("https://media.ccc.de/v/talk-1"))
        assertFalse(YoutubeLinkHelper.isSupportedMediaUrl("https://unknownsite.org/file.mp4"))
    }

    @Test
    fun testFindMediaUrlInSharedText() {
        val ytText = "Listen to this great video: https://youtu.be/dQw4w9WgXcQ check it out"
        assertEquals("https://youtu.be/dQw4w9WgXcQ", YoutubeLinkHelper.findMediaUrlInText(ytText))

        val scText = "Listen to this track on soundcloud https://soundcloud.com/user/track right now"
        assertEquals("https://soundcloud.com/user/track", YoutubeLinkHelper.findMediaUrlInText(scText))

        val noLinkText = "Just random text without media link https://google.com"
        assertNull(YoutubeLinkHelper.findMediaUrlInText(noLinkText))
    }

    @Test
    fun testDownloadQueuePrioritization() = runBlocking(Dispatchers.Default) {
        DownloadQueueCoordinator.resetForTesting()

        val blocker1 = CompletableDeferred<Unit>()
        val blocker2 = CompletableDeferred<Unit>()
        val id1 = UUID.randomUUID()
        val id2 = UUID.randomUUID()
        val id3 = UUID.randomUUID()
        val id4 = UUID.randomUUID()

        // Launch two jobs to occupy the 2 concurrency slots
        val job1 = launch { DownloadQueueCoordinator.withPermit(id1) { blocker1.await() } }
        val job2 = launch { DownloadQueueCoordinator.withPermit(id2) { blocker2.await() } }

        delay(150)

        // Launch job 3 and then job 4 sequentially to guarantee initial queue order
        val orderLog = mutableListOf<UUID>()
        val job3 = launch { DownloadQueueCoordinator.withPermit(id3) { orderLog.add(id3) } }
        delay(100)
        val job4 = launch { DownloadQueueCoordinator.withPermit(id4) { orderLog.add(id4) } }
        delay(100)

        // Initially id3 is rank 1, id4 is rank 2
        var orders = DownloadQueueCoordinator.queueOrder.value
        assertEquals(1, orders[id3])
        assertEquals(2, orders[id4])

        // Prioritize id4 so it jumps ahead of id3
        DownloadQueueCoordinator.prioritize(id4)

        orders = DownloadQueueCoordinator.queueOrder.value
        assertEquals(1, orders[id4])
        assertEquals(2, orders[id3])

        // Release slot 1: id4 was prioritized so it must be executed before id3
        blocker1.complete(Unit)
        delay(150)
        assertTrue("id4 should run first", orderLog.isNotEmpty() && orderLog[0] == id4)

        // Release slot 2
        blocker2.complete(Unit)
        delay(150)
        assertEquals(listOf(id4, id3), orderLog)

        job1.join()
        job2.join()
        job3.join()
        job4.join()
    }

    @Test
    fun testVideoPlaybackModelIsAudioExtension() {
        assertTrue(VideoPlaybackModel.isAudioExtension("Song.mp3"))
        assertTrue(VideoPlaybackModel.isAudioExtension("Track.m4a"))
        assertTrue(VideoPlaybackModel.isAudioExtension("Audio.opus"))
        assertTrue(VideoPlaybackModel.isAudioExtension("Music.flac"))
        assertFalse(VideoPlaybackModel.isAudioExtension("Movie.mp4"))
        assertFalse(VideoPlaybackModel.isAudioExtension("Video.mkv"))
        assertFalse(VideoPlaybackModel.isAudioExtension("Stream.webm"))
    }
}
