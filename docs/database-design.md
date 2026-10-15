# 数据库设计（database-design）

引擎：Room 2.8.5 + 系统 SQLite。文件：`app/src/main/java/com/xinchen/echomind/data/db/`。
导出 schema：`app/schemas/`（`room.schemaLocation` 由 KSP 参数配置，JSON 入库以便迁移测试）。
数据库名 `echomind.db`，当前版本 **3**（只有 `settings` 还没进 schema，Phase 7）。

> **各版本实际包含的表**（`app/schemas/…/N.json` 可核对）：
> v1 = `imported_folder`、`audio_book`、`playback_state`、`listening_session`；
> v2（Phase 5a）+ `subtitle_segment`；v3（Phase 6）+ `transcript_job`，同时把 `subtitle_segment.job_id` 补成外键（整表重建，见 §5.1）。
> Phase 3 期间往 v1 加表不写 Migration 的前提是「开发包数据可丢」；一旦有已安装的真实包，必须先冻结 v1 再走显式 Migration（`architecture.md` D12）——v2、v3 都是这么做的。

## 1. ER 关系

```mermaid
erDiagram
    IMPORTED_FOLDER ||--o{ AUDIO_BOOK : "包含（CASCADE）"
    AUDIO_BOOK ||--|? PLAYBACK_STATE : "1:1 播放记忆（CASCADE）"
    AUDIO_BOOK ||--o{ LISTENING_SESSION : "收听历史（无 FK，见 §4.3）"
    AUDIO_BOOK ||--o{ TRANSCRIPT_JOB : "转录任务（CASCADE）"
    AUDIO_BOOK ||--o{ SUBTITLE_SEGMENT : "字幕（CASCADE）"
    TRANSCRIPT_JOB ||--o{ SUBTITLE_SEGMENT : "产出批次（SET NULL）"
    SETTINGS {
        string key PK
    }
```

要点：
- **`LISTENING_SESSION` 故意不建外键**，这样「删除书架记录」不会带走听书历史（需求 NFR-04）；代价是历史行需要自带展示用的快照字段（§4.3）。
- `PLAYBACK_STATE` 与 `AUDIO_BOOK` 一对一：用 `audio_id` 同时作 PK 与 FK，数据库层面就杜绝重复行。
- `SUBTITLE_SEGMENT.job_id` 用 `SET NULL`：清理任务历史不影响已生成字幕的可用性。

## 2. imported_folder（文件夹授权）

| 列 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| `id` | TEXT | PK | UUID |
| `tree_uri` | TEXT | UNIQUE | SAF 树 URI（持久化授权的载体） |
| `display_name` | TEXT | | 用户可读名字（`DocumentFile.name`） |
| `granted_at_ms` | INTEGER | | 授权时间（墙钟，仅展示） |
| `last_scan_at_ms` | INTEGER | | 上次扫描完成时间 |
| `permission_state` | TEXT | | `GRANTED` / `REVOKED`（检测与降级见 `architecture.md` §8 与 FR-110） |
| `is_hidden` | INTEGER | 默认 0 | 用户隐藏但保留记录（列已建，隐藏功能在 Phase 7 的文件夹管理页） |

## 3. audio_book（音频元数据与文件引用）

