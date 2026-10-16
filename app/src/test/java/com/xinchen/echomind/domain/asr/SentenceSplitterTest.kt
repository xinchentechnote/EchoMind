package com.xinchen.echomind.domain.asr

import com.xinchen.echomind.domain.model.RecognizedSegment
import com.xinchen.echomind.domain.model.SubtitleDraft
import com.xinchen.echomind.domain.model.TokenTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 切句与时间清洗（FR-602 / FR-603 / FR-604；`docs/asr-design.md` §6）。
 *
 * 全是纯逻辑断言，不需要模型也不需要设备：输入形状照 2026-10-15 真推理产出的那种
 * 「逐字绝对毫秒」构造（`docs/testing.md` §4 Phase 6 第 3 项终）。
 *
 * 有一条贯穿全场的读法：**句子的 `endMs` 只有两种来源**——下一句的起点，或这一 VAD 段的语音末尾。
 * 断言里的结束时间全是这两种之一，没有「按字数摊派」的余量（AGENTS.md §5）。
 */
class SentenceSplitterTest {

    private fun token(text: String, startMs: Long, endMs: Long = startMs + 100) =
        TokenTiming(token = text, startMs = startMs, endMs = endMs)

    /**
     * 匀速说话，每字 [stepMs]。
     *
     * 逐字的 `endMs` 故意给一个明显偏大的值（+999）：切句器必须按「下一个字的起点」重算，
     * 而不是信这个凭空多出来的尾巴。
     */
    private fun speech(text: String, fromMs: Long, stepMs: Long = 100): List<TokenTiming> =
        text.mapIndexed { index, char -> token(char.toString(), fromMs + index * stepMs) }

    private fun segment(startMs: Long, endMs: Long, tokens: List<TokenTiming>) =
        RecognizedSegment(startMs, endMs, tokens)

    private fun split(vararg segments: RecognizedSegment) = SentenceSplitter.split(segments.toList())

    private fun List<SubtitleDraft>.texts(): List<String> = map { it.text }

    /** 造一段不重复的中文，长度 [size]；下标 15 的倍数处会重复用字，但整体顺序仍可断言。 */
    private fun hanChars(size: Int): List<Char> = (0 until size).map { CN[it % CN.size] }

    @Test
    fun `两个VAD段中间静音够长就是两句`() {
        val drafts = split(
            segment(0, 1_000, speech("那片笑声", 0)),
            segment(2_000, 3_000, speech("想起那年", 2_000)),
        )

        assertEquals(listOf("那片笑声。", "想起那年。"), drafts.texts())
        assertEquals(listOf(0L, 2_000L), drafts.map { it.startMs })
        // 第一句收在本段语音末尾（1000），中间那 1000 ms 静音不属于任何一句
        assertEquals(listOf(1_000L, 3_000L), drafts.map { it.endMs })
    }

    @Test
    fun `段内起点差超过门槛就断句而且合并窗口也合不回去`() {
        // 起点差 550 ms：够上绝对下限 500，也是本段中位字距（100）的 5 倍 → 是停顿。
        // 但 550 ≤ mergeGap(700)：段内断句用的是模型给的时间证据，不许被「治 VAD 过度切分」那条规则抹掉，
        // 否则 §6 第 2 条白写
        val drafts = split(
            segment(0, 2_000, listOf(token("那", 0), token("片", 100), token("笑", 650), token("声", 750))),
        )

        assertEquals(listOf("那片。", "笑声。"), drafts.texts())
        assertEquals(listOf(0L, 650L), drafts.map { it.startMs })
        assertEquals(listOf(650L, 2_000L), drafts.map { it.endMs })
    }

    @Test
    fun `整段匀速地慢不是停顿一个字都不许多切`() {
        // 设备实测到的那份失败：歌唱素材每个字本身占 400~600 ms，相邻起点差全都 >500。
        // 只按绝对门槛判就会把 8 个字切成 8 句单字字幕（2026-10-15 那条日志是 58 字 → 53 句）。
        // 起点差是「前一字的发音长度 + 静音」，模型没给发音长度，所以只能跟本段自己的典型字距比。
        val drafts = split(segment(0, 4_800, speech("那片笑声想起那年", 0, stepMs = 550)))

        assertEquals(listOf("那片笑声想起那年。"), drafts.texts())
        assertEquals(0L, drafts.single().startMs)
        assertEquals(4_800L, drafts.single().endMs)
    }

