package com.xinchen.echomind.domain.transcript

import com.xinchen.echomind.domain.model.RecognizedSegment
import com.xinchen.echomind.domain.model.TokenTiming
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * 一次转录的**断点**（FR-512，2026-10-30）：进程被杀 / 用户取消后，已识别完的语音段与
 * 「音频解到哪」一起落在一个小文件里，重试时从断点继续，不再从头烧整本书的 CPU。
 *
 * 字段分两组：
 * - **续跑位置**：[resumeSample] 是「最后一个已识别段在整条 16 kHz 时间线上的终点样本」。
 *   它取段的终点而不是解码位置，是为了续跑**不丢音频**：旧一轮的 VAD 在确认段结束前吃掉的
 *   尾巴（段终点之后的静音窗）≥ 解码位置与段终点之差，从段终点重喂，新 VAD 顶多重看一遍静音；
 *   从解码位置续跑则可能把一段刚开口、还没确认结束的语音丢掉。
 * - **成果与账目**：[segments] 是已识别的段（逐字时间原样保留，收尾切句仍按整本做）；
 *   [totalChunks]/[rejectedChunks]/[doneSegments] 是中途账目，恢复它们，
 *   收尾的质量闸门（`TranscriptQuality`）与进度口径才和一遍跑完完全等价。
 *
 * [uri]/[fileLastModifiedMs]/[modelId]/[modelVersion] 是断点的**身份**：文件变过（FR-107 的
 * 「旧字幕不可信」同一判据）或换模型重试，断点作废——不同来源或不同权重的字混进同一条时间线，
 * 比没有断点更糟。
 *
 * 纯 Kotlin + `java.io`（AGENTS.md §3）：编解码在 JVM 单测里钉死。`writeUTF` 是修改版 UTF-8，
 * 任意 token 文本（含换行、控制字符）都能无损往返。
 */
data class TranscriptCheckpoint(
    val audioId: String,
    val uri: String,
    val fileLastModifiedMs: Long,
    val modelId: String,
    val modelVersion: String,
    /** 最后一个已识别段的终点（16 kHz 绝对样本下标）；0 = 还没有识别出任何段（这种断点不会写盘） */
    val resumeSample: Long,
    val totalChunks: Int,
    val rejectedChunks: Int,
    val doneSegments: Int,
    val segments: List<RecognizedSegment>,
    /** 断点写入的墙钟时间，仅用于排查；不参与任何判定 */
    val savedAtWallMs: Long,
)

/**
 * [TranscriptCheckpoint] 的二进制编解码。不用 JSON 是为了不引新依赖（AGENTS.md §3），
 * `DataOutputStream` 的定长布局也更省盘：一小时书的断点在几百 KB 量级。
 *
 * 读到的 [FORMAT_VERSION] 不认识就抛 [IOException]——旧版本的断点宁可作废（从头识别），
 * 也不能按猜出来的布局读出一份错位的假数据。
 */
object TranscriptCheckpointCodec {

    /** 文件头：magic 用来把「断点文件」与任何恰好同名的东西区分开 */
    private const val MAGIC = 0x45434D42 // "ECMB"，EchoMind Checkpoint
    private const val FORMAT_VERSION = 1

    fun write(checkpoint: TranscriptCheckpoint, out: DataOutputStream) {
        out.writeInt(MAGIC)
        out.writeInt(FORMAT_VERSION)
        out.writeUTF(checkpoint.audioId)
        out.writeUTF(checkpoint.uri)
        out.writeLong(checkpoint.fileLastModifiedMs)
        out.writeUTF(checkpoint.modelId)
        out.writeUTF(checkpoint.modelVersion)
        out.writeLong(checkpoint.resumeSample)
        out.writeInt(checkpoint.totalChunks)
        out.writeInt(checkpoint.rejectedChunks)
        out.writeInt(checkpoint.doneSegments)
        out.writeLong(checkpoint.savedAtWallMs)
        out.writeInt(checkpoint.segments.size)
        for (segment in checkpoint.segments) {
            out.writeLong(segment.startMs)
            out.writeLong(segment.endMs)
            out.writeInt(segment.tokens.size)
            for (token in segment.tokens) {
                out.writeUTF(token.token)
                out.writeLong(token.startMs)
                out.writeLong(token.endMs)
            }
        }
    }

    /** 布局与 [write] 严格对称；任何截断、版本不符都以 [IOException] 报废，调用方按「无断点」处理 */
    fun read(input: DataInputStream): TranscriptCheckpoint {
        val magic = input.readInt()
        check(magic == MAGIC) { "不是断点文件（magic=$magic）" }
        val version = input.readInt()
        check(version == FORMAT_VERSION) { "断点格式版本不支持：$version（本机实现 $FORMAT_VERSION）" }
        val audioId = input.readUTF()
        val uri = input.readUTF()
        val fileLastModifiedMs = input.readLong()
        val modelId = input.readUTF()
        val modelVersion = input.readUTF()
        val resumeSample = input.readLong()
        val totalChunks = input.readInt()
        val rejectedChunks = input.readInt()
        val doneSegments = input.readInt()
        val savedAtWallMs = input.readLong()
        val segmentCount = input.readInt()
        require(segmentCount >= 0) { "段数为负：$segmentCount" }
        val segments = ArrayList<RecognizedSegment>(segmentCount)
        repeat(segmentCount) {
            val startMs = input.readLong()
            val endMs = input.readLong()
            val tokenCount = input.readInt()
            require(tokenCount >= 0) { "token 数为负：$tokenCount" }
            val tokens = ArrayList<TokenTiming>(tokenCount)
            repeat(tokenCount) {
                tokens += TokenTiming(token = input.readUTF(), startMs = input.readLong(), endMs = input.readLong())
            }
            segments += RecognizedSegment(startMs = startMs, endMs = endMs, tokens = tokens)
        }
        return TranscriptCheckpoint(
            audioId = audioId,
            uri = uri,
            fileLastModifiedMs = fileLastModifiedMs,
            modelId = modelId,
            modelVersion = modelVersion,
            resumeSample = resumeSample,
            totalChunks = totalChunks,
            rejectedChunks = rejectedChunks,
            doneSegments = doneSegments,
            segments = segments,
            savedAtWallMs = savedAtWallMs,
        )
    }
}