| 列 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| `id` | TEXT | PK | UUID，跨重扫稳定 |
| `uri` | TEXT | **UNIQUE 索引** | SAF 文档 URI；去重键（FR-106） |
| `folder_id` | TEXT | FK→imported_folder CASCADE, 索引 | |
| `relative_path` | TEXT | | 相对树根路径，用于展示与排序 |
| `file_name` | TEXT | | 含扩展名 |
| `title` | TEXT | 索引 | 展示标题 |
| `title_is_manual` | INTEGER | | 1=用户改过，重扫不覆盖（FR-105） |
| `size_bytes` | INTEGER | | 变更检测指纹之一 |
| `file_last_modified_ms` | INTEGER | | 变更检测指纹之二 |
| `duration_ms` | INTEGER | 默认 0 | 0 且 `duration_read_failed=1` 表示未知 |
| `duration_read_failed` | INTEGER | | |
| `is_favorite` | INTEGER | | 书架收藏（FR-201） |
| `file_status` | TEXT | | `PRESENT` / `MISSING` |
| `transcription_state` | TEXT | 索引 | `NONE`/`QUEUED`/`RUNNING`/`READY`/`FAILED`/`EXPIRED` |
| `added_at_ms` | INTEGER | | 首次入库时间 |
| `last_scanned_at_ms` | INTEGER | | 该条记录最近被扫描确认的时间 |
| `updated_at_ms` | INTEGER | | |

变更检测口径：`size_bytes` 或 `file_last_modified_ms` 任一变化 → `transcription_state = EXPIRED`（FR-107）；扫描中树里消失的 URI → `file_status = MISSING`（FR-108），**不删行**。

## 4. 播放与统计

### 4.1 playback_state（每首 1 行，恢复 + 聚合缓存）

| 列 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| `audio_id` | TEXT | PK + FK→audio_book CASCADE | 一对一 |
| `last_position_ms` | INTEGER | | 恢复位置（音频内位置） |
| `duration_ms` | INTEGER | | 播放时已知的时长 |
| `play_count` | INTEGER | | **缓存值**：`is_counted=1` 的会话数 |
| `completed_count` | INTEGER | | **缓存值**：`completed=1` 的会话数 |
| `listened_ms_total` | INTEGER | | **缓存值**：`SUM(listened_ms)` |
| `last_played_at_ms` | INTEGER | 索引 | 书架「最近播放」排序用 |
| `last_completed_at_ms` | INTEGER | | |
| `playback_speed` | REAL | 默认 1.0 | 每首独立（FR-303） |
| `is_finished` | INTEGER | | 位置已到末尾（下次从头播的提示依据） |
| `created_at_ms` / `updated_at_ms` | INTEGER | | |

### 4.2 listening_session（真源：每次收听一行）

| 列 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| `id` | INTEGER | PK autoincrement | |
| `audio_id` | TEXT | 索引，**无 FK** | 书删了也保留历史 |
| `audio_title_snapshot` | TEXT | | 会话结束时的标题快照 |
| `duration_ms_snapshot` | INTEGER | | 会话结束时的时长快照（完播分母） |
| `started_at_ms` | INTEGER | 索引 | 墙钟，仅展示 |
| `ended_at_ms` | INTEGER | | 0 表示会话仍 `ACTIVE` |
| `listened_ms` | INTEGER | | **累计收听时长口径**：单调时钟测得的真实播放墙上时间（不乘倍速） |
| `consumed_audio_ms` | INTEGER | | 播放中音频位置正向推进之和（不含拖动跳跃），完播判定用 |
| `start_position_ms` / `end_position_ms` | INTEGER | | 会话首末位置 |
| `max_reached_position_ms` | INTEGER | | 会话到达过的最远位置 |
| `completed` | INTEGER | | 完播判定结果（FR-406） |
| `is_counted` | INTEGER | | 是否算一次有效播放（`listened_ms ≥ 阈值`） |
| `session_status` | TEXT | | `ACTIVE` / `FINISHED` / `INTERRUPTED` / `CRASHED` / `SKIPPED_TOO_SHORT` |
| `created_at_ms` / `updated_at_ms` | INTEGER | | |

`ACTIVE` 行是崩溃检测锚点：启动时把 `ended_at_ms = 0` 的行结算为 `CRASHED`（按已落盘的 `listened_ms` 决定是否 `is_counted`），从而不需要 WAL 之外的额外恢复机制。

两条恢复细节（避免误读）：

