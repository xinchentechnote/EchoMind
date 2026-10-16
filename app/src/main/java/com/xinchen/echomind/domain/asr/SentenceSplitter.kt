package com.xinchen.echomind.domain.asr

import com.xinchen.echomind.core.conf.AudioFormats
import com.xinchen.echomind.domain.model.RecognizedSegment
import com.xinchen.echomind.domain.model.SubtitleDraft
import com.xinchen.echomind.domain.model.TokenTiming

/**
 * 把逐字时间收拾成句级字幕（FR-602 切句、FR-603 时间清洗、FR-604 时间只许来自模型；`docs/asr-design.md` §6）。
 *
 * 输入是**按 VAD 段分组**的 token（[RecognizedSegment]），因为实测证明「块边界 ≠ 句子边界」：
 * 同一段歌词整块 20 s 给「那片笑声让我想起我的那些年」，切成两块给「偏笑声让」+「想起我的那些话」
 * （`docs/testing.md` §4 Phase 6 第 3 项终）。逐字时间各自成立，文本要在这一层拼回来。
 *
 * 三条与「不许伪造时间」直接相关的取舍：
 * 1. **间隔只能用模型给得起的量，而且「给得起」不等于「说明问题」**。Paraformer 的 `durations` 实测是空数组，
 *    能算的只有**相邻起点之差**，而这个差里混着前一个字自己的发音长度——唱歌时每个字占 400~600 ms，
 *    那叫慢，不叫停顿。所以段内断句要求差同时越过**绝对下限** [intraPauseMs] 和**本段中位字距的倍数**
 *    [pauseMedianRatio]（设备实测教训写在 `core/conf/AudioFormats.kt` 的 `ASR_INTRA_SEGMENT_PAUSE_MS` 上）。
 * 2. **句子时长不许凑**。一句的 `endMs` 要么是下一句的起点、要么是这一段的语音边界；两者都给不出正时长时
 *    宁可并进邻居或如实丢掉，也不写一个看着顺眼的 `+N ms`。
 * 3. **句末「。」是展示层补的**（模型不产出标点，`docs/asr-design.md` §1-A7），代码里标明，别当句读准确率。
 *
 * 流水（每步都有 JVM 单测）：
 * 清洗排序与重叠 → 段内按标点/离群停顿断句 → 跨 VAD 段合并过度切分 → 超长句按「弱标点 → 空白 → 长度」降级再切
 * （只有最后一档标 [SubtitleDraft.boundaryGuess]）→ 时间收口（单调不减、时长 > 0）。
 */
object SentenceSplitter {

    /** 强标点：见到就断句（FR-602 的第一优先级）。当前模型不给标点，给了就照办。 */
    private const val STRONG_PUNCT = "。！？；!?;"

    /** 弱标点：只作为长句兜底切分点（FR-602 的第二优先级），单独不足以断句。 */
    private const val WEAK_PUNCT = "，、,："

    /**
     * 一个 token + 它的绝对时间。内部形态：`endMs` 恒等于**下一个 token 的起点**（末 token 取本段语音末尾）。
     * [wordStart] 标记「这是一个英文词的开头」（sentencepiece 的 ▁ 前缀，多语种模型 D41）——
     * 拼句时它前面要补空格；中文逐字 token 此标记恒为 false。
     */
    private class Timed(val token: String, val startMs: Long, val endMs: Long, val wordStart: Boolean = false)

    /**
     * 候选句 = [items] 这段连续字。
     *
     * [segmentFinal] 表示它是本 VAD 段的最后一句——只有这种句子才允许跨段合并（§6 第 3 条治的是 VAD 过度切分），
     * 段内因为起点差而断开的句子如果又被合回去，第 2 条就白做了。
     * [gapAfterMs] 是它与下一句之间「模型给得出的间隔」，-1 表示还没算（等下一段）。
     */
    private class Cut(
        val items: List<Timed>,
        val endsStrong: Boolean,
        var gapAfterMs: Long,
        val boundaryGuess: Boolean,
        val segmentFinal: Boolean,
    ) {
        val startMs: Long get() = items.first().startMs
        val endMs: Long get() = items.last().endMs
        val text: String get() = joinText(items)
        val chars: Int get() = text.count { !it.isWhitespace() }
    }

