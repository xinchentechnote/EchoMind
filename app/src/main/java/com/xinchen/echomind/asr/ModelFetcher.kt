package com.xinchen.echomind.asr

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * 把一个 URL 的字节流写进目标文件。测试里注入假实现，真机上用 [HttpFetcher]。
 *
 * 约定：[onProgress] 每收到一块就回调一次（参数是本次已收字节与总字节，未知总数传 -1），
 * 回调里抛异常即中止本次取回；失败必须抛 [IOException]，不许返回半截文件还报成功。
 */
fun interface ModelFetcher {
    fun fetch(url: String, target: File, onProgress: (receivedBytes: Long, totalBytes: Long) -> Unit)
}

/**
 * [HttpURLConnection] 版取回器（不引 OkHttp：项目里已有 Media3 与 WorkManager，网络这块不需要第三个库）。
 *
 * 重定向自己跟：GitHub release 与 hf-mirror 都会 302 到对象存储，而
 * `java.net.HttpURLConnection` 在 `instanceFollowRedirects=false` 下要把 `Location` 按当前地址解析
 * （可能是相对路径），这条行为由 `ModelManagerTest` 用本机 HTTP 服务实测覆盖。
 */
object HttpFetcher : ModelFetcher {

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val MAX_REDIRECTS = 5
    private const val BUFFER_BYTES = 64 * 1024

    override fun fetch(
        url: String,
        target: File,
        onProgress: (receivedBytes: Long, totalBytes: Long) -> Unit,
    ) {
        var current = URI.create(url).toURL()
        var redirects = 0
        while (true) {
            val connection = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
            }
            try {
                val status = connection.responseCode
                if (status in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("$status 但没有 Location：$current")
                    redirects++
                    if (redirects > MAX_REDIRECTS) throw IOException("重定向超过 $MAX_REDIRECTS 次：$url")
                    current = resolve(current, location)
                    continue
                }
                if (status !in 200..299) throw IOException("HTTP $status：$current")
                writeBody(connection, current, target, onProgress)
                return
            } finally {
                connection.disconnect()
            }
        }
    }

    /** `Location` 可能是相对路径，必须按当前 URL 解析；解析不出来就是服务端不合规，如实报错。 */
    private fun resolve(base: URL, location: String): URL = try {
        URI.create(base.toString()).resolve(location).toURL()
    } catch (e: IllegalArgumentException) {
        throw IOException("无法解析重定向地址 Location=\"$location\"（来自 $base）", e)
    }

    private fun writeBody(
        connection: HttpURLConnection,
        url: URL,
        target: File,
        onProgress: (Long, Long) -> Unit,
    ) {
        val total = connection.contentLengthLong
        target.parentFile?.mkdirs()
        connection.inputStream.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(BUFFER_BYTES)
                var received = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    received += read
                    onProgress(received, total)
                }
                if (total >= 0 && received != total) {
                    throw IOException("连接提前结束：$url 期望 $total B，实收 $received B")
                }
            }
        }
    }
}
