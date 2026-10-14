# 测试与验证（testing）

## 1. 环境前置（每次都要）

```bash
source ~/android-dev/env.sh     # 否则 GRADLE_USER_HOME 退回 ~/.gradle，且找不到 adb/aapt2
```

## 2. 命令

| 目的 | 命令 | 说明 |
| --- | --- | --- |
| 调试包构建 | `./gradlew :app:assembleDebug` | 主验证路径 |
| JVM 单元测试 | `./gradlew :app:testDebugUnitTest` | domain 纯逻辑，无需设备 |
| 单个测试类 | `./gradlew :app:testDebugUnitTest --tests '*SessionTrackerTest'` | |
| 仪器化测试（Room/Media3/SAF） | `./gradlew :app:connectedDebugAndroidTest` | 需要 `emulator-5554` 或真机 |
| 安装到设备 | `adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk` | |
| 启动 | `adb -s emulator-5554 shell am start -n com.xinchen.echomind/.MainActivity` | |
| 读 UI 层级 | `rm -f /tmp/em.xml && adb -s emulator-5554 shell uiautomator dump /sdcard/em.xml && adb -s emulator-5554 pull /sdcard/em.xml /tmp/em.xml` | **必须先 `rm -f`**：dump 失败时不会覆盖目标文件，直接 cat 会读到上一屏 |
| 截图 | `adb -s emulator-5554 exec-out screencap -p > /tmp/shot.png` | |
| 查数据库 | `adb -s emulator-5554 shell run-as com.xinchen.echomind ls databases` | debug 包可 `run-as`；导出后用 sqlite3 读 |
| 模拟来电抢焦点 | `adb -s emulator-5554 emu gsm call 15551234567` / `… emu gsm cancel 15551234567` | **这是唯一能造出 `AUDIOFOCUS_LOSS_TRANSIENT` 的路**。测之前先确认 `dumpsys audio` 里 `In ring or call: false` 且焦点栈只剩我们，否则上一次卡住的 `state=RINGING` 会让铃声抢不到焦点，得到「什么都没发生」的假阴性 |
| 看焦点与播放态 | `adb -s emulator-5554 shell dumpsys audio`；`… shell dumpsys media_session` | 读位置要用 `grep -E "com\.xinchen\.echomind/androidx" -A 16` 再挑 `state=… position=…`；用 `package=com.xinchen.echomind` 当锚点加 `-A 12` 会**漏掉 state 行** |
| 模拟耳机拔出 | — | **做不到**：`ACTION_AUDIO_BECOMING_NOISY` 是受保护广播，`am broadcast` 打不进来；`adb emu help` 里没有耳机/音频路由命令。只能真机 |
| 发布包 | `./gradlew :app:assembleRelease` | **本机 `--offline` 下必失败**（lint-gradle 未缓存），已用 `checkReleaseBuilds=false` 规避 |

### 2b. 设备自动化实测到的坑（Phase 3 / 5）

- `connectedDebugAndroidTest` **会清掉被测 App 的数据**：跑完 `dumpsys package` 显示 `notLaunched=true`，`databases/` 目录消失，SAF 持久化授权随之失效，App 也被卸载。之后要 `adb install -r` 重装并重新走一遍文件夹授权。→ 设备验证排在仪器化测试之后。
- `uiautomator dump` 在「刚启动 / 刚点过一下」之后经常返回 `ERROR: null root node returned by UiTestAutomationBridge`。它失败时**既不写也不删**目标文件：必须 `adb shell rm -f /sdcard/em.xml` → dump → `pull` → 校验 `</hierarchy>`，并重试（本项目实测 2~3 次内成功）。只删本地副本不够。
- `adb shell input text` 不支持非 ASCII（中文直接 `NullPointerException in InputShellCommand.sendText`）→ 设备上的搜索用例只能打 ASCII；中文搜索由 `AudioBookDaoTest` 覆盖。
- `adb shell mv 'a b' c` 的引号在设备端会被重新拆分，得到 `mv a b c` → 报 `not directory`。整条命令要作为一个字符串传：`adb shell "mv 'a b' c"`。
- 连发十几个 `input keyevent` 会让 App 在 `HardwareRenderer.nSyncAndDrawFrame` 上 ANR（环境现象，不是业务代码）。键入清理要慢、要少。
- 读库要连 WAL 一起取：`run-as com.xinchen.echomind cat databases/echomind.db{,-wal,-shm}` 三份都拉到 /tmp，再 `~/android-dev/sdk/platform-tools/sqlite3` 打开。只拉主库会读到旧快照（实测出现过刚写入却查出 0 行）。
- **装到设备的 APK 必须由 `:app:assembleDebug` 产出**。只跑 `:app:assembleDebugAndroidTest` 之后 `install -r app-debug.apk` 会把**旧包**再装一遍（Phase 5 实测踩过：改了 `TestSubtitleParser` 却仍导出旧格式文本，DB 里的 `text` 列就是判据）。
- SAF 选目录与文件在这台模拟器上的可点位置：`DocumentsUI` 底部确认键是 `android:id/button1`，文本 **「USE THIS FOLDER」**，bounds `[37,2148][1043,2274]` → 点 (540,2210)；随后的授权确认弹窗 ALLOW 也是 `button1`，`[790,1298][968,1424]` → 点 (879,1361)。网格视图里点文件的 `title` 节点**没有反应**，要点 `item_root` 的中心（实测 783,1758）。
- `dumpsys media.session` 在服务名上是 `media_session`（下划线）；`media.session` 会返回 `Can't find service`。队列里正在播哪一首看 `metadata: … description=<标题>` 与 `state=… position=…`。
- 播放页 dump 出来的 `0:08 / -0:36` 这类读数要拿去和 `audio_book.duration_ms` 对账：两者相加就是当前这一首的时长。Phase 5 有一次「字幕没刷新」的疑似 bug 就是这样破的——实际是短曲播完自动切到了下一首（那一首确实没字幕）。