    /**
     * @param segments 按整篇顺序给出的 VAD 段识别结果，时间必须是**绝对**毫秒（`TokenTimingMapper` 的产物）
     * @param maxSentenceChars 单句字数上限（FR-602 的 60 字）
     * @param intraPauseMs 段内断句的起点差绝对下限（§6 第 2 条）
     * @param pauseMedianRatio 起点差还得 ≥ 本段中位字距 × 此倍数才断（慢唱不算停顿）
     * @param mergeGapMs 相邻两句间隔 ≤ 此值且合起来不超长 → 合并（§6 第 3 条，治 VAD 过度切分）
     * @return 时间升序、每句时长 > 0 的字幕草稿
     */
    fun split(
        segments: List<RecognizedSegment>,
        maxSentenceChars: Int = AudioFormats.ASR_MAX_SENTENCE_CHARS,
        intraPauseMs: Long = AudioFormats.ASR_INTRA_SEGMENT_PAUSE_MS,
        pauseMedianRatio: Int = AudioFormats.ASR_PAUSE_MEDIAN_RATIO,
        mergeGapMs: Long = AudioFormats.ASR_SENTENCE_MERGE_GAP_MS,
    ): List<SubtitleDraft> {
        require(maxSentenceChars > 0) { "单句字数上限必须为正：$maxSentenceChars" }
        require(pauseMedianRatio >= 1) { "停顿倍数至少为 1：$pauseMedianRatio" }

        val cuts = segments.flatMap { cutSegment(it, intraPauseMs, pauseMedianRatio) }
        if (cuts.isEmpty()) return emptyList()
        fillInterSegmentGaps(cuts)
        val merged = mergeAdjacent(cuts, maxSentenceChars, mergeGapMs)
        return settle(merged.flatMap { splitOverlong(it, maxSentenceChars) })
    }

    // ── 第 1 步：段内清洗 + 断句 ──

    /**
     * 一个 VAD 段 → 若干候选句。
     *
     * 先做两件清洗（FR-603 的「与上一段重叠」就在这里没了）：字按起点排好（跨块拼接可能乱序），
     * 再把每个字的 `endMs` 压到下一个字的起点——[TokenTiming] 自己只保证 `endMs ≥ startMs`，
     * 管不了「上一个字盖到下一个字头上」。夹完剩下的 `endMs` 只有两种来源：下一字起点，或本段末尾。
     *
     * 断句基准是**这一段自己的中位字距**：一段里语速/唱速是均匀的，突然变大那一档才是换气。
     * 用绝对门槛（哪怕 500 ms）会把慢速素材逐字切开——设备实测过，见 `AudioFormats` 里那段记录。
     */
    private fun cutSegment(
        segment: RecognizedSegment,
        intraPauseMs: Long,
        pauseMedianRatio: Int,
    ): List<Cut> {
        val items = normalize(segment)
        if (items.isEmpty()) return emptyList()
        val baseline = medianDelta(items) * pauseMedianRatio

        val cuts = mutableListOf<Cut>()
        val current = mutableListOf<Timed>()
        for ((index, item) in items.withIndex()) {
            current += item
            val strong = isStrongPunct(item.token)
            val gapToNext = if (index + 1 < items.size) items[index + 1].startMs - item.startMs else -1L
            val isPause = gapToNext >= intraPauseMs && gapToNext >= baseline
            val endsHere = index == items.lastIndex || strong || isPause
            if (endsHere) {
                cuts += Cut(
                    items = current.toList(),
                    endsStrong = strong,
                    // 段末那句的间隔要等下一段才知道，先放 -1 让 fillInterSegmentGaps 补
                    gapAfterMs = if (index == items.lastIndex) -1L else gapToNext.coerceAtLeast(0L),
                    boundaryGuess = false,
                    segmentFinal = index == items.lastIndex,
                )
                current.clear()
            }
        }
        return cuts
    }

    /**
     * 相邻起点差的中位数（偶数个取**靠下**那个，宁可让基准偏低、多认一个停顿，也不要虚高的基准把停顿抹平）。
     * 只有一个差时它就等于那个差，× 倍数 之后必然断不了——「一个字比另一个字慢」不是停顿证据。
     */
    private fun medianDelta(items: List<Timed>): Long {
        val deltas = (1 until items.size).map { items[it].startMs - items[it - 1].startMs }
        if (deltas.isEmpty()) return 0L
        return deltas.sorted()[(deltas.size - 1) / 2]
    }

    private fun normalize(segment: RecognizedSegment): List<Timed> {
        val ordered = segment.tokens.sortedBy { it.startMs }
        if (ordered.isEmpty()) return emptyList()
        val starts = ArrayList<Long>(ordered.size)
        for ((index, token) in ordered.withIndex()) {
            // 起点只往回收拢到「不早于前一个字」，不往前沿：往前就是造一个模型没给的时间
            starts += if (index == 0) ordered[index].startMs else ordered[index].startMs.coerceAtLeast(starts[index - 1])
        }
        return ordered.mapIndexed { index, timed ->
            val start = starts[index]
            val rawEnd = if (index == ordered.lastIndex) segment.endMs else starts[index + 1]
            // sentencepiece 的 ▁ 前缀 = 词界 + 词文（▁the → the + wordStart）；中文 token 没有 ▁
            val raw = timed.token.trim()
            val wordStart = raw.startsWith("▁")
            val body = raw.removePrefix("▁").trim()
            Timed(token = body, startMs = start, endMs = rawEnd.coerceAtLeast(start), wordStart = wordStart)
        }
    }