    @Test
    fun `比本段典型字距明显更慢的那一档才断句`() {
        // 同样 550 的差，放在一堆 150 的差里就是换气（3.7 倍中位）——上一条的镜像，
        // 用来钉住「判据是相对量」而不是「550 这个数本身太小」
        val tokens = listOf(
            token("那", 0), token("片", 150), token("笑", 300), token("声", 450),
            token("想", 1_000), token("起", 1_150), token("那", 1_300), token("年", 1_450),
        )
        val drafts = split(segment(0, 1_600, tokens))

        assertEquals(listOf("那片笑声。", "想起那年。"), drafts.texts())
        assertEquals(1_000L, drafts[0].endMs)
    }

    @Test
    fun `起点差不到门槛就不许凭空多切一刀`() {
        val drafts = split(segment(0, 1_000, speech("那片笑", 0, stepMs = 250)))

        assertEquals(listOf("那片笑。"), drafts.texts())
        assertEquals(0L, drafts.single().startMs)
        assertEquals(1_000L, drafts.single().endMs)
    }

    @Test
    fun `标点优先于间隔且句号不重复补`() {
        val drafts = split(
            segment(0, 1_000, listOf(token("那", 0), token("片", 100), token("。", 200), token("笑", 250), token("声", 300))),
        )

        assertEquals(listOf("那片。", "笑声。"), drafts.texts())
        assertEquals(250L, drafts[0].endMs) // 句号那句收在下一个字的起点
    }

    @Test
    fun `VAD切得过碎的两段在合并窗口内接回一句`() {
        val drafts = split(
            segment(0, 1_000, speech("那片笑声", 0)),
            segment(1_500, 2_500, speech("想起那年", 1_500)),
        )

        assertEquals(listOf("那片笑声想起那年。"), drafts.texts())
        assertEquals(0L, drafts.single().startMs)
        assertEquals(2_500L, drafts.single().endMs)
    }

    @Test
    fun `合并后超过字数上限就不合并`() {
        val left = speech(String(CharArray(40) { '字' }), 0)
        val right = speech(String(CharArray(40) { '句' }), 4_100)
        val drafts = split(segment(0, 4_000, left), segment(4_100, 8_000, right))

        assertEquals(2, drafts.size)
        // 各 40 字 + 展示层补的句号；合起来 80 字超上限，所以不并
        assertEquals(listOf(41, 41), drafts.map { it.text.length })
    }

    @Test
    fun `标点收尾的句子永远不和下一句合并`() {
        val drafts = split(
            segment(0, 1_000, listOf(token("那", 0), token("片", 100), token("。", 200))),
            segment(1_100, 2_000, speech("笑声", 1_100)),
        )

        assertEquals(listOf("那片。", "笑声。"), drafts.texts())
    }

    @Test
    fun `超长句优先在弱标点处切`() {
        val chars = hanChars(90).toMutableList()
        chars[44] = '，'
        val drafts = split(segment(0, 10_000, speech(chars.joinToString(""), 0)))

        assertEquals(2, drafts.size)
        assertEquals(chars.subList(0, 45).joinToString(""), drafts[0].text) // 逗号留在前一句，不再补句号
        assertEquals(chars.subList(45, 90).joinToString("") + "。", drafts[1].text)
        assertEquals(4_500L, drafts[0].endMs)
        assertEquals(4_500L, drafts[1].startMs)
        assertTrue("两句都有标点作依据，不是硬切", drafts.none { it.boundaryGuess })
    }

    @Test
    fun `最大起点差够不上停顿就只能在字数上限硬切并如实标记`() {
        // 90 字、字距 100 ms，中间「恰好最大」的那一档是 250 ms：既不到绝对下限 500，也不是中位字距的两倍。
        // 设计稿曾经把「最大 token 间隔」当超长句的第三档切点——设备实测证明那是错的（慢唱的最大间隔只是慢），
        // 所以现在只留「弱标点 → 空白 → 长度」，时间证据一律由 §6 第 2 条负责
        val chars = hanChars(90)
        val starts = (0 until 90).map { if (it < 60) it * 100L else 6_150 + (it - 60) * 100L }
        val tokens = chars.mapIndexed { index, char -> token(char.toString(), starts[index]) }
        val drafts = split(segment(0, 20_000, tokens))

        assertEquals(2, drafts.size)
        assertEquals(chars.subList(0, 60).joinToString("") + "。", drafts[0].text)
        assertEquals(6_150L, drafts[1].startMs)
        assertTrue("这个边界没有任何证据支撑，必须标 boundaryGuess", drafts[0].boundaryGuess)
        assertTrue("尾巴只是剩下的部分", !drafts[1].boundaryGuess)
    }