- **崩溃路径不降级成 `SKIPPED_TOO_SHORT`**：进程内结算会把「不足阈值」的行标成 `SKIPPED_TOO_SHORT`，而启动恢复一律标 `CRASHED`、只按阈值决定 `is_counted`。同一场景两个状态值是有意的：`CRASHED` 记的是「这一行不是正常关掉的」，对排查更有用；两种口径下 `is_counted` 的判定完全一致，统计不会因为走哪条路而变数字。
- **`ended_at_ms` 用最后一次 flush 的时刻**（`updated_at_ms > 0` 时），不是「现在」：从最后一次 flush 到进程死亡之间那段无法可信度量，宁可少算。顺带把 `updated_at_ms` 拉平到同一时刻，避免「结束时间比更新时间还早」。

### 4.3 为什么会话行带快照

统计页要能展示「那本书当时 45 分钟、标题是《…》」，而书可能已被从书架移除。快照把展示所需的最小信息固化在历史里；`audio_id` 仍保留，便于日后重新导入同一本书时历史自动接上（同一 URI → 同一 `id`，因为 `id` 由 URI 派生见 §8）。

## 5. subtitle_segment（字幕，v2 起）

| 列 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| `id` | INTEGER | PK autoincrement | |
| `audio_id` | TEXT | FK→audio_book CASCADE | |
| `segment_index` | INTEGER | 与 `audio_id` 组成 UNIQUE | 时间序 = 索引序 |
| `start_ms` / `end_ms` | INTEGER | | 来源见 `asr-design.md`（禁止均分伪造） |
| `text` | TEXT | | 已切句 |
| `source` | TEXT | NOT NULL | `ASR` / `MANUAL_TEST`。手工导入的测试字幕必须与模型产物分得开（AGENTS.md §5） |
| `job_id` | TEXT | 可空，FK→transcript_job **SET NULL**，索引 | 产出批次。v3 起有外键（§5.1）；只有 ASR 提交路径写它，手工导入的测试字幕永远是 null |
| `created_at_ms` | INTEGER | | 落库时刻，也是「字幕比文件新还是旧」的判据（§5.2） |

索引：`UNIQUE(audio_id, segment_index)`、`(audio_id, start_ms)`、`(job_id)`。
读取路径：`SELECT ... WHERE audio_id = ? AND start_ms BETWEEN ? AND ?`（窗口 ±N 句）或整本有序加载进内存索引（长音频用 `segment_index` 分页）。二分查找在内存里对 `List<SubtitleSegment>` 做，列表由 DAO 一次按 `segment_index` 顺序取（Phase 5 评估：单本 5000 句量级约几百 KB，可接受；超限则改为窗口查询）。

重转时替换语义：**一个事务内** `DELETE FROM subtitle_segment WHERE audio_id = ?` + 批量插入新行（FR-605），避免半套字幕。实现见 `SubtitleRepositoryImpl.replace()`（`db.withTransaction`），仪器化测试 `SubtitleRepositoryTest.再次导入是整本替换而不是追加` 覆盖。

### 5.1 `job_id` 的外键是 v2→v3 补上来的（为什么当初先不建）

v2 那版刻意没给 `job_id` 挂 FK：SQLite 不支持 `ALTER TABLE ... ADD CONSTRAINT`，后补 FK 只能整表重建（建新表→按列名拷贝→删旧表→改名→重建索引），而 `transcript_job` 当时还不存在，挂 FK 得先建一张空表把结构钉死——Phase 6 想改一列就得再来一次重建，得不偿失。
所以 v2 的代价是「可以写入指向不存在任务的 `job_id`」，但当时只有 `SubtitleRepositoryImpl` 写这一列且恒写 `null`，实际不可达。