    /**
     * 段末与下一句之间补间隔 = 下一句起点 − 本句末尾。
     *
     * 这个合并窗口必须**宽于 VAD 的 `minSilence`（250 ms）**才谈得上合并：VAD 能断成两段就说明中间静音 ≥250 ms，
     * 拿 250 ms 当门槛等于永远不合（`docs/asr-design.md` §6 第 3 条原稿正是这个数，所以它形同虚设——已改成 700 ms 并记在那里）。
     */
    private fun fillInterSegmentGaps(cuts: List<Cut>) {
        for (index in 0 until cuts.lastIndex) {
            if (cuts[index].gapAfterMs < 0) {
                cuts[index].gapAfterMs = (cuts[index + 1].startMs - cuts[index].endMs).coerceAtLeast(0L)
            }
        }
        cuts.last().gapAfterMs = 0L
    }

    // ── 第 2 步：把被 VAD 切碎的句子接回来 ──

    private fun mergeAdjacent(cuts: List<Cut>, maxSentenceChars: Int, mergeGapMs: Long): List<Cut> {
        val out = mutableListOf(cuts.first())
        for (next in cuts.drop(1)) {
            val current = out.last()
            val canMerge = current.segmentFinal &&
                !current.endsStrong &&
                current.gapAfterMs <= mergeGapMs &&
                current.chars + next.chars <= maxSentenceChars
            if (canMerge) {
                out[out.lastIndex] = Cut(
                    items = current.items + next.items,
                    endsStrong = next.endsStrong,
                    gapAfterMs = next.gapAfterMs,
                    boundaryGuess = current.boundaryGuess || next.boundaryGuess,
                    segmentFinal = next.segmentFinal,
                )
            } else {
                out += next
            }
        }
        return out
    }

    // ── 第 3 步：超长句按「弱标点 → 空白 → 长度」再切（FR-602 的回退顺序）──

    private fun splitOverlong(cut: Cut, maxSentenceChars: Int): List<Cut> {
        if (cut.chars <= maxSentenceChars) return listOf(cut)
        val out = mutableListOf<Cut>()
        var rest = cut
        while (rest.chars > maxSentenceChars) {
            val point = pickSplitIndex(rest, maxSentenceChars)
            if (point.at <= 0) break // 一个字就超长（整串 token 比上限还长）：硬切也切不出可读的句子，照原样留着
            val head = rest.items.subList(0, point.at)
            val tail = rest.items.subList(point.at, rest.items.size)
            out += Cut(
                items = head,
                endsStrong = isStrongPunct(head.last().token),
                gapAfterMs = (tail.first().startMs - head.last().endMs).coerceAtLeast(0L),
                boundaryGuess = rest.boundaryGuess || point.guessed,
                segmentFinal = false,
            )
            rest = Cut(
                items = tail,
                endsStrong = rest.endsStrong,
                gapAfterMs = -1L,
                boundaryGuess = false,
                segmentFinal = rest.segmentFinal,
            )
        }
        out += rest
        return out
    }

    /** 切点位置；[guessed] 为 true 表示这是最后一档「按长度硬切」，边界不是任何证据给的。 */
    private class SplitPoint(val at: Int, val guessed: Boolean)

