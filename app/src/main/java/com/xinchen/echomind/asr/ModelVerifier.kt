package com.xinchen.echomind.asr

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * 单个模型文件的核对失败原因。
 *
 * 只有失败才需要类型：一切「看起来对」的情况都归到 [ModelVerification.failures] 为空，
 * 免得调用方去猜 Partial / AlmostReady 这种模糊状态。
 */
sealed interface FileFailure {

    val fileName: String

    data class Missing(override val fileName: String) : FileFailure

    data class SizeMismatch(
        override val fileName: String,
        val expectedBytes: Long,
        val actualBytes: Long,
    ) : FileFailure

    data class HashMismatch(
        override val fileName: String,
        val expectedSha256: String,
        val actualSha256: String,
    ) : FileFailure
}

/** [ModelVerifier] 对一个 [ModelSpec] 的一次核对结果。 */
data class ModelVerification(
    val spec: ModelSpec,
    val dir: File,
    val failures: List<FileFailure>,
    val verifiedAtMs: Long,
) {
    val isReady: Boolean get() = failures.isEmpty()
}

/**
 * 按清单核对已存在的模型文件：存在 → 大小 → SHA-256，三步全过才算就绪。
 *
 * 大小先判是为了省时间：`model.int8.onnx` 有 243 MB，截断文件（下载中断留下的）
 * 靠长度就能否决，不必先花一两秒算完整哈希（AGENTS.md §5：不许「差不多就用」）。
 */
object ModelVerifier {

    private const val DIGEST_BUFFER_BYTES = 64 * 1024

    /** 清单里一个模型包在本机的目录（`modelsRoot/<id>`）。 */
    fun dirOf(modelsRoot: File, spec: ModelSpec): File = File(modelsRoot, spec.id)

    fun verify(spec: ModelSpec, modelsRoot: File, verifiedAtMs: Long): ModelVerification {
        val dir = dirOf(modelsRoot, spec)
        return ModelVerification(
            spec = spec,
            dir = dir,
            failures = spec.files.mapNotNull { fileSpec -> checkFile(File(dir, fileSpec.fileName), fileSpec) },
            verifiedAtMs = verifiedAtMs,
        )
    }

    /** 单个文件是否已经满足清单（大小 + 哈希）。下载循环用它跳过已就绪的文件。 */
    fun isFileValid(file: File, fileSpec: ModelFileSpec): Boolean = checkFile(file, fileSpec) == null

    fun checkFile(file: File, fileSpec: ModelFileSpec): FileFailure? {
        if (!file.isFile) return FileFailure.Missing(fileSpec.fileName)
        val actualBytes = file.length()
        if (actualBytes != fileSpec.expectedBytes) {
            return FileFailure.SizeMismatch(fileSpec.fileName, fileSpec.expectedBytes, actualBytes)
        }
        val actualSha256 = sha256(file)
        if (!actualSha256.equals(fileSpec.expectedSha256, ignoreCase = true)) {
            return FileFailure.HashMismatch(fileSpec.fileName, fileSpec.expectedSha256, actualSha256)
        }
        return null
    }

    /** 流式 SHA-256，小写十六进制。整文件读进内存会吃掉 243 MB 堆，所以分块。 */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DIGEST_BUFFER_BYTES)
        FileInputStream(file).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return toHex(digest.digest())
    }

    /**
     * 小写十六进制。不用 `String.format("%02x")`（受默认 Locale 影响，中文/阿拉伯数字环境下会算出
     * 对不上的校验值），也不用 `HexFormat`（那是 JDK 17 API，Android 上要到 API 34 才有）。
     */
    fun toHex(bytes: ByteArray): String {
        val builder = StringBuilder(bytes.size * 2)
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xFF
            builder.append(HEX_DIGITS[value shr 4]).append(HEX_DIGITS[value and 0x0F])
        }
        return builder.toString()
    }

    private val HEX_DIGITS = "0123456789abcdef"
}
