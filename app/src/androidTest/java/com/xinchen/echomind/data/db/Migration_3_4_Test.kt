package com.xinchen.echomind.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xinchen.echomind.core.conf.AudioFormats
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v3 → v4 迁移（AGENTS.md §4）：`transcript_job` 加三列进度字段。
 *
 * 纯 `ADD COLUMN`，但仍然要三件事同时成立：
 * 1. `runMigrationsAndValidate` 与 `app/schemas/…4.json` 逐字段对账——尤其是 `DEFAULT 0` 与实体上
 *    `@ColumnInfo(defaultValue = "0")` 必须两边一致，少一边 Room 就判「schema 不同」；
 * 2. 已有的任务行、字幕行一条不少、内容不变，老行的新列是默认值（phase null、位置/时长 0）；
 * 3. 迁移后的库能被 Room 当 v4 打开，新的进度 DAO 真能写进去、读出来。
 */
@RunWith(AndroidJUnit4::class)
class Migration_3_4_Test {

    private val dbName = "migrate_v3_to_v4.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        EchoMindDatabase::class.java,
    )

    @Test
    fun v3升到v4后任务表结构符合导出schema() {
        helper.createDatabase(dbName, 3).use { created -> writeV3Fixture(created) }

        helper.runMigrationsAndValidate(dbName, 4, false, Migrations.MIGRATION_3_4).close()
    }

    @Test
    fun 迁移保留任务与字幕且老行的新列是默认值() {
        helper.createDatabase(dbName, 3).use { created -> writeV3Fixture(created) }
        helper.runMigrationsAndValidate(dbName, 4, false, Migrations.MIGRATION_3_4).close()

        val db = openMigratedDb()
        try {
            runBlocking {
                val job = db.transcriptJobDao().getById("j1")!!
                assertEquals("b1", job.audioId)
                assertEquals(TranscriptJobEntity.Status.Success, job.status)
                assertEquals(3, job.totalSegments)
                assertEquals(3, job.doneSegments)
                assertEquals("f36a0433bcf0", job.modelVersion)
                assertNull(job.phase)
                assertEquals(0L, job.positionMs)
                assertEquals(0L, job.durationMs)

                val segments = db.subtitleSegmentDao().getByAudio("b1")
                assertEquals(listOf("旧句一", "旧句二", "旧句三"), segments.map { it.text })
                assertEquals(listOf("j1", "j1", "j1"), segments.map { it.jobId })
                assertEquals(1, db.audioBookDao().count())
            }
        } finally {
            db.close()
            deleteTestDb()
        }
    }

    /** 新列不只是摆设：时间口径的进度要能写进 RUNNING 行并被应用层读成百分比 */
    @Test
    fun 迁移后新的进度字段可写可读() {
        helper.createDatabase(dbName, 3).use { created -> writeV3Fixture(created) }
        helper.runMigrationsAndValidate(dbName, 4, false, Migrations.MIGRATION_3_4).close()

        val db = openMigratedDb()
        try {
            runBlocking {
                val dao = db.transcriptJobDao()
                dao.insert(
                    TranscriptJobEntity(
                        id = "j2",
                        audioId = "b1",
                        modelId = "paraformer-zh-2023-09-14-int8",
                        modelVersion = "f36a0433bcf0",
                        createdAtMs = 10_000L,
                    ),
                )
                assertEquals(1, dao.markRunning("j2", startedAtMs = 10_001L, totalSegments = 0, maxConcurrent = AudioFormats.ASR_MAX_CONCURRENT_JOBS))
                assertEquals(
                    1,
                    dao.updateProgress("j2", doneSegments = 4, phase = TranscriptJobEntity.Phase.Transcoding, positionMs = 30_000L, durationMs = 120_000L),
                )
                val row = dao.getById("j2")!!
                assertEquals(TranscriptJobEntity.Phase.Transcoding, row.phase)
                assertEquals(30_000L, row.positionMs)
                assertEquals(120_000L, row.durationMs)
                assertEquals(4, row.doneSegments)
                // 老的 SUCCESS 行不受影响
                assertNull(dao.getById("j1")!!.phase)
            }
        } finally {
            db.close()
            deleteTestDb()
        }
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

    private fun writeV3Fixture(db: SupportSQLiteDatabase) {
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
                AudioBookEntity.FileStatus.Present, AudioBookEntity.TranscriptionState.Ready,
                3_000L, 3_000L, 3_000L,
            ),
        )
        db.execSQL(
            "INSERT INTO transcript_job (id, audio_id, model_id, model_version, status, total_segments, done_segments," +
                " created_at_ms, started_at_ms, finished_at_ms) VALUES (?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any>(
                "j1", "b1", "paraformer-zh-2023-09-14-int8", "f36a0433bcf0", "SUCCESS", 3, 3, 9_000L, 9_001L, 9_500L,
            ),
        )
        listOf("旧句一" to 0L, "旧句二" to 1_200L, "旧句三" to 2_400L).forEachIndexed { index, (text, startMs) ->
            db.execSQL(
                "INSERT INTO subtitle_segment (id, audio_id, segment_index, start_ms, end_ms, text, source, job_id, created_at_ms)" +
                    " VALUES (?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(
                    (index + 1).toLong(), "b1", index, startMs, startMs + 1_000L, text,
                    SubtitleSegmentEntity.Source.ASR, "j1", 9_500L,
                ),
            )
        }
    }
}