## 3. 测试分层策略

| 层 | 位置 | 覆盖内容 |
| --- | --- | --- |
| 纯 JVM 单测 | `app/src/test/` | **已有（83 项）**：`AudioTreeWalker`（8）、`AudioImportPlanner`（9）、`TitleDeriver`（7）、`Format`（5）、`ShelfViewModel`（2）、**`SessionTracker`（22：有效次边界、倍速只放大消耗量、墙钟跳变、缓冲/暂停不计时、拖到末尾不完播、切歌先结算上一场）**、**`PlayerViewModel`（10：按记忆位置起播、已在播不重开、听完从头播、自动切歌换页头、收藏作用于正在播的那本、书不在书架不起播、字幕窗口跟随位置、切歌后换成另一套字幕、导入写进正在播的那本、同一句内部推进不重发窗口）**、**`SubtitleSynchronizer`（11：首句/中间/末句、空表、位置在首句之前、gap 迟滞、5000 句二分）**、**`TestSubtitleParser`（9：SRT/VTT/TSV、缺小时与一位小数毫秒、乱序重排、`end ≤ start` 拒绝、空文件失败）**。**待补**：`SentenceSplitter`（标点/无标点/异常时间戳）、`PcmChunkPlanner`（分段时间偏移）——两者属 Phase 6 |
| 仪器化测试 | `app/src/androidTest/` | **已有（48 项）**：Room DAO 读写与唯一索引（11）、`AudioRepositoryImpl` 的导入编排（9）、`PlaybackRepository`（21：会话行生命周期、flush 覆盖写不累加、结算的事务性、增量==重算、重算能修正被写坏的缓存、崩溃恢复标 `CRASHED`/幂等/短会话不计次、删书保留历史、倍速只落自己那一列、时长未知不覆盖已知）、**`Migration_1_2_Test`（3：v1→v2 结构对导出 JSON、历史保留、FK 与索引存在）**、**`SubtitleRepositoryTest`（4：索引连续、整本替换、摘要分辨来源、删书连带删字幕但留历史）**。**待补**：SAF 真实 URI 扫描、转录结果入库 |
| 手工设备验证 | 见 §6 | 耳机拔出、低内存、真实模型推理、飞行模式；焦点抢占与后台播放已在模拟器实测（§4 Phase 4），七句窗口与点句跳转已实测（§4 Phase 5） |

约束：`domain` 包不得 import `android.*`（这是「能在 JVM 里测」的前提）；`TimeSource` 注入假时钟，因此时长类断言不依赖真实时间流逝。实测这条约束确实成立——`SessionTrackerTest` 全程没有 Robolectric、没有设备。

## 4. 已执行并通过的验证（真实记录）

### Phase 0（2026-10-14）
- 工具链核查：JDK Temurin 17.0.18、SDK platform `android-36` / `android-37.0`、build-tools 35/36/37、无 NDK、`emulator-5554` 运行中（API 36 / arm64-v8a / 1080x2400）。
- 依赖版本在阿里云镜像与 `repo.maven.apache.org` 实测存在（清单见 `environment.md` §4）。
- sherpa-onnx 分发形态、Kotlin API 面、时间戳能力、模型大小与校验值：见 `asr-design.md` §1 表格（每条附来源）。

### Phase 2（2026-10-14）
```
$ ./gradlew :app:assembleDebug :app:testDebugUnitTest
> Task :app:kspDebugKotlin            ← Room 代码生成成功
> Task :app:compileDebugKotlin
> Task :app:testDebugUnitTest NO-SOURCE
BUILD SUCCESSFUL in 5s
app/build/outputs/apk/debug/app-debug.apk   16,110,245 B
```
- 失败并修复的过程（如实记录）：
  1. `org.jetbrains.kotlin.android:2.3.12` 不存在 → 改回 built-in Kotlin 路线（`environment.md` §5b）。
  2. Gradle 9.6 DSL 两处弃用即报错：`assets.srcDir(...)`、`freeCompilerArgs += listOf(...)` → 改为 `directories.add(...)` / `addAll(...)`。
  3. 自写代码错误：`AudioBookEntity` 里对 `String` 常量误用 `.name` → 修正。
- 设备验证：`adb install -r` → `Success`；`am start` 后 `pidof` 有进程、`dumpsys` 显示 `topResumedActivity=...MainActivity`；`uiautomator dump` 取到 `书架 / 该页面将在 Phase 3 实现 / EchoMind` 等节点；截图确认 Material 3 主题与底部导航渲染正常。
- **未做**：任何业务逻辑测试（`testDebugUnitTest` 是 `NO-SOURCE`，因为还没有测试用例）。不得据此声称测试通过。

