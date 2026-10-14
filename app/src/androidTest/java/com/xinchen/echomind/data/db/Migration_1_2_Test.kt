package com.xinchen.echomind.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v1 → v2 迁移（AGENTS.md §4：表结构变更 = 显式 Migration + 提交 schema JSON + MigrationTestHelper）。
 *
 * 用户设备上的 v1 库里装着真实书架和收听历史，所以「迁移通过」必须是三件事同时成立：
 * 1. `runMigrationsAndValidate` 把迁移结果与 `app/schemas/…2.json` 逐字段对账 —— Migration 里的 DDL 是手写的，
 *    与实体注解漂移就是 bug，这条是唯一能发现漂移的机制；
 * 2. 已有的 folder / audio_book / listening_session 行一条不少（NFR-04：历史要活过一切）；
 * 3. 迁移后的文件能被 Room 当 v2 打开 —— 打开时报 identityHash 不匹配就说明迁移没建出 Room 期望的结构。
 */
@RunWith(AndroidJUnit4::class)
class Migration_1_2_Test {

    private val dbName = "migrate_v1_to_v2.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        EchoMindDatabase::class.java,
    )

    /**
     * `recreateTableOnMigrationError` 传 **false**：Room 允许它在失败时重建表来"修好"迁移，
     * 那等于把 destructive migration 请回来（AGENTS.md §4）。纯增量迁移不该需要它，需要就是错。
     */
    @Test
    fun v1升到v2后字幕表结构符合导出schema() {
        val created = helper.createDatabase(dbName, 1)
        writeV1Fixture(created)
        created.close()

        helper.runMigrationsAndValidate(dbName, 2, false, Migrations.MIGRATION_1_2).close()
    }

    @Test
    fun 迁移保留已有书籍与收听历史() {
        val created = helper.createDatabase(dbName, 1)
        writeV1Fixture(created)
        created.close()
        helper.runMigrationsAndValidate(dbName, 2, false, Migrations.MIGRATION_1_2).close()

        val db = openMigratedDb()
        try {
            runBlocking {
                assertEquals(1, db.audioBookDao().count())
                val book = db.audioBookDao().getById("b1")
                assertNotNull(book)
                assertEquals("第一首", book!!.title)

                // 统计真源那张表一个字节都不能动
                val settled = db.listeningSessionDao().settledOf("b1")
                assertEquals(1, settled.size)
                assertEquals(30_000L, settled[0].listenedMs)
                assertEquals(ListeningSessionEntity.Status.FINISHED, settled[0].sessionStatus)
                assertEquals(0, db.listeningSessionDao().countOpen("b1"))

                // 新表是空的、可用的
                assertEquals(0, db.subtitleSegmentDao().getByAudio("b1").size)
                db.subtitleSegmentDao().insertAll(
                    listOf(
                        SubtitleSegmentEntity(
                            audioId = "b1",
                            segmentIndex = 0,
                            startMs = 0L,
                            endMs = 1_200L,
                            text = "迁移后写入的第一句",
                            source = SubtitleSegmentEntity.Source.MANUAL_TEST,
                            createdAtMs = 5_000L,
                        ),
                    ),
                )
                assertEquals(1, db.subtitleSegmentDao().getByAudio("b1").size)
            }
        } finally {
            db.close()
            ApplicationProvider.getApplicationContext<Context>().deleteDatabase(dbName)
        }
    }

    /** 外键声明也要跟着建出来，否则「删书连带删字幕」（NFR-04）在真机上不成立 */
    @Test
    fun 字幕表带着指向audio_book的外键() {
        helper.createDatabase(dbName, 1).close()
        val db = helper.runMigrationsAndValidate(dbName, 2, false, Migrations.MIGRATION_1_2)
        try {
            db.query("PRAGMA foreign_key_list(`subtitle_segment`)").use { c ->
                val tables = generateSequence { if (c.moveToNext()) c else null }
                    .map { it.getString(it.getColumnIndexOrThrow("table")) }
                    .toList()
                assertTrue("外键应指向 audio_book，实际=$tables", tables.contains("audio_book"))
            }
            db.query(
                "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='subtitle_segment'",
            ).use { c ->
                val indexes = generateSequence { if (c.moveToNext()) c else null }
                    .map { it.getString(it.getColumnIndexOrThrow("name")) }
                    .toSet()
                assertTrue(
                    "索引缺失，实际=$indexes",
                    indexes.containsAll(
                        setOf(
                            "index_subtitle_segment_audio_id_segment_index",
                            "index_subtitle_segment_audio_id_start_ms",
                        ),
                    ),
                )
            }
        } finally {
            db.close()
            ApplicationProvider.getApplicationContext<Context>().deleteDatabase(dbName)
        }
    }

    /** 用 Room 打开迁移后的文件：这一步失败说明迁移出的结构与实体不一致 */
    private fun openMigratedDb(): EchoMindDatabase =
        Room.databaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            EchoMindDatabase::class.java,
            dbName,
        )
            .addMigrations(*Migrations.ALL)
            .build()

    private fun writeV1Fixture(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO imported_folder (id, tree_uri, display_name, granted_at_ms, last_scan_at_ms, permission_state, is_hidden)" +
                " VALUES (?,?,?,?,?,?,?)",
            arrayOf<Any>(
                "f1",
                "content://com.android.externalstorage.documents/tree/primary%3AMusic",
                "测试文件夹",
                1_000L,
                2_000L,
                ImportedFolderEntity.PermissionState.Granted,
                0,
            ),
        )
        db.execSQL(
            "INSERT INTO audio_book (id, uri, folder_id, relative_path, file_name, title, title_is_manual," +
                " size_bytes, file_last_modified_ms, duration_ms, duration_read_failed, is_favorite, file_status," +
                " transcription_state, added_at_ms, last_scanned_at_ms, updated_at_ms) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any>(
                "b1", "content://x/tree/primary%3AMusic/1.mp3", "f1", "1.mp3", "1.mp3", "第一首",
                0, 10_000L, 3_000L, 60_000L, 0, 0,
                AudioBookEntity.FileStatus.Present, AudioBookEntity.TranscriptionState.None,
                3_000L, 3_000L, 3_000L,
            ),
        )
        db.execSQL(
            "INSERT INTO listening_session (audio_id, audio_title_snapshot, duration_ms_snapshot, started_at_ms," +
                " ended_at_ms, listened_ms, consumed_audio_ms, start_position_ms, end_position_ms," +
                " max_reached_position_ms, completed, is_counted, session_status, created_at_ms, updated_at_ms)" +
                " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any>(
                "b1", "第一首", 60_000L, 10_000L, 40_000L, 30_000L, 28_000L, 0L, 28_000L, 28_000L,
                1, 1, ListeningSessionEntity.Status.FINISHED, 10_000L, 40_000L,
            ),
        )
    }
}