v3 补 FK 的做法（`Migrations.MIGRATION_2_3`）：
- 建 `subtitle_segment_new`（两条 FK：`audio_id` CASCADE、`job_id` SET NULL），`INSERT ... SELECT` **显式列名**把数据搬过去，主键 `id` 原值保留——那些 id 即将被 `job_id` 引用，重排就是改历史；
- `DROP TABLE` 旧表 + `ALTER TABLE ... RENAME`，再重建三条索引（漏一条不会报错，只会让读取路径静默退化成全表扫）。
- 搬数据时外键已开着，所以指向不存在任务的 `job_id` 会让整条迁移失败而不是被悄悄吞掉。这是有意的：v2 的代码不可能写出这种值，真出现了就是库被外部改过，必须让人看见。

覆盖它的仪器化测试 `Migration_2_3_Test`（5 条）不只看 `runMigrationsAndValidate` 与 `3.json` 对账，还在这台迁移后的库上真跑了一遍行为：逐句比对字幕内容与 id、`PRAGMA foreign_key_list` 有两条外键、删书级联带走字幕与任务、**删任务行只把 `job_id` 置空而不删字幕**、三条索引都在。

### 5.2 只有 ASR 字幕算「转录过」

`source` 一列把「模型产物」和「手工导入的测试字幕」分开（AGENTS.md §5）。任务状态机只看前者：
`SubtitleSegmentDao.countAsrOf()` / `latestAsrCreatedAtMs()` 两条查询都带 `AND source = 'ASR'`，所以一副测试字幕不会让 `enqueue` 误判成「已经转好了」，也不会让取消后的书架谎报 READY。

`latestAsrCreatedAtMs` 与 `audio_book.file_last_modified_ms` 比大小，就是「这副字幕跟当前文件对不对得上」的判据（FR-107）——用的还是扫描路径那套 mtime 规则，不新造第二种口径。任务被取消后书架回哪个状态（NONE / READY / EXPIRED）由它决定，见 `TranscriptRepositoryImpl.stateAfterRollback()`。

## 6. transcript_job（转录任务，v3 起）

| 列 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| `id` | TEXT | PK | UUID |
| `audio_id` | TEXT | FK→audio_book CASCADE, 索引 | |
| `model_id` / `model_version` | TEXT | | 用哪个模型跑的版本化记录 |
| `status` | TEXT | | `QUEUED`/`RUNNING`/`SUCCESS`/`FAILED`/`CANCELLED` |
| `total_segments` | INTEGER | | 分段总数（规划后才知道，初始 0） |
| `done_segments` | INTEGER | | 进度分子（≥1s 节流写库） |
| `error_code` | TEXT | | `MODEL_MISSING`/`DECODE_FAILED`/`OUT_OF_MEMORY`/`PERMISSION_REVOKED`/`OUTPUT_UNRELIABLE`/`CANCELLED`/`INTERRUPTED`/`UNKNOWN`。`INTERRUPTED` = 进程被杀/重启时还挂着，不是模型或文件出了问题。`OUTPUT_UNRELIABLE` = **模型跑完了但字不够格当字幕**（时间戳不合格的块超过 `AudioFormats.ASR_MAX_REJECTED_CHUNK_PERCENT`，或一句都产不出，含「这段音频本来就没有语音」）；判它的是 `domain/asr/TranscriptQuality`，取舍见 `architecture.md` D34——**不给 SUCCESS、也不写空字幕**，因为空字幕会让书在书架上显示「已有字幕」 |
| `error_message` | TEXT | | 面向用户的说明，直接取异常原文，不做二次加工 |
| `work_request_id` | TEXT | | WorkManager 请求 ID，用于取消与重启对账。**v3 建列，写入方是 `TranscriptRepositoryImpl.attachWorkRequest()`（2026-10-15 落地）**：只认还没结束的行，写不回返回 `false`，由 `TranscriptWorkScheduler` 翻译成「刚入队的请求立刻 `cancelWorkById` 撤掉」（用例 `按id取行并把工作请求id写回活动任务` / `已结束的任务写不回工作请求id`）。⚠ 这条写入路径**只有仓储用例、没有 worker 级证据**——真正的入队方还没有生产调用方 |
| `created_at_ms` / `started_at_ms` / `finished_at_ms` | INTEGER | | |