### Phase 3（2026-10-14 ~ 10-15）

自动化：

```
$ ./gradlew :app:assembleDebug :app:testDebugUnitTest
> Task :app:kspDebugKotlin
> Task :app:compileDebugKotlin
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL
  AudioImportPlannerTest  tests=9  failures=0
  AudioTreeWalkerTest     tests=8  failures=0
  TitleDeriverTest        tests=7  failures=0
  FormatTest              tests=5  failures=0
  ShelfViewModelTest      tests=2  failures=0     ← 合计 31 项，全绿

$ ./gradlew :app:connectedDebugAndroidTest        # emulator-5554 / API 36 / arm64
BUILD SUCCESSFUL in 13s
  testsuites tests="20" failures="0" errors="0" skipped="0"
  （AudioBookDaoTest 11 + AudioRepositoryImportTest 9）
```

设备实测（`emulator-5554`，fixture 在 `/sdcard/Music/EchoMind`：3 层目录、6 个 mp3（含大写扩展名 `5.MP3`）、1 个 `notes.txt`）：

| 验证项 | 做法 | 结果 |
| --- | --- | --- |
| FR-101 授权持久化 | 导入 → `dumpsys activity permissions` | `mode=0x3 owned=0x3 persistable=0x3 persisted=0x1`；`pm clear`/仪器化测试清空后重新授权同样通过 |
| FR-103/104 递归与时长 | 导入后 `select title,file_status from audio_book` | 6 行全 PRESENT；`notes.txt` 未入库；时长读数 0:05/0:12/0:19/0:29/0:44/1:30，与文件名预期一致 |
| FR-105 手改标题不被覆盖 | 行菜单→重命名→`ManualRename`→保存→重扫 | `title_is_manual=1`，重扫后仍是 `ManualRename`，其他 5 首保持派生标题 |
| FR-106 重复扫描不重复建记录 | 连续点「重扫」3 次 | `count(*)` 恒为 6；提示「新增 0，更新 0，消失 0」 |
| FR-108 文件消失 | `mv third/deep\ -\ 深层.mp3 /sdcard/Music/held.bin` → 重扫 | 行灰显 + 「· 文件已不在」，DB `MISSING`，提示「消失 1」；行不可点 |
| FR-108 反向（文件回来） | `mv` 回原位 → 重扫 | 提示「1 首文件已回来」，DB 回 PRESENT，行恢复可点（截图确认） |
| FR-201/203 列表与搜索 | 顶部搜索框输入 `DEEP` | 即时过滤到 1 行（中文关键词由 DAO 仪器化测试覆盖，`adb input text` 打不了中文） |
| FR-202 收藏 tab | 点 ☆ → 切「收藏」 | 只剩该首且为 ★；取消收藏后显示「还没有收藏」 |
| 导航 | 点行 → 播放页 → 返回 | 进入 placeholder「该页面将在 Phase 4-5 实现」，返回栈正常 |

本轮发现并修掉的问题（都是**只看代码看不出、必须跑起来才暴露**的）：

1. `takePersistableUriPermission` 传 `FLAG_GRANT_READ\|PERSISTABLE`（0x41）在 API 36 抛 `IllegalArgumentException("Requested flags 0x41, but only 0x3 are allowed")` → 只能传模式位；根因还包括 androidx `activity:1.13.0` 的 `OpenDocumentTree` 请求意图**一个授权位都不加**（`javap -c` 实测），所以必须自定义契约。
2. 文件移走再原样移回（size/mtime 都没变）时 `file_status` 永远停在 MISSING → 加 `revivedIds` 判定，并补 2 项 JVM 单测 + 1 项仪器化测试。
3. `ShelfUiState.tab` 与真正的查询条件是两份状态：点 tab 后列表变了但高亮不动、空态文案读旧值 → 状态收敛为单一来源，补 `ShelfViewModelTest`。
4. `Icons.Outlined.Star` 与 `Icons.Filled.Star` 在 material-icons-core 1.7.8 里几何相同（截图裁剪对比），收藏态看不出区别 → 改用 ★/☆ 字符。
5. 双标题栏（外层 Scaffold 与页面各自一个 topBar）→ 只留页面级。
6. 没播过的书也画 0% 进度条，整屏灰线 → 仅 `lastPlayedAtMs > 0` 才画。
7. 收藏/最近播放为空时提示「没有匹配的音频」，像是搜索坏了 → 空态文案按 tab 区分。

**未做**：真实撤销 SAF 授权的端到端验证（见 §6）；扫描取消；文件夹级重新授权 UI。

### Phase 4（2026-10-14 ~ 10-15）

自动化（2026-10-15 03:00 全量重跑，`--rerun-tasks`）：

```
$ ./gradlew :app:assembleDebug :app:testDebugUnitTest --rerun-tasks
> Task :app:kspDebugKotlin
> Task :app:compileDebugKotlin
> Task :app:packageDebug
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 5s
app/build/outputs/apk/debug/app-debug.apk   16,488,328 B

  AudioImportPlannerTest   tests= 9  failures=0
  AudioTreeWalkerTest      tests= 8  failures=0
  SessionTrackerTest       tests=22  failures=0
  TitleDeriverTest         tests= 7  failures=0
  FormatTest               tests= 5  failures=0
  PlayerViewModelTest      tests= 6  failures=0
  ShelfViewModelTest       tests= 2  failures=0      ← 合计 59 项，全绿

$ ./gradlew :app:connectedDebugAndroidTest            # drill36(AVD) - 16 / emulator-5554
BUILD SUCCESSFUL in 15s
  40 tests  0 failures  0 skipped  1.973s  100% successful
  （AudioBookDaoTest 11 + AudioRepositoryImportTest 9 + PlaybackRepositoryTest 20）
  之后单独补跑 PlaybackRepositoryTest：21 tests 0 failures（新增「倍速单独保存不影响进度与计数」）
  → 仪器化合计 41 项
```

