package com.xinchen.echomind.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * 表结构与 Migration 的唯一入口。
 *
 * 首个对外安装版本发布后，任何表结构变更都必须写显式 Migration（不做 destructive migration，AGENTS.md §4），
 * 并把导出的 JSON 提交进 app/schemas/ 供仪器化迁移测试校验（docs/database-design.md §11）。
 *
 * v1 = imported_folder / audio_book / playback_state / listening_session（Phase 2~4）。
 * v2 = + subtitle_segment（Phase 5，`Migrations.MIGRATION_1_2`）。
 * v3 = + transcript_job，并给 `subtitle_segment.job_id` 补上外键（整表重建，`Migrations.MIGRATION_2_3`）。
 */
@Database(
    entities = [
        ImportedFolderEntity::class,
        AudioBookEntity::class,
        PlaybackStateEntity::class,
        ListeningSessionEntity::class,
        SubtitleSegmentEntity::class,
        TranscriptJobEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class EchoMindDatabase : RoomDatabase() {
    abstract fun audioBookDao(): AudioBookDao
    abstract fun importedFolderDao(): ImportedFolderDao
    abstract fun playbackStateDao(): PlaybackStateDao
    abstract fun listeningSessionDao(): ListeningSessionDao
    abstract fun subtitleSegmentDao(): SubtitleSegmentDao
    abstract fun transcriptJobDao(): TranscriptJobDao
    abstract fun shelfDao(): ShelfDao

    companion object {
        const val NAME = "echomind.db"
    }
}
