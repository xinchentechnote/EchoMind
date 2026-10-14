package com.xinchen.echomind.domain.subtitle

import com.xinchen.echomind.domain.model.SubtitleSegment

/**
 * 测试字幕的解析器（只服务 Phase 5 的字幕管线验证，与 ASR 无关）。
 *
 * 为什么需要它：ASR 要到 Phase 6 才接，但七句窗口、二分定位、点击跳转、事务替换这些管线
 * 现在就得能验。可行的做法是让用户导入**真实存在**的字幕文件（SRT/VTT/TSV，时间戳由文件本身给出），
 * 而不是我们在代码里按字数均分造一份——那是 AGENTS.md §5 明令禁止的伪造时间戳。
 *
 * 因此这里只做「读文件里写好的时间」这一件事：
 * - 解析不出时间 → 整份拒绝并给出行号，绝不回退到均分；
 * -  cues 乱序 → 按 start 排序（这是重排，不是造时间），索引在落库时由仓库重编；
 * - 不猜缺失的 end：SRT 缺 end 的行直接判失败。
 */
object TestSubtitleParser {

    /** 解析结果：失败带一句能直接给用户看的中文原因（含定位信息） */
    sealed interface Result {
        data class Ok(val segments: List<SubtitleSegment>) : Result
        data class Failed(val reason: String) : Result
    }

    fun parse(raw: String): Result {
        val text = raw.removePrefix("\uFEFF")
        if (text.isBlank()) return Result.Failed("文件是空的")
        val timestamped = text.contains("-->") || text.contains("WEBVTT")
        return if (timestamped) parseTimestamped(text, stripVttTags = text.contains("WEBVTT"))
        else parseTsv(text)
    }

    private fun parseTimestamped(text: String, stripVttTags: Boolean): Result {
        val lines = text.lines()
        val segments = ArrayList<SubtitleSegment>()
        var index = 0
        var pendingStart: Long? = null
        var pendingEnd: Long? = null
        var cueLine = 0
        val body = StringBuilder()

        fun flush() {
            val start = pendingStart ?: return
            val end = pendingEnd ?: return
            val line = body.toString().trim().let { if (stripVttTags) it.replace(TAG_PATTERN, "") else it }
                .replace(Regex("\\s+"), " ")
                .trim()
            if (line.isNotEmpty()) {
                segments.add(SubtitleSegment(index = index++, startMs = start, endMs = end, text = line))
            }
            body.setLength(0)
            pendingStart = null
            pendingEnd = null
        }

        for ((offset, rawLine) in lines.withIndex()) {
            val line = rawLine.trim()
            val lineNumber = offset + 1
            val nextLine = lines.getOrNull(offset + 1)?.trim().orEmpty()
            when {
                // 空行是 cue 的结束（SRT/VTT 都靠它分隔），不结算就会把下一条的序号当正文吃掉
                line.isEmpty() -> flush()

                line.equals("WEBVTT", ignoreCase = true) || line.startsWith("NOTE") ||
                    line.startsWith("STYLE") || line.startsWith("::") || line.startsWith("Language:") -> Unit

                // 紧跟着时间行的纯数字行是 cue 序号
                pendingStart != null && line.isNotEmpty() && line.all { it.isDigit() } && nextLine.contains("-->") -> Unit

                line.contains("-->") -> {
                    flush()
                    cueLine = lineNumber
                    val parts = line.split("-->")
                    val left = parseClock(parts[0].trim().substringAfterLast(' '))
                        ?: return Result.Failed("第 $lineNumber 行的开始时间无法解析：${parts[0].trim()}")
                    val right = parseClock(parts[1].trim().substringBefore(' '))
                        ?: return Result.Failed("第 $lineNumber 行的结束时间无法解析：${parts[1].trim()}")
                    if (right <= left) return Result.Failed("第 $cueLine 行结束时间不晚于开始时间")
                    pendingStart = left
                    pendingEnd = right
                }

                else -> {
                    if (pendingStart != null) body.append(line).append(' ')
                }
            }
        }
        flush()
        return failIfEmpty(segments) ?: ok(segments)
    }

    /** 每行 `开始ms 结束ms 文本`，制表符或分号分隔；允许 # 开头注释行与空行 */
    private fun parseTsv(text: String): Result {
        val segments = ArrayList<SubtitleSegment>()
        var index = 0
        for ((offset, rawLine) in text.lines().withIndex()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            // 只切两刀：正文里再出现分隔符也还是同一句
            val delimiter = if (line.contains('\t')) '\t' else ';'
            val parts = line.split(delimiter, limit = 3).map { it.trim() }
            if (parts.size < 3) return Result.Failed("第 ${offset + 1} 行不是「开始 结束 文本」三列：$line")
            val start = parts[0].toLongOrNull() ?: return Result.Failed("第 ${offset + 1} 行的开始时间不是毫秒整数：${parts[0]}")
            val end = parts[1].toLongOrNull() ?: return Result.Failed("第 ${offset + 1} 行的结束时间不是毫秒整数：${parts[1]}")
            if (end <= start) return Result.Failed("第 ${offset + 1} 行结束时间不晚于开始时间")
            if (parts[2].isEmpty()) return Result.Failed("第 ${offset + 1} 行没有正文")
            segments.add(SubtitleSegment(index = index++, startMs = start, endMs = end, text = parts[2]))
        }
        return failIfEmpty(segments) ?: ok(segments)
    }

    private fun ok(segments: List<SubtitleSegment>): Result.Ok {
        val sorted = segments.sortedBy { it.startMs }
        return Result.Ok(sorted.mapIndexed { i, s -> s.copy(index = i) })
    }

    private fun failIfEmpty(segments: List<SubtitleSegment>): Result? =
        if (segments.isEmpty()) Result.Failed("没有解析出任何一条带时间戳的字幕") else null

    /**
     * `HH:MM:SS,mmm` / `HH:MM:SS.mmm` / `MM:SS.mmm`；毫秒可缺省。
     * 返回 null 表示格式不对——调用方必须把它当错误，不能拿 0 兜底（兜底等于伪造时间）。
     */
    private fun parseClock(value: String): Long? {
        val cleaned = value.trim()
        if (cleaned.isEmpty()) return null
        val pieces = cleaned.split(':')
        if (pieces.size !in 2..3) return null
        val fractional = pieces.last()
        val cut = fractional.indexOfAny(charArrayOf('.', ','))
        val secondPart: String
        val millis: Long
        if (cut == -1) {
            secondPart = fractional
            millis = 0L
        } else {
            val tail = fractional.substring(cut + 1)
            val digits = tail.takeWhile { it.isDigit() }
            if (digits.isEmpty() || digits.length != tail.length) return null
            secondPart = fractional.substring(0, cut)
            // 不足三位按高位补齐：".5" 是 500ms，不是 5ms
            millis = digits.padEnd(3, '0').take(3).toLongOrNull() ?: return null
        }
        val numbers = (pieces.dropLast(1) + secondPart).map { it.toLongOrNull() ?: return null }
        val seconds = when (numbers.size) {
            2 -> numbers[0] * 60 + numbers[1]
            3 -> numbers[0] * 3_600 + numbers[1] * 60 + numbers[2]
            else -> return null
        }
        if (seconds < 0 || millis !in 0..999) return null
        return seconds * 1_000 + millis
    }

    private val TAG_PATTERN = Regex("<[^>]*>")
}