> **报告计数有一个坑**：AGP 9.4.1 用 `com.android.tools.androidtest:android-test-engine:1.0.1` 跑仪器化测试，顶层 `TEST-<device>.xml` 会写成 `tests="0"`。权威数字要看
> `app/build/reports/androidTests/connected/debug/index.html`、或 `test-result-exit-code.txt`、或直接
> `adb shell am instrument -w com.xinchen.echomind.test/androidx.test.runner.AndroidJUnitRunner`。别拿那个 0 当结论。

设备实测（`emulator-5554`，fixture 同 Phase 3 的 6 个 mp3；位置/状态一律用 `dumpsys media_session` 与 `run-as` 拉出的 Room DB 读数，不靠目测）：

| 验证项 | 做法 | 结果 |
| --- | --- | --- |
| FR-304 前台服务与通知 | 起播后 `dumpsys notification` / `dumpsys media_session` | `isForeground=true foregroundId=1001 types=0x2`，渠道「播放控制」，无 `AudioHardening background playback would be muted` |
| 通知显示哪本书 | 播 `长章节` → 下拉截图 | 标题是真实书名；`MediaItem` 的 `metadata: size=4, description=三体` |
| 通知上的暂停/播放 | 点通知的暂停 → 采样位置 3s | 位置冻在 79541 不变；点播放能续 |
| 媒体键 | `adb shell input keyevent KEYCODE_MEDIA_PAUSE/PLAY` | 作用在同一个播放器上，页面同步 |
| FR-305 瞬时焦点丢失 | `adb emu gsm call 15551234567` → `dumpsys audio` | `loss: LOSS_TRANSIENT`；会话当场结算；`state=PAUSED(2) position=2511` |
| FR-305 挂断后不自动续播 | `adb emu gsm cancel …` 后按 3/5/5s 三点采样 | 2511 → 2511 → 2511（13s 内没有自动续播） |
| FR-305 用户主动恢复 | 点播放 | `PLAYING position=4748` → 8106，并新开一场 `ACTIVE` |
| 会话结算的阈值 | 上面那场只听了 3.0s | `SKIPPED_TOO_SHORT`，`is_counted=0`（<5000ms 不计次，FR-401） |
| FR-305 长会话中断 | 三体/录音/第二章 各听一段后来电 | `INTERRUPTED 34795/19156`、`18646/12030`、`64345/44675`（listened/consumed） |
| FR-307 自动切歌分账 | 队列连播 6 首 | 每首各自一行会话，`play_count` 不串；页头与统计跟着换成正在播的那本 |
| FR-405 崩溃恢复 | 播放中 `am force-stop` → 重启 | 残留 `ACTIVE` 行标成 `CRASHED`，`ended_at_ms` 取最后一次 flush 的 `updated_at_ms` |
| FR-406/407 拖到末尾不算听完 | 直接 seek 到末尾再停 | `completed=0`，`consumed_audio_ms` 远小于 `duration×0.9` |
| FR-410 两条路等价 | 增量维护后 `recomputeAggregates()` | `play_count=7 / completed=3 / listened=471992` 两边一致 |
| FR-411 统计页对账 | 页面读数 vs `sqlite3` 聚合 | 页面「12 分钟 / 15 次 / 11 次」= DB `752249 / 15 / 11` |
| 写库频率（FR-412） | 播放中反复读 `listening_session.updated_at_ms`（App 内部 `elapsedRealtime`）与 `dumpsys media_session` 位置对比 | `updated_at_ms` 以 ~10s 的台阶前进，不是每 500ms；500ms tick 只推 `StateFlow` 不碰库。另外这条路径在代码里是单点的：`flushNow()` 只在 `lastFlushMonotonic` 超过 `POSITION_FLUSH_INTERVAL_MS` 或状态跃迁时调用 |

一个**测量本身的坑**（值得记下来，否则会误判成统计 bug）：`listened_ms > consumed_audio_ms` 在这台模拟器上是正常现象。实测它渲染 89785ms 的音频要用 126219ms 墙钟（比值 0.711），较短的文件在 0.913/0.923/0.918——音频比墙钟慢。因为两个口径本来就不同源（前者墙钟、后者音频位置），所以 `listened > consumed` 是环境现象，不是把时间算重了。第一版采样脚本把 adb 往返的 ~0.75s 也算进 `dt`，得出 0.75~0.88 的假比值，改用 App 内部时间戳后才对。

本轮发现并修掉的问题（**全部是只看代码看不出、必须跑起来才暴露**的）：

