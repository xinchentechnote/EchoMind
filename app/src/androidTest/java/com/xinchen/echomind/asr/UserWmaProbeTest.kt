package com.xinchen.echomind.asr

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 用户真实 WMA（34 分钟书解 `user-book.wma`）的解码+转码探针：两条支路各跑一遍，记录 RTF 与坏包数。
 * 不是回归用例（素材 35 MB 只在宿主机与模拟器上），是给 `docs/testing.md` 留实测数字的。
 */
@RunWith(AndroidJUnit4::class)
class UserWmaProbeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun 用户wma解码与转码m4a全量探针() {
        runBlocking {
            val uri = Uri.fromFile(DeviceAsrFixtures.fixture("user-book.wma", context))
            val out = File(context.cacheDir, "user-book.m4a")
            out.delete()
            val transcoder = WmaTranscoder(context)
            val t0 = android.os.SystemClock.elapsedRealtime()
            val durationMs = transcoder.transcodeToM4a(uri, out)
            val wall = android.os.SystemClock.elapsedRealtime() - t0
            Log.i(TAG, "m4a 转码成功：duration=$durationMs ms，输出 ${out.length()} B，wall=$wall ms（RTF ${wall / 1000f / (durationMs / 1000f)}）")
            assertTrue(durationMs > 0 && out.length() > 0)
            out.delete()
        }
    }

    /** D44 转录管线走的支路：LAME 编 mp3，位置回调要走到结尾 */
    @Test
    fun 用户wma转码mp3全量探针() {
        runBlocking {
            val uri = Uri.fromFile(DeviceAsrFixtures.fixture("user-book.wma", context))
            val out = File(context.cacheDir, "user-book.mp3")
            out.delete()
            var last = 0L
            var calls = 0
            val t0 = android.os.SystemClock.elapsedRealtime()
            val durationMs = WmaTranscoder(context).transcodeToMp3(uri, out, onPosition = { decodedMs, _ ->
                last = decodedMs
                calls++
            })
            val wall = android.os.SystemClock.elapsedRealtime() - t0
            Log.i(
                TAG,
                "mp3 转码成功：duration=$durationMs ms，输出 ${out.length()} B，wall=$wall ms" +
                    "（RTF ${wall / 1000f / (durationMs / 1000f)}），位置回调 $calls 次，末次 $last ms，" +
                    "MediaExtractor 读到时长 ${PcmDecoder.containerDurationMs(context, Uri.fromFile(out))} ms",
            )
            assertTrue(durationMs > 0 && out.length() > 0)
            assertTrue("位置回调没走到结尾：$last / $durationMs", Math.abs(last - durationMs) < 1_000)
            out.delete()
        }
    }

    private companion object {
        const val TAG = "UserWmaProbe"
    }
}
