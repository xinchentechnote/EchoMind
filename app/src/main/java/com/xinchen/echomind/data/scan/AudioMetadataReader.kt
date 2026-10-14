package com.xinchen.echomind.data.scan

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri

interface AudioMetadataReader {
    /** null 表示读不到时长：UI 显示「时长未知」，且该首不参与完播判定（docs/playback-design.md §3.3） */
    fun durationMs(uri: String): Long?
}

/**
 * 系统能力读取时长：先走 MediaMetadataRetriever（容器级 duration），
 * 拿不到再用 MediaExtractor 的 track duration 兜底。不引入 FFmpeg（决策：先实测系统能力）。
 */
class SystemAudioMetadataReader(private val context: Context) : AudioMetadataReader {

    override fun durationMs(uri: String): Long? =
        durationFromMetadata(uri) ?: durationFromExtractor(uri)

    private fun durationFromMetadata(uri: String): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(uri))
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }
        } catch (e: Exception) {
            null
        } catch (e: AssertionError) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun durationFromExtractor(uri: String): Long? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, Uri.parse(uri), null)
            var best = 0L
            for (i in 0 until extractor.trackCount) {
                val format: MediaFormat = extractor.getTrackFormat(i)
                if (!format.containsKey(MediaFormat.KEY_DURATION)) continue
                best = maxOf(best, format.getLong(MediaFormat.KEY_DURATION) / 1000)
            }
            best.takeIf { it > 0 }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }
}