1. `MediaSessionService` 里只把 `MediaSession` 赋给字段、没有 `addSession(session)`：会话本身能用（媒体键有效），但**没有媒体通知**、`startForeground` 拿不到通知，后台播放被系统按「无前台通知」处理，日志 `AudioHardening … would be muted`。→ `onCreate` 加 `addSession`、`onDestroy` 加 `removeSession`。
2. `MediaItem.fromUri()` 不带 metadata（`size=2, description=null`）→ 通知只显示「EchoMind is running」，用户看不出在播哪本。→ 用 `MediaMetadata.Builder` 带标题/时长/`MEDIA_TYPE_AUDIO_BOOK`。
3. 播放页的标题/收藏/统计绑在**路由参数**那一本上：自动切歌后正文是另一首，页头还停在原来那本。→ `playingId` 跟随控制器状态 + `flatMapLatest` 换数据源，补 6 项 `PlayerViewModelTest`（含「收藏切换作用于正在播的那一本」）。
4. 队列播完后点「播放」毫无反应。我先按「`playWhenReady==false` 所以走播放分支」修了一版，**修错了**：临时加的 `Log.d("PROBE resume …")` 一行都没打印，证明根本没进 resume 分支 → 真因是 `STATE_ENDED` 时 Media3 仍保留 `playWhenReady=true`。→ 判据换成 `player.isPlaying`，并显式处理「末尾重开 + IDLE 需 `prepare()`」。
5. 瞬时焦点丢失没有结算会话：`dumpsys audio` 显示 `LOSS_TRANSIENT`，但 `onPlayWhenReadyChanged` 的 `REASON_AUDIO_FOCUS_LOSS` 分支没触发——Media3 对这种丢失只置 `playbackSuppressionReason`。→ 在 `onPlaybackSuppressionReasonChanged` 里结算。
6. 接着 5 又发现：焦点回来时 **Media3 自己把 `playWhenReady` 恢复成 true**，于是铃声响着队列还在往后播，违反 FR-305 的「不自动续播」。→ 记 `waitingForUserToResume`，suppression 归零时立刻再 `pause()`。上表三点采样就是这一条的实测凭据。
7. 进度在 ViewModel 里存了第二份，与控制器不同源（拖条与 tick 互相覆盖）。→ 位置只有 `controller.state` 一个来源。
8. `PlayerHolder` 曾提供 `release()`，服务销毁时释放播放器 → 回前台是个 IDLE 空壳，播放按钮点了没反应（与 4 同症状的另一半）。→ 播放器属于进程，`onDestroy` 只 `removeSession`。

**未做 / 测不了**：
- `ACTION_AUDIO_BECOMING_NOISY`：**模拟器造不出这个场景**——它是受保护广播，`adb shell am broadcast` 打不进来；`adb emu help` 也没有耳机/音频路由开关命令。代码路径（`handleAudioBecomingNoisy=true` + `REASON_AUDIO_BECOMING_NOISY` 结算）已实现但**未实测**，见 §6。
- 来电场景有个坑：一次 `adb emu gsm call` 卡在 `state=RINGING`（TC@2），`adb emu gsm cancel` 返回 `OK` 但 telecom 里那条还挂着，后续几次尝试铃声没真正抢到焦点（`dumpsys audio` 无新 `handleLoss`），那一轮结论作废重测。可复现的排查顺序：`adb emu gsm list` → `dumpsys telecom | grep state=` → `dumpsys audio | sed -n '/Audio Focus stack/,/^Player base/p'`，确认 `In ring or call: false` 且焦点栈里只有我们再重测。
- FR-309 的「冷启动位置差 ≤10s」设备实测：仪器化测试把 App 数据（含 SAF 授权）清空了，需要重新导入文件夹才能测，本轮排在文档之后未做。

### Phase 5（2026-10-15 03:00 ~ 04:00）

自动化（最后一次全量，改完书架那格文案后重跑）：

```
$ source ~/android-dev/env.sh && ./gradlew :app:assembleDebug :app:testDebugUnitTest
> Task :app:kspDebugKotlin
> Task :app:compileDebugKotlin
> Task :app:testDebugUnitTest
> Task :app:packageDebug
BUILD SUCCESSFUL in 4s
app/build/outputs/apk/debug/app-debug.apk   16,610,174 B

  AudioImportPlannerTest      tests= 9  failures=0
  AudioTreeWalkerTest         tests= 8  failures=0
  SessionTrackerTest          tests=22  failures=0
  SubtitleSynchronizerTest    tests=11  failures=0   ← 新增
  TestSubtitleParserTest      tests= 9  failures=0   ← 新增
  TitleDeriverTest            tests= 7  failures=0
  FormatTest                  tests= 5  failures=0
  PlayerViewModelTest         tests=10  failures=0   ← 6 → 10
  ShelfViewModelTest          tests= 2  failures=0   合计 83 项，全绿

$ ./gradlew :app:assembleDebugAndroidTest && adb -s emulator-5554 install -r -t …/app-debug-androidTest.apk
$ adb -s emulator-5554 shell am instrument -w -e package com.xinchen.echomind \
      com.xinchen.echomind.test/androidx.test.runner.AndroidJUnitRunner
  AudioBookDaoTest ...........           (11)
  Migration_1_2_Test ...                  (3)  ← 新增
  AudioRepositoryImportTest .........     (9)
  PlaybackRepositoryTest .....................(21)
  SubtitleRepositoryTest ....        (4)  ← 新增
Time: 3.131
OK (48 tests)
```

v1→v2 迁移（`Migration_1_2_Test`，设备上真实 SQLite）：

