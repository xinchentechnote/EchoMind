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
| 远端 CI | `gh run list` / `gh run view <id> --log-failed` | 仓库 `https://github.com/xinchentechnote/EchoMind`（私有），工作流 `.github/workflows/ci.yml`：push/PR 跑 `assembleDebug` + `testDebugUnitTest` 并上传 APK。**判定成败要读 `--json conclusion`**，`gh run watch` 接了管道就拿不到退出码 |
| 仪器化测试（CI 覆盖不到） | 只能本地 `adb install -r` + `am instrument` | **CI 里没有模拟器**，那 52 项（48 项 Room/Repository + 4 项真实 URL 下载）仍然只有本机会跑（决定与代价见 `docs/environment.md` §8） |

### 2b. 设备自动化实测到的坑（Phase 3 / 5 / 6）

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
- **仪器化测试的方法名不能带空格**（Phase 6 实测）：`minSdk = 26` 时 D8 出的是 dex 039，`Space characters in SimpleName '…' are not allowed prior to DEX version 040` 直接把 `dexBuilderDebugAndroidTest` 打停。JVM 单测不经过 dex，所以 `app/src/test/` 里那批带空格的中文名字一直是合法的——只有写进 `androidTest` 才会炸。
- **`adb shell mkdir` 在 `/sdcard/Android/data/<pkg>/files/` 下建的子目录 App 读不到**（Phase 6 实测）：属主变成 `root`（`drwxrws--- root ext_data_rw`），App 侧 `File.isFile` 一律 false，表现是「文件明明 `ls` 得到，测试却报缺模型」。要把模型/素材放进 App 私有目录就用 `run-as`：`adb push <file> /data/local/tmp/x` → `adb shell run-as <pkg> sh -c 'cat /data/local/tmp/x > files/models/<id>/<file>'`。顺带好处是布局与生产 `filesDir/models` 一致。
- **`am instrument` 不清数据**（与 §2b 第一条对照）：只有 `connectedDebugAndroidTest` 会重装并清空，`adb install -r -t` + `am instrument -w` 这条路跑完整套件后 SAF 授权与库都在。
- **测音频内容必须自己数零字节**（Phase 6 实测）：书架那批「《三体》」素材帧体 99% 是 `0x00`，也就是静音。它能通过所有「长度类」断言（时长、进度、切歌、样本数），所以只要断言里出现 `peak`/`rms`，就必须同时准备**一份真音频**和**一份静音对照**。素材放 `getExternalFilesDir("asr-fixtures")`：读写自己的 external 目录不需要运行时权限，`adb push` 就能放。

## 3. 测试分层策略

| 层 | 位置 | 覆盖内容 |
| --- | --- | --- |
| 纯 JVM 单测 | `app/src/test/` | **已有（169 项）**：`AudioTreeWalker`（8）、`AudioImportPlanner`（9）、`TitleDeriver`（7）、`Format`（5）、`ShelfViewModel`（2）、**`SessionTracker`（22：有效次边界、倍速只放大消耗量、墙钟跳变、缓冲/暂停不计时、拖到末尾不完播、切歌先结算上一场）**、**`PlayerViewModel`（10：按记忆位置起播、已在播不重开、听完从头播、自动切歌换页头、收藏作用于正在播的那本、书不在书架不起播、字幕窗口跟随位置、切歌后换成另一套字幕、导入写进正在播的那本、同一句内部推进不重发窗口）**、**`SubtitleSynchronizer`（11：首句/中间/末句、空表、位置在首句之前、gap 迟滞、5000 句二分）**、**`TestSubtitleParser`（9：SRT/VTT/TSV、缺小时与一位小数毫秒、乱序重排、`end ≤ start` 拒绝、空文件失败）**、**`ModelCatalog`/`ModelVerifier`/`ModelManager`（10 + 9 + 16：清单合法性、SHA-256 测试向量、下载与断点状态机）**、**`PcmConversion`（11）/`SincResampler`（12：恒等、常数增益、通带与阻带实测频响、分块==一次性、生产调用形状、`reset()`、长度 `ceil(N·out/in)`）/`AsrChunkPlanner`（14）**、**`TokenTimingMapper`（14：秒→绝对毫秒、`endMs`=下一字起点、末字收块尾、取整只向下、数量不等/负值/NaN/越界/倒序一律抛 `Mismatch` 而不猜、同毫秒零长 token 合法）**。**待补**：`SentenceSplitter`（标点/无标点/异常时间戳）——属 Phase 6 第 5 项 |
| 仪器化测试 | `app/src/androidTest/` | **已有（70 项）**：Room DAO 读写与唯一索引（11）、`AudioRepositoryImpl` 的导入编排（9）、`PlaybackRepository`（21：会话行生命周期、flush 覆盖写不累加、结算的事务性、增量==重算、重算能修正被写坏的缓存、崩溃恢复标 `CRASHED`/幂等/短会话不计次、删书保留历史、倍速只落自己那一列、时长未知不覆盖已知）、**`Migration_1_2_Test`（3：v1→v2 结构对导出 JSON、历史保留、FK 与索引存在）**、**`SubtitleRepositoryTest`（4：索引连续、整本替换、摘要分辨来源、删书连带删字幕但留历史）**、**`ModelManagerNetworkTest`（4：真 URL 下载、重定向、404 不落地、校验失败）**、**`PcmDecoderTest`（4：真 MP3 解码 148s 素材、时长对账 0.22%、块连续与 ±1 幅度、静音对照、取消、非音频报错）**、**`VadSegmenterTest`（6：真模型分段且起点是 16k 样本号、段不重叠、`maxSpeechDuration` 强制切段生效、同一音频不同喂入块长结果一致、release 后不可用、主线程拒绝构造、缺文件/非 16k 给人话错）**、**`SherpaOnnxEngineTest`（8：设备侧按清单核对 243 MB、逐字时间落在块内且单调（单位=秒）、端到端解码→VAD→分块→识别跨块时间单调、>20s 与样本数不符都拒绝、缺模型报错点名文件、主线程拒绝装载、秒当毫秒立刻抛）**。**待补**：SAF 真实 URI 扫描、转录结果入库 |
| 手工设备验证 | 见 §6 | 耳机拔出、低内存、飞行模式；**本地推理已在模拟器实测**（§4 Phase 6 第 3 项终：真模型装载 + 端到端识别），焦点抢占与后台播放已在模拟器实测（§4 Phase 4），七句窗口与点句跳转已实测（§4 Phase 5） |

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

