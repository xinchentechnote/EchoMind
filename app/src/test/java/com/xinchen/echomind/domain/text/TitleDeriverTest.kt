package com.xinchen.echomind.domain.text

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * FR-105 的默认标题推导。这里把「什么算前缀」固化下来，避免后面改正则时悄悄改变书架展示。
 */
class TitleDeriverTest {

    @Test
    fun `去掉扩展名与中文集数前缀`() {
        assertEquals("三体", TitleDeriver.defaultTitle("第03集 三体.mp3"))
        assertEquals("回声", TitleDeriver.defaultTitle("[12] 回声.mp3"))
        assertEquals("讲稿", TitleDeriver.defaultTitle("第二章讲稿.mp3"))
    }

    @Test
    fun `去掉数字编号前缀的几种写法`() {
        assertEquals("第一章", TitleDeriver.defaultTitle("01 - 第一章.mp3"))
        assertEquals("音频", TitleDeriver.defaultTitle("03_音频.mp3"))
        assertEquals("尾声", TitleDeriver.defaultTitle("09) 尾声.MP3"))
    }

    @Test
    fun `下划线转空格并压缩空白`() {
        assertEquals("平凡 的世界", TitleDeriver.defaultTitle("平凡_的世界.mp3"))
        assertEquals("人 间 草木", TitleDeriver.defaultTitle("人  间   草木.mp3"))
    }

    @Test
    fun `纯数字文件名保留数字不猜内容`() {
        assertEquals("345", TitleDeriver.defaultTitle("345.mp3"))
    }

    @Test
    fun `没有扩展名时直接用原名`() {
        assertEquals("无名音频", TitleDeriver.defaultTitle("无名音频"))
    }

    @Test
    fun `超长标题截断到上限`() {
        val title = TitleDeriver.defaultTitle("字".repeat(200) + ".mp3")
        assertEquals(120, title.length)
    }

    /** 任何输入都不能退化成空串——空标题会让书架无法辨识条目 */
    @Test
    fun `极端文件名回退到原始文件名而不是空串`() {
        assertEquals(".mp3", TitleDeriver.defaultTitle(".mp3"))
        assertEquals("01_.mp3", TitleDeriver.defaultTitle("01_.mp3"))
    }
}