    /**
     * 返回切点（尾句从 `items[at]` 开始），三档按证据强弱排：弱标点 → 空白 → 字数上限处硬切（FR-602 的
     * 「超出按标点或空格回退切分」）。只在「head 不超 [maxSentenceChars] 字」的窗口里找，否则切了等于没切。
     * `at = 0` 表示无切点。
     *
     * **这里不再有「最大起点差」那一档**（原 §6 第 4 条设计稿有）：能走到这一层的句子，段内都没有够得上
     * 停顿的间隔（同一套判据在 [cutSegment] 就用掉了），而跨段合并来的句子字数必然 ≤ 上限、进不了超长分支。
     * 既然「最大」在这个集合里不再携带任何信息，留着它只会让硬切冒充证据。
     */
    private fun pickSplitIndex(cut: Cut, maxSentenceChars: Int): SplitPoint {
        val items = cut.items
        var used = 0
        var windowEnd = -1 // head 的最后一项下标
        for (index in items.indices) {
            used += countChars(items[index].token)
            if (index == 0 && used > maxSentenceChars) return SplitPoint(0, false) // 首字就超长
            if (used > maxSentenceChars) break
            windowEnd = index
        }
        if (windowEnd < 0 || windowEnd >= items.lastIndex) return SplitPoint(0, false)

        // ① 窗口内最后一个弱标点（标点留在 head 末尾）
        for (index in windowEnd + 1 downTo 1) {
            if (isWeakPunct(items[index - 1].token)) return SplitPoint(index, false)
        }
        // ② 空白（纯空白 token 被 trim 成空串；带英文的素材才用得上这一档）
        for (index in windowEnd + 1 downTo 1) {
            if (items[index - 1].token.isEmpty() || items[index].token.isEmpty()) return SplitPoint(index, false)
        }
        // ③ 字数上限处硬切
        return SplitPoint(windowEnd + 1, true)
    }

    // ── 第 4 步：时间收口（FR-603：清洗后单调不减、时长 > 0）──

    private fun settle(cuts: List<Cut>): List<SubtitleDraft> {
        val ordered = cuts.sortedBy { it.startMs }
        val drafts = mutableListOf<SubtitleDraft>()
        for ((index, cut) in ordered.withIndex()) {
            if (!hasContent(cut.text)) continue // 只剩个标点的句子没有存在价值（也不许给它编时长）
            val text = displayText(cut.text)
            if (text.isEmpty()) continue
            val next = ordered.getOrNull(index + 1)
            val start = cut.startMs
            var end = if (next != null) cut.endMs.coerceAtMost(next.startMs) else cut.endMs.coerceAtLeast(cut.startMs)
            if (end <= start) {
                // 零长句有三种收场，没有一种需要造时间：
                // ①和上一句本来就连着（上一句的结束就是本句起点）→ 文本并进上一句，上一句的时长不变；
                // ②上一句离得远（中间是一大段静音）→ 不并，否则那句话会莫名其妙占掉几十秒；
                // ③后面还有句子 → 结束就用下一句的起点；谁都给不出就如实丢掉。
                val last = drafts.lastOrNull()
                if (last != null && last.endMs >= start) {
                    drafts[drafts.lastIndex] = last.copy(
                        text = last.text + text,
                        boundaryGuess = last.boundaryGuess || cut.boundaryGuess,
                    )
                    continue
                }
                if (next != null && next.startMs > start) {
                    end = next.startMs
                } else {
                    continue
                }
            }
            drafts += SubtitleDraft(startMs = start, endMs = end, text = text, boundaryGuess = cut.boundaryGuess)
        }
        return drafts
    }

    /**
     * 句末补句号——**展示层补的，不是模型判的句读**（A7：模型不产出标点）。
     * 拉丁文本补「.」、中文补「。」（按末字符的码位判断，多语种模型 D41）；只补句号，不猜逗号和问号。
     */
    private fun displayText(raw: String): String {
        val trimmed = raw.trim().trimEnd { it.isWhitespace() }
        if (trimmed.isEmpty()) return ""
        val last = trimmed.last()
        if (last in STRONG_PUNCT || last in WEAK_PUNCT || last == '.') return trimmed
        val period = if (last.code < 0x2E80 && last.isLetterOrDigit()) "." else "。"
        return trimmed + period
    }

    private fun isStrongPunct(token: String): Boolean = token.length == 1 && token[0] in STRONG_PUNCT

    /** 句子里有没有真正的说话内容（去掉空白和标点还剩东西）。 */
    private fun hasContent(text: String): Boolean =
        text.any { !it.isWhitespace() && it !in STRONG_PUNCT && it !in WEAK_PUNCT }

    private fun isWeakPunct(token: String): Boolean = token.length == 1 && token[0] in WEAK_PUNCT

    private fun countChars(token: String): Int = token.count { !it.isWhitespace() }

    /** 中文逐字连排；英文词的开头（▁ 标记或两侧都是字母/数字）补空格，否则会粘成一坨。 */
    private fun joinText(items: List<Timed>): String {
        val sb = StringBuilder()
        for (item in items) {
            if (item.token.isEmpty()) continue
            val spaceBefore = sb.isNotEmpty() && (item.wordStart || needsSpace(sb.last(), item.token.first()))
            if (spaceBefore) sb.append(' ')
            sb.append(item.token)
        }
        return sb.toString()
    }

    private fun needsSpace(left: Char, right: Char): Boolean = left.isLetterOrDigit() && right.isLetterOrDigit() &&
        left.code < 0x2E80 && right.code < 0x2E80
}
