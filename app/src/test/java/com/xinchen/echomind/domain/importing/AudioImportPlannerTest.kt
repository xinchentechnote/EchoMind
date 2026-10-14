package com.xinchen.echomind.domain.importing

import com.xinchen.echomind.domain.model.ExistingAudio
import com.xinchen.echomind.domain.model.ScannedAudioFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioImportPlannerTest {

    private fun scanned(uri: String, size: Long = 1000, mtime: Long = 100) =
        ScannedAudioFile(uri = uri, relativePath = uri.substringAfterLast('/'), fileName = uri.substringAfterLast('/'), sizeBytes = size, lastModifiedMs = mtime)

    private fun existing(uri: String, size: Long = 1000, mtime: Long = 100, manual: Boolean = false, missing: Boolean = false) =
        ExistingAudio(id = "id-of-$uri", uri = uri, sizeBytes = size, lastModifiedMs = mtime, titleIsManual = manual, fileMissing = missing)

    @Test
    fun `首次导入把全部文件视为新增`() {
        val plan = AudioImportPlanner.plan(emptyList(), listOf(scanned("a"), scanned("b"), scanned("c")))
        assertEquals(3, plan.newFiles.size)
        assertEquals(0, plan.changed.size)
        assertEquals(0, plan.vanishedIds.size)
    }

    /** FR-106：重复扫描不能产生新记录 */
    @Test
    fun `重复扫描不产生新增记录`() {
        val files = listOf(scanned("a"), scanned("b"))
        val first = AudioImportPlanner.plan(emptyList(), files)
        val asRows = first.newFiles.map { existing(it.uri, it.sizeBytes, it.lastModifiedMs) }
        val second = AudioImportPlanner.plan(asRows, files)
        assertEquals(0, second.newFiles.size)
        assertEquals(0, second.changed.size)
        assertEquals(2, second.unchangedCount)
    }

    @Test
    fun `同一 URI 重复出现只算一次`() {
        val plan = AudioImportPlanner.plan(emptyList(), listOf(scanned("a"), scanned("a"), scanned("a")))
        assertEquals(1, plan.newFiles.size)
    }

    @Test
    fun `大小变化被识别为变更`() {
        val plan = AudioImportPlanner.plan(listOf(existing("a", size = 1000)), listOf(scanned("a", size = 2048)))
        assertEquals(1, plan.changed.size)
        assertTrue(plan.changed.single().sizeChanged)
        assertEquals(0, plan.newFiles.size)
    }

    @Test
    fun `修改时间变化被识别为变更`() {
        val plan = AudioImportPlanner.plan(listOf(existing("a", mtime = 100)), listOf(scanned("a", mtime = 999)))
        assertEquals(1, plan.changed.size)
        assertTrue(plan.changed.single().modifiedChanged)
    }

    @Test
    fun `库里有但树里没有的标记为消失`() {
        val plan = AudioImportPlanner.plan(listOf(existing("a"), existing("gone")), listOf(scanned("a")))
        assertEquals(listOf("id-of-gone"), plan.vanishedIds)
    }

    @Test
    fun `空扫描把全部记录标记为消失而不是新增`() {
        val plan = AudioImportPlanner.plan(listOf(existing("a"), existing("b")), emptyList())
        assertEquals(0, plan.newFiles.size)
        assertEquals(2, plan.vanishedIds.size)
    }

    /** 设备实测出的 bug：文件移走又移回来（size/mtime 都没变）时书架永远灰着 */
    @Test
    fun `MISSING 的文件原样回来只标记为复活不新增不变更`() {
        val plan = AudioImportPlanner.plan(
            listOf(existing("a", missing = true), existing("b")),
            listOf(scanned("a"), scanned("b")),
        )
        assertEquals(listOf("id-of-a"), plan.revivedIds)
        assertEquals(0, plan.newFiles.size)
        assertEquals(0, plan.changed.size)
        assertEquals(1, plan.unchangedCount)
    }

    @Test
    fun `MISSING 的文件回来且内容变了算变更而不是复活`() {
        val plan = AudioImportPlanner.plan(
            listOf(existing("a", size = 1000, missing = true)),
            listOf(scanned("a", size = 2048)),
        )
        assertEquals(0, plan.revivedIds.size)
        assertEquals(1, plan.changed.size)
    }
}