### 远端 CI 与 Phase 6 第 1 项（2026-10-15 04:18 ~ 04:36）

建仓库 → 配 CI → 取回 AAR，这一段全部是**远端与真机的实测**，没有推测。

| 动作 | 结果 | 凭据 |
| --- | --- | --- |
| 首次提交并推私有库 | `a7fb83a`（94 files / 10,662 insertions）→ `main`，`visibility=PRIVATE` | `gh repo view --json visibility` |
| 暂存前审计 | `git ls-files --others --exclude-standard` 共 92 个待入库文件，最大 48 KB；`git diff --cached --name-only` 里**没有** `local.properties` / `app/libs` / `*.aar` / `*.onnx` / 密钥；`app/schemas/{1,2}.json` 已入库 | 命令输出 |
| 换行符 | 新加 `.gitattributes`（`* text=auto eol=lf`、`*.bat text eol=crlf`），`gradlew.bat` 工作区保持 CRLF、仓库内是 LF（这就是 `eol=crlf` 的语义，不是丢了） | `git show :gradlew.bat \| od -c` |
| CI 第 1 次 | **失败**：`command -v sdkmanager` 在 runner 上找不到 | run 38630970985 |
| CI 第 2 次 | **失败**：SDK 校验过了，Gradle 报 `Plugin com.google.devtools.ksp:2.3.12 was not found` | run 38631245294 |
| 诊断（不猜） | 临时加探测工作流，在 runner 上打状态码矩阵：aliyun `gradle-plugin` / `public` 对 KSP marker **502**，Maven Central / plugins.gradle.org **GET=200 HEAD=200**；runner 镜像 Readme 确认已自带 `android-37.0` + `build-tools 37.0.0` | run 38631763968，用完即删 |
| CI 第 3 次 | **成功**：`assembleDebug` + 83 项 JVM 单测（`BUILD SUCCESSFUL in 5m36s`，job 6m19s），AAR 按钉住哈希取回、`sha256sum -c` 通过，APK artifact 25,405,781 B | run 38632644725，14 个步骤全 success |
| AAR 取回（本机） | `sherpa-onnx-static-link-onnxruntime-1.13.8.aar` 38,691,998 B / 3.0s / 13.0 MB/s；`shasum -a 256` = `b22c3fc1…`，与 `gh api` 读到的官方 `digest` **逐字符一致** | `app/libs/`（gitignore 内） |
| ABI 收窄 | `abiFilters` 改为只留 `arm64-v8a`（`adb shell getprop ro.product.cpu.abi` 实测 `arm64-v8a`） | `app/build.gradle.kts` |
| 接 AAR 后的本机构建 | `assembleDebug` **BUILD SUCCESSFUL 23s**，APK 16.1 MB → **40,794,372 B**；`unzip -l` 里 sherpa 只有 `lib/arm64-v8a/libsherpa-onnx-jni.so`（24,169,352 B）一份；`testDebugUnitTest` **BUILD SUCCESSFUL，83 tests / 0 failures**（从 XML 汇总，不看绿字） | 本机命令输出 |
| 模型取回（本机） | `model.int8.onnx` 243,371,218 B / 6.2s / **39.2 MB/s**，sha256 `f36a0433…` == HF LFS `oid`；`tokens.txt` 75,756 B（钉住 `59aba887…`）；`silero_vad.onnx` 643,854 B == 官方 `digest` | `app/libs/models/`（gitignore 内） |

**这一轮没做 / 做不了**：

- **没有跑过任何一次推理**。AAR 只是被链接进 APK 并打包了 `.so`，`asr/` 里还没有引擎代码，模型文件还躺在开发机的 `app/libs/models/`。所以 Phase 6 状态仍是 TODO，「ASR 端到端未完成」。
- CI 没有模拟器 → `assembleDebugAndroidTest` 之后那 48 项仪器化测试不在 CI 门禁里。
- CI 的仓库顺序按 `GITHUB_ACTIONS` 分岔，因此**本机跑不到 CI 那条解析路径**：本机模拟 `GITHUB_ACTIONS=true` 时 `repo.maven.apache.org` 对 HEAD 回 **403**（Gradle 正好先发 HEAD），这是本机特有的拦截，runner 上是 200。结论只能靠远端实测拿，别拿本机 curl 的 200 反驳它。

### Phase 6 第 2 项：模型清单 + 下载校验（2026-10-15 04:50 ~ 05:25）

`asr/ModelCatalog.kt`、`asr/ModelVerifier.kt`、`asr/ModelFetcher.kt`（`HttpFetcher`）、`asr/ModelManager.kt` 四个文件 + 35 条 JVM 单测，`ModelManager` 已挂进 `AppGraph`。
收尾时补了 `AndroidManifest` 的 `INTERNET` 权限与 4 条**打到真实 URL** 的仪器化用例（`androidTest/asr/ModelManagerNetworkTest.kt`）。