索引：`(audio_id)`、`(status)`、`(audio_id, created_at_ms)`。

历史按版本保留（不做「一书一行」覆盖），失败重试会产生新行，旧行保留错误上下文——`retry()` 是「再入队一次」，不是把旧行改回 `QUEUED`。

**实现现状（v3）**：DAO 是 `TranscriptJobDao`，每条跃迁语句都带 `WHERE status IN (...)` 守卫并返回受影响行数，所以重复结算第二次是 0 行、已取消的任务被 worker 写回 RUNNING 在 SQL 层面就改不动；0 行由 `TranscriptRepositoryImpl` 翻译成 `false` 交给调用方，而不是抛异常打断 worker。
**这套守卫现在是生产代码的承重墙**：`asr/TranscriptCoordinator` 的「抢不到行就收手」完全建在上面——`markRunning` 除了 `WHERE status='QUEUED'`
还带一条 `AND NOT EXISTS (SELECT 1 FROM transcript_job WHERE status='RUNNING' AND id <> :id)`，一条语句挡住三种情况：第二个 worker 抢跑、
进程被杀后 WorkManager 复活 worker 而库里那行早已被 `recoverInterrupted()` 标成 `FAILED(INTERRUPTED)`、以及**另一本书正在跑时的并发转录**
（全机同时只允许一行 RUNNING，见 `architecture.md` D36）。三条设备用例分别对应：`正在跑的行不会被第二个worker重复跑` /
`重启后复活的worker不再覆盖中断结论` / `别的任务正在跑时第二本书的任务抢不到运行中的行`。
唯一的例外是 `commitSubtitles()`：那条路径只有成功分支，跃迁失败意味着这批字幕不该落库，于是 `check` 抛出、事务回滚——宁可一句都不写，也不能留下「字幕是新的、任务还在 RUNNING」的账。
状态跃迁与 `audio_book.transcription_state` 的写在同一个 `withTransaction` 里（§9），行为由 `TranscriptRepositoryTest`（**19 条**仪器化用例）覆盖。
`total_segments` 存的是 **VAD 段数**而不是句数：一句字幕可能来自多个块，把分母改成句数会让进度条倒退，所以 SUCCESS 时也只把 `done_segments` 追平到入库前那个 `total_segments`。
⚠ **中途 `total_segments` 恒为 0，是刻意的而不是没写**：VAD 是流式的，读到最后一段之前没人知道总段数，而写「N/N」会画出一条永远 100% 的假进度。所以 `TranscriptJobRow.progress` 在分母为 0 时给 **null**（用例 `分段数未知时没有百分比`），UI 那侧的契约是 `progress == null` → 显示「已识别 N 段」而不是百分比（FR-507；通知栏已经按这个写，见 `TranscriptWorker.notification`）。

## 7. settings（键值）

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `key` | TEXT PK | 例如 `default_playback_speed`、`subtitle_font_scale`、`theme_mode` |
| `value` | TEXT | 原始字符串，读取时按类型解析 |
| `updated_at_ms` | INTEGER | |

用 Room KV 而不是 DataStore 的理由见架构决策 D8：单一本地存储真源，迁移/备份一致。
**注意**：统计阈值（`PlaybackPolicy` 里的常量）不进 settings 表——它们影响历史数据口径，属于代码级配置，改了必须走「重算统计」。

## 8. ID 生成与去重