| 用例 | 断言 |
| --- | --- |
| 结构符合导出 schema | `helper.runMigrationsAndValidate(NAME, 2, recreateTableOnMigrationError=false, MIGRATION_1_2)` 不抛；`PRAGMA foreign_key_list('subtitle_segment')` 含 `audio_book`；两条索引名在 `sqlite_master` 里 |
| 保留已有书籍与收听历史 | 先按 v1 DDL 手插 1 条 folder + 1 本 `第一首` + 1 场 `FINISHED`（listened 30000），迁移后 `count()==1`、会话数字原样 |
| 字幕表可用 | 迁移后对同一 `audio_id` 插入 2 句并按 `segment_index` 读回 |

字幕仓库（`SubtitleRepositoryTest`）：索引连续、重复导入是整本替换不是追加、摘要能分辨数量与来源、**删书连带删字幕但 `listening_session` 留着**（NFR-04）。

设备实测（`emulator-5554`，fixture：`/sdcard/Music/EchoMind/echoMind-test.srt`，9 句、0.5s~27s、每句 3s；这条通道导入的内容**不是 ASR 产物**，见下面的标注要求）：

| 验证项 | 做法 | 结果 |
| --- | --- | --- |
| v2 建库 | 全新安装后 `run-as … sqlite3 echomind.db "PRAGMA user_version"` | `2`，`subtitle_segment` 存在 |
| 真实 SAF 导入测试字幕 | 播放页 debug 按钮 → `OpenDocument` → 选 `echoMind-test.srt` | DB 落 9 行 `source=MANUAL_TEST`，页头「9 句 · 测试字幕，非 ASR 产物」 |
| FR-701 中间句 | 播到 0:12（暂停后点句定位） | 7 行：第三段前 3 句 + 当前句 + 后 3 句，截图为证 |
| FR-701 末句不补位 | 点最后一句（24000ms） | 只有 4 行（前 3 + 当前），后面不填空 |
| FR-702 当前句高亮 | 截图目视 | 当前句加粗 + `secondaryContainer` 底色，其余次要色 |
| FR-706 点句跳转 | 分别点第四段/第六段/第八段，读 `playback_state.last_position_ms` | 12000 / 15000 / 24000，**精确等于该句 `start_ms`** |
| FR-705 拖动进度 | `input swipe` 拖 SeekBar 到 0:18 | 当前句翻到第六段，窗口整体右移一句 |
| FR-705 倍速 | 点 2x，读 `playback_state.playback_speed` | `2.0`，窗口不错位 |
| FR-705 切歌 | 让 29s 的《三体》自动播到下一首《录音》 | 字幕区换成「还没有字幕…」（《录音》确实没有），数量与来源跟着换 |
| 覆盖安装不丢字幕 | 改文案后 `install -r` 新版 | 9 行仍在，页头仍报 9 句 |

FR-707（只在当前句变化时更新）用**设备 + 单测两头**取证：设备上暂停后逐句点定位，`uiautomator dump` 的 7 行文本序列按 `start_ms` 一格一格移动，同一句内部推进时文本一个字都不变（列表没有整页重绘的迹象）；行为侧由 `PlayerViewModelTest.同一句内部的位置推进不重发窗口` 锁住——它在同一个句子内部连推 5 个 tick，断言 `StateFlow<SubtitleWindow>` 一次都没有新值，跨过下一句起点才发第二次。

本轮踩到并修掉的问题：

1. **`AbstractMethodError: …GeneratedSerializer.typeParametersSerializers()`**（3 项迁移测试全红）。根因在依赖图里：Compose BOM 2026.09.00 用 `{strictly 1.7.3}` 钉住 `kotlinx-serialization-core`，而 room-testing 2.8.5 的 schema bundle 要 1.8.1。→ `app/build.gradle.kts` 里对**名字含 `AndroidTest` 的配置** `resolutionStrategy.force` 四个 artifact 到 1.8.1（App 运行时不碰序列化，主依赖图不动）。改完 `dependencies --configuration debugAndroidTestRuntimeClasspath` 里确认 `-> 1.8.1`。
2. **只重编 androidTest、忘了重编 App**：修完解析器后我只跑了 `:app:assembleDebugAndroidTest` 就 `install -r app-debug.apk`，设备上是**旧 APK**，导出的字幕文本带着 cue 序号（`开始今天的阅读 2`）。→ 装到设备上的那个 APK 必须由 `:app:assembleDebug` 产出；DB 里的文本就是这次的判据。
3. **解析器自己的两个 bug 被它的首批测试抓住**（先写测试再改代码的顺序是对的）：SRT 的 cue 序号行被当成正文拼进句子（空行没有充当 cue 结束符）；TSV 正文里再出现分隔符时被拆坏（`split` 没加 `limit = 3`）。
4. **`Migrations.kt` 的 KDoc 里写 `app/schemas/*.json` 会编译成「Unclosed comment」**——`/*` 在 Kotlin 里会开嵌套块注释。已改成中文描述。
5. **一次误判**：《三体》页头显示「9 句」但字幕区写着「还没有字幕」，我当成流没刷新的 bug 查。真相是 29 秒的《三体》在我翻 DocumentsUI 期间播完并自动切到了《第二章》（44s，确实没字幕），`0:08 / -0:36` 的时长就是证据。**切歌换字幕这条逻辑本身是对的**（JVM 里有 `切歌后字幕跟着换成新歌的那一套` 兜着）。教训：看 dump 的时间读数对账，别只看文案。
6. **书架那格「无字幕」是一句假话**：有测试字幕的书仍显示「无字幕」。没有改成去 JOIN `subtitle_segment`，而是把 NONE 的文案改成「未转录」——这一格描述的本来就是 `transcription_state`（测试字幕按 §5 的诚实性要求不该动它）。见 `architecture.md` D21。