| 动作 | 结果 | 凭据 |
| --- | --- | --- |
| 第 1 次构建 | **失败**（编译期）：`encodeHex` 在当前 stdlib 上不存在；`lastPublishAtMs` 是可空局部变量、被闭包改写 → SMARTCAST_IMPOSSIBLE | `compileDebugKotlin` 输出 |
| 哈希实现改法 | 自写 `ModelVerifier.toHex`（查表），**不用** `String.format("%02x")`（受默认 Locale 影响，可能算出对不上的校验值）、**不用** `java.util.HexFormat`（Android 要 API 34） | `ModelVerifier.kt` |
| 第 2 次构建 | 主源码过了，测试源码编译失败 4 类（`specOf` 形参顺序、`HttpServer.port` 不存在、`RecordingFetcher` 缺省值、`assertTrue` 参数顺序） | `compileDebugUnitTestKotlin` 输出 |
| 第 3 次运行 | **118 tests / 4 failed**（从 `app/build/test-results/**/*.xml` 汇总，不看绿字） | 同上 |
| 失败 1（真问题） | `ModelCatalog.byId` 对未知 id 抛的是 `NoSuchElementException`（`List.first` 的默认行为），测试期望 `IllegalArgumentException` → 改成 `firstOrNull ?: throw IllegalArgumentException("模型清单里没有 id=…")`，让日志能直接说出是哪个 id | 测试 + 代码 |
| 失败 2（真问题） | `delete()` 成功后 `ModelStatus.error` 挂着「model.onnx 不存在」——删除本来就该让文件消失，把核对结论塞进 `error` 等于自造一条假错误 → `applyVerifications` 只在操作失败时写 `error`，缺失情况一律由 `ModelVerification.failures` 表达 | 测试 + 代码 |
| 失败 3、4（**测试写错了，不是代码错了**） | 断言「进度按 4/8/12/16 依次推给状态流」拿到空列表。原因是 `StateFlow` 是**合并式**的：假 fetcher 里那几次回调之间没有任何挂起点，收集器只能在下载结束后看到最后一个值。改成**在回调内部就地读 `statuses.value`**，才真正测到限流逻辑（`[4, 4, 12, 16]`：8 被 250ms 限流压掉、尾包必发） | `ModelManagerTest` 两条进度用例 |
| 最终本机构建 | `assembleDebug` **BUILD SUCCESSFUL**，APK 40,794,372 → **40,854,051 B**（新增 4 个 asr 类）；`testDebugUnitTest` **118 tests / 0 failures** | `ls -l` + 测试 XML |
| 清单二次复核 | `tokens.txt` 重新取回 75,756 B、sha256 `59aba8873a…cb6e6` **与清单一致**；这次 hf-mirror 返回 200 但 `url_effective` 落在 `/api/resolve-cache/…` → **它自己会重定向**，正是 `HttpFetcher` 手工处理 `Location` 的实测依据 | `curl -w %{url_effective}` + `shasum -a 256` |
| VAD 源复核 | **没做成**：05:10 起 `github.com:443` 本机连接超时（两次各 75 s），与 04:20 取 AAR 时 13 MB/s 相比是网络抖动。探测的两个 hf-mirror 备选路径都 **404**，所以**没有**写进清单（AGENTS.md §5 不许虚构地址）；清单里 VAD 仍是 GitHub 单源 | 命令输出 |
| 设备冒烟 | 覆盖安装 → `am start .MainActivity` → `mCurrentFocus` 是本 App、`uiautomator dump` 拿到书架文本（`搜索标题`/`继续上次收听`/`未转录`…）、`logcat` 无 `FATAL EXCEPTION`；`run-as ls files` 只有 `profileInstalled`，**没有** `models/` → `ModelManager` 构造确实不做任何 IO | `emulator-5554`，截图 `/tmp/echomind-p6a-launch.png` |
| **设备上的第一个真问题** | 写 FR-501 注记时翻 `AndroidManifest.xml`，发现**根本没有 `android.permission.INTERNET`** —— 前面所有下载代码在设备上必然失败，而 35 条单测一条都发现不了（它们不信权限，也不碰真实 socket）。补权限，并把「必须在设备上跑一次真 URL」写成本轮的验收动作 | `grep INTERNET` 无结果 → manifest 现列出该权限 |
| 权限真的生效 | `dumpsys package com.xinchen.echomind` 的 `requested permissions` 现在含 `android.permission.INTERNET`（与 `POST_NOTIFICATIONS`、`FOREGROUND_SERVICE*` 并列） | 覆盖安装后设备输出 |
| 设备真实下载 | `asr/ModelManagerNetworkTest` **4 tests / OK（`Time: 2.258`）**：拿清单里 `tokens.txt` 的**真实 hf-mirror URL**、用与 `AppGraph` 同一条代码路径（`ModelManager` + `HttpFetcher` + `AndroidTimeSource`）落到 `filesDir/instrumented-models/paraformer-zh-2023-09-14-int8/`。断言分两路：① 长度 == 75,756 B、② 测试里**再独立算一次** `ModelVerifier.sha256` == 清单值（不复用 `download()` 内部的比对结果）；`download-provenance.properties` 含 `.url/.bytes/.sha256/.checksum=pinned-by-first-download`；第二次 `download()` 不重取（`lastModified` 不变）；不存在的远端文件 → `ModelDownloadException` 且消息含 `HTTP 404`，目录里既不留文件也不留 `.part`；空目录 `refresh()` 给出 `Missing` 而不是崩 | `am instrument -w -e class com.xinchen.echomind.asr.ModelManagerNetworkTest` |
| Android 的 `HttpURLConnection` 是谁 | 失败堆栈里是 `com.android.okhttp.internal.huc.HttpsURLConnectionImpl` → **设备上的 `HttpURLConnection` 底层是 OkHttp**，与 host JDK 那份实现不是同一套代码。这就是「JVM 单测全绿 ≠ 设备能下载」的机制性原因，也是这 4 条仪器化用例存在的理由 | instrument 输出堆栈 |
| VAD 的设备用例 | **本轮移除，并且不声称验证过**：`github.com:443` 从设备侧同样连不上 —— `SocketTimeoutException: failed to connect to github.com/20.205.243.166 (port 443) from /10.0.2.15 (port 54514) after 15000ms`，与宿主机 05:10 起的现象一致。留着它只会因上游不可达把套件变红（首轮确实是 5 tests / 1 failed，失败原因就是这个超时）。VAD 在设备上的真实取回**仍未验证**，它依旧是 GitHub 单源风险（§3） | 首次 instrument 完整输出 |
| 换权限后数据存活 | `adb install -r`（带新权限）之后：`run-as ls databases` 仍有 `echomind.db`(+`-shm`/`-wal`)，书架 UI 显示 `deep - 深层`、`已播到 1:29 / 1:29`、`37 分钟前`、`未转录`，`logcat -b crash` 为空 → 权限变更不是破坏性升级，收听历史与 SAF 授权没被清掉。顺手删掉仪器化测试留在 `filesDir` 的 `profileInstalled` 与 Phase 5a 遗留的 `migrate_v1_to_v2.db.lck` | 截图 `/tmp/echomind-p6a-internet.png`、`/tmp/ui-p6a.xml` |
| 仪器化全套 | 完整 `am instrument`（不带 `-e class`）**OK (52 tests)，`Time: 5.095`** —— 48 项旧用例 + 这 4 项新用例，覆盖安装 `install -r -t` 之后跑的，SAF 授权与既有数据库都没清 | instrument 输出 |
| 收口复跑 | `:app:testDebugUnitTest --rerun` → **118 tests / 0 failures / 0 skipped**（仍从 `app/build/test-results/**/*.xml` 汇总）；APK 40,854,051 B | 本机命令输出 |

**这一轮没做 / 做不了**：