    @Test
    fun `真停顿会让长句在停顿处断开而不到字数上限`() {
        // 与上一条只差那个大间隔的大小：700 ms 够上绝对下限、也是中位字距（100）的 7 倍 → §6 第 2 条直接断句，
        // 断出来的两句都只有 60/30 字，走不到超长兜底，boundaryGuess 自然是 false
        val chars = hanChars(90)
        val starts = (0 until 90).map { if (it < 60) it * 100L else 6_600 + (it - 60) * 100L }
        val tokens = chars.mapIndexed { index, char -> token(char.toString(), starts[index]) }
        val drafts = split(segment(0, 20_000, tokens))

        assertEquals(2, drafts.size)
        assertEquals(chars.subList(0, 60).joinToString("") + "。", drafts[0].text)
        assertEquals(chars.subList(60, 90).joinToString("") + "。", drafts[1].text)
        assertEquals(6_600L, drafts[0].endMs) // 前句收在停顿的末尾，不侵入后句
        assertTrue(drafts.none { it.boundaryGuess })
    }

    @Test
    fun `起点差全都并列时按长度硬切并标注边界是猜的`() {
        val chars = hanChars(70)
        val drafts = split(segment(0, 20_000, speech(chars.joinToString(""), 0)))

        assertEquals(2, drafts.size)
        assertEquals(chars.subList(0, 60).joinToString("") + "。", drafts[0].text)
        assertEquals(chars.subList(60, 70).joinToString("") + "。", drafts[1].text)
        assertTrue("第一句的边界是硬切的，必须如实标记", drafts[0].boundaryGuess)
        assertTrue("第二句只是剩下的尾巴", !drafts[1].boundaryGuess)
    }

    @Test
    fun `一个token就超上限时不切成零长句`() {
        val drafts = SentenceSplitter.split(
            listOf(segment(0, 1_000, listOf(token("这是一整串没有分开很长很长的文本", 0)))),
            maxSentenceChars = 5,
        )

        assertEquals(1, drafts.size)
        assertEquals("这是一整串没有分开很长很长的文本。", drafts.single().text)
        assertTrue(!drafts.single().boundaryGuess)
    }

    @Test
    fun `上一个字压到下一个字头上时以起点为准夹住`() {
        // 甲的 endMs 给了 900（模型给不出这个数，故意造的脏数据），乙起点在 100 → 甲的结束必须变成 100
        val drafts = split(
            segment(0, 2_000, listOf(token("甲", 0, 900), token("乙", 100, 200), token("丙", 900, 1_000))),
        )

        assertEquals(listOf("甲乙。", "丙。"), drafts.texts())
        assertEquals(listOf(0L, 900L), drafts.map { it.startMs })
        assertEquals(listOf(900L, 2_000L), drafts.map { it.endMs })
        assertTrue("前句的结束不许越过下一句的起点", drafts[0].endMs <= drafts[1].startMs)
    }

    @Test
    fun `token乱序时按时间重排文本也跟着走`() {
        val drafts = split(segment(0, 1_000, listOf(token("甲", 300), token("乙", 100), token("丙", 200))))

        assertEquals(listOf("乙丙甲。"), drafts.texts())
        assertEquals(100L, drafts.single().startMs)
    }

    @Test
    fun `零长句并进上一句而不是编一个时长出来`() {
        // 前一段以句号收尾（endsStrong 挡住合并），后一段起止相同 → 文本并进前一句，前一句的时长不变
        val drafts = split(
            segment(0, 1_000, listOf(token("甲", 0), token("乙", 100), token("。", 200))),
            segment(500, 500, listOf(token("尾", 500, 500))),
        )

        assertEquals(1, drafts.size)
        assertEquals("甲乙。尾。", drafts.single().text)
        assertEquals(500L, drafts.single().endMs)
    }

    @Test
    fun `隔着一大段静音的零长字不并给上一句`() {
        // 上一句 31_000 结束，这个字在 60_000 且给不出时长：并过去等于让那句话白占 29 秒，所以丢掉
        val drafts = split(
            segment(30_000, 31_000, speech("前半句", 30_000)),
            segment(60_000, 60_000, listOf(token("零", 60_000, 60_000))),
        )

        assertEquals(listOf("前半句。"), drafts.texts())
        assertEquals(31_000L, drafts.single().endMs)
    }

