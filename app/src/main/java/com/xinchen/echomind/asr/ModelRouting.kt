package com.xinchen.echomind.asr

/**
 * 「自动选模型」的路由（D41，纯 Kotlin 可 JVM 测）。
 *
 * 判据是**探针转写文本的字符系别多数票**，不是模型自报的语言标签——实测 SenseVoice 的语言标签不可靠：
 * 中文书解、英文 TTS 全被它判成 `<|yue|>`（D41 打样记录），拿它路由会把英文书错送进 Paraformer。
 * 而 CJK 与拉丁字符的多少是铁证。
 *
 * 规则（2026-10-16 二次修正）：
 * - 探针转写里 CJK 字符数 ≥ 拉丁字符数 → 中文内容 → Paraformer（书解主场景最优解）；
 * - 拉丁占多数 → SenseVoice（英文可读、有时间戳）；
 * - 空转写/几乎没字（纯器乐前奏，典型如英文歌开头 15 s 钢琴）→ SenseVoice 兜底：
 *   多语种中英都能解（中文质量实测极好），而 Paraformer 对非中文是乱码——设备实测教训；
 * - 探测本身失败（解码异常，null）→ Paraformer：基础设施故障时回落主场景历史行为。
 *
 * 简化声明：一个文件全程一个模型——英文书里夹中文段不会触发重路由（D41）。
 */
object ModelRouting {

    fun hasCJK(transcript: String?): Boolean =
        transcript?.any { (it.code in 0x4E00..0x9FFF) || (it.code in 0x3400..0x4DBF) } == true

    fun resolve(transcript: String?): ModelSpec {
        // null = 探测本身失败（解码异常等基础设施问题）：回落主场景的历史行为
        if (transcript == null) return ModelCatalog.paraformer
        val latin = transcript.count { it.isLetterOrDigit() && it.code < 0x2E80 }
        val cjk = transcript.count { (it.code in 0x4E00..0x9FFF) || (it.code in 0x3400..0x4DBF) }
        // 汉字数 ≥ 拉丁数（且确实有汉字）→ 中文内容；否则（拉丁占多/空转写）→ 多语种兜底
        return if (cjk > 0 && cjk >= latin) ModelCatalog.paraformer else ModelCatalog.senseVoice
    }
}