- **243 MB 那份模型没在设备上取回过**。设备上跑通的是 75 KB 的 `tokens.txt`——权限、TLS、重定向、`filesDir` 写入与改名、`.part` 清理这些**机制**都验证到了，但整份 int8 的下载时长、内存占用（流式 SHA-256 在低内存设备上的表现）与进度限流的实际观感仍属空白。它属于「设置页里点下载」那次交付（Phase 6 第 6 项 / Phase 7），届时要有 `adb` 侧的字节数与耗时凭据。
- **`silero_vad.onnx` 从设备上一次都没取回过**（GitHub 不可达，见上表）。清单值仍是 04:20 宿主机 curl + 官方 `digest` 比对的结果。
- **本地导入路径未实现**（§7 第 2 条），完全离线的首次准备现在没有可用通道。
- 没有任何 UI 入口消费 `ModelManager.statuses`，`AppGraph.modelManager` 暂时只有构造它的代码与仪器化测试用到。
- `HttpFetcher` 不做 HTTP Range 续传：断线后同一个文件从头重下，这是取舍不是遗漏（见 `docs/architecture.md` D24）。
- 设备用例依赖外网可达，本机与 CI 都**没有**把它们放进门禁（CI 只跑 `assembleDebug` + `testDebugUnitTest`）。所以它们是本机一次性凭据，不是每次提交都守得住的回归网。

### Phase 6 第 3 项（前半）：PCM 转换 / 抗混叠重采样 / ≤20s 分块（2026-10-15 05:40 ~ 06:05）

`domain/asr/PcmConversion.kt`、`domain/asr/SincResampler.kt`、`domain/asr/AsrChunkPlanner.kt` + `domain/model/AsrModels.kt`，
共 36 条 JVM 单测（`SincResamplerTest` 11、`PcmConversionTest` 11、`AsrChunkPlannerTest` 14）。**全部是纯 Kotlin**：
`grep` 确认 `domain/` 下没有任何 `android.*` / `androidx.*` / Media3 / Room 的 import（AGENTS.md §3 的前提）。

| 动作 | 结果 | 凭据 |
| --- | --- | --- |
| 为什么不用现成的重采样 | 实测 `media3-common-1.11.1.aar` 的 `classes.jar` 里**类路径含 `resampl` 的条目 0 条**（`AudioResamplingAudioProcessor` 已不在该产物中），`media3-decoder-1.11.1.aar` 只有 `Buffer/Decoder/SimpleDecoder` 一族、**没有 `MediaDecoder`** → 只能用框架 `MediaExtractor`+`MediaCodec` 解码、采样率自己搬 | `unzip -l classes.jar \| grep -ci resampl` = 0；`androidx/media3/decoder/*` 类名清单 |
| 首轮运行 | **34 tests / 3 failed**（看 `app/build/test-results/**/*.xml`，不看绿字） | 同上 |
| 失败 1（**真问题，改的是生产代码**） | `同采样率必须是恒等变换`：原来 `isFinal` 那块允许「中心已越过数据末尾」的输出靠零填充算出来，于是 44100→16000 产出 16001~16016 个、同采样率下长度与数值都不再不变。产出判据改成**只有「中心样本还在数据里」**（非 final 还要求整个抽头窗在数据里），恒等与精确长度同时恢复 | `SincResampler.resample` / `outputsProducible` |
| 失败 2、3（**测试写错，不是代码错**） | ① `assertEquals(span.sampleCount, plans.sumOf { … })` 里 Int 与 Long 装箱后走 `(Object,Object)` 重载，永远不等 → 显式 `.toLong()`；② 把 `msOf(44_100L, 44_100)` 期望成 `1L`，而 `msOf` 是「样本→毫秒」，正确值是 `1_000L` → 改断言 | 两条测试改动 |
| 二轮运行 | **34 tests / 1 failed**：恒等用例在输入**恰好为 `0.0f`** 的两个样本上失败，逐元素差 `2.9e-33`（系数在整数栅格上只剩中心抽头 1.0，旁瓣是 `sin(π·整数)` 的浮点残差）→ 不是滤波错，是 `contentEquals` 对零没有相对容差。改成「最大逐元素差 < 1e-5」并把实测值写进断言消息 | 用 Python 复刻同一套 float32 运算定位到只有 i=208/419 两个样本不一致，再改断言 |
| 频响实测（不是估算） | 临时探针单测（1 秒单频正弦、掐头去尾各 64 样本做相干 DFT，跑完即删）：48k→16k 在 1/3.5/5 kHz = 0.0/0.0/−0.09 dB，6.5 kHz −1.33，7 kHz −2.38，7.9 kHz −5.56；**会折进输出 4 kHz 的 12 kHz 压到 −69.3 dB**，20 kHz −91.0 dB；44.1k→16k 同形（5 kHz −0.05、7 kHz −2.16、12 kHz −77.9）。据此把测试阈值从「> 40dB」提到「> 60dB」 | 探针 `system-out`（XML 报告），已删的 `SincMeasurementProbeTest.kt` |
| 为什么先前写成 −40 dB | 我最初用一份 Python 复刻算出 −40.4 dB 并打算照抄进文档；**混叠 bin 取错**（拿 12 kHz 当输出 bin，而它折到 4 kHz）加上探针窗宽不同，两次复刻给出 −40/−54/−69 三个值。最后是在真代码上跑出来的 −69.3 才写进 KDoc。教训：复刻脚本的数不能当凭据 | 本表上两行 |
| 边缘效应（如实记录） | 常数 0.25 输入：中段逐样本 == 0.25（单测断言 <1e-4），**首样本 0.170、末样本 0.267** —— 头尾各 `T=16` 个输出（≈±1ms）的抽头窗伸出数据边界要补零，有限窗 FIR 的固有现象，不是 bug。`docs/asr-design.md` §5 用它解释「VAD 段边界有 ±1ms 量级的不确定」 | 同一探针输出 |
| 输出长度的可预测式 | 实测 `out.size == ceil(N × outRate / inRate)` 对 N = 1/2/33/100/4410/44100/97600 全部成立（先前猜的 `floor(N·out/in)+1` 在整除时多给 1 个，已弃用），钉成单测。`PcmDecoder` 要靠它做「样本数 ÷ 采样率 == 时长」对账 | `SincResamplerTest.输出样本数正好等于…` |
| 分块 == 一次性 | 377 样本一片喂入与整段一次喂入**逐元素相同**（`reset()` 用例同样比对新建实例） | `SincResamplerTest` |
| 转换的声道混合 | 双声道按**均值**下混：等幅反相 → ≈0；同相 → 原值；单侧非零 → 幅度减半（不是取左声道）。16-bit 小端字节序、`-32768 → -1.0f` 缩放、尾字节如实报出、3 声道均值，共 11 条用例 | `PcmConversionTest` |
| 分块规划 | 单块 ≤ `AudioFormats.ASR_MAX_SEGMENT_MS`(20s)、第 N 块起点用**全局样本下标**换算（180 块连续 20s 段实测 `endMs == 3_600_000`，即 60 分钟零漂移）；均分使各块长度最多差 1 样本；空段/负起点段跳过；`maxChunkMs = 0` 直接 `require` 拒绝 | `AsrChunkPlannerTest` 14 条 |
| 本机最终构建 | `assembleDebug` **BUILD SUCCESSFUL**，APK 40,854,051 → **40,873,192 B**；`testDebugUnitTest --rerun` **154 tests / 0 failures / 0 skipped**（118 → 154，本轮 +36） | `ls -l` + 测试 XML |