#### 顺手补上的 Phase 4 遗留：FR-309 冷启动 ≤10s（2026-10-15 04:00）

文件夹已经重新导入过，所以这条能测了。做法：让《长章节》播到中段 → **在同一个 `adb shell` 里**读 `dumpsys media_session` 的位置并立刻 `am force-stop`（分两次 adb 调用会把 ~0.8 s 的往返也算进差值里，第一版就是这么测歪的）→ 冷启动 → 读恢复位置。

| 量 | 值 |
| --- | --- |
| 杀掉瞬间播放器位置（`dumpsys media_session`） | 61853 ms |
| 最后一次 flush 落库的位置（`playback_state.last_position_ms`） | 58459 ms |
| 冷启动后书架「继续上次收听」卡片 | `已播到 0:58 / 1:29` = 58459 ms，与库里一致 |
| 差值 | **3394 ms ≤ 10000 ms**（FR-309 达标；上界就是 `POSITION_FLUSH_INTERVAL_MS`） |
| 冷启动到书架可读 | `am start` 后 3 s 内（这台模拟器） |

同一次运行还确认了：《录音》《第二章》都标着 `is_finished=1`，重开都从 0 起（FR-407 的「听完了就从头播」在设备上也成立）。

#### 这一轮真机上抓到的 bug：自动切歌不换倍速

《第二章》设成 1.5x 播完，自动前进到《长章节》（记忆 1.0x）后 `dumpsys media_session` 仍是 `speed=1.5`——**播的是上一首的速率，记的却是自己那本的账**（`sessionSpeed` 已经换值，所以统计里记的 `speed` 与实际播放速率不一致）。根因：`onMediaItemTransition` 只做了 `startSession` + 记忆位置 seek，没有像 `switchTo()` 那样 `player.setPlaybackSpeed(item.speed)`；Media3 不会替我们改速率。
→ 补上那一行。**修复后实测**：《第二章》（存 2.0）播完前进到《长章节》（存 1.0），`dumpsys` 立刻是 `speed=1.0`、`description=长章节`。
**为什么没有自动化测试**：`Media3PlaybackController` 一个测试都没有——它需要一个 `Player` 替身（Media3 的 `SimpleBasePlayer`）才测得动，本项目现有的替身接缝只有 `PlaybackRepository`、`AudioRepository`、`SubtitleRepository` 与 `PlaybackController`。所以控制器这一层的 bug 只能靠设备实测发现（Phase 4 的 6 个也是）。已记进 §5 待补。

**未做 / 测不了**：
- **没有任何 ASR 产物**：`asr/`、`work/` 包还不存在，模型没下载过。上面所有字幕都来自手工 SRT，只能证明**字幕管线**（存、读、算窗口、跳转、标注）通，不能证明转录可用。
- FR-602 中文标点切句、FR-603 对模型时间戳的清洗：未实现，无测试。
- 「装了 v1 旧包的用户覆盖安装」这条真实升级路径没走过：本机从未安装过 v1 版本的 App，只有 `MigrationTestHelper` 在设备上造的 v1 文件被迁移过。
- FR-708「长句换行仍可辨识」只做了目视（本轮 fixture 每句都短，没触发换行）；字号调节属 Phase 7。
- `uiautomator dump` 在 DocumentsUI 的确认弹窗上不稳定，仍按 §2b 的先 `rm -f` 再 dump 处理。

## 5. 待补的自动化测试清单（按阶段）

