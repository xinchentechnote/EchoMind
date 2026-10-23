package com.xinchen.echomind.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 显式 Migration 全部集中在这里，一条一个 val，顺序与 `app/schemas` 目录下导出的 JSON 对应。
 *
 * 为什么手写 DDL 而不用 Room 的自动迁移：自动迁移会重建整表，而 SQLite 加外键必须走
 * 「建新表→拷数据→删旧表→改名→重建索引」，静默重写用户全部收听历史的风险不值得省这几行
 * （AGENTS.md §4：正式版禁止 destructive migration）。DDL 必须与 Room 由实体生成的
 * `CREATE TABLE` 逐字一致，一致性由 `Migration_1_2_Test` / `Migration_2_3_Test` 用导出的 schema JSON 校验。
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

    /**
     * v2 → v3：建 `transcript_job`，并把 `subtitle_segment` 整表重建以补上 `job_id` 的外键。
     *
     * 为什么要重建：SQLite 不支持 `ALTER TABLE ... ADD CONSTRAINT`，加 FK 只有
     * 「建新表 → 拷数据 → 删旧表 → 改名 → 重建索引」这一条路（`docs/database-design.md` §5.1）。
     * 顺序是有讲究的：
     * 1. 先建 `transcript_job`，否则重建字幕表时外键指向的表还不存在；
     * 2. 拷贝用列名清单而不是 `SELECT *`——新旧表列序一旦因为实体改动而不同，`*` 会静默错位；
     * 3. 旧表的索引在 `DROP TABLE` 时自动消失，所以改名后要按 Room 的命名重建三条（v3 比 v2 多一条 `job_id`；少一条就与导出 schema 不符）。
     *
     * 已有字幕行 `job_id` 恒为 null（Phase 5 只写测试字幕），所以 `SET NULL` 这条外键在本机上不会拒收任何历史数据。
     */
    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `transcript_job` (" +
                    "`id` TEXT NOT NULL, " +
                    "`audio_id` TEXT NOT NULL, " +
                    "`model_id` TEXT NOT NULL, " +
                    "`model_version` TEXT NOT NULL, " +
                    "`status` TEXT NOT NULL, " +
                    "`total_segments` INTEGER NOT NULL, " +
                    "`done_segments` INTEGER NOT NULL, " +
                    "`error_code` TEXT, " +
                    "`error_message` TEXT, " +
                    "`work_request_id` TEXT, " +
                    "`created_at_ms` INTEGER NOT NULL, " +
                    "`started_at_ms` INTEGER, " +
                    "`finished_at_ms` INTEGER, " +
                    "PRIMARY KEY(`id`), " +
                    "FOREIGN KEY(`audio_id`) REFERENCES `audio_book`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE" +
                    ")",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_transcript_job_audio_id` ON `transcript_job` (`audio_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_transcript_job_status` ON `transcript_job` (`status`)")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_transcript_job_audio_id_created_at_ms` " +
                    "ON `transcript_job` (`audio_id`, `created_at_ms`)",
            )

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `subtitle_segment_new` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`audio_id` TEXT NOT NULL, " +
                    "`segment_index` INTEGER NOT NULL, " +
                    "`start_ms` INTEGER NOT NULL, " +
                    "`end_ms` INTEGER NOT NULL, " +
                    "`text` TEXT NOT NULL, " +
                    "`source` TEXT NOT NULL, " +
                    "`job_id` TEXT, " +
                    "`created_at_ms` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`audio_id`) REFERENCES `audio_book`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                    "FOREIGN KEY(`job_id`) REFERENCES `transcript_job`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL" +
                    ")",
            )
            db.execSQL(
                "INSERT INTO `subtitle_segment_new` " +
                    "(`id`, `audio_id`, `segment_index`, `start_ms`, `end_ms`, `text`, `source`, `job_id`, `created_at_ms`) " +
                    "SELECT `id`, `audio_id`, `segment_index`, `start_ms`, `end_ms`, `text`, `source`, `job_id`, `created_at_ms` " +
                    "FROM `subtitle_segment`",
            )
            db.execSQL("DROP TABLE `subtitle_segment`")
            db.execSQL("ALTER TABLE `subtitle_segment_new` RENAME TO `subtitle_segment`")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_subtitle_segment_audio_id_segment_index` " +
                    "ON `subtitle_segment` (`audio_id`, `segment_index`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_subtitle_segment_audio_id_start_ms` " +
                    "ON `subtitle_segment` (`audio_id`, `start_ms`)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_subtitle_segment_job_id` ON `subtitle_segment` (`job_id`)")
        }
    }

    /**
     * v3 → v4：`transcript_job` 加三列进度字段（`phase` / `position_ms` / `duration_ms`，FR-507 的时间口径进度）。
     *
     * 纯 `ADD COLUMN`，不重建表、不碰任何已有行：`phase` 可空无默认（终态行本就该是 null），
     * 两个数值列 `NOT NULL DEFAULT 0`——实体上对应 `@ColumnInfo(defaultValue = "0")`，少写一边
     * `MigrationTestHelper` 会以「默认值不一致」拒收（Room 把 DEFAULT 当 schema 的一部分对账）。
     * 老的 RUNNING 行在启动恢复里会被标 INTERRUPTED，所以不需要给历史行回填进度。
     */
    val MIGRATION_3_4: Migration = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `transcript_job` ADD COLUMN `phase` TEXT")
            db.execSQL("ALTER TABLE `transcript_job` ADD COLUMN `position_ms` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `transcript_job` ADD COLUMN `duration_ms` INTEGER NOT NULL DEFAULT 0")
        }
    }

    /** 装配进 `Room.databaseBuilder(...).addMigrations(...)`；顺序无关，Room 按 startVersion/endVersion 排路径 */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
}