**这一轮没做 / 做不了**：

- **一个真实字节都没解过**。这三个类是纯数学，输入全靠合成信号与手搓字节；`PcmDecoder`（`MediaExtractor` + `MediaCodec`）还没写，
  所以「MP3 → 这些函数的输入」那一步完全空白，重采样器也从没吃过真书解音频。下一项就是它，并要在 `emulator-5554` 上实测样本数与时长对账。
- **没有仪器化测试**：这三个类不 import Android 类型，按 AGENTS.md §6「纯逻辑走 JVM 单测」处理；但它们的上游（解码）与下游（VAD/识别）都要设备验证。
- **设备上的耗时没测**：宿主机 JVM 上 1,000,000 样本 44.1k→16k 是 6 ms（JIT 热身后），arm64 真机要慢多少未知，等 Phase 6 端到端一起量。
- 通带 7 kHz 处 −2.2 dB 的跌落是 33 抽头的物理限制，本轮**不**打算用更长的滤波器去修（理由见 `docs/architecture.md` D26）。
- `SpeechSpan` 目前没有生产者：VAD 段落 → `SpeechSpan` 的转换属 `VadSegmenter`（下一项），而且 `SpeechSegment.start` 的单位（样本还是毫秒）**仍未在设备上验证**。

### Phase 6 第 3 项（后半）：PcmDecoder 真解码 + 设备实测（2026-10-15 06:08 ~ 06:25）

`asr/PcmDecoder.kt`（框架 `MediaExtractor` + `MediaCodec` 同步模式，串起 `PcmConversion` 与 `SincResampler`）
+ 仪器化 `asr/PcmDecoderTest.kt` 4 条 + `SincResamplerTest` 补 1 条生产调用形状。**这是本项目第一批真实音频字节**：
上一轮的 36 条单测吃的全是合成信号与手搓字节。

| 动作 | 结果 | 凭据 |
| --- | --- | --- |
| 首次编译 | 2 个 `Unresolved reference 'ENCODING_PCM_16BIT'`：那个常量不在 `MediaCodec` 上，在 `android.media.AudioFormat`。同时把私有的 `"pcm-encoding"` 字面量换成 `MediaFormat.KEY_PCM_ENCODING` | `javap -classpath android-37.0/android.jar` 里两个符号都在（`AudioFormat.ENCODING_PCM_16BIT`、`MediaFormat.KEY_PCM_ENCODING`），不靠记忆 |
| 补一条 JVM 用例：生产调用形状 | 既有分块用例把**最后一块**标成 `isFinal=true`，而 `PcmDecoder` 做不到（EOS 标志可能挂在空缓冲上），真实形状是「每个数据块都 `isFinal=false` + 结尾一次**空输入** `isFinal=true`」。补这条后先跑：**155 tests / 0 failures** —— 流式实现本来就自洽，但这句话此前没有测试撑着 | `SincResamplerTest.生产调用形状…` |
| 仪器化 APK 打不过 dex | `dexBuilderDebugAndroidTest FAILED`：`Space characters in SimpleName '取消：sink 拒绝一个块之后立刻停…' are not allowed prior to DEX version 040`。`minSdk = 26` → D8 出 039 dex，**方法名里的空格非法**（JVM 单测不经过 dex，所以 118 → 154 那批带空格的中文名字一直没暴露这条）。仓库里现存仪器化名字恰好都没有空格，改名为 `回调返回false就立刻停手已交付样本数仍然自洽` 后即通过 | 完整报错文本；改名前后两次 `assembleDebugAndroidTest` |
| 素材放哪儿 | App 没有存储权限（走 SAF），所以 `Uri.fromFile("/sdcard/Music/…")` 读不到。改成放进 **App 自有 external files 目录**（`getExternalFilesDir("asr-fixtures")`，读写自己那份不需要任何运行时权限，比为了测试给 App 加 `READ_MEDIA_AUDIO` 干净）：`adb shell mkdir -p` + `adb push`。缺素材时**直接 fail 并打印放文件的命令**，不用 `assumeTrue` 静默跳过 | 4 条用例的 `PcmDecoderTest` 日志 |
| **首轮设备实测：4 条里 2 条红，`peak=0.0`** | 块连续性、时长对账、样本数全过，**只有幅度是零**。宿主机逐字节数 0x00 占比：`01 - 三体.mp3` 与 `5.MP3` 的帧体 **99.0% 是 0x00**，md5（`8952509d…`/`362b35cb…`）跟 `/Users/xinchen/Documents/music` 里 21 首都对不上 → **Phase 3~5 用的这批「书解」素材本身就是静音占位 MP3** | Python 统计帧体零字节占比 + md5 比对 |
| 这件事的后果（如实说） | 书架、时长读取、进度恢复、倍速、切歌、字幕窗口的结论**不受影响**——它们只看长度与状态，静音音频是完全合法的输入。但**音频内容从未在设备上被验证过**，转录精度更不能拿静音去测。`PcmDecoder` 的 `peak > 0.05` 断言正是为这种「读得到文件却解出全零」的假成功写的，它第一次跑就抓到了 | 本表上一行 |
| 换真素材 + 留静音做反向对照 | 真素材用用户自己的 `那些花儿.mp3`（2,381,693 B，帧体仅 **1.4%** 是 0x00）；静音那份保留成一条**对照用例**：它必须解出「长度对得上但逐样本全零」，用来证明 `peak=0` 来自文件而不是解码链路把数据洗平了 | `PcmDecoderTest.静音素材解出全零但长度照样对得上` |
| 真解码实测（4 条全绿） | `mime=audio/mpeg`、解码器上报 `srcRate=44100`、真素材 `ch=2`（**均值下混第一次跑在真实立体声上**）、`peak=0.6585692`、`rms=0.08412`；容器 148,303 ms vs 解出样本换算 148,636 ms → **偏差 0.22%**；`outSamples=2,378,188`、581 块（前 580 块都是满块 4096）；`maxCodecBufferBytes=4,608` = 文件的 **0.002 倍** → 「峰值内存与音频总长无关」（NFR-05/07）这句话第一次有数字 | `adb logcat -s PcmDecoderTest` 全行 |
| 取消路径 | `blockSize=1024`、第 4 块返回 `false` → `cancelled=true`、`outputSamples=3072`（正好 3 块）、绝对起始样本仍然首尾相接、80 ms 就停手 | 同上 |
| 非音频输入 | 写一个 `.txt` 喂进去 → `PcmDecodeException("打不开音频容器（file:///…txt）：IOException Failed to instantiate extractor.")`，错误里带 URI 和底层异常类名，不静默产出 0 样本 | 同上 |
| RTF 与一次纯调度改进 | 先前「一进一出 + 每轮 10 ms 超时」是**等待 bound** 的：真素材首轮 27,974 ms（RTF 0.1886）。改成「贪心喂入 + 本轮有进展就非阻塞轮询输出」→ 24,557 ms（RTF 0.1656）；静音那份 5,458 → 3,680 ms（0.1824 → 0.1230）。**产出完全没变**（`outSamples 2,378,188`、`blocks 581`、`peak/rms` 逐项相同）才敢说它是调度而不是算法改动。兜底 stall 上限 20,000 → 3,000 轮（≈30 s 毫无进展就放弃，不再可能挂 200 s） | 两轮 logcat 的 `wallMs/rtf` 对比 |
| 本轮最终构建 | `assembleDebug` **BUILD SUCCESSFUL**，APK 40,873,192 → **40,919,888 B**；JVM `testDebugUnitTest --rerun` **155 / 0 / 0**；仪器化整套件 `am instrument -w` **OK (56 tests)**（48 Room/Repository + 4 网络 + 4 解码），耗时 39.6 s | 测试 XML + `ls -l` + instrument 输出 |