- `audio_book.id`：由 `uri` 决定的稳定 UUID（`UUID.nameUUIDFromBytes(uri.toByteArray())`），因此文件被从书架删除后重新导入同一 URI，历史会话（`listening_session`，无外键）能自动接上；`playback_state` 会随删书级联清掉，靠 FR-410 的重算从会话表重建。
- `imported_folder.id`：由 `tree_uri` 同样派生。
- 去重判定在应用层（`AudioImportPlanner` 按 URI 比对 size/mtime），**唯一索引只是兜底**：`audio_book.uri` UNIQUE + 主键即 URI 派生值，所以即使两次扫描穿透了应用层判定，`@Upsert` 也只会把同一 URI 收敛成一行（更新而非第二条）。写入用 `@Upsert`，不用 `@Insert(REPLACE)`——Room 的 REPLACE 落到 SQLite 是 DELETE+INSERT，会触发 `playback_state` 的 `ON DELETE CASCADE`，把一次重扫变成删书（见 `architecture.md` D10）。

## 9. 事务与索引原则

- 扫描结果整批写入用 `@Transaction`（或 `withTransaction`），单批一个事务，避免逐条 fsync。
  **当前实现的真实边界**：`upsertAll` 批量写自带事务（KSP 生成的 `AudioBookDao_Impl` 里走 `performSuspending(db, false, true)`），但 `importFolder` 的「插新 → 逐条改变更 → 标 MISSING/PRESENT → markScanned」仍是多次独立提交，中途失败会留下半套状态。**没做**（`AudioRepositoryImpl` 里没有 `withTransaction`），原因是逐条 `upsert` 得先改成批量、`changed` 那条循环得先收成一个 DAO 调用，属于独立一次改动，不能顺手做。
- 字幕替换、任务状态跃迁 + `audio_book.transcription_state` 更新，必须在同一事务里，防止状态与数据不一致。
  **已落地**：`SubtitleRepositoryImpl.replace()` 与 `TranscriptRepositoryImpl` 的每条跃迁都是 `db.withTransaction`。
  `commitSubtitles` 没有复用 `SubtitleRepository.replace()`：那条接口写不了 `job_id`，而且它会再开一层事务（Room 允许嵌套、内层直接加入外层，但两套代码各自假设自己拥有事务，边界就不清楚了）。所以字幕实体在 `TranscriptRepositoryImpl` 里直接构造，事务由拥有状态跃迁的那一方开。
- 聚合缓存（`play_count` 等）的写入路径：会话 `finalize` 时在同一事务里 (a) 更新会话行 (b) `UPDATE playback_state`；重算入口用 §10 的 SQL 覆盖缓存值。

## 10. 统计口径的 SQL 定义（与 `playback-design.md` 一致）

```sql
-- 重算某首 / 全量重算（FR-410，`PlaybackStateDao.recomputeAll`，一条语句对所有行生效）
UPDATE playback_state SET
  play_count = (SELECT COUNT(*) FROM listening_session
                WHERE listening_session.audio_id = playback_state.audio_id
                  AND ended_at_ms > 0 AND is_counted = 1),
  completed_count = (SELECT COUNT(*) FROM listening_session
                WHERE listening_session.audio_id = playback_state.audio_id
                  AND ended_at_ms > 0 AND completed = 1),
  listened_ms_total = (SELECT COALESCE(SUM(listened_ms), 0) FROM listening_session
                WHERE listening_session.audio_id = playback_state.audio_id AND ended_at_ms > 0),
  last_played_at_ms = COALESCE((SELECT MAX(CASE WHEN ended_at_ms > 0 THEN ended_at_ms ELSE updated_at_ms END)
                FROM listening_session
                WHERE listening_session.audio_id = playback_state.audio_id), last_played_at_ms),
  last_completed_at_ms = COALESCE((SELECT MAX(ended_at_ms) FROM listening_session
                WHERE listening_session.audio_id = playback_state.audio_id
                  AND ended_at_ms > 0 AND completed = 1), last_completed_at_ms);

-- 全局统计页
SELECT COALESCE(SUM(listened_ms),0) FROM listening_session WHERE ended_at_ms > 0;      -- 总收听时长
SELECT COUNT(*) FROM listening_session WHERE ended_at_ms > 0 AND is_counted = 1;        -- 总播放次数
SELECT COUNT(*) FROM listening_session WHERE ended_at_ms > 0 AND completed = 1;         -- 总完播次数
```

