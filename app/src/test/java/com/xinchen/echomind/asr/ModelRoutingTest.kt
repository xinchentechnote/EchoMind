package com.xinchen.echomind.asr

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 转写文本字符系别多数票 → 模型路由（D41，规则经两轮设备实测修正）：
 * CJK 占多数 → Paraformer；拉丁占多数或空转写（歌曲前奏无人声）→ SenseVoice；
 * 探测异常（null）→ 保守 Paraformer。
 */
class ModelRoutingTest {

    @Test
    fun `探针转写里汉字占多数路由到paraformer`() {
        assertEquals(ModelCatalog.paraformer, ModelRouting.resolve("听懂好书生活更好本期解读由翻书出品"))
        // 中英混排：汉字占多数算中文内容（typical: 中文书解里夹英文专有名词）
        assertEquals(ModelCatalog.paraformer, ModelRouting.resolve("欢迎收听听书生活更好 welcome"))
    }

    @Test
    fun `拉丁占多数路由到sensevoice`() {
        assertEquals(
            ModelCatalog.senseVoice,
            ModelRouting.resolve("CHAPTER ONE THE OLD LIGHTHOUSE STOOD AT THE EDGE OF THE HARBOUR"),
        )
        assertEquals(ModelCatalog.senseVoice, ModelRouting.resolve("hello world 42"))
    }

    @Test
    fun `空转写纯器乐前奏路由到sensevoice兜底`() {
        // 英文歌/有前奏的歌：开头 30 s 可能解不出文字，Paraformer 对这类内容是乱码（用户实测）
        assertEquals(ModelCatalog.senseVoice, ModelRouting.resolve(""))
        assertEquals(ModelCatalog.senseVoice, ModelRouting.resolve("   "))
    }

    @Test
    fun `少量幻觉汉字压不过拉丁正文`() {
        // 器乐前奏的幻觉识别（实测「我不道」级别的一两个字）不该劫持英文歌的路由
        assertEquals(
            ModelCatalog.senseVoice,
            ModelRouting.resolve("我不道 WE WERE BOTH YOUNG WHEN I FIRST SAW YOU"),
        )
    }

    @Test
    fun `探测异常null保守落paraformer`() {
        assertEquals(ModelCatalog.paraformer, ModelRouting.resolve(null))
    }
}