**这一轮没做 / 做不了**：

- **`PcmDecoder` 还没有生产调用方**：只有测试在调它。`VadSegmenter`、`SherpaOnnxEngine`、`TranscriptCoordinator` 都没代码，
  所以「解码 → VAD → 逐段识别 → 字幕」这条链目前只到第二环。
- **只验过 MP3**：`audio/mp4a-latm`（m4a）、`audio/flac` 这些容器一份都没测；`readFormat` 里「非 16-bit 输出编码就抛」那条分支
  是防御性代码，在这台模拟器上从未触发（MP3 解码器给的就是 16-bit）。
- **RTF ≈ 0.17 只是模拟器数字**：10 小时的书光解码就要 ~1.7 小时。真机数字未知，而 ASR 推理本身才是主成本，目前**一个推理耗时都没测到**。
- **缺真语音素材**（本轮新暴露的阻塞）：转录精度需要一段 3~10 分钟中文书解，机器上现在只有静音占位与歌曲。要么用户提供，要么等模型跑通后现场录一段。
- `SpeechSegment.start` 与 `timestamps` 的单位**仍未验证**——这条从上一轮原样带过来，要等 `SherpaOnnxEngine` 落地才能钉死。

### Phase 6 第 3 项（终）：silero VAD + 第一次真推理（2026-10-15 06:30 ~ 07:25，含复跑）

`asr/VadSegmenter.kt`（流式窗缓冲 + `VadUtterance`）、`asr/SherpaOnnxEngine.kt`（`OfflineRecognizer`，文件系统路径、`assetManager = null`）、
`domain/asr/TokenTimingMapper.kt`（秒 → 绝对毫秒，纯 Kotlin）+ 14 条 JVM 单测 + 6 条 VAD 仪器化 + 8 条推理仪器化。
**上一轮遗留的两个「不许猜」的单位问题在这一轮被真数据钉死，ASR 第一次产出可用于字幕的逐字时间。**

