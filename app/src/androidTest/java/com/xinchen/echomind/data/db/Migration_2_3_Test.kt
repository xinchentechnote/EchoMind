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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v2 → v3 迁移（AGENTS.md §4）。这一条比 v1→v2 危险：`subtitle_segment` 要**整表重建**才能加上
 * 指向 `transcript_job` 的外键（SQLite 没有 ALTER TABLE ADD CONSTRAINT），
 * 而那张表里装着用户已经跑出来的字幕。所以「迁移通过」同样得是三件事同时成立：
 * 1. `runMigrationsAndValidate` 与 `app/schemas/…3.json` 逐字段对账（手写 DDL 与实体漂移唯一的发现机制）；
 * 2. 已有的 folder / audio_book / listening_session / **subtitle_segment** 行一条不少、内容不变；
 * 3. 迁移后的文件能被 Room 当 v3 打开，且外键真的生效（级联删与 SET NULL 都得实测，光看 DDL 说不出）。
 */
@RunWith(AndroidJUnit4::class)
class Migration_2_3_Test {

    private val dbName = "migrate_v2_to_v3.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        EchoMindDatabase::class.java,
    )

    /** 同 v1→v2：`recreateTableOnMigrationError=false`，纯增量迁移不该靠 Room 重建表来「修好」它 */
    @Test
    fun v2升到v3后任务表结构符合导出schema() {
        helper.createDatabase(dbName, 2).use { created -> writeV2Fixture(created) }

        helper.runMigrationsAndValidate(dbName, 3, false, Migrations.MIGRATION_2_3).close()
    }

    /** 字幕是重建表搬过去的，搬丢一句用户就发现书还在、字幕没了。所以逐句比对，不只数条数。 */
    @Test
    fun 迁移保留字幕内容与已有收听历史() {
        helper.createDatabase(dbName, 2).use { created -> writeV2Fixture(created) }
        helper.runMigrationsAndValidate(dbName, 3, false, Migrations.MIGRATION_2_3).close()

        val db = openMigratedDb()
        try {
            runBlocking {
                assertEquals(1, db.audioBookDao().count())
                val book = db.audioBookDao().getById("b1")
                assertNotNull(book)
                assertEquals("第一首", book!!.title)

                val settled = db.listeningSessionDao().settledOf("b1")
                assertEquals(1, settled.size)
                assertEquals(30_000L, settled[0].listenedMs)

                val segments = db.subtitleSegmentDao().getByAudio("b1")
                assertEquals(listOf(0, 1, 2), segments.map { it.segmentIndex })
                assertEquals(listOf("旧句一", "旧句二", "旧句三"), segments.map { it.text })
                assertEquals(listOf(0L, 1_200L, 2_400L), segments.map { it.startMs })
                assertTrue(segments.all { it.source == SubtitleSegmentEntity.Source.MANUAL_TEST })
                // v2 没有任务表，job_id 只能全是 null；重建表要把它保住而不是塞个假 id
                assertTrue(segments.all { it.jobId == null })
                // 主键也保住了：AUTOINCREMENT 的 id 被 job_id 引用过，重排就是改历史
                assertEquals(listOf(1L, 2L, 3L), segments.map { it.id })
            }
        } finally {
            db.close()
            deleteTestDb()
        }
    }

    /** 外键声明要真能执行：删书连带删字幕和任务，这是 NFR-04 的行为，不是 DDL 里的装饰 */
    @Test
    fun 迁移后删书连带删字幕与任务() {
        helper.createDatabase(dbName, 2).use { created -> writeV2Fixture(created) }
        val migrated = helper.runMigrationsAndValidate(dbName, 3, false, Migrations.MIGRATION_2_3)
        migrated.execSQL(
            "INSERT INTO transcript_job (id, audio_id, model_id, model_version, status, total_segments, done_segments, created_at_ms)" +
                " VALUES (?,?,?,?,?,?,?,?)",
            arrayOf<Any>(
                "j1", "b1", "paraformer-zh-2023-09-14-int8", "f36a0433bcf0", "SUCCESS", 3, 3, 9_000L,
            ),
        )
        migrated.execSQL("UPDATE subtitle_segment SET job_id = 'j1', source = 'ASR' WHERE audio_id = 'b1'")
        migrated.close()

        val db = openMigratedDb()
        try {
            runBlocking {
                assertEquals(3, db.subtitleSegmentDao().getByAudio("b1").size)
                db.audioBookDao().delete("b1")

                assertEquals(0, db.subtitleSegmentDao().getByAudio("b1").size)
                assertNull(db.transcriptJobDao().getById("j1"))
                // 唯独收听历史不动：统计要活过书籍删除（NFR-04）
                assertEquals(1, db.listeningSessionDao().settledOf("b1").size)
            }
        } finally {
            db.close()
            deleteTestDb()
        }
    }

    /**
     * `job_id` 的外键是 SET NULL：删掉任务行不该把字幕一起删。
     * 这一条只有在迁移后的库上才测得出来——v2 那张表没有这个约束，Room 新建的库也不替迁移背书。
     */
    @Test
    fun 删任务行只把字幕的job_id置空不删字幕() {
        helper.createDatabase(dbName, 2).use { created -> writeV2Fixture(created) }
        helper.runMigrationsAndValidate(dbName, 3, false, Migrations.MIGRATION_2_3).close()

        val db = openMigratedDb()
        try {
            runBlocking {
                db.transcriptJobDao().insert(
                    TranscriptJobEntity(
                        id = "j-keep",
                        audioId = "b1",
                        modelId = "paraformer-zh-2023-09-14-int8",
                        modelVersion = "f36a0433bcf0",
                        createdAtMs = 9_000L,
                    ),
                )
                db.subtitleSegmentDao().deleteByAudio("b1")
                db.subtitleSegmentDao().insertAll(
                    listOf(
                        SubtitleSegmentEntity(
                            audioId = "b1",
                            segmentIndex = 0,
                            startMs = 0L,
                            endMs = 1_000L,
                            text = "跟着任务走的一句",
                            source = SubtitleSegmentEntity.Source.ASR,
                            jobId = "j-keep",
                            createdAtMs = 9_000L,
                        ),
                    ),
                )

                db.openHelper.writableDatabase.execSQL("DELETE FROM transcript_job WHERE id = 'j-keep'")

                val left = db.subtitleSegmentDao().getByAudio("b1")
                assertEquals(1, left.size)
                assertEquals("跟着任务走的一句", left[0].text)
                assertNull(left[0].jobId)
            }
        } finally {
            db.close()
            deleteTestDb()
        }
    }

    /** 重建表最容易漏的就是索引：DROP 之后不重建，读取路径会静默退化成全表扫 */
    @Test
    fun 迁移把两张表的索引都建回来() {
        helper.createDatabase(dbName, 2).close()
        val db = helper.runMigrationsAndValidate(dbName, 3, false, Migrations.MIGRATION_2_3)
        try {
            assertEquals(
                setOf(
                    "index_subtitle_segment_audio_id_segment_index",
                    "index_subtitle_segment_audio_id_start_ms",
                    "index_subtitle_segment_job_id",
                ),
                explicitIndicesOf(db, "subtitle_segment"),
            )
            assertEquals(
                setOf(
                    "index_transcript_job_audio_id",
                    "index_transcript_job_status",
                    "index_transcript_job_audio_id_created_at_ms",
                ),
                explicitIndicesOf(db, "transcript_job"),
            )
            db.query("PRAGMA foreign_key_list(`subtitle_segment`)").use { c ->
                val tables = generateSequence { if (c.moveToNext()) c else null }
                    .map { it.getString(it.getColumnIndexOrThrow("table")) }
                    .toSet()
                assertTrue("应有两条外键，实际=$tables", tables.containsAll(setOf("audio_book", "transcript_job")))
            }
        } finally {
            db.close()
            deleteTestDb()
        }
    }

    /** SQLite 给 TEXT 主键自动建的 `sqlite_autoindex_*` 不算 Room 的索引，剔掉才能做集合等值断言 */
    private fun explicitIndicesOf(db: SupportSQLiteDatabase, table: String): Set<String> =
        db.query("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name=?", arrayOf(table)).use { c ->
            generateSequence { if (c.moveToNext()) c else null }
                .map { it.getString(it.getColumnIndexOrThrow("name")) }
                .filterNot { it.startsWith("sqlite_autoindex_") }
                .toSet()
        }

    private fun openMigratedDb(): EchoMindDatabase =
        Room.databaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            EchoMindDatabase::class.java,
            dbName,
        )
            .addMigrations(*Migrations.ALL)
            .build()

    private fun deleteTestDb() {
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(dbName)
    }

    private fun writeV2Fixture(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO imported_folder (id, tree_uri, display_name, granted_at_ms, last_scan_at_ms, permission_state, is_hidden)" +
                " VALUES (?,?,?,?,?,?,?)",
            arrayOf<Any>(
                "f1",
                "content://com.android.externalstorage.documents/tree/primary%3AMusic",
                "测试文件夹",
                1_000L, 2_000L,
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
        // 三句 v2 时代的字幕：重建表要一句不少地搬过去
        listOf("旧句一" to 0L, "旧句二" to 1_200L, "旧句三" to 2_400L).forEachIndexed { index, (text, startMs) ->
            db.execSQL(
                "INSERT INTO subtitle_segment (id, audio_id, segment_index, start_ms, end_ms, text, source, job_id, created_at_ms)" +
                    " VALUES (?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(
                    (index + 1).toLong(), "b1", index, startMs, startMs + 1_000L, text,
                    SubtitleSegmentEntity.Source.MANUAL_TEST, null, 5_000L,
                ),
            )
        }
    }
}
