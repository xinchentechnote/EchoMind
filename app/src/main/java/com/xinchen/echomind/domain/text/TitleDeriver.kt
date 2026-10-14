package com.xinchen.echomind.domain.text

/**
 * 从文件名推导默认标题（FR-105）。用户手改过的标题不再被扫描覆盖。
 *
 * 处理：去扩展名 → 去前缀集数编号（`03_`、`01 - `、`第12集`、`[12]`）→ 下划线转空格 →
 * 压缩空白 → 截断超长。全数字文件名（`345.mp3`）保留数字，不猜内容。
 */
object TitleDeriver {

    private const val MAX_TITLE_CHARS = 120

    private val extensionPattern = Regex("\\.[A-Za-z0-9]{1,5}$")

    private val leadingNumberPatterns = listOf(
        Regex("^\\s*(?:第\\s*[0-9零一二三四五六七八九十百千]+\\s*[集讲章节回篇]\\s*[-_.、:：]?\\s*)"),
        Regex("^\\s*\\[[0-9]{1,4}\\]\\s*[-_.:：]?\\s*"),
        Regex("^\\s*[0-9]{1,4}\\s*(?:[-–—.．]|\\))\\s+"),
        Regex("^\\s*[0-9]{1,4}\\s*_+\\s*"),
    )

    fun defaultTitle(fileName: String): String {
        var s = extensionPattern.replace(fileName, "")
        for (pattern in leadingNumberPatterns) {
            val match = pattern.find(s) ?: continue
            s = s.removeRange(match.range)
            break
        }
        s = s.replace('_', ' ').replace(Regex("\\s+"), " ").trim()
        if (s.isBlank()) s = fileName
        return if (s.length > MAX_TITLE_CHARS) s.substring(0, MAX_TITLE_CHARS).trim() else s
    }
}