| 动作 | 结果 | 凭据 |
| --- | --- | --- |
| 先把探针跑一遍（不交付） | 写完 `SherpaFirstInferenceProbeTest` 直接在设备上量：`段0 start=223904`、`timestamps[0]=13.599999 … last=19.32`、`tokens=13 timestamps=13 durations=0`。探针里那条 `start % 512 == 0` 断言**跑红了**——它是我猜的判据，不是模型的判据 | logcat `SherpaProbe`；探针文件本轮结束时删除，结论改由正式测试守护 |
| **单位结论①** `SpeechSegment.start` | **16 kHz 下的样本下标**，且落在 1 ms（= 16 样本）网格上。判据：60 s 素材喂进 VAD 得 962,560 样本 = 60,160 ms，而 9 个 start 全是六位数量级（`223904 … 944288`），当毫秒解释比整篇长 15 倍 → 只能是样本号；`start % 16 == 0` 逐段成立，`% 512` 不成立（说明它对齐到毫秒而不是窗边界） | `VadSegmenterTest.真素材流式分段起点是16k样本下标且段与段不重叠`（断言 `%16==0` 且 `start+len ≤ 总样本`） |
| **单位结论②** `timestamps` | **秒**，相对这一块音频。19.2 s 的块给出 13 个 token、首个换算出 **13,580 ms**（原值 ≈13.58 s；更早那轮探针在 20.224 s 的块上读到 13.599999~19.32，两个数字互相印证）。当毫秒解释整条轴会往前错 1000 倍、立刻出块。`durations` 长度实测 **0** → Paraformer 不给每字时长，所以 `endMs` 只能取**下一个字的 `startMs`**（末字收到块末尾）：起点仍是模型原值，不是均分伪造 | `SherpaOnnxEngineTest.逐字时间落在块内且单调单位是秒`；`TokenTimingMapperTest.每个字的结束就是下一个字的开始…` |
| 反向对照：把秒当毫秒会怎样 | 正式测试里把已换算的毫秒再当秒喂一次 → `TokenTimingMapper` 立刻抛 `Mismatch`（越界），而不是夹回块内产出漂移字幕 | `SherpaOnnxEngineTest.把秒当成毫秒喂进换算器会立刻抛错…` |
| 模型怎么摆到设备上 | 先按上一轮的做法 `adb shell mkdir` + `adb push` 到 external 目录 → **App 读不到**：`adb shell mkdir` 建的子目录属主是 `root`（`drwxrws--- root ext_data_rw`），App 侧 `file.isFile` 直接返回 false，6 条 VAD 测试里 5 条报「缺少模型」。改用 `run-as`：`adb push` 到 `/data/local/tmp` → `run-as com.xinchen.echomind sh -c 'cat … > files/models/<id>/<file>'`，于是文件归 App 自己，**目录布局也与生产 `AppGraph.modelsRoot = filesDir/models` 完全一致** | 首轮 instrument 输出（5 条 fail 的报错文本）+ `run-as … ls -l files/models` |
| 设备侧核对三个模型 | `ModelVerifier.verify(paraformer, filesDir/models)` → OK；目录合计 **243,446,974 B** 与清单 `totalBytes` 逐项相等；`silero_vad.onnx` 643,854 B 亦按清单核对通过。**注意：这证明的是「搬上来的这份与清单一致」，不等于「设备能从网络下载它」** | `SherpaOnnxEngineTest.设备侧按清单复核识别模型的三个文件` 日志行 |
| 第一次真推理（单块） | 19.2 s 真音频 → `text=「那片笑声让我想起我的那些年」`、`tokens=13`、首字 13,580 ms、末字收到 19,200 ms、`decodeMs=2749` → **RTF 0.1432**；引擎装载 `OfflineRecognizer` 实测 **4,906 ms**（后台线程，主线程构造被 `check` 拒掉） | logcat `SherpaEngineTest` |
| 第一次端到端（解码 → VAD → 分块 → 识别） | 前 60 s 素材：VAD 出 9 段（`lenList=[31168 … 18272]`，最长 118,720 样本 = 7.4 s，无一超 20 s），逐块识别取前 6 块 → **58 个 token**、跨度 14,134~49,446 ms、跨块时间单调、`RTF(纯识别)=0.1313`、`RTF(纯VAD)=0.0170`（1,021 ms / 60,160 ms）。识别出的原文（节选）：`在我生命每一个角落静静为我开着` / `我曾以为我会永远守在他身旁` / `今天我们已经离去在人海茫茫` / `他们都老了吧` | 两条测试的 logcat 全行 |
| 切块边界会影响识别结果（如实记录） | 同一段歌词：整块 20 s 喂进去给「那片笑声让我想起我的那些**年**」，VAD 切成 13,994~15,942 ms 与 16,234~20,166 ms 两块则给「**偏**笑声让」「想起我的那些**话**」。**逐字时间仍然有效**（都在自己块内、跨块单调），但文本与断句随块边界变化 → 字幕按块拼句时不能假设「块边界 = 句子边界」，这正是 Phase 6 第 5 项 `SentenceSplitter` 要处理的 | 上面两行的日志对比 |
| 一条测试自己错了 | `同一素材不同解码块长得出一模一样的分段` 首轮红：`4096 → (259744, 63840)` vs `1024 → (259744, 60768)`。根因**不在 VAD**：测试用「解到第 20 秒就停」当输入，两种块长的停止点相差 3,072 样本（323,584 vs 320,512），最后一截是 `flush()` 出来的，自然不等长。改成**同一份 `FloatArray`、只换每次 `push` 的长度**（4096 / 1024 / 3000（与 512 不整除）/ 单块）后四者完全一致 → 窗缓冲实现是对的，`%512` 那类「按窗对齐」的猜想确实是错的 | 失败断言原文 + 改名后 `OK (6 tests)` |
| VAD 的两条边界事实 | ①`minSpeechDuration` 不约束 `flush()` 出来的最后一截（本轮最长素材的第一段 1,948 ms、最后一段 1,142 ms），断言只对 `dropLast(1)` 成立；②`maxSpeechDuration=20s` 的强制切段**实测生效**（9 段全部 ≤20 s），所以 `AsrChunkPlanner` 是第二道保险而不是摆设 | `VadSegmenterTest` 两条断言 |
| 引擎的三条护栏 | ①超 20 s 的块（21 s，故意用 `maxChunkMs=30_000` 绕过规划器）→ `AsrInputException`，不悄悄截断；②`samples.size ≠ chunk.sampleCount` → `AsrInputException`；③`modelsRoot` 下没有模型 → `AsrModelNotReadyException`，消息里点名 `model.int8.onnx 不存在`、`tokens.txt 不存在` 并给出目录；④`release()` 幂等，release 后再 `push`/`recognize` 立刻抛，不会 use-after-free | `SherpaOnnxEngineTest` 4 条 + `VadSegmenterTest.release之后再喂要立刻报错` |
| 主线程护栏真的会拦 | 两条测试都用 `runOnMainSync { … }` 构造 → 都在 native 加载之前抛 `IllegalStateException`（VAD 与识别器各一条） | `VadSegmenterTest.主线程构造要直接拒绝`、`SherpaOnnxEngineTest.主线程装载模型要直接拒绝` |
| `AudioFormats` 补常量 | `VAD_WINDOW_SAMPLES/THRESHOLD/MIN_SILENCE_SEC/MIN_SPEECH_SEC/MAX_SPEECH_SEC` + `ASR_FEATURE_DIM=80`/`ASR_DITHER=0f`/`ASR_NUM_THREADS=2`（都是设备上装载成功的那组值），并把清单里的文件名收进 `ModelCatalog.PARAFORMER_MODEL_FILE` 等常量，避免 `asr/` 两处写死同一字符串（AGENTS.md §3） | `grep -c` 确认无重复字面量 |
| 本轮最终构建 | `assembleDebug` **BUILD SUCCESSFUL**；JVM `testDebugUnitTest --rerun` **169 / 0 failures / 0 skipped**（16 个类，从 XML 汇总脚本读出）；仪器化整套件 `am instrument -w` **OK (70 tests)**，耗时 104.4 s（其中推理 29.6 s、VAD 13.2 s） | 测试 XML 汇总 + instrument 输出 |
| **复跑整套件（同日 07:19，为了看结果稳不稳）** | 结论：**内容完全可复现，耗时不可**。复现的部分——58 个 token、跨度 14,134~49,446 ms、9 段 `startList=[223904 … 944288]` 与 `lenList` 逐项相同、块文本逐字相同（`偏笑声让` / `想起我的那些话` / …）、`durations` 仍为空、模型目录仍 243,446,974 B、整套件仍 OK (70 tests)（81.8 s）。抖动的部分——`decodeMs` 端到端 3,922 → 2,467（RTF 0.131 → **0.083**）、单块 2,749 → 2,564（0.143 → **0.134**）、VAD 1,021 → 1,211 ms（0.0170 → **0.0201**）、装载 4,906 → **4,914 ms**。**所以文档里所有 RTF 都只当量级看，别当指标**（AGENTS.md §5 的诚实性要求也适用于我自己的数字） | 第二次 `adb logcat -d -s SherpaEngineTest:I VadSegmenterTest:I` 全行 |
| APK 尺寸不是逐字节可复现的 | 同一天三次构建读出 40,889,235 / 40,951,147 / **40,859,995 B**（`--rerun-tasks` 全量重建后），相差最大 ~91 KB。代码在这几次之间只有注释与文档改动，所以差异来自打包环节（未 minify；具体成因未查，不在本轮范围）。**结论：以后只按「~40.9 MB」记量级，不再把字节数当回归判据** | 三次 `ls -l app/build/outputs/apk/debug/app-debug.apk` |