    @Test
    fun `整篇只有一个零长字就如实丢掉`() {
        val drafts = split(segment(1_000, 1_000, listOf(token("啊", 1_000, 1_000))))

        assertTrue("给不出正时长的句子不许伪装成字幕", drafts.isEmpty())
    }

    @Test
    fun `sentencepiece词界前缀拼出带空格的英文`() {
        // SenseVoice 的英文 token 带 ▁ 词界前缀（D41）：拼句时去前缀、词间补空格
        val drafts = split(
            segment(
                0,
                2_000,
                listOf(token("▁Where", 0), token("have", 500), token("all", 1_000), token("▁the", 1_500), token("flowers", 2_000)),
            ),
        )

        assertEquals(listOf("Where have all the flowers。".replace("。", ".")), drafts.texts())
    }

    @Test
    fun `只剩标点的不成句`() {
        val drafts = split(segment(0, 1_000, listOf(token("。", 0), token("！", 500))))

        assertTrue(drafts.isEmpty())
    }

    @Test
    fun `空白是第三档切点且英文分词之间补空格`() {
        val drafts = SentenceSplitter.split(
            listOf(segment(0, 1_000, listOf(token("hello", 0), token(" ", 100), token("world", 200)))),
            maxSentenceChars = 5,
        )

        // 拉丁文本句尾补「.」（D41 多语种适配）
        assertEquals(listOf("hello.", "world."), drafts.texts())
        assertTrue("空白是模型给的证据，不算硬切", drafts.none { it.boundaryGuess })

        val joined = split(segment(0, 1_000, listOf(token("he", 0), token("llo", 100))))
        assertEquals("he llo.", joined.single().text)
    }

    @Test
    fun `输出永远时间升序每句时长为正且互不重叠`() {
        val chars = hanChars(200)
        val drafts = split(
            segment(0, 30_000, speech(chars.joinToString(""), 0, stepMs = 120)),
            segment(30_500, 31_000, speech("尾巴", 30_500)),
            segment(60_000, 60_000, listOf(token("零", 60_000, 60_000))),
        )

        assertTrue("不该是空的", drafts.isNotEmpty())
        for (index in drafts.indices) {
            assertTrue("第 $index 句时长必须为正", drafts[index].endMs > drafts[index].startMs)
            if (index + 1 < drafts.size) {
                assertTrue("起点必须单调不减", drafts[index + 1].startMs >= drafts[index].startMs)
                assertTrue("句子之间不许重叠", drafts[index].endMs <= drafts[index + 1].startMs)
            }
        }
        assertEquals("200 字按 60 字上限切成 4 句，加「尾巴」共 5 句；那个孤立的零长字被丢掉", 5, drafts.size)
    }

    @Test
    fun `空输入和空段都不产出字幕`() {
        assertTrue(SentenceSplitter.split(emptyList()).isEmpty())
        assertTrue(split(segment(0, 1_000, emptyList())).isEmpty())
    }

    @Test
    fun `字数上限必须为正`() {
        val emptyInput = runCatching { SentenceSplitter.split(emptyList(), maxSentenceChars = 0) }.exceptionOrNull()
        assertTrue(emptyInput is IllegalArgumentException)
        val withTokens = runCatching {
            SentenceSplitter.split(listOf(segment(0, 1_000, speech("那片笑声", 0))), maxSentenceChars = 0)
        }.exceptionOrNull()
        assertTrue(withTokens is IllegalArgumentException)
    }

    @Test
    fun `负值和结束早于开始根本进不来`() {
        // FR-603 的前两种异常在构造 `TokenTiming` 时就被挡住（`TokenTimingMapper` 那层已经抛过一轮），
        // 切句器因此不需要「把负值抬到 0」这种把误差藏起来的操作
        val negative = runCatching { token("啊", -5) }.exceptionOrNull()
        assertTrue(negative is IllegalArgumentException)
        val reversed = runCatching { token("啊", 100, 50) }.exceptionOrNull()
        assertTrue(reversed is IllegalArgumentException)
        val badSegment = runCatching { segment(2_000, 1_000, emptyList()) }.exceptionOrNull()
        assertTrue(badSegment is IllegalArgumentException)
    }

    private companion object {
        /** 测试用的中文字面：不重复，好让「文本顺序跟着时间走」这类断言真的能看出来。 */
        val CN = "一二三四五六七八九十天地方左右".toCharArray()
    }
}