两个必须留意的细节（都是为了让「增量」与「重算」两条路对得上账，实测对账见 `testing.md` §4 FR-410）：

1. **计数与时长只算 `ended_at_ms > 0` 的行**。正在进行的会话还没结算，增量路径（`applySettledSession`）也还没把它计入；重算若不排掉它，就永远比增量多一截。
2. **`last_played_at_ms` 是例外**，口径是「最后一次收听活动发生的时刻」：已结算的行取 `ended_at_ms`，进行中的行取 `updated_at_ms`（等于它最后一次 flush 的时刻），两者取最大。它由 `saveProgress` 单点维护，`applySettledSession` **不写**这一列——两处都写必然各自更新一半。`last_completed_at_ms` 只在有完播时推进。
3. `session_status` 是「怎么结束的」，不是「听完了没有」；完播一律数 `completed = 1`。

阈值定义在 `core/conf/PlaybackPolicy.kt`（唯一来源），统计页的规则说明文案由同一组常量渲染，避免文档/文案/代码三处漂移（FR-903）。

## 11. 迁移策略

1. `exportSchema = true`，JSON 提交进 `app/schemas/`。
2. **正式版禁止** `fallbackToDestructiveMigration()`；开发期 v1 内部演进除外（本文与 roadmap 里明确标注）。
3. 每个后续版本变更：写 `Migration(OLD, NEW)`，含索引/外键重建（SQLite 改表要建新表再 rename，字幕这类有索引的表尤其注意）。
4. 仪器化测试用 `MigrationTestHelper` + 导出 JSON 校验每条迁移。
   **两条真实迁移都已落地**：
   - v1→v2（Phase 5a）：`Migrations.MIGRATION_1_2` 手写建 `subtitle_segment` + 两条索引，DDL 与 `2.json` 逐字对齐；`Migration_1_2_Test` 三项全绿（结构校验 / 已有书籍与收听历史保留 / `PRAGMA foreign_key_list` 确认 FK）。
   - v2→v3（Phase 6）：`Migrations.MIGRATION_2_3` 建 `transcript_job` + 三条索引，并**整表重建** `subtitle_segment` 以补 `job_id` 外键（做法与理由见 §5.1）；`Migration_2_3_Test` 五条覆盖了结构对账、字幕逐句不丢、两条 FK 的存在与**行为**（级联删、SET NULL）、三条索引。
   写手写 DDL 的固定动作：先 `./gradlew :app:kspDebugKotlin` 导出 `N.json`，把 Room 生成的 `createSql` 与索引语句逐条抄进 Migration——注意 TEXT 主键落成表级 `PRIMARY KEY(\`id\`)`（SQLite 因此会额外建一个 `sqlite_autoindex_*`，它不是 Room 的索引，测试做集合断言时要剔掉）。
   `MigrationTestHelper` 的 `recreateTableOnMigrationError` 传 `false`——传 `true` 等于把「迁移失败就重建表」这条破坏性后路偷偷请回来。
   helper 从 assets 读 schema，路径是 `"$databaseClassName/$version.json"`（没有 `schemas/` 前缀），所以 `build.gradle.kts` 里是 `assets.directories.add("$projectDir/schemas")`。
5. 迁移里对统计类字段的变更（例如改 `is_counted` 定义）必须同时提供数据回填 SQL，且回填口径写进本文，不留「静默改变历史数字」。MIGRATION_1_2 与 MIGRATION_2_3 都只新增表（后者搬的是字幕原值，不改任何统计列），因此无需回填。
6. `Migrations.ALL` 是 `Room.databaseBuilder` 唯一注册的迁移集（`EchoMindApp`），加新版本时往这个数组里追加，不要漏——漏了就是运行时 `IllegalStateException`。