**这一轮没做 / 做不了**：

- **还没有生产调用方**：`VadSegmenter`/`SherpaOnnxEngine`/`TokenTimingMapper` 只被测试调用，`AppGraph` 里没有它们，
  `transcript_job` 表与 `TranscriptCoordinator` 还没写 → **「点一下就能转录并出字幕」仍未交付**（Phase 6 第 4、6 项）。
- **识别文本没做人工抽查**：素材是歌曲《那些花儿》，本轮只证明了「时间落在块内、跨块单调、RTF 可行」，
  **没有**对 10 处字幕做时间对齐误差抽查（FR-506 要求 ±ms 分布）。需要一段真中文书解才能测精度，机器上仍然没有。
- **只测了 60 s 内的音频**：一小时书的峰值内存、`filesDir/models` 被 `ModelManager.delete()` 之后的重下载路径、
  推理中途取消，都没有覆盖。
- **模型是 `adb` 搬上去的**：`model.int8.onnx` 与 `silero_vad.onnx` 至今没在设备上通过 HTTP 取回过（GitHub 不通、243 MB 走 hf-mirror 未测）。
- 内存：模拟器 2.5 GB（可用 ~1.2 GB），本轮把 VAD + 识别器同时装过一次（端到端测试）而没测峰值 RSS；真机 2 GB 机器上的表现未知。


| Phase | 用例 |
| --- | --- |
| 3 | **已覆盖**：递归 3 层子目录 + 扩展名白名单 + 深度/数量截断；连续扫描不产生新记录；同一 URI 重复出现只算一次；size/mtime 变化 → CHANGED 且字幕标 EXPIRED、手改标题不被覆盖；文件消失 → MISSING 且不删记录；文件原样回来 → MISSING 翻回 PRESENT；跨文件夹重复文件留在原文件夹；授权无法持久化 → 导入失败不写库；重扫跳过授权失效文件夹但继续其他文件夹；时长读取失败 → `duration_read_failed`；书架 tab/搜索状态与查询同源。**待补（依赖 Phase 4 的会话表）**：消失文件的收听历史仍然保留 |
| 4 | **已覆盖**：4.9s 不计次 / 5.0s 计次；缓冲+暂停+焦点丢失不计时；2x 播 10min → `listened_ms=600000`、`consumed_audio_ms=1200000`；拖到末尾不完播；1.5x 听完 → 完播；两次听完 → 完播 2；崩溃恢复不重复累计（含幂等与「不结算之后的新会话」）；重算与增量一致；重算能修正被写坏的缓存；切歌结束旧会话；删书保留 `listening_session`；统计只算已结算行；倍速只落自己那一列；时长未知不覆盖已知时长。**待补（最大的一块）**：`Media3PlaybackController` 零测试——需要 Media3 `SimpleBasePlayer` 当 `Player` 替身才测得动，而「自动切歌不换倍速」这个真 bug 正是因为没有这层测试才只能在设备上撞见。另待补：消失文件的收听历史仍然保留 |
| 5 | **已覆盖**：首句/中间/末句窗口与不补位；空字幕表与「位置还在首句之前」（`currentIndex=-1`）；gap 内不跳动、越过 `start-250ms` 才切；seek 后窗口更新；5000 句二分正确性；换曲后重挂字幕流；导入解析（SRT/VTT/TSV、缺小时格式、一位小数毫秒、乱序重排、`end ≤ start` 整份拒绝、空文件失败）；单事务整本替换；删书连带删字幕且历史留着。**待补**：真实 ASR 产出的时间重叠/倒退（要 Phase 6 的模型输出）、长句换行的高亮目视（fixture 每句都短）|
| 6 | **已覆盖（2026-10-15，纯逻辑层 → 解码层 → VAD → 真推理）**：分段绝对偏移（第 N 段不从 0 开始、180 段零漂移、块间不重叠不留缝）；>20s 段均分且各块长度最多差 1 样本；空段/负起点段跳过；`msOf`/`samplesOf` 换算与取整；16-bit LE 字节序与 ±1 缩放；多声道均值下混（反相 → ≈0、单侧 → 减半）与尾字节如实报出；重采样恒等性/常数增益/通带与阻带频响/分块==一次性/生产调用形状/`reset()`/输出长度 `ceil(N·out/in)`；**设备上真 MP3 解码**（148s 立体声素材：样本数对账偏差 0.22%、581 块首尾相接、幅度落在 ±1、`peak=0.659`、单缓冲上界 4,608 B 与文件长度无关、取消只交付 3 块、非音频文件给人话异常、静音素材解出全零）；**设备上真 VAD**（9 段、起点=16k 样本下标且 `%16==0`、段不重叠、`maxSpeechDuration` 生效、不同喂入块长结果一致）；**设备上真推理**（逐字 `timestamps` 单位=秒、落在自己块内、跨块单调、`durations` 空所以 `endMs` 取下一字起点、>20s 与样本数不符被拒）。**待补**：无标点长文本切句、异常时间戳清洗、取消/重试状态机、重转替换的原子性、转录结果入库（`FakeAsrEngine` 或真引擎）、字幕时间对齐误差人工抽查（FR-506）、m4a/FLAC 容器 |
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
