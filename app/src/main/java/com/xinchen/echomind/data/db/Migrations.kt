package com.xinchen.echomind.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 显式 Migration 全部集中在这里，一条一个 val，顺序与 `app/schemas` 目录下导出的 JSON 对应。
 *
 * 为什么手写 DDL 而不用 Room 的自动迁移：自动迁移会重建整表，而 SQLite 加外键必须走
 * 「建新表→拷数据→删旧表→改名→重建索引」，静默重写用户全部收听历史的风险不值得省这几行
 * （AGENTS.md §4：正式版禁止 destructive migration）。DDL 必须与 Room 由实体生成的
 * `CREATE TABLE` 逐字一致，一致性由 `Migration_1_2Test` 用导出的 schema JSON 校验。
 */
object Migrations {

    /**
     * v1 → v2：新增 `subtitle_segment`。只建表 + 建索引，不碰任何已有表，
     * 所以不需要回填 SQL（统计口径没变，docs/playback-design.md §7）。
     */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `subtitle_segment` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`audio_id` TEXT NOT NULL, " +
                    "`segment_index` INTEGER NOT NULL, " +
                    "`start_ms` INTEGER NOT NULL, " +
                    "`end_ms` INTEGER NOT NULL, " +
                    "`text` TEXT NOT NULL, " +
                    "`source` TEXT NOT NULL, " +
                    "`job_id` TEXT, " +
                    "`created_at_ms` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`audio_id`) REFERENCES `audio_book`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE" +
                    ")",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_subtitle_segment_audio_id_segment_index` " +
                    "ON `subtitle_segment` (`audio_id`, `segment_index`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_subtitle_segment_audio_id_start_ms` " +
                    "ON `subtitle_segment` (`audio_id`, `start_ms`)",
            )
        }
    }

    /** 装配进 `Room.databaseBuilder(...).addMigrations(...)`；顺序无关，Room 按 startVersion/endVersion 排路径 */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