| Phase | 用例 |
| --- | --- |
| 3 | **已覆盖**：递归 3 层子目录 + 扩展名白名单 + 深度/数量截断；连续扫描不产生新记录；同一 URI 重复出现只算一次；size/mtime 变化 → CHANGED 且字幕标 EXPIRED、手改标题不被覆盖；文件消失 → MISSING 且不删记录；文件原样回来 → MISSING 翻回 PRESENT；跨文件夹重复文件留在原文件夹；授权无法持久化 → 导入失败不写库；重扫跳过授权失效文件夹但继续其他文件夹；时长读取失败 → `duration_read_failed`；书架 tab/搜索状态与查询同源。**待补（依赖 Phase 4 的会话表）**：消失文件的收听历史仍然保留 |
| 4 | **已覆盖**：4.9s 不计次 / 5.0s 计次；缓冲+暂停+焦点丢失不计时；2x 播 10min → `listened_ms=600000`、`consumed_audio_ms=1200000`；拖到末尾不完播；1.5x 听完 → 完播；两次听完 → 完播 2；崩溃恢复不重复累计（含幂等与「不结算之后的新会话」）；重算与增量一致；重算能修正被写坏的缓存；切歌结束旧会话；删书保留 `listening_session`；统计只算已结算行；倍速只落自己那一列；时长未知不覆盖已知时长。**待补（最大的一块）**：`Media3PlaybackController` 零测试——需要 Media3 `SimpleBasePlayer` 当 `Player` 替身才测得动，而「自动切歌不换倍速」这个真 bug 正是因为没有这层测试才只能在设备上撞见。另待补：消失文件的收听历史仍然保留 |
| 5 | **已覆盖**：首句/中间/末句窗口与不补位；空字幕表与「位置还在首句之前」（`currentIndex=-1`）；gap 内不跳动、越过 `start-250ms` 才切；seek 后窗口更新；5000 句二分正确性；换曲后重挂字幕流；导入解析（SRT/VTT/TSV、缺小时格式、一位小数毫秒、乱序重排、`end ≤ start` 整份拒绝、空文件失败）；单事务整本替换；删书连带删字幕且历史留着。**待补**：真实 ASR 产出的时间重叠/倒退（要 Phase 6 的模型输出）、长句换行的高亮目视（fixture 每句都短）|
| 6 | 分段绝对偏移（第 N 段不从 0 开始）；无标点长文本切句；异常时间戳清洗；取消/重试状态机；重转替换的原子性；`FakeAsrEngine` 端到端入库 |
| 迁移 | **已覆盖 MIGRATION_1_2**：`MigrationTestHelper` 对导出 `2.json` 校验（`recreateTableOnMigrationError=false`）、已有书籍与收听历史保留、FK 与索引存在。规则不变：每条新 Migration 都要有对应测试，`fallbackToDestructiveMigration` 保持不出现 |

## 6. 只能人工/真机验证的项（尚未执行，勿声称完成）

- [ ] 蓝牙耳机拔出/断开实际暂停（`ACTION_AUDIO_BECOMING_NOISY`）。**模拟器原理上做不到**：受保护广播 + `adb emu` 无耳机命令；代码路径存在但一行实测都没有。
- [ ] 其他 App 长期占用焦点（`AUDIOFOCUS_LOSS`）时的表现。来电（`LOSS_TRANSIENT`）已在模拟器实测，长期占用没造出场景。
- [ ] App 到后台 30 分钟持续播放不被系统杀（只验证了「切后台仍播 + 划掉任务后进程存活」，没有做长时间）。
- [ ] 2–3 GB RAM 真机上模型加载峰值 RSS、转录 RTF。
- [ ] 飞行模式下：已导入音频播放 + 已有字幕阅读全部可用（MVP 验收 10）。
- [ ] 撤销 SAF 授权后的降级提示与重新授权流程。**仍未做**：需要真机或 `appops`/系统设置里手动撤销。目前只有仪器化测试证明「授权缺失时：导入抛 `PermissionRevokedException` 且不写库、重扫把该文件夹标成 REVOKED 并跳过、其他文件夹照常扫」，以及 UI 提示「文件夹授权已失效，请重新授权」。
- [ ] 大目录（≥500 个 mp3）扫描耗时与内存。

### 6b. 已在模拟器实测、不再列为「只能真机」的项

- [x] 来电抢占焦点 → 暂停并结算会话、恢复不自动播放（FR-305）：`adb emu gsm call/cancel` + `dumpsys audio` + 位置三点采样，见 §4 Phase 4。
- [x] 后台播放不被静音、通知栏可控制（FR-304）：`dumpsys notification` 显示前台通知，通知的暂停/播放按钮实测有效。
- [x] 七句窗口跟随播放/拖动/倍速/切歌，点句跳到该句 `start_ms`（FR-701/702/705/706）：`uiautomator dump` 行数与文本 + 截图 + `run-as sqlite3` 读 `playback_state.last_position_ms` 复核，见 §4 Phase 5。
- [x] 冷启动续播位置与退出前差 ≤10s（FR-309）：真 `am force-stop` + 冷启动实测，差 3394 ms，见 §4 Phase 5。
- [x] 每首独立倍速在重开与自动切歌后都跟着换（FR-303）：设备实测 `dumpsys media_session` 的 `speed` 与 `playback_state.playback_speed` 对齐（顺带修掉「自动切歌不换倍速」的 bug，见 §4 Phase 5）。
- [x] 首次进播放页会弹通知授权（`POST_NOTIFICATIONS`）：模拟器实测到系统弹窗与 ALLOW 之后的通知栏控制。

复现步骤：安装 debug 包 → 按 `requirements.md` 对应 FR 编号逐条操作，结果与截图追加到本节。

## 7. 性能与资源自查项

- 进度写库频率（应 ≤1 次/10s）：**已实测**（Phase 4）——采 App 自己的 `updated_at_ms`（`elapsedRealtime`）差值，稳定在 ~10s；500ms tick 只推 `StateFlow`，不碰库。
- 字幕列表只在当前句变化时重组：**逻辑半边已证**（Phase 5：`PlayerViewModelTest.同一句内部的位置推进不重发窗口` 断言同一句内推进零次重发、跨句才发一次；链路上还有 `distinctUntilChanged` 与 `StateFlow` 的内容相等去重两道）。**帧重计数没测**：Compose recomposition 计数工具未接入，Phase 7 一并做。
- 转录期间主线程无阻塞：`adb shell dumpsys gfxinfo com.xinchen.echomind` 看 jank。（Phase 6）
- 不整本 PCM 进内存：`Debug.getRuntimeStat("mem.max")` 在转录中采样。（Phase 6）
