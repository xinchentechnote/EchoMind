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
- **同一件事在 Phase 6 代价大得多，因为「数据」里躺着 243 MB 模型和音频素材**：AGP 的卸载走 `deletePackageX`（日志里能看到 `DATA_REMOVED=true`），连 `filesDir/models/` 和 `Android/data/<pkg>/files/asr-fixtures/` 一起删。实测后果：整套件 **94 条里红 13 条**，全部集中在需要素材的四个类（`PcmDecoderTest` 3 / `VadSegmenterTest` 5 / `SherpaOnnxEngineTest` 1 / `TranscriptCoordinatorTest` 4），报错原文是「缺少测试素材…（存在=false）」和 `AsrModelNotReadyException: model.int8.onnx 不存在`；另外 81 条（Room / 仓储 / 迁移）照常绿。**红的原因是数据没了，不是代码坏了**，但只看尾行数字区分不出来。
- 所以 ASR 那批用例改用**不卸载的跑法**（AGENTS.md §1「不要杀/不要重装用户的东西」也指向这里）：
  ```bash
  ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest          # 两个包都要重新编译
  adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
  adb -s emulator-5554 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
  # 模型：先 push 到 /data/local/tmp，再 run-as 拷进 App 私有目录（直接 mkdir 出来的目录属主是 root，App 读不到）
  # 三个文件都要搬：paraformer 是 model.int8.onnx + tokens.txt，VAD 是 silero_vad.onnx；目录名用 ModelCatalog 里的 spec.id
  adb -s emulator-5554 push app/libs/models/sherpa-onnx-paraformer-zh-2023-09-14/model.int8.onnx app/libs/models/sherpa-onnx-paraformer-zh-2023-09-14/tokens.txt app/libs/models/silero-vad/silero_vad.onnx /data/local/tmp/
  adb -s emulator-5554 shell run-as com.xinchen.echomind sh -c 'mkdir -p files/models/paraformer-zh-2023-09-14-int8 files/models/silero-vad'
  for f in model.int8.onnx tokens.txt; do adb -s emulator-5554 shell run-as com.xinchen.echomind sh -c "cat /data/local/tmp/$f > files/models/paraformer-zh-2023-09-14-int8/$f"; done
  adb -s emulator-5554 shell run-as com.xinchen.echomind sh -c 'cat /data/local/tmp/silero_vad.onnx > files/models/silero-vad/silero_vad.onnx'
  # 素材：external 目录可以直接 push（real 是 2,381,693 B 的真人的歌，silent 那份 478,716 B 是 99% 0x00 的负例）
  adb -s emulator-5554 push /Users/xinchen/Documents/music/那些花儿.mp3 /storage/emulated/0/Android/data/com.xinchen.echomind/files/asr-fixtures/pcm-fixture-real.mp3
  adb -s emulator-5554 shell am instrument -w -e package com.xinchen.echomind com.xinchen.echomind.test/androidx.test.runner.AndroidJUnitRunner
  ```
  `am instrument` 只跑测试、不动包，**上一轮的设备实测（SAF 授权、数据库、模型）全部留着**；`-e class <FQCN>` 能单跑一个类（`-e class '<FQCN>#<中文方法名>'` 单跑一条也行，方法名里有 `#` 之前的整串要加引号），`-e package com.xinchen.echomind` 只跑本包的 12 个类。
- ⚠ **这台模拟器的数据状态会变，别照抄上一轮的结论**：Phase 6c-3 那轮结束时 `echomind.db`（`user_version=3`）里 `audio_book` / `imported_folder` / `subtitle_segment` / `transcript_job` **全是 0 行**（AGP 那轮把书和授权一起带走了，而 `am instrument` 用的是 in-memory 库，不会把任何东西填回去），所以做 Phase 6d 之前必须重新走一遍 SAF 选目录 + 授权（坐标见本节）。**Phase 6d 之后的现状**：库里有 7 本书、35 行 `source=ASR` 字幕、7 行 `transcript_job`（含 SUCCESS/FAILED(INTERRUPTED)/FAILED(OUTPUT_UNRELIABLE)/CANCELLED 各态），`那些花儿` 是 READY——这些是**人工从界面点出来的**，`connectedDebugAndroidTest` 一跑就又没了。
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
- **WorkManager 的前台服务要在自己清单里合并出类型**（Phase 6d 实测，点「开始转录」直接崩）：光有 `FOREGROUND_SERVICE_DATA_SYNC` 权限不够，Android 14+ 还查**起服务那个组件**的 `foregroundServiceType`。`setForeground(dataSync)` 会去起 `androidx.work.impl.foreground.SystemForegroundService`，而 work-runtime 2.12.0 清单里它的类型是空的，报错原文 `foregroundServiceType 0x00000001 is not a subset of foregroundServiceType attribute 0x00000000 in service element of manifest file`。修法是 `<service android:name="androidx.work.impl.foreground.SystemForegroundService" android:foregroundServiceType="dataSync" tools:node="merge" />`；**验收要看合并后的清单**（`app/build/intermediates/merged_manifests/`）或装好的包，读源码看不出来。
- **底部导航别对带参数的路由用 `saveState`/`restoreState`**（Phase 6d 实测）：`popUpTo(start){saveState=true}` + `restoreState=true` 会把被 pop 的返回栈存起来再恢复；转录页是 `transcript?audioId=…`，恢复时把「转录这本书？」卡片连同 ViewModel 状态一起复活，表现是**点底部标签毫无反应**。去掉这两个 flag、只留 `popUpTo(start)` + `launchSingleTop` 就好了（`MainActivity.kt` 里有注释）。
- **测音频内容必须自己数零字节**（Phase 6 实测）：书架那批「《三体》」素材帧体 99% 是 `0x00`，也就是静音。它能通过所有「长度类」断言（时长、进度、切歌、样本数），所以只要断言里出现 `peak`/`rms`，就必须同时准备**一份真音频**和**一份静音对照**。素材放 `getExternalFilesDir("asr-fixtures")`：读写自己的 external 目录不需要运行时权限，`adb push` 就能放。

## 3. 测试分层策略

| 层 | 位置 | 覆盖内容 |
| --- | --- | --- |
| 纯 JVM 单测 | `app/src/test/` | **已有（169 项）**：`AudioTreeWalker`（8）、`AudioImportPlanner`（9）、`TitleDeriver`（7）、`Format`（5）、`ShelfViewModel`（2）、**`SessionTracker`（22：有效次边界、倍速只放大消耗量、墙钟跳变、缓冲/暂停不计时、拖到末尾不完播、切歌先结算上一场）**、**`PlayerViewModel`（10：按记忆位置起播、已在播不重开、听完从头播、自动切歌换页头、收藏作用于正在播的那本、书不在书架不起播、字幕窗口跟随位置、切歌后换成另一套字幕、导入写进正在播的那本、同一句内部推进不重发窗口）**、**`SubtitleSynchronizer`（11：首句/中间/末句、空表、位置在首句之前、gap 迟滞、5000 句二分）**、**`TestSubtitleParser`（9：SRT/VTT/TSV、缺小时与一位小数毫秒、乱序重排、`end ≤ start` 拒绝、空文件失败）**、**`ModelCatalog`/`ModelVerifier`/`ModelManager`（10 + 9 + 16：清单合法性、SHA-256 测试向量、下载与断点状态机）**、**`PcmConversion`（11）/`SincResampler`（12：恒等、常数增益、通带与阻带实测频响、分块==一次性、生产调用形状、`reset()`、长度 `ceil(N·out/in)`）/`AsrChunkPlanner`（14）**、**`TokenTimingMapper`（14：秒→绝对毫秒、`endMs`=下一字起点、末字收块尾、取整只向下、数量不等/负值/NaN/越界/倒序一律抛 `Mismatch` 而不猜、同毫秒零长 token 合法）**。**待补**：`SentenceSplitter`（标点/无标点/异常时间戳）——属 Phase 6 第 5 项 |
| 仪器化测试 | `app/src/androidTest/` | **已有（91 项）**：Room DAO 读写与唯一索引（11）、`AudioRepositoryImpl` 的导入编排（9）、`PlaybackRepository`（21：会话行生命周期、flush 覆盖写不累加、结算的事务性、增量==重算、重算能修正被写坏的缓存、崩溃恢复标 `CRASHED`/幂等/短会话不计次、删书保留历史、倍速只落自己那一列、时长未知不覆盖已知）、**`Migration_1_2_Test`（3：v1→v2 结构对导出 JSON、历史保留、FK 与索引存在）**、**`Migration_2_3_Test`（5：v2→v3 结构对 `3.json`、字幕逐句不丢且主键原值保留、删书带走字幕与任务、删任务只把 `job_id` 置空、两条外键与三条索引都在）**、**`SubtitleRepositoryTest`（4：索引连续、整本替换、摘要分辨来源、删书连带删字幕但留历史）**、**`TranscriptRepositoryTest`（16：入队/重复入队/READY 拦截/只有测试字幕不算转好/书不存在；跑完一轮的状态与字幕归属；重转录整本替换；空字幕拒收；重复结算整体回滚；非法跃迁返回 false；重试新建一行；取消后按 NONE/READY/EXPIRED 回退；重启恢复标 INTERRUPTED 且幂等；分段数未知时不画假百分比）**、~~**`ModelManagerNetworkTest`（4：真 URL 下载、重定向、404 不落地、校验失败）**~~ → **已删（2026-10-15，Phase 6f）**：模型内置化后主清单不再持有 `INTERNET` 权限，而仪器化测试跑在 App 进程里（测试包自己的权限救不了它）；在线路径降为非产品代码后，重定向/404/校验失败的行为由 `ModelManagerTest` 里两条本机 `127.0.0.1` HTTP 服务用例继续覆盖，**`PcmDecoderTest`（4：真 MP3 解码 148s 素材、时长对账 0.22%、块连续与 ±1 幅度、静音对照、取消、非音频报错）**、**`VadSegmenterTest`（6：真模型分段且起点是 16k 样本号、段不重叠、`maxSpeechDuration` 强制切段生效、同一音频不同喂入块长结果一致、release 后不可用、主线程拒绝构造、缺文件/非 16k 给人话错）**、**`SherpaOnnxEngineTest`（8：设备侧按清单核对 243 MB、逐字时间落在块内且单调（单位=秒）、端到端解码→VAD→分块→识别跨块时间单调、>20s 与样本数不符都拒绝、缺模型报错点名文件、主线程拒绝装载、秒当毫秒立刻抛）**。**待补**：SAF 真实 URI 扫描、转录结果入库（接口与用例 v3 已有，缺的是生产调用方） |
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

### Phase 6c-1（转录任务状态机）：v3 表 + 显式迁移 + 单事务提交（2026-10-15 07:35 ~ 08:10，含复跑）

新增 `data/db/TranscriptJobEntity.kt`/`TranscriptJobDao.kt`、`domain/model/TranscriptModels.kt`、`domain/transcript/TranscriptRepository.kt`、
`data/repo/TranscriptRepositoryImpl.kt`、`Migrations.MIGRATION_2_3`，以及 `Migration_2_3_Test`（5 条仪器化）+ `TranscriptRepositoryTest`（16 条仪器化，Room 真库 + 假时钟）。
`AppGraph` 多了 `transcriptRepository`。**这一轮交付的是「状态与落库的正确性」，不是「点一下就能转录」——调用方在 4b。**

| 动作 | 结果 | 凭据 |
| --- | --- | --- |
| **先让 KSP 导出再对账**（不反过来） | 手写 `MIGRATION_2_3` 的 DDL 之后跑 `:app:kspDebugKotlin`，`app/schemas/…/3.json` 是**Room 认为该有的形状**；迁移测试用 `runMigrationsAndValidate(NAME, 3, recreateTableOnMigrationError=false, MIGRATION_2_3)` 逐字节比对，不一致就红。**`recreateTableOnMigrationError` 必须传 false**：传 true 时 helper 会偷偷「删表重建」，把「你的手写 DDL 写错了」伪装成「迁移成功」 | `Migration_2_3_Test.v2升到v3后任务表结构符合导出schema` |
| TEXT 主键在 SQLite 里的真面目 | Room 导出的是 `primaryKeyColumns=[id]`，落到 SQL 是**表级** `PRIMARY KEY(\`id\`)`（不是列级 `INTEGER PRIMARY KEY` 那种隐式 rowid 别名），于是 SQLite 自动加一个 `sqlite_autoindex_transcript_job_1`。断言「索引有哪些」时**必须滤掉 `sqlite_autoindex_*`**，只比 Room 自己声明的那几条名字，否则「主键自带的索引」会被算成多出来的一条、测试永远红，而照着报错去改 DDL 就是把表结构改坏 | `迁移把两张表的索引都建回来`（`PRAGMA index_list` + 过滤后按名字断言，另用 `PRAGMA foreign_key_list` 断言字幕表两条 FK：`audio_book` 与 `transcript_job`） |
| **SQLite 不能 `ALTER TABLE … ADD CONSTRAINT`** | 所以 v2→v3 给 `subtitle_segment.job_id` 补外键只能**整表重建**：建 `subtitle_segment_new`（带 FK）→ **按列名** `INSERT INTO … SELECT` → `DROP` 旧表 → `RENAME` 回来 → 重建三条索引（比 v2 多的那条正是 `job_id`）。列名清单而不是 `SELECT *`：新表多一列时 `*` 会静默错位。`id` 是 TEXT 主键、原值照搬，不重新编号 | `database-design.md` §5.1、`architecture.md` D31；行为测试 `迁移保留字幕内容与已有收听历史`（三句 `text/startMs/segmentIndex` 逐句相等、`source` 仍是 `MANUAL_TEST`、`job_id` 仍是 null、`id` 仍是 `[1,2,3]`） |
| 迁移不是只测结构，**FK 行为也得测** | ①删书 → 字幕与任务行一起走（`ON DELETE CASCADE`）；②**删任务行 → 字幕留着，只有 `job_id` 被置空**（`SET NULL`）。第二条是有意的设计取舍：转录记录是审计信息，任务行删掉不该把用户已经在看的字幕一起带走。直接 `execSQL` 删，绕开 DAO（DAO 里没有删任务的方法，删了也不该有） | `迁移后删书连带删字幕与任务`、`删任务行只把字幕的job_id置空不删字幕` |
| 状态机写进 SQL，不写在 Kotlin 里 | 每条跃迁都是 `UPDATE transcript_job SET … WHERE id=:id AND status IN (…)` 并返回**受影响行数**：0 行就是非法跃迁或重复结算。实测四条：对已 `FAILED` 的行再 `markRunning`、再 `updateProgress`、二次 `markFailed`、`cancel("不存在的任务")` —— 全部返回 false，且那一行仍是 `FAILED`+`MODEL_MISSING`+原错误消息（没被后一次写覆盖）。不用「先读再判再写」（两个协程同时读到 RUNNING 就会双双结算，`finished_at_ms` 被后到的覆盖）；也不加 version 列（这张表只有转录流程在写，读-改-写的代价没有对象） | `非法跃迁返回false而不是把已结束的任务改回运行` |
| 重复结算 = 整体回滚 | `commitSubtitles` 里 `markSuccess` 返回 0 就 `check(…) > 0` 抛异常，**整个事务回滚**：实测第二次提交（对已 SUCCESS 的任务）抛 `IllegalStateException`，表里仍是第一批「第一句」那一条、任务仍 SUCCESS、书仍 READY。宁可抛异常让调用方看到，也不留「字幕换了一批但状态说没换」的库 | `重复结算第二次整体失败并回滚字幕` |
| 空字幕不许标成功 | `require(segments.isNotEmpty())` → `IllegalArgumentException`，且**一行都不碰**：任务与书籍状态仍停在 RUNNING、字幕表 0 行（既没被清空也没被写成空批次）。空结果当成功是 ASR 最容易的自欺方式（模型没出字 = 这本书「转录好了」） | `空字幕提交被拒绝且不写任何行` |
| **重转录的原子性**（AGENTS.md §4「删旧+插新单事务」） | 单事务内 `deleteByAudio` → 按传入顺序重编 `segment_index`（连续、从 0）→ `source` 恒 `ASR`、`job_id` = 本任务 → `markSuccess` → 书籍 `transcription_state=READY`。分母沿用 **VAD 段数**（不是句子数）：否则切句让句子比段少，进度条会从 100% 掉下去；`total_segments=0` 时 domain 侧 `progress` 直接是 null，不画 0% 也不画 100% | `跑完一轮后任务SUCCESS字幕带job_id且书架READY`、`重转录整本替换旧字幕`、`分段数未知时没有百分比` |
| **取消之后书是什么状态：不硬写，推导** | 没有写死 `NONE`。取消/失败后按「这本书还有没有 ASR 字幕 vs 字幕落库时刻是否早于 `file_last_modified_ms`」回退成 `NONE`/`READY`/`EXPIRED` —— **复用扫描器已有的过期判据**（`AudioRepositoryImpl` 同一规则），不另造第二套「过期」定义（AGENTS.md §4：口径只许一处）。三种结局各有测试，其中「重转录中途取消」那条断言旧字幕的 `job_id` 仍指向第一次的任务 | `取消后书架回到NONE`、`取消重转录保住上一轮的好字幕`、`文件变过以后取消重转录回到EXPIRED` |
| **只有 ASR 字幕算「转录过」** | `countAsrOf`/`latestAsrCreatedAtMs` 都带 `source='ASR'`：靠测试字幕把书标成 READY 是假话（AGENTS.md §5），所以「库里只有 MANUAL_TEST 字幕」拦不住入队，`force=false` 的重复入队也只数 ASR 行 | `只有测试字幕的书不算已转录`、`已就绪的书再入队被拦住而force放行`、`不在书架的书建不出任务` |
| 重试**新建一行**，不改旧行 | `retry(id)` = 取出旧行的 `audio_id` → `enqueue(force=true)`；旧行留着 `FAILED` + `error_code/message`，用户问「上次为什么失败」时表里真有东西可查。找不到 id 返回 `EnqueueOutcome.JobNotFound`（结局用返回值表达，不用异常） | `重试新建一行并保住旧行的错误上下文` |
| 进程被杀的对账 | 恢复不区分「跑一半」还是「还没跑」：`WHERE status IN ('QUEUED','RUNNING')` 的行全部 → `FAILED`/`INTERRUPTED`（消息写死「任务被中断（进程退出或系统重启）」），书籍状态按同一推导规则落定；返回处理了几行。**幂等**：连着调两次实测 2 → 0 | `重启恢复把挂着的任务标成中断` |
| `model_version` 不编版本号 | `ModelCatalog.transcriptRef` 取 `model.int8.onnx` 的 **SHA-256 前 12 位**当版本（清单里钉住的那串）。换 token 表也算换版本，所以记的是权重文件本身而不是编造的版本号 | `入队写出QUEUED行并把书架标成QUEUED`（断言 `modelId`/`modelVersion` 与清单一致、`totalSegments=0`、`startedAtMs=null`） |
| 编译期踩到的三条（写下来免得再踩） | ①中文测试方法名里**不能用全角冒号 `：`**（不是合法标识符字符，`compileDebugAndroidTestKotlin` 报 `NON_ABSTRACT_FUNCTION_WITH_NO_BODY`）；②`SupportSQLiteOpenHelper` 上没有 `execSQL`，要 `db.openHelper.writableDatabase.execSQL(…)`；③v2 夹具里 `job_id` 传 null，`arrayOf<Any>` 直接拒 → 得写 `arrayOf<Any?>` | 三次编译报错原文 |
| **我自己的操作失误（如实记）** | 跑仪器化之前敲了 `adb shell pm clear com.xinchen.echomind`，把 `adb`+`run-as` 搬上来的 **243 MB 模型和两份音频素材一起抹掉**，9 条 ASR 测试红。**这一步本来不需要**：`am instrument` 不清应用数据（本文 §2 早就写着）。按 §4 记录的流程恢复：`adb push` 那些花儿.mp3 → `getExternalFilesDir/asr-fixtures/pcm-fixture-real.mp3`；三个模型 → `/data/local/tmp` → `run-as com.xinchen.echomind sh -c 'cat … > files/models/<id>/…'`；`cp` 带空格的路径要整体放进 `adb shell "cp '…' …"`（第一次直接写路径报 `not directory`）。**副作用仍然成立：这台模拟器上 App 的 SAF 授权与书架被清空，后续 UI 实测必须重新导入 `/sdcard/Music/EchoMind`** | 恢复后 `run-as … ls -l files/models/…` 大小 243,371,218 / 75,756 / 643,854 与清单一致，整套件重新全绿 |
| 本轮最终构建（08:04 复跑，全新执行） | `assembleDebug` **BUILD SUCCESSFUL**；`testDebugUnitTest --rerun` 从 XML 汇总：**16 个类 / 169 用例 / 0 failures / 0 errors / 0 skipped**；仪器化整套件 `am instrument -w` **OK (91 tests)**，`Time: 80.709`（从 70 项涨到 91 项 = 迁移 5 + 状态机 16）。这一轮跑之前**没有**再碰 `pm clear`，模型与素材原地复用 | 测试 XML 汇总脚本 + instrument 尾行 |

**这一轮没做 / 做不了**：

- **没有生产调用方**：`TranscriptRepository` 只被测试调用，UI 与 WorkManager 都不认识它；`work_request_id` 这一列建了但**没有写入方**（要等 4b 提交 WorkManager 之后回填）。
- **还没有真字幕入库**：`commitSubtitles` 吃的是测试里手工构造的 `SubtitleSegment` 列表；VAD → 识别 → 切句 → 提交的端到端串接是 4b + 第 5 项（`SentenceSplitter`）。
- 状态机没做真并行压力测试（「第二次结算被 SQL 拦下」是顺序调用两次测的，不是两个协程抢同一行）。

### Phase 6c-2（切句）：`SentenceSplitter` + 设备端到端第四环（2026-10-15 08:20 ~ 08:45，含复跑）

新增 `domain/asr/SentenceSplitter.kt`、`domain/model/RecognizedSegment`、`SubtitleDraft.boundaryGuess`，
`AudioFormats` 多四个切句阈值（60 字 / 500ms / ×2 / 700ms，唯一出处）。测试：**25 条 JVM 单测** + `SherpaOnnxEngineTest` 里
**1 条设备端到端用例**（解码 → VAD → 识别 → **切句**）。**这一轮交付的是「字句怎么变成句子」，不是「点一下就能转录」——调用方仍在 4b。**

| 动作 | 结果 | 凭据 |
| --- | --- | --- |
| **最重要的一条：设备用例抓到了纯逻辑测不出来的错** | 设计稿（本文 §4 Phase 6 第 3 项终、`asr-design.md` §6 原稿）把「相邻起点差 ≥300ms」当段内停顿判据。第一次在设备上跑通「识别→切句」给出 **58 字 → 53 句**，平均每句 **1.6 字**，等于逐字打字；而当时三条断言（时长为正 / 不重叠 / 摘掉标点后与模型输出逐字相等）**全部通过**。「切句正确」在这种断言下是可以假的 | `SherpaOnnxEngineTest.真模型的逐字时间喂进切句器得到能读的句子`（原来只有三条，红不了） |
| 根因不是阈值调错了，是**判据本身不成立** | `durations` 实测长度 0（§4 Phase 6 第 3 项终已钉死）→ 相邻**起点**差 = 前一字的发音长度 + 中间静音，**减不掉发音长度**。歌唱素材每个字本身占 400~600ms，那叫慢不叫停顿，任何绝对门槛都会把慢当停顿 | `SentenceSplitterTest.整段匀速地慢不是停顿一个字都不许多切`（8 字、字距 550ms 全均匀 → 必须还是一句） |
| 改判据：绝对下限 **且** 相对本段中位数 ×2 | 停顿必须同时 ≥`ASR_INTRA_SEGMENT_PAUSE_MS`(500) 且 ≥`median(本段起点差) × ASR_PAUSE_MEDIAN_RATIO`(2)。取中位数不取平均值：一段里只有一个大间隔时平均值会被它自己抬上去反而断不开；偶数个字取靠下那个（宁可多认一个停顿，不要让虚高基准抹平停顿）；只有 1 个间隔时中位数就是它本身 → 必然断不开，「一个字比另一个字慢」不是证据 | 上面那条的镜像用例 `比本段典型字距明显更慢的那一档才断句`（同样 550ms 的差，放进 150ms 字距里就必须断） |
| **补了一条下限断言，让「逐字字幕」再也绿不了** | `平均每句 ≥3 字`。**验证这条断言不是摆设**：把两个阈值临时改回 300/×1 重新编译装 APK 复跑 → 立刻红 `平均每句只有 1.6 字：这是逐字字幕不是句子`；改回 500/×2 复跑 → 绿 | 同用例，实测数值见下一行 |
| 改完之后的真实产出（同一天两次独立进程复跑，结论一致） | 60 s 歌唱素材：6 段 / **58 字 → 9 句**，平均每句 **6.4 字**，按长度硬切 **0 句**。句子与《那些花儿》歌词对得上：「偏笑声让想起我的那些话。」/「在我生命每一个角落。」/「静静为我开着。」/「我曾以为我会永远。」/「守在他身旁。」/「今天我们。」/「已经离去。」/「在人海茫茫。」/「他们都老了吧。」。**注意第一句仍是错的**（模型按 VAD 切块后就把「那片笑声让我」听成「偏笑声让」，切句层不背这个锅，见 §4 Phase 6 第 3 项终的「块边界不等于句子边界」） | logcat `SherpaEngineTest: 切句：6 段 / 58 字 → 9 句` + `平均每句 6.4 字`（08:42 与 08:44 两轮逐项相同） |
| 顺手删掉一条**够不着**的档位 | §6 第 4 条原稿在超长句里有「在最大 token 间隔处再切」一档。改完停顿判据后它的条件与段内断句**完全相同**，而能进超长分支的句子要么段内已无停顿可断、要么是跨段合并来的（合并本身有 60 字上限，进不了超长分支）→「本集合里最大」不再携带信息，删。回退顺序变成「弱标点 → 空白 → 60 字硬切（只有硬切标 `boundaryGuess`）」，与 FR-602 原文「超出按标点或空格回退切分」一致 | `最大起点差够不上停顿就只能在字数上限硬切并如实标记`（90 字、字距 100ms、中间夹一个 250ms 的「最大间隔」→ 必须硬切且 `boundaryGuess=true`）；对照 `真停顿会让长句在停顿处断开而不到字数上限`（只把 250 改成 700 → 两句都是 60/30 字，`boundaryGuess=false`） |
| 合并窗口不许把段内断句抹掉 | `segmentFinal` 标志：只有本段最后一句允许跨段合并。段内因停顿断开的两句如果再被合回去，§6 第 2 条就白做 | `段内起点差超过门槛就断句而且合并窗口也合不回去`（间隔 550ms ≤ mergeGap 700ms 也不合） |
| 零长句的三种收场，**没有一种需要造时间** | ①与上一句本来连着 → 文本并进上一句、上一句时长不变；②隔着一大段静音 → **不并**（否则那句话白占几十秒）；③后面有句子 → 用下一句起点；谁都给不出 → 如实丢掉。`+N ms` 这种收口全类没有 | `零长句并进上一句而不是编一个时长出来`、`隔着一大段静音的零长字不并给上一句`、`整篇只有一个零长字就如实丢掉` |
| 只剩标点的 token 不成句 | `hasContent` 挡掉「只有一个『。』」这种句子，也不给它编时长 | `只剩标点的不成句` |
| 清洗顺序：先重排再夹重叠 | 跨块拼接可能乱序 → 按起点排；起点只往**不早于前一字**收拢（往前就是造一个模型没给的时间）；上一字的 `endMs` 夹到下一字起点。`TokenTiming` 自己只保证 `endMs ≥ startMs`，管不了互相盖 | `token乱序时按时间重排文本也跟着走`、`上一个字压到下一个字头上时以起点为准夹住` |
| 一条性质断言，不靠具体用例 | 200 字超长段 + 一个短段 + 一个孤立零长字混着喂，输出必须「时间升序 / 每句时长 > 0 / 互不重叠」——FR-603 验收动作就是这个 | `输出永远时间升序每句时长为正且互不重叠` |
| 中文连排 vs 英文粘成一坨 | 两侧都是字母/数字（且都 <0x2E80）才补空格，中文之间不补。纯空白 token `trim` 成空串后既是「第三档切点」也不占字数 | `空白是第三档切点且英文分词之间补空格`（`he`+`llo` → `he llo。`） |
| 写测试时自己的失误（如实记） | ①新用例插进类里时 `old_string` 多吃掉了下一条的 `@Test` + 函数签名，把邻居的函数体变成孤儿 → 靠 `Read` 复查发现，补回签名；②`PUNCT_FOR_TEST` 只加了引用没加定义（编译前自查到）；③`filter { !it.isWhitespace() && it !in PUNCT_FOR_TEST }` 的集合必须是 splitter 实际标点集的**超集**，宽一点无所谓（两边都摘），窄一点就会假红 | 编译输出 + 复查 |
| 本轮最终构建（08:44 复跑 + 09:05 对最终代码树再跑一次） | `assembleDebug` / `assembleDebugAndroidTest` **BUILD SUCCESSFUL**；`testDebugUnitTest --rerun` 从 XML 汇总：**17 个类 / 194 用例 / 0 failures / 0 errors / 0 skipped**（169 → 194 = `SentenceSplitterTest` 25 条）；仪器化整套件 `am instrument -w` **OK (92 tests)**，`Time: 91.114` → 补完 KDoc 后同一棵树再跑 **89.051 s**（91 → 92 项 = 新增那条切句设备用例；两轮耗时差 2 s，与 §4 一直声明的模拟器抖动同量级，不当判据）。同一轮顺带记下装载耗时新样本：`OfflineRecognizer` **3,398 ms**（此前 4,906 / 4,914）、单块 19.2s RTF **0.115 / 0.123**、端到端 6 块 RTF **0.133** | 测试 XML 汇总脚本 + instrument 尾行 + logcat |

**这一轮没做 / 做不了**：

- **没有生产调用方**：`SentenceSplitter` 只被测试调用，`commitSubtitles` 至今没吃过它产出的草稿（4b）。
- **三个阈值全部未标定**：500ms / ×2 / 700ms 只在**一份歌唱素材**上验过，朗读型真人语音 fixture 还没有（AGENTS.md §6「先实测再定数」这条这里只算半条）。已在 `asr-design.md` §6 与 `AudioFormats` 注释里写明「未标定」。
- **`boundaryGuess` 不落库**：`subtitle_segment` 没有对应列，硬切出来的句子和证据充分的句子在库里长得一样。加列要连带 v3→v4 迁移，等 §8 降级策略一起定。
- 句子质量只断言了「结构性质 + 平均每句字数」，**没有断言语义正确性**（没人能自动化判断「这句该在哪里断」）。人工读那 9 句是本轮的实际验收手段。



### Phase 6c-3（协调器接线，即 roadmap 的 4b）：生产代码第一次把模型的字写进字幕表 + WorkManager（2026-10-15，AGP 那轮 11:19 / `am instrument` 12:24 / 协调器单类复跑 12:30 ~ 12:31 / 最终构建 12:39）

新增 `asr/TranscriptCoordinator.kt`、`domain/asr/TranscriptQuality.kt`、`work/TranscriptWorker.kt`、`work/TranscriptWorkScheduler.kt`，
在 `EchoMindApp` 的 `AppGraph` 里装配（协调器 `:96`、调度器 `:104`、`recoverInterrupted()` 在 `onCreate` 里 `:122`）。
测试：**9 条 JVM 单测**（`TranscriptQualityTest`）+ **8 条仪器化端到端**。**这一轮交付的是「六环串起来能把字写进库」，不是「用户能转录」——界面上没有任何入口，`schedule()` 全仓库零调用方。**

| 动作 | 结果 | 凭据 |
| --- | --- | --- |
| **本项目最该被记住的一行：模型的字第一次由生产代码写进 `subtitle_segment`** | 148 s 整首歌《那些花儿》跑完整条链（真解码、真 VAD、真模型、真 `TranscriptRepositoryImpl` + 真 Room DAO/entity/schema v3），**35 句**入库（`source=ASR`、`job_id` 指向那一行、`segment_index` 连续、时间升序、不越出容器时长）。此前所有轮次的字幕都来自手工导入的测试文件。⚠ **写的是用例进程里的 in-memory Room**（`Room.inMemoryDatabaseBuilder`）：生产代码路径第一次被真跑通，但**用户机上那份 `echomind.db` 里仍然一句 ASR 字幕都没有**（实测 `subtitle_segment` 0 行、`transcript_job` 0 行、`audio_book` 0 行——AGP 那轮 11:19 把数据连书一起清了，之后没有重新导入）。要那句字幕出现在用户能看见的库里，得等任务 #23 的界面入口 | logcat `EchoMindTranscript: 转录收口：解码 2378188 样本（44100 Hz/2 ch → 16000 Hz，容器时长 148303 ms），语音段 24 个，块 24 个，丢弃 0 个；句子 35（硬切边界 0 句），产出字幕的段 24/24`（12:31:09.770）；用例 `真模型跑完整首歌后字幕第一次进了数据库`；`run-as cat` 拉库后 `select count(*) from subtitle_segment` = 0 |
| 端到端耗时与**产出稳定性**（同一条用例三次独立进程） | 墙钟 **42,965 / 33,453 / 63,714 ms**（含装载；对 148 s 音频 ≈ RTF 0.29 / 0.23 / 0.43）——**最快与最慢差 1.9 倍**，所以耗时只能报区间不能报结论。反过来说更有价值的是：**三次的产出逐字一致**（都是 24 段 / 24 块 / 丢弃 0 / 35 句 / 硬切边界 0 / 产出字幕的段 24/24），说明解码→VAD→识别→切句这条链在这台机器上是确定的，抖的只是速度 | 三条 logcat：`wall=33453ms`（12:31:09.794）、`wall=63714ms`（13:06:39.799）+ 前一轮 42,965 ms；三次的「转录收口」行逐项相同 |
| **中途 `total_segments` 恒为 0 是设计而不是 bug**（流式解码时段数还不知道），协调器如实上报 0 | 进度回调实际是 `[(1,0),(2,0),…,(24,0)]` ⇒ `TranscriptJobRow.progress == null`。**UI 与通知因此不许画百分比**，只能说「已识别 N 段」——这一条是给 Phase 6d 定契约，不是记录 | 上面那行 logcat 的 `进度上报` 字段；`database-design.md` §6 |
| 三道守护（授权 / 模型 / 文件）都排在装载模型之前，实测省下了 243 MB | 三条失败全部在 **130 ms 内**出完（`12:30:23.072` PermissionRevoked、`.096` ModelMissing、`.203` DecodeFailed），而整个类 8 条只用 46.777 s。失败原文直接进 `error_message`，不做二次加工 | `EchoMindTranscript: 转录失败（ModelMissing）：VAD 模型缺失或与清单不符（silero-vad），请先在模型页准备好` + `VAD 模型不合格：silero_vad.onnx 不存在`；用例 `模型没准备好时标MODEL_MISSING且不写字幕`、`文件已不在原位置时报解码失败且不启动推理` |
| **改前会把每一本书都判成「授权已失效」**：SAF 可持久化授权发在**树** uri 上，而 `audio_book.uri` 是 `listFiles()` 给的**文档** uri | `hasReadGrant(文档uri)` 必然 false。修法是 `TranscriptionTarget.treeUri` + `ImportedFolderDao.byId`。日志里打出来的正是树：证明修复后查的对象对了 | `转录失败（PermissionRevoked）：文件夹授权已失效，请重新授权后重试：content://com.android.externalstorage.documents/tree/primary%3AMusic`；用例 `授权查的是所属文件夹的树而不是文档uri` |
| 状态守卫是**生产的承重墙**，不只是测试里的漂亮话 | 「抢不到行就收手」完全依赖 `markRunning` 的 `WHERE status='QUEUED'`；`recoverInterrupted()` 先把上次被杀留下的行标 INTERRUPTED，复活的 worker 就覆盖不掉这个结论 | 用例 `正在跑的行不会被第二个worker重复跑`、`重启后复活的worker不再覆盖中断结论` |
| 取消与「没有语音」这两条容易画假账的分支 | 取消位为真时**只停手不写终态**（终态归 `cancel()` 那条路径）；478 KB 静音素材解出 479,818 样本、VAD 段 0、句 0 ⇒ `FAILED(OUTPUT_UNRELIABLE)` 而**不是** `SUCCESS`，`audio.transcription_state` 也就不会被谎报成 READY | `转录收口：解码 479818 样本（44100 Hz/1 ch → 16000 Hz，容器时长 29919 ms），语音段 0 个，块 0 个，丢弃 0 个；句子 0（硬切边界 0 句），产出字幕的段 0/0` + `转录失败（OutputUnreliable）：模型没有给出任何带时间的字（识别 0 块，丢弃 0 块）。如果这段音频本来就没有语音，这是正常结果`；用例 `取消位为真时立刻停手且不写终态`、`没有语音的素材标成结果不可用而不是成功` |
| 质量闸门（丢弃块占比 >20% 或零句 ⇒ 不可靠）单独可测 | 9 条 JVM 单测：阈值边界、零句、全丢、`AudioFormats.ASR_MAX_REJECTED_CHUNK_PERCENT` 只有一处定义。取整方式与占比口径在 `asr-design.md` §5 第 5 条 | `domain/asr/TranscriptQualityTest`（XML 计数 9） |
| **本轮新发现的架构违规，没修**：唯一名是 `transcript-<audioId>`（每本书一个），两本书同时入队 = 两个 worker 各 `open()` 一份模型，违反 AGENTS.md §3「同一时刻只有一个 ASR 引擎句柄」 | 取舍与修法记在 `architecture.md` D36：跨书单一唯一名 + `ExistingWorkPolicy.APPEND`。**为什么不顺手改**：这条要一条真并发的用例才算交付，而现在没有界面入口，点不到就爆不出来——写成待办而不是写成已修。**（后续：这一条已在 Phase 6c-4 修掉，见下面那一节）** | `TranscriptWorkScheduler.kt`（原 `uniqueName`，现已删）+ D36/FR-507 |
| ⚠ **`work/` 那两个类一次都没跑过**（诚实标注，别被 102 全绿骗了） | 8 条端到端用例**直接调协调器**，不经过 worker；`schedule()` 的调用方全仓库为 0（只有 `EchoMindApp.kt:104` 构造了它）⇒ `setForeground`/前台通知/`REPLACE`（**该策略已在 Phase 6c-4 改成全局唯一名 + `APPEND`，改后仍零测试**）/`isStopped` 传递/意外异常补 `FAILED(UNKNOWN)` **全是代码，零设备证据**。`OUT_OF_MEMORY` 分支同样只有代码 | `grep -rn "\.schedule(" app/src` 无匹配；`TranscriptWorker.kt` 与 `TranscriptWorkScheduler.kt` |
| AGP 那条标准命令在这台设备上**会自己拆掉自己的测试数据**，得到 13 条假红 | 见 §2b 新增的三条：`deletePackageX` 连 `filesDir/models` 与 `asr-fixtures` 一起删 → 94 条里红 13（`PcmDecoderTest` 3 / `VadSegmenterTest` 5 / `SherpaOnnxEngineTest` 1 / `TranscriptCoordinatorTest` 4），报错原文是「缺少测试素材…（存在=false）」；Room/仓储/迁移那 81 条照常绿，所以**只看尾行数字区分不出「数据没了」和「代码坏了」**。绕法：`adb install -r -t` 两个包 + `run-as cat` 重搬模型 + `am instrument` | `app/build/outputs/androidTest-results/connected/debug/TEST-drill36(AVD) - 16.xml`（tests=94 failures=13 errors=0） |
| 顺带把「模型装载吃掉多少内存」量出来了（原 §7 的两条自查项之一） | 端到端**边跑边采样** 37 次 `dumpsys meminfo`：PSS `85 → 112 → 139 → 401 → …` 之后 31 个点全在 414~442 MB 抖动，峰值 **PSS 442 MB / RSS 559 MB**（这台 AVD 总内存 2.42 GB，正好在目标机型区间）。台阶 = 243 MB 的 int8 模型，平线 = 24 段逐个识别。⚠ 平线**不能**当「没整本载入 PCM」的证据（整本也只有 9.5 MB），那条保证在代码结构里；详见 §6b、§7 | 采样脚本 + `/tmp/memsample/mem.txt` |
| 本轮最终构建（对最终代码树） | `:app:assembleDebug` + `:app:testDebugUnitTest --rerun` **BUILD SUCCESSFUL in 1s**（44 tasks，1 executed / 43 up-to-date），XML 汇总 **18 个类 / 203 用例 / 0 failures / 0 errors / 0 skipped**（194 → 203 = `TranscriptQualityTest` 9 条）；仪器化 `am instrument -w -e package` **OK (102 tests)** `Time: 140.845`（92 → 102 = 协调器 8 条 + `attachWorkRequest` 2 条）；协调器类单跑 **OK (8 tests)** `Time: 46.777`；单条端到端用例第三次独立跑 **OK (1 test)** `Time: 63.86`（wall 63,714 ms，见上一行的三次对照） | 12:39 构建输出 + 12:24/12:31 instrument 尾行 + logcat `CoordinatorTest: 端到端…wall=63714ms`（13:06:39） |
| 文档同步（AGENTS.md §7，改了行为就改文档） | `asr-design.md` §4 组件表 / §5 步骤 5~8 / **新增 §5.1 编排层六条** / §8 错误码逐行状态 / §9 / §10；`requirements.md` FR-504~509、FR-601/602/604 状态与理由；`database-design.md` §6（`OUTPUT_UNRELIABLE`、`work_request_id` 写入方、18 条用例、`total_segments` 中途恒 0）；`architecture.md` 包树 / §5.3 全图重画 / §6 线程表 / **§10 新增 D34 D35 D36**；`roadmap.md` 4b 改 DONE + 第 5、6 项与遗留清单；本节与 §2b、§5 | 六个文件的 diff |

**这一轮没做 / 做不了**：

- **用户点不到**：没有转录页、书架上也没有入队按钮，`TranscriptWorkScheduler.schedule()` 零调用方。FR-505 的验收动作（「点一下就能转录并看到进度」）仍然是 0 分 → Phase 6d。
- **`work/` 与 `OUT_OF_MEMORY` 零证据**（见上表倒数第 3 行）。前台通知能不能真显示、被系统杀掉后 `recoverInterrupted` 与 WorkManager 重启谁先跑，都要等 Phase 6d 有入口之后在设备上验。
- **硬切那一档设备上从未走到**（35 句里 `guessedBoundaries=0`）：只有 JVM 用例覆盖，属于「代码到得了但素材没让它露脸」。
- **切句三个阈值仍未标定**，`boundaryGuess` 仍不落库（D35）；真 ASR 字幕的时间对齐误差**还没做人工抽查**（FR-506）——抽查要先把字幕看得见，所以排在 6d 之后。
- 真机（2~3 GB RAM）峰值 RSS 与 RTF、m4a/FLAC 容器、大文件（>1 h）都还是空白（§6、§7）。

### Phase 6c-4（D36 串行化，任务 #23 的前置）：全机同时只跑一个转录（2026-10-15，整套件 13:50 起 / 证伪与闸门装回 14:15~14:25）

上一轮留下的架构违规（`transcript-<audioId>` 每本书一个唯一名 ⇒ 两本书同时入队 = 两个 worker 各 `open()` 一份 243 MB 模型）。
改动两处：`TranscriptJobDao.markRunning` 的跃迁 SQL 加 `AND NOT EXISTS (SELECT 1 FROM transcript_job WHERE status = 'RUNNING' AND id <> :id)`；
`TranscriptWorkScheduler` 改成**全局单一唯一名** `transcript-series` + `ExistingWorkPolicy.APPEND`（删掉 `uniqueName(audioId)`）。
新增 2 条仪器化用例（协调器 8→**9**、仓储 18→**19**），取舍记在 `architecture.md` D36。

| 动作 | 结果 | 凭据 |
| --- | --- | --- |
| **先证伪再算交付**（AGENTS.md §6「修 bug 先写能复现它的测试」的另一半：断言必须抓得住反向） | 临时把 DAO 里的 `NOT EXISTS` 子句删掉、重装 APK，两条用例**各自变红**：仓储侧红在 `assertFalse("第二本书竟然也拿到了运行中的行")`（栈顶 `TranscriptRepositoryTest.kt:263`），协调器侧红在 `java.lang.IllegalStateException: 第二本书竟然跑起来了（那就是两个引擎并存）：Failed(failure=OutputUnreliable, message=模型没有给出任何带时间的字（识别 0 块，丢弃 0 块）...)`（栈顶 `TranscriptCoordinatorTest.kt:257`）。**协调器那条的失败方式特别说明问题**：没有闸门时第二本书真的解了码、真的装了引擎，只是素材恰好没语音才落进 `Failed` —— 所以用例断言的是 `is Skipped`，只断言「它失败」会一路绿 | 删闸门后的 `assembleDebug`（BUILD SUCCESSFUL in 1s）+ 两次 `am instrument -e class`，各自 `Tests run: 1, Failures: 1` |
| 闸门装回后同两条用例合跑 | **OK (2 tests)**，`Time: 0.321` | `adb install -r` 之后一次 `-e class 'A#方法,B#方法'` |
| 第二本书抢不到行时**什么都没发生**（这才是「不写字幕」的可执行含义） | 它的行停在 `QUEUED`、`started_at_ms` 为 null、`subtitle_segment` 里 `audio_id='b'` 0 行；第一本书仍为 `RUNNING`。⚠ 用例只断言了**协调器在 `open()` 之前收手**（靠 `markRunning` 返回 false），没有断言引擎句柄数（native 层拿不到句柄计数） | 用例 `别的书正在跑时第二个协调器收手且不写字幕`、`别的任务正在跑时第二本书的任务抢不到运行中的行` |
| **为什么守卫写在 SQL 里而不是「先查再改」** | 查与改之间就是并发窗口：两个 worker 都能读到「没有 RUNNING」，于是各自开一份模型。SQLite 的单语句原子性是这里唯一能白拿的保证，而它也是**唯一能被仪器化用例断言的那一层**——WorkManager 的排队发生在它自己的进程内状态里，测试直接调协调器根本不经过它 | `TranscriptJobDao.kt` 的 `markRunning` + 用例断言的是仓储返回值 |
| WorkManager 那一半（`APPEND` 排队）**没有用例** | 同一唯一名下不并发是官方文档语义，本机没测；要测得先有能入队的界面按钮（任务 #23）。如实写成「只有代码」 | `TranscriptWorkScheduler.kt`（`UNIQUE_NAME` / `enqueueUniqueWork`）；`work/` 两个类仍然零测试 |
| 新闸门带来的**新失效模式**（写清楚，别只报修了什么） | 一行卡在 `RUNNING` 会把**后面所有**转录一起挡住（原来按书分开时不会）。释放车道有三处：协调器 `settle`、`TranscriptWorker` 的 `FAILED(UNKNOWN)` 兜底、`EchoMindApp.onCreate` 的 `recoverInterrupted()`；但 worker 只 `catch Exception`，`UnsatisfiedLinkError` 这类 `Error` 会绕过它，那时要等下次进程启动才恢复。**这条目前没用例** | `TranscriptWorker.kt:74`、`EchoMindApp.kt:122`、`TranscriptJobDao.markInterrupted` |
| 整套件（对**同一份带闸门**的代码树，13:50 那一轮；上面的证伪是之后的临时偏离，做完已原样装回并重装 APK） | `:app:assembleDebug` **BUILD SUCCESSFUL**（证伪前后各一次：in 1s / in 2s）；`testDebugUnitTest --rerun` XML 汇总 **18 个类 / 203 用例 / 0 failures / 0 errors / 0 skipped**；仪器化 `am instrument -w -e package` **OK (104 tests)**，`Time: 140.846`。逐类：`TranscriptRepositoryTest` 19、`TranscriptCoordinatorTest` 9、`SherpaOnnxEngineTest` 9、`PlaybackRepositoryTest` 21、`AudioBookDaoTest` 11、`AudioRepositoryImportTest` 9、`VadSegmenterTest` 6、`PcmDecoderTest` 4、`ModelManagerNetworkTest` 4、`SubtitleRepositoryTest` 4、`Migration_2_3_Test` 5、`Migration_1_2_Test` 3（合计 104）。⚠ 走 `am instrument` 而不是 `connectedDebugAndroidTest`，后者会 `deletePackageX` 把模型和素材连数据一起删（§2b） | 测试 XML 汇总 + instrument 尾行与逐类点号 |
| 写测试时自己的两次失误（如实记，这两条都不是代码 bug） | ①第一条用例断言第二本书的 `transcription_state == NONE`，实际是 `QUEUED`——**`enqueue` 本来就会把书标成 QUEUED**（既有用例守的就是这件事），所以改断言而不是改代码；②第二条用例给第二本书复用了同一个文件 uri，撞上 `audio_book.uri` 的 UNIQUE 索引，`enqueue` 直接返回 `AudioNotFound`，改成「第二本书用静音素材那份文件」（守护在解码之前，用哪份文件不影响这条用例要证的事） | 两次失败输出 `expected:<[NONE]> but was:<[QUEUED]>` 与 `ClassCastException: EnqueueOutcome$AudioNotFound cannot be cast to EnqueueOutcome$Created` |

**这一轮没做 / 做不了**：

- `work/TranscriptWorker`、`TranscriptWorkScheduler` **仍零测试**，`schedule()` 仍零生产调用方 ⇒ 排队语义、前台通知、撤销路径都只有代码。
- 「卡在 RUNNING 把整条队挡住」没有用例（要造一个不结算的 RUNNING 行才能测，而那需要绕过协调器的写入方，测了也不等于生产路径）。
- 真并发（两个 worker 同时起步）没测：仪器化测试直接调协调器，测的是**闸门**而不是**竞态**；这一条要等界面入口后在设备上点两本书才知道。

### Phase 6d（转录页 UI + 取消/重试 + FR-508 降级文案，任务 #23）：第一次「点一下就出字幕」（2026-10-15 20:20 ~ 21:05，全套件 21:00 复跑）

生产改动四处：新增 `ui/transcript/{TranscriptScreen,TranscriptViewModel,TranscriptDisplay}.kt`、`MainActivity.kt` 的底部导航、`AndroidManifest.xml` 给 WorkManager 的前台服务补 `foregroundServiceType`。新增测试两层：JVM `TranscriptDisplayTest`（13 条）、仪器化 `work/TranscriptForegroundServiceTypeTest`（2 条）。

| 动作 | 结果 | 凭据 |
| --- | --- | --- |
| **FR-504/506 一直缺的那条用户路径终于跑通了**：书架 → 行尾 ⋮ →「转录这本书」→ 转录页确认卡片 →「开始转录」 | 状态依次是 排队中 → 转录中（「已识别 N 段（总段数要等解码跑完才知道）」，**分母未知时不给百分比也不画进度条**）→「共 24 段全部识别」。**用户机上那份 `echomind.db`**（不是测试进程的 in-memory 库）里现在有 `source=ASR` 的 **35 行**字幕：`segment_index` 0~34 连续、`start_ms` 最小 14,134、`end_ms` 最大 145,766，都在容器时长 148,304 ms 之内；书 `那些花儿` 的 `transcription_state=READY`。播放页的七句窗口读的就是这 35 行 | `sqlite3` 直查（`/tmp/echodb5`）；截图 `/tmp/shot_tr2.png` |
| **第一个真 bug：点「开始转录」直接把进程干掉** | `java.lang.IllegalArgumentException: foregroundServiceType 0x00000001 is not a subset of foregroundServiceType attribute 0x00000000 in service element of manifest file`，栈顶在 `androidx.work.impl.foreground.SystemForegroundService.startForeground`。**为什么权限齐了还崩**：Android 14+ 除了 `FOREGROUND_SERVICE_DATA_SYNC` 权限，还要求**起前台服务的那个组件本身**声明类型；那个组件是 work-runtime 2.12.0 清单里的 `SystemForegroundService`，它自带的 `foregroundServiceType` 是空的，合并进我们 App 后仍然是空 | 崩溃日志原文 + `app/build/intermediates/merged_manifests/debug/…/AndroidManifest.xml` 里合并后那一行 |
| 修法 + **先写能复现它的测试**（AGENTS.md §6） | `AndroidManifest.xml` 显式合并该 service：`<service android:name="androidx.work.impl.foreground.SystemForegroundService" android:foregroundServiceType="dataSync" tools:node="merge" />`。新增 `TranscriptForegroundServiceTypeTest` 2 条，设备 **OK (2 tests)**：①从**装好的包**读 `PackageManager.getServiceInfo(…)` 断言 `foregroundServiceType and FOREGROUND_SERVICE_TYPE_DATA_SYNC != 0`；②断言 `requestedPermissions` 含 `FOREGROUND_SERVICE_DATA_SYNC` / `FOREGROUND_SERVICE` / `POST_NOTIFICATIONS`。⚠ 它故意读**已安装清单**而不是源码：这条 bug 的本质就是「源码里权限齐、合并后的组件属性没齐」，读源码的测试抓不到 | 用例 `workManager的前台服务在合并清单里声明了dataSync类型`、`前台服务类型与配对的权限一起声明` |
| **第二个真 bug：点了底部「书架」切不走** | `popUpTo(start){saveState=true}` + `restoreState=true` 会把被 pop 掉的返回栈存下来再恢复；转录页是带查询参数的路由（`transcript?audioId=…`），恢复时把「转录这本书？」卡片连同 ViewModel 状态一起复活，实测点标签毫无反应。**修法**：去掉 `saveState`/`restoreState`，只留 `popUpTo(start)` + `launchSingleTop`（`navigateToShelfTab()` 同样处理），并在代码里留注释说明为什么不许加回来。修完按 navA→navE 重走一遍（转录页**有**卡片 → 书架 → 转录页**无**卡片 → 统计 → 书架），每步都对 | `MainActivity.kt` 的 tab `onClick`；5 次 `uiautomator dump`（`/tmp/ui_nav*.xml`） |
| **一条没能定性的可疑记录（如实写，不算已解决）** | 导航 bug 还在的那段时间里，库里多出两行**相隔 13 秒**的同书 SUCCESS 任务：`c29cc479` 20:44:47 与 `c4595ba7` 20:45:00，而我只点了一次「开始转录」。两种猜测**都没有证据**：①卡片复活后点击/`LaunchedEffect` 被重复触发；②旧导航把同一路由压了两个实例，各提交一次。修掉导航后按同样顺序重点 4 次（含对 READY 书走「覆盖重转」）**没有再复现**——这只能说明「修完之后没再见到」，不等于找到了原因。**顺带暴露一个真缺口**：`enqueue()` 对已经在 RUNNING/SUCCESS 的书不做去重，第二次点确实会新建一行；现在只有 UI 的确认卡片挡着，**仓储层没有第二道闸** | 设备库 `transcript_job` 全表时间线（见上）；`TranscriptRepositoryImpl.enqueue` |
| **FR-508 的四条降级文案都在设备上出现过**，且**没有一条承诺「帮你把时间填上」** | `INTERRUPTED`（上次进程被杀留下的行，`recoverInterrupted()` 在 `onCreate` 翻成 FAILED）→ 文案 + 「原始信息：…」把 `error_message` 原文照登；`OUTPUT_UNRELIABLE`（静音素材）→ 文案里「丢弃比例超过 **20%**」那个数是从 `AudioFormats.ASR_MAX_REJECTED_CHUNK_PERCENT` 渲染的，不是写死字符串，并明说「宁可失败也不按字数均分时间」；`CANCELLED`（真在跑的中途取消）→ 已取消 + 取消说明；`MODEL_MISSING` 文案里的「请先在模型页准备好」**目前指向还不存在的页面**（模型页 = 任务 #24），这是一处已知的文案与现实不符 | 截图 `/tmp/shot_failed.png`；JVM 用例 `每种失败都给得出一个动作`、`不合格占比那句话跟着常量走` |
| 取消 / 重试在 UI 上没有说谎 | 重试是**新建一行**，旧行连错误码与原文留在列表里当上下文（实测 `dad73010`→`c29cc479`）。对**已结算**的行点取消，仓储返回 false，UI 不宣称「已取消」：第一次点取消时那轮其实已经跑完，卡片如实显示「已完成」；重做了一次真正的中途取消（入队后 ~4 s）才拿到 `CANCELLED`（`5963bd56` 20:55:50） | 设备库时间线；`/tmp/ui_cancel*.xml` |
| **D36 串行化第一次在界面上看得见** | 两本书先后入队，同屏一张「转录中」（有进度文案）+ 一张「排队中」（`progressLabel` 返回 null ⇒ **既无百分比也无进度条**），前排结算之后后排才起步 | 截图 `/tmp/shot_queue.png`；JVM 用例 `排队中的任务一行进度都不给` |
| 通知授权 | 进转录页请求 `POST_NOTIFICATIONS`，模拟器实测到系统弹窗；ALLOW 之后转录期间前台通知可见（`dataSync` 类型） | 弹窗截图 + `dumpsys notification` |
| 构建与测试（**同一份代码树**，21:00 那一轮） | `:app:assembleDebug` + `:app:testDebugUnitTest --rerun-tasks` **BUILD SUCCESSFUL in 17s**；XML 汇总 **19 个类 / 216 用例 / 0 failures / 0 errors / 0 skipped**（新增 `TranscriptDisplayTest` 13）。仪器化 `am instrument -w -e package` **OK (106 tests)**，`Time: 95.147`。逐类：`PlaybackRepositoryTest` 21、`TranscriptRepositoryTest` 19、`AudioBookDaoTest` 11、`AudioRepositoryImportTest` 9、`TranscriptCoordinatorTest` 9、`SherpaOnnxEngineTest` 9、`VadSegmenterTest` 6、`Migration_2_3_Test` 5、`PcmDecoderTest` 4、`ModelManagerNetworkTest` 4、`SubtitleRepositoryTest` 4、`Migration_1_2_Test` 3、`TranscriptForegroundServiceTypeTest` 2（合计 106）。⚠ 仍走 `am instrument` 而不是 `connectedDebugAndroidTest`：后者会 `deletePackageX` 把模型、素材连数据一起删（§2b） | 测试 XML 汇总 + instrument 尾行与逐类点号 |

**这一轮没做 / 做不了**：

- `TranscriptWorker.doWork` 主体、`isStopped` → 取消位、意外异常补 `FAILED(UNKNOWN)`、`cancelWorkById` 撤销路径**仍然没有自动化用例**（新增那 2 条只测清单属性）。它们这一轮有了**设备证据**（前台通知可见、worker 日志「跳过：任务 … 的状态是 Failed，不该再跑推理」、中途取消真的停下来），但那是人工点出来的，不是用例。
- `schedule()` 现在有生产调用方了（转录页 ViewModel），但 `APPEND` 排队语义仍无用例。
- **「转录与播放同时在界面上跑不卡顿」（FR-509）没测**：验证转录时播放器没在放；§7 那条 `dumpsys gfxinfo` 主线程无阻塞同样还是空白。
- ~~重复入队缺仓储层去重~~ → **不缺（2026-10-15 复核修正，文档此前漏改）**：`enqueue` 的 `activeOf` 守卫在 16ee324 就已落地并有仪器化用例（`重复入队返回同一个活动任务而不是排两个队` / `已就绪的书再入队被拦住而force放行`），QUEUED/RUNNING 重复点击返回同一行不新建，READY 拦截 + force 放行也早有用例。
- `OUT_OF_MEMORY` 分支、朗读型切句阈值标定、m4a/FLAC 容器仍然没动。

### Phase 6e（模型页 + 243 MB 下载验证，任务 #24）：`ModelManager.statuses` 第一次有生产消费者（2026-10-15 23:30 ~ 24:00）

生产改动四处：新增 `ui/settings/{SettingsScreen,SettingsViewModel}.kt`（`ModelDisplay.kt` 是上一轮留下的文案层，本轮接上）、`MainActivity.kt` 设置路由换成真页面、新增 `ui/common/UiLog.kt`（`safeLogW`）、`TranscriptViewModel` 的 `Log.w` 一并换掉。新增测试：JVM `ModelDisplayTest`（15 条）+ `SettingsViewModelTest`（8 条，**真 ModelManager + 假 fetcher + TemporaryFolder + 假时钟**，不用假接口测空壳）。

| 验收点 | 结果 | 凭据 |
| --- | --- | --- |
| 设置页渲染真实状态（不另算一个数） | 两包「已就绪」、逐文件「已核对通过」、`tokens.txt` 如实标「官方没有发布校验值，用的是本机首次取回时钉住的那份」、来源只报主机名（hf-mirror.com / github.com），就绪的包**没有**下载按钮只有「导入/删除」 | `uiautomator dump` 全文 + 截图 `/tmp/shot_settings.png` |
| 缺文件的诚实显示 | `run-as rm model.int8.onnx` + 重启后：包「不可用」、该文件行红字「model.int8.onnx 不存在」、`tokens.txt` 仍「已核对通过」（**不是整包一刀切**）、按钮变「继续下载（232 MB）」与「删除残留文件」 | `uiautomator dump`（23:49） |
| **App 自己下载 243 MB（原 Phase 6 缺口②）** | 点「继续下载」→ 进度行 `正在下载 model.int8.onnx：23.3 MB / 232 MB（10%）` + 真进度条（截图 `/tmp/shot_downloading.png`），磁盘上是 `model.int8.onnx.part`；**~130 s 后改名**（App 内大小 + 整份 SHA-256 都过了才改），包回「已就绪」；`download-provenance.properties` 新增 `url/bytes=243371218/sha256/checksum=official/fetched_at_ms`；**宿主机独立 `shasum -a 256` 与清单官方值逐字符一致**（`f36a0433…5945`） | `run-as ls`（t=60s/120s 仍是 `.part`，130s 变正式名）+ provenance `cat` 全文 + 本机 `shasum` 输出 |
| 下载中的交互约束 | 下载中：本包按钮收起、另一包的「导入/删除」禁用（`busy` 全局一把）；总大小已知报百分比，与转录页「分母未知不报百分比」同一套规矩 | 截图 + `ModelDisplayTest` 的 `总数未知只报已收字节不报百分比` |
| 用户数据无恙 | `那些花儿` 仍 `READY`、`subtitle_segment` 35 行 `source=ASR` 在库；书架、播放状态原样 | `adb exec-out run-as … cat databases/echomind.db` + `sqlite3` 查询 |
| 构建与测试（同一份代码树） | `:app:assembleDebug` + `:app:testDebugUnitTest` **BUILD SUCCESSFUL**；XML 汇总 **21 个类 / 239 用例 / 0 failures / 0 errors / 0 skipped**（216 → 239 = `ModelDisplayTest` 15 + `SettingsViewModelTest` 8）。仪器化不涉及（本轮没有新仪器化用例，总数仍 106） | 测试 XML 汇总 |

**本轮挖出的坑**：VM 失败路径的 `android.util.Log.w` 在 JVM 单测里抛 "not mocked"，把 `runCatching` 的 fold 结果炸掉、表现为 `message == null`（`TranscriptViewModel` 同型代码存在但从未被 JVM 测到）。修法是 `ui/common/safeLogW` 包一层，**没有**开 `testOptions.isReturnDefaultValues`（build.gradle.kts 的注释写明不靠开关掩盖问题）。

**这一轮没做 / 做不了**：
- 「删除」按钮没在设备上按下（要删的是刚下回来的 243 MB 真文件，删了没网络就得重新 `adb push`）；SAF 导入同样只有 JVM 证据——两条的真机动作留给下一轮。
- 下载中途取消、断网恢复、候选源 fallback（hf-mirror 失败后试 huggingface）没在 UI 上演过。
- `MODEL_MISSING` 的完整闭环（删模型 → 转录页文案 → 按指引回设置页恢复）没连起来走。
- VM 的 `message` 走 Snackbar，转屏（配置变更）会丢——与转录页同款已知行为，Phase 7 一并处理。

### Phase 6f（模型内置 APK + 去 INTERNET，用户决策 D39）：飞行模式下就位与恢复（2026-10-16 00:05 ~ 00:30）

用户决策「模型直接内置，不用依赖网络下载」。生产改动：模型三文件进 `assets/models/<specId>/`（不入仓库）；
`app/build.gradle.kts` 加 assets 缺失/大小不符即失败的检查；`ModelManager` 新增 `ensureProvisioned`/`provision`
（复用 `.part` 规矩，`copyAndCheck` 顺手改为 `finally` 清半截文件）；`applyVerifications` 补复位 `isImporting`（真 bug，见下）；
`EchoMindApp.onCreate` 启动就位；主清单**删除 `INTERNET` 权限**；模型页按钮「下载/导入」→「从安装包恢复」，SAF 选择器移除；
`ModelManagerNetworkTest`（4 条仪器化）删除；CI 加「取回并校验内置模型」一步（缓存 + `sha256sum -c`）。
测试：`ModelDisplayTest` 重写（恢复语义 + 「不许出现下载字样」断言）、`SettingsViewModelTest` 重写（assets 恢复/缺文件/坏内容/删除后恢复）、
`ModelManagerTest` 新增 provision 5 条 + 忙位落定回归 1 条；**JVM 239 → 241 全绿**（BUILD SUCCESSFUL），仪器化 106 → 102。

| 验收点 | 结果 | 凭据 |
| --- | --- | --- |
| **飞行模式冷启动自动就位** | `airplane_mode_on=1` 下 `rm -rf files/models` → 冷启动 → ~1 分钟内三个文件 + 两份 provenance 落盘，字节数与清单一致（`model.int8.onnx` 243,371,218 B）；设置页两包「已就绪」 | `run-as ls -la`（00:19）+ provenance `cat`（`apk_asset=models/…/model.int8.onnx`、`bytes=243371218`、`checksum=official`）+ UI dump |
| **删单文件 → 页面按钮离线恢复** | `rm model.int8.onnx` → 切走再切回设置 → 「不可用」+「model.int8.onnx 不存在」+「从安装包恢复（232 MB）」（截图 `/tmp/shot_missing_offline.png`）→ 点下去 ~20 s → 「已就绪」，文件 243,371,218 B 原样回来（截图 `/tmp/shot_restored_offline.png`），全程飞行模式 | UI dump 两次 + `run-as ls`（00:23） |
| 来源记录三键不混 | 恢复写 `apk_asset=<asset 路径>`，与在线 `url`、SAF `local_import` 区分；`tokens.txt` 仍如实标 `checksum=pinned-by-first-download` | provenance 全文 |
| 主清单无 INTERNET | 合并后的 App 不再声明 `INTERNET`；飞行模式是冗余保险而非依赖 | `AndroidManifest.xml`（删权限的注释留在原位说明原因） |
| 用户数据无恙 | `那些花儿` 仍 `READY`、35 行 `source=ASR` 字幕在库 | `run-as cat databases/echomind.db` + `sqlite3` |
| APK 体积 | 40,9xx,xxx → **267,493,659 B**（约 267.5 MB）——内置方案的固有代价，记入 D39 与技术债表 | `ls -la app/build/outputs/apk/debug/app-debug.apk` |

**本轮挖出并修掉一个真 bug**：`applyVerifications` 在「一次操作做完」时只复位 `isDownloading`，**从不复位 `isImporting`**——
恢复成功后包永远显示「恢复中」（文件其实已「已核对通过」）。旧 `importFile` 路径只在异常分支复位它、成功分支同样漏，
但那时导入没有 UI 所以从未露脸；内置化让 `isImporting` 第一次出现在用户界面上，bug 跟着落地。已修（成功收尾两个忙位都落定）
并配回归用例 `恢复成功后忙位落定不许卡在恢复中`。
**还挖出一个自己的坑（非代码）**：`adb shell run-as … sh -c 'ls files/models/*/'` 的单引号被本地 zsh 吃掉，
设备端 `sh -c` 只收到 `ls` 一个词，等于反复列 App 根目录、`test -f` 恒假——一度误判「就位没发生」。
教训：`run-as` 后面的命令直接给（它自己就是命令入口），不要套 `sh -c` 加引号。

**这一轮没做 / 做不了**：
- 「删除」按钮本体（含确认对话框）没在设备上按下——它删的是刚恢复回来的 243 MB；删除逻辑有 JVM 用例（`删除只动这个包的目录`）。
- 「转录页 `MODEL_MISSING` 文案 → 按指引去设置页恢复 → 回来重试成功」的完整闭环没连起来走（三段各自都有证据）。
- assets 被打包工具弄坏（内容坏了但大小恰好对上）只有 JVM 用例（SHA-256 闸门），设备上没有对应场景。

### Phase 6g（首次播放实时转录并显示 + APPEND 毒化链修复，2026-10-16 11:00 ~ 12:00）

生产改动：新增 `domain/transcript/LiveTranscriptStore`（纯 Kotlin 内存预览账本）；`TranscriptCoordinator` 每识别完一个 VAD 段单段切句发布、收尾 `clear`（放 `finally`，所有结局一致）；`EchoMindApp`/`AppGraph` 装配；`PlayerViewModel` 新增自动发起判定（三无才发）+ 实时窗口流 + 状态行四态（`startTranscription` 做成 lambda 是为了 JVM 可测）；`PlayerScreen` 字幕区接状态行与实时预览窗口；**`TranscriptWorkScheduler` 的 `APPEND` → `APPEND_OR_REPLACE`、`TranscriptWorker` 失败返回 `success`（真雷，见下）**。

| 验收点 | 结果 | 凭据 |
| --- | --- | --- |
| **自动发起（三无判定）** | SAF 文件夹 `cp` 一份真语音 → 重扫出新书（NONE、无任务、无字幕）→ 进播放页自动入队开跑；转过/取消过/有测试字幕/已 READY 的书都不发（4 条 JVM 判定用例） | 设备端到端 + `PlayerViewModelTest` |
| **实时预览逐段增长、收尾清场** | 仪器化新用例（收集器必须在 `Dispatchers.IO` 上：`runBlocking` 的事件循环在阻塞解码期间不会跑别的协程——写这条用例时踩的第一个坑）：运行中快照序列只涨不缩、run 返回后预览清空、库里 35 句 | 单类 `OK (1 test)` + 整套件 `OK (103 tests)` |
| **播放页实时显示（UI）** | t+3s「正在转录：已识别 0 段 · 已出 0 句」（模型装载中，如实显示不画假进度）；t+12s「已识别 1 段 · 已出 6 句」+ 歌词实时流出、当前句高亮；t+20s SUCCESS、状态行退场、「35 句」正式字幕接管（点句跳转/七句窗口复用） | 截图 `/tmp/shot_live_1.png`（0 段）、`/tmp/shot_live_4.png`（1 段 6 句）+ UI dump 三连 |
| **入库与状态** | 24 段全部识别、35 句 `source=ASR` 落库、书变 READY（有一轮 7.2 s 跑完 24 段，RTF≈0.05——链路无人为减速） | `run-as sqlite3` 查询 |
| **APPEND 毒化链（真雷）** | 第一次端到端时任务卡 QUEUED、worker 从不启动、logcat 无任何 worker 日志、`work_request_id` 已正常写回——**链里有 6d 的一次取消，`APPEND` 把新任务连带取消**（与 WorkManager 文档注记一致；技术债表里旧注「引用文档」的语义此前被理解反了）。修：`APPEND_OR_REPLACE` + worker 失败返回 `success`；复测自动转录即时开跑 | 修复前后 DB 对比（QUEUED 不动 → SUCCESS）；`architecture.md` D36 补修正 |
| 构建与测试 | `:app:assembleDebug` + `:app:testDebugUnitTest` **BUILD SUCCESSFUL**；JVM **22 个类 / 250 用例 / 0 failures**（241 → 250：`LiveTranscriptStoreTest` 6 + `PlayerViewModelTest` 重写 +9）；仪器化 **OK (103 tests)**（102 → 103：协调器实时预览用例） | XML 汇总 + instrument 尾行 |

**诊断过程（「排队中」假象）**：UI 显示「转录排队中（另一本书正在转录）」→ 查库**没有任何 RUNNING 行** → `work_request_id` 已写回说明 `schedule()` 正常执行 → WorkManager 完全没有 worker 日志 → 唯一特殊史是 6d 的一次用户取消 → 查 WorkManager 文档：`APPEND` 下「链被取消则新任务连带取消」。这条正是技术债表里「`APPEND` 排队无用例（引用文档）」欠下的那笔。
**测试痕迹清理**：卡住的 QUEUED 行靠 `recoverInterrupted()` 在重启时标 `FAILED(INTERRUPTED)`（顺带验证了它），再用 `run-as sqlite3` 删行、书状态复位 NONE 后重测（模拟器有 `/system/bin/sqlite3`，`run-as` 下直接可写 App 数据库）。

**这一轮没做 / 做不了**：
- 实时转录 + 播放同时跑的掉帧采样（FR-509）没测——这个功能让「播放 + 转录同时跑」成了产品默认路径，Phase 7 的 gfxinfo 采样优先级上调。
- 预览期间拖动进度条跳到未识别区域没专门验（窗口走「首句之前不补位」的既有逻辑）。
- `MODEL_MISSING` 失败在播放页的文案只有 JVM 用例（模型已内置，设备上造不出来）。
- 上一轮（6e/6f）遗留：删除按钮本体、`MODEL_MISSING` 三段闭环——继续挂账。

### 格式支持（m4a/flac/wav 放行 + WMA 探针，2026-10-16 12:15 ~ 12:40）

生产改动一处：`AudioFormats.SUPPORTED_EXTENSIONS` = `mp3, m4a, aac, flac, wav`（白名单唯一出处）；`ShelfScreen` 空态文案同步。
新增测试：仪器化 `asr/DeviceCodecProbeTest`（枚举设备解码器 + 断言无 WMA + 断言四类必备解码器在）；JVM walker 新用例
`生产白名单的格式全部收进来且大小写不敏感`（真实清单：五种格式 + 大写 .WAV 照收、wma/ogg/txt 照丢）。

| 验收点 | 结果 | 凭据 |
| --- | --- | --- |
| 新格式导入 | 三份素材（`afconvert` 自同一份 148 s 真语音）重扫入库，时长全部读出（148,637~148,701 ms） | `run-as sqlite3` 查 `audio_book` |
| m4a 端到端 | 首播自动转录 → SUCCESS、38 句 `source=ASR`、READY | DB 查询（job `SUCCESS\|23 段`、字幕 38 行） |
| flac 端到端 | 同上（SUCCESS、38 句、READY） | 同上 |
| wav 端到端 | 同上（SUCCESS、24 段、38 句、READY） | 同上 |
| **WMA 无解码器（证据）** | 设备 39 个解码器可解类型 `[3gpp, amr-wb, flac, g711-alaw, g711-mlaw, mp4a-latm, mpeg, opus, raw, vorbis]`——**无 `x-ms-wma`**，断言钉住 | logcat `DeviceCodecProbe` + `DeviceCodecProbeTest` 绿 |
| 构建与测试 | JVM 全绿（walker 用例进 `AudioTreeWalkerTest`）；仪器化 `DeviceCodecProbeTest` `OK (1 test)` | gradle 输出 + instrument 尾行 |

**过程坑**：①新仪器化类一度 `ClassNotFoundException`——根因是 `MediaCodecInfo.isDecoder` 不存在（只有 `isEncoder`）导致
`compileDebugAndroidTestKotlin` 失败，而当时链式命令里 gradle 失败被 `tail`/`grep` 吞了、`adb install` 装的还是旧 APK；
教训：链式命令里 gradle 的 BUILD 状态必须在 install 之前显式检查。②写 WMA 探针查的是「解码器枚举」而不是造 wma 文件试
MediaExtractor——前者是能力的直接证据，后者只证明容器解析失败，混起来会得出「能/不能」的假结论。

### WMA 支持（libwma + 导入转码 + 解码转录，D40，2026-10-16 19:00 ~ 19:30）

生产改动：`app/libs/wma-jni/arm64-v8a/libwma.so`（自建，哈希钉住）+ `cpp/wma_jni.c` JNI 壳 + `asr/WmaJni/WmaPcmDecoder/WmaTranscoder`
+ `data/repo/WmaPlaybackStore`（路径约定）+ 导入编排 WMA 钩子（NEW/CHANGED 都转码，失败跳过该本并打日志）+
播放队列 URI 换路 + `removeBook` 清转码产物 + 白名单加 `wma`。构建检查：缺 so/大小不符直接 GradleException。

| 验收点 | 结果 | 凭据 |
| --- | --- | --- |
| 解码正确性（对照宿主机） | `WmaPcmDecoder` 解 148 s wma：44100 Hz/双声道/时长 148,637 ms，16k 输出 **2,377,724 样本 vs 宿主机同解码器参考 2,377,723（差 1，重采样取整）**，非静音 | `WmaDecodeTest` `OK (2 tests)` + logcat |
| 转码产物可读 | `WmaTranscoder` 产 m4a：MediaExtractor 读出 `audio/mp4a-latm`、时长 147~150 s 窗口内 | 同上第 2 条 |
| 导入转码 | SAF 文件夹放 .wma → 重扫入库：时长 148,636 ms（**来自转码器**，Media3 读不出 WMA 时长所以 `durationReadFailed=0`），`files/transcoded/<id>.m4a` 生成 | `run-as sqlite3` + `ls` |
| 播放走转码产物 | 首播 WMA 书：播放位置持续推进（43 s 处采样）——ExoPlayer 播的是 `transcoded/<id>.m4a` | DB `playback_state.last_position_ms` + UI |
| 转录走 libwma | 首播自动转录：SUCCESS、26 段、**42 句**、丢弃 0、READY（句数与 mp3 的 35 不同是解码样本流随格式浮动的正常现象） | DB + `EchoMindTranscriptWork: 转录成功` 日志 |
| 构建与测试 | JVM **253 全绿**（+2：wma 播放 URI 换路/非 wma 原样）；仪器化 `WmaDecodeTest` 2 条 + 整套件见尾行 | XML + instrument 尾行 |

**过程坑（三条都进了代码或文档）**：
1. `MediaCodecInfo` 没有 `isDecoder`（只有 `isEncoder`），新探针类编译失败曾被链式命令的 `tail`/`grep` 吞掉——
   教训：链式命令里 gradle 的 BUILD 状态必须在下一步前显式检查。
2. SAF 输入走 `/proc/self/fd/<fd>` 让 FFmpeg 的 file protocol 重开路径：**file:// 素材能过（应用自有目录）、
   SAF content:// 必挂**（scoped storage 沿符号链接重开被拒）——正解是自定义 AVIO 直接在 fd 上 read/seek。
   同一类坑第二次出现（第一次是 m4a/flac/wav 用应用自有目录的 file uri，掩盖了它）。
3. 改 JNI 签名后忘重编 .so：Kotlin 传 jint、旧 so 按 jstring 解 → JNI abort。`jniLibs` 在构建期打包，
   改 C 后必须重编 so 再重编 APK。

**这一轮没做 / 做不了**：
- 转码产物缺失的复活路径（清数据后重扫不重转，播放会失败）——已知边角，Phase 7 加检测。
- 真机上的 libwma 兼容性（arm64 通用 NEON，理论无差）——真机验证仍是 Phase 7 整体欠账。
- WMA Pro / WMA Lossless（wmav3/wmapro）不在解码器清单里，.wma 之外的标准变体不支持，如实记录。

### 字幕时间轴锚 PTS（用户反馈：mp3 后段字幕比语音快一两行，2026-10-16 19:35 ~ 20:00）

**排查过程（全部有数据）**：
1. `PcmTimelineProbeTest`（新仪器化探针，可测任意 URI）：解码流样本数 ↔ 容器 PTS 逐段对照——
   `那些花儿.mp3` 全程漂移 **0.0 ms**，最大单帧 PTS 步进 26.1 ms；
2. 同一首歌六种格式各自转录的字幕**逐句对照**：mp3 与 wav 前三句时间**完全相同**（14134/21890/25930），
   mp3 与 wma 的对齐句（「在我生命每一个角落」21858 vs 21890）一致——三条解码时间轴互证无系统性偏差；
3. wma 版多出的两句（6285「我不道。」/12374「嗯。」）是前奏器乐的幻觉识别（WMA 有损频谱差异 → VAD/识别
   行为浮动），非时间轴错误——宿主机包络互相关（相关系数 0.927、最优偏移 0 桶）证实 wma 文件内容与 mp3 逐秒对齐。

**结论与修复**：我们的测试文件上字幕时间轴与音频对齐；用户文件上能产生该症状的机制是「解码样本计数短于
容器 PTS」（VBR/坏帧）。修复 = `domain/asr/PtsTimeline`（纯 Kotlin，6 JVM）+ `PcmDecoder` 输出循环锚定：
输出缓冲的 PTS 超前流内位置 >20 ms 就垫等长静音（静音进 VAD 无副作用），PTS 倒退重锚。
**干净文件零变化**：`PcmDecoderTest` 4 条、协调器端到端（24 段 → 35 句、丢弃 0）、探针（漂移仍 0.0 ms）
全部复跑一致；整套件 **OK (107 tests)**；JVM 253 → **259**。

**用户真实文件已入库为常驻回归素材（2026-10-16 项目主人拍板保留）**：
`test-data/0906 多动的孩子也能好好学【音频】.mp3`（56.2 分钟，32 kHz，117.9 kbps）与
`test-data/如何培养孩子的社会能力.wma`（34 分 21 秒，wmav2 44.1 kHz 双声道——libwma 的真实世界素材）。
用法：推上设备后交给时间轴探针或端到端用例——
```
adb push "test-data/0906 多动的孩子也能好好学【音频】.mp3" /sdcard/Android/data/com.xinchen.echomind/files/asr-fixtures/user-test.mp3
adb push "test-data/如何培养孩子的社会能力.wma" /sdcard/Android/data/com.xinchen.echomind/files/asr-fixtures/user-test.wma
adb shell am instrument -w -e probe_name user-test.mp3 -e class com.xinchen.echomind.asr.PcmTimelineProbeTest ...
```
（`test-data/` 已进 `.gitignore`：现有两份跟踪保留，目录里将来新增的文件不会被 `git add -A` 连带提交。）

**用户真实文件的探针结果（test-data/ 的 56 分钟书解 mp3，32 kHz）**：解码样本数 ↔ PTS 全程漂移仅 **72 ms**
（RTF 视角：107,956,224 样本 / 3,373 s，32000 Hz/2ch）——解码侧机制在他们自己的文件上同样**不成立**。
PTS 锚定作为「构造上保证字幕时间轴 = 播放时钟」的加固保留；用户观察到的秒级提前若复测仍在，
下一步必须拿他们测试时的具体文件与设备（模拟器 or 真机、是否蓝牙/倍速）复现。

**仍可能造成「字幕提前」体感的非 bug 因素（如实记录）**：
- 字幕换行有 250 ms 的提前量（`SubtitleSynchronizer.GAP_ENTER_MARGIN_MS`，防抖设计）；
- 模拟器/蓝牙/部分真机的音频输出缓冲会让「听到的」滞后于 positionMs 若干百毫秒——
  这是播放环境的固有属性，不是字幕时间轴错误；若用户文件复测仍偏移，需要拿到那份 mp3 探针定位。

## 5. 待补的自动化测试清单（按阶段）

| Phase | 用例 |
| --- | --- |
| 3 | **已覆盖**：递归 3 层子目录 + 扩展名白名单 + 深度/数量截断；连续扫描不产生新记录；同一 URI 重复出现只算一次；size/mtime 变化 → CHANGED 且字幕标 EXPIRED、手改标题不被覆盖；文件消失 → MISSING 且不删记录；文件原样回来 → MISSING 翻回 PRESENT；跨文件夹重复文件留在原文件夹；授权无法持久化 → 导入失败不写库；重扫跳过授权失效文件夹但继续其他文件夹；时长读取失败 → `duration_read_failed`；书架 tab/搜索状态与查询同源。**待补（依赖 Phase 4 的会话表）**：消失文件的收听历史仍然保留 |
| 4 | **已覆盖**：4.9s 不计次 / 5.0s 计次；缓冲+暂停+焦点丢失不计时；2x 播 10min → `listened_ms=600000`、`consumed_audio_ms=1200000`；拖到末尾不完播；1.5x 听完 → 完播；两次听完 → 完播 2；崩溃恢复不重复累计（含幂等与「不结算之后的新会话」）；重算与增量一致；重算能修正被写坏的缓存；切歌结束旧会话；删书保留 `listening_session`；统计只算已结算行；倍速只落自己那一列；时长未知不覆盖已知时长。**待补（最大的一块）**：`Media3PlaybackController` 零测试——需要 Media3 `SimpleBasePlayer` 当 `Player` 替身才测得动，而「自动切歌不换倍速」这个真 bug 正是因为没有这层测试才只能在设备上撞见。另待补：消失文件的收听历史仍然保留 |
| 5 | **已覆盖**：首句/中间/末句窗口与不补位；空字幕表与「位置还在首句之前」（`currentIndex=-1`）；gap 内不跳动、越过 `start-250ms` 才切；seek 后窗口更新；5000 句二分正确性；换曲后重挂字幕流；导入解析（SRT/VTT/TSV、缺小时格式、一位小数毫秒、乱序重排、`end ≤ start` 整份拒绝、空文件失败）；单事务整本替换；删书连带删字幕且历史留着。**待补**：真实 ASR 产出的时间重叠/倒退（要 Phase 6 的模型输出）、长句换行的高亮目视（fixture 每句都短）|
| 6 | **已覆盖（2026-10-15，纯逻辑层 → 解码层 → VAD → 真推理 → 任务状态机）**：分段绝对偏移（第 N 段不从 0 开始、180 段零漂移、块间不重叠不留缝）；>20s 段均分且各块长度最多差 1 样本；空段/负起点段跳过；`msOf`/`samplesOf` 换算与取整；16-bit LE 字节序与 ±1 缩放；多声道均值下混（反相 → ≈0、单侧 → 减半）与尾字节如实报出；重采样恒等性/常数增益/通带与阻带频响/分块==一次性/生产调用形状/`reset()`/输出长度 `ceil(N·out/in)`；**设备上真 MP3 解码**（148s 立体声素材：样本数对账偏差 0.22%、581 块首尾相接、幅度落在 ±1、`peak=0.659`、单缓冲上界 4,608 B 与文件长度无关、取消只交付 3 块、非音频文件给人话异常、静音素材解出全零）；**设备上真 VAD**（9 段、起点=16k 样本下标且 `%16==0`、段不重叠、`maxSpeechDuration` 生效、不同喂入块长结果一致）；**设备上真推理**（逐字 `timestamps` 单位=秒、落在自己块内、跨块单调、`durations` 空所以 `endMs` 取下一字起点、>20s 与样本数不符被拒）；**转录任务状态机**（入队/重复入队/已有 ASR 字幕拦截/只有测试字幕不算/书不存在；RUNNING→进度→SUCCESS 与字幕归属互相对得上；重转录整本替换；空字幕拒收且不碰已有字幕；重复结算整体回滚；非法跃迁返回 false；重试新建一行保留旧错误上下文；取消按 NONE/READY/EXPIRED 推导；重启恢复标 INTERRUPTED 且幂等；VAD 段数为 0 时不画假百分比）。**已覆盖（2026-10-15，端到端 9 条仪器化 + 9 条 JVM）**：整首歌跑完整条链并**第一次把模型的字写进生产库**（24 段 → 24 块 → 丢弃 0 → 35 句入库，索引连续 / 时间升序 / 不越出容器时长）；三道守护（授权缺失、模型缺失、文件不在原位）都在装载模型之前返回且不写字幕；`markRunning` 抢不到行就收手；`recoverInterrupted` 之后复活的 worker 覆盖不掉 INTERRUPTED；**跨书并发抢不到第二行 RUNNING**（D36，两条用例并对「删掉闸门」的构建证过伪，见 §4 Phase 6c-4）；取消位为真时停手不写终态；静音素材 → `OUTPUT_UNRELIABLE` 而不是 SUCCESS；中途 `total_segments` 恒 0 的如实上报；质量闸门的阈值边界与零句判定（JVM）。**已覆盖（2026-10-15，Phase 6d 转录页，见 §4）**：进度文案的诚实性（排队中 → null；分母未知 → 「已识别 N 段」且**不含 `%`**；分母出来 → `已识别 3/10 段（30%）`；成功 → `共 7 段全部识别`）；五种状态标签互不重名；可取消/可重试矩阵；**每种失败都给得出一个动作**（8 个 `TranscriptFailure` 分支逐个断言含「重试」或等价动作）；`OutputUnreliable` 文案里的阈值来自 `AudioFormats.ASR_MAX_REJECTED_CHUNK_PERCENT`；`AlreadyTranscribed` 文案里嵌的按钮名与 `startButtonLabel(Ready)` 同源；只有 READY 需要 `force`；Expired 文案说清「旧字幕会被整本替换」且不含「覆盖重转」；**装好的包里 `SystemForegroundService` 声明了 `dataSync`**（仪器化，读已安装清单——这是「点开始转录直接崩」那条 bug 的回归闸门）。**待补**：`work/TranscriptWorker.doWork` 主体、`isStopped` → 取消位、意外异常补 `FAILED(UNKNOWN)`、`cancelWorkById` 撤销路径**仍无用例**（这一轮只有人工点出来的设备证据）；`APPEND` 排队语义无用例（`schedule()` 现在有生产调用方了）；~~重复入队没有仓储层去重~~ → **已修正（2026-10-15 复核）**：`activeOf` 守卫 16ee324 就有且有用例，「第二次点会新建一行」对 QUEUED/RUNNING 不成立；「一行卡在 RUNNING 把后面所有转录挡住」没有用例（要绕过协调器才能造出这种行）；`OUT_OF_MEMORY` 分支无用例；切句阈值的**朗读型真人语音标定**（500ms/×2/700ms 现在只有一份歌唱素材撑着，见 §4 Phase 6c-2）；`boundaryGuess` 落库（要 v3→v4 迁移）；真 ASR 结果入库后的字幕时间对齐误差人工抽查（FR-506）；m4a/FLAC 容器；**模型页的设备实测尾巴（2026-10-15，6e 定、6f 改口径）**：~~SAF 选文件导入、下载中途取消/断网/fallback~~（→ 6f 内置化后两者已无产品入口，不再欠）；删除按钮本体（确认对话框在设备上按下）；`MODEL_MISSING` 从出现到恢复的完整闭环 |
| 迁移 | **已覆盖 MIGRATION_1_2**：`MigrationTestHelper` 对导出 `2.json` 校验（`recreateTableOnMigrationError=false`）、已有书籍与收听历史保留、FK 与索引存在。**已覆盖 MIGRATION_2_3**：结构对 `3.json`、字幕逐句不丢且 `id` 原值保留、删书带走字幕与任务（CASCADE）、**删任务只把 `job_id` 置空不删字幕**（SET NULL）、两条 FK 与三条索引都在（断言时滤掉 `sqlite_autoindex_*`）。规则不变：每条新 Migration 都要有对应测试，`fallbackToDestructiveMigration` 保持不出现 |

## 6. 只能人工/真机验证的项（尚未执行，勿声称完成）

- [ ] 蓝牙耳机拔出/断开实际暂停（`ACTION_AUDIO_BECOMING_NOISY`）。**模拟器原理上做不到**：受保护广播 + `adb emu` 无耳机命令；代码路径存在但一行实测都没有。
- [ ] 其他 App 长期占用焦点（`AUDIOFOCUS_LOSS`）时的表现。来电（`LOSS_TRANSIENT`）已在模拟器实测，长期占用没造出场景。
- [ ] App 到后台 30 分钟持续播放不被系统杀（只验证了「切后台仍播 + 划掉任务后进程存活」，没有做长时间）。
- [ ] 2–3 GB RAM 真机上模型加载峰值 RSS、转录 RTF。**已有一半**：这台 `drill36` AVD 的总内存就是 2,531,868K（≈2.42 GB），实测峰值 PSS 442 MB / RSS 559 MB（见 §7 与 §6b），量级落在目标机型范围内；**换手机、换 SoC 后的 RTF 仍未测**，243 MB 模型在低存储机型上的装载耗时与是否被 lowmemorykiller 杀掉也仍未测。
- [ ] 飞行模式下：已导入音频播放 + 已有字幕阅读全部可用（MVP 验收 10）。
- [ ] 撤销 SAF 授权后的降级提示与重新授权流程。**仍未做**：需要真机或 `appops`/系统设置里手动撤销。目前只有仪器化测试证明「授权缺失时：导入抛 `PermissionRevokedException` 且不写库、重扫把该文件夹标成 REVOKED 并跳过、其他文件夹照常扫」，以及 UI 提示「文件夹授权已失效，请重新授权」。
- [ ] 大目录（≥500 个 mp3）扫描耗时与内存。
- [ ] 播放中发起转录不卡顿（FR-509）与转录期间主线程无阻塞。**界面入口已经有了**（任务 #23），但这一轮验证转录时播放器没在放，也没采 `dumpsys gfxinfo`（上一轮在仪器化进程里采它回 `No process found`，这一轮 Activity 是在渲染的，能采——只是没采）。见 §7。

### 6b. 已在模拟器实测、不再列为「只能真机」的项

- [x] 来电抢占焦点 → 暂停并结算会话、恢复不自动播放（FR-305）：`adb emu gsm call/cancel` + `dumpsys audio` + 位置三点采样，见 §4 Phase 4。
- [x] 后台播放不被静音、通知栏可控制（FR-304）：`dumpsys notification` 显示前台通知，通知的暂停/播放按钮实测有效。
- [x] 七句窗口跟随播放/拖动/倍速/切歌，点句跳到该句 `start_ms`（FR-701/702/705/706）：`uiautomator dump` 行数与文本 + 截图 + `run-as sqlite3` 读 `playback_state.last_position_ms` 复核，见 §4 Phase 5。
- [x] 冷启动续播位置与退出前差 ≤10s（FR-309）：真 `am force-stop` + 冷启动实测，差 3394 ms，见 §4 Phase 5。
- [x] 每首独立倍速在重开与自动切歌后都跟着换（FR-303）：设备实测 `dumpsys media_session` 的 `speed` 与 `playback_state.playback_speed` 对齐（顺带修掉「自动切歌不换倍速」的 bug，见 §4 Phase 5）。
- [x] 模型装载与转录期间的内存峰值（Phase 6）：仪器化端到端**边跑边采样** 37 次 `dumpsys meminfo com.xinchen.echomind`，PSS 曲线 `85 → 112 → 139 → 401 → 400 → 433 → …（此后 31 个点全在 414~442 之间）`——**一个 ~260 MB 的台阶 + 一条平线**，台阶对应 243 MB 的 `model.int8.onnx` 装载，平线对应 24 段逐个识别；峰值 PSS 442 MB、RSS 559 MB，进程结束后释放。做法：`am instrument -w -e class '<FQCN>#<方法名>'` 放后台，前台 `while kill -0 $PID; do adb shell dumpsys meminfo …; sleep 1.5; done`。
- [x] 首次进播放页会弹通知授权（`POST_NOTIFICATIONS`）：模拟器实测到系统弹窗与 ALLOW 之后的通知栏控制。
- [x] **从界面发起一次真转录**（书架 ⋮ →「转录这本书」→ 转录页「开始转录」→ 排队中/转录中/已完成，35 句 `source=ASR` 字幕进用户库、书变 READY、播放页七句窗口读到它）：见 §4 Phase 6d。
- [x] 转录的前台服务通知（`dataSync` 类型）在 targetSdk 36 上真起来了、转录中途点取消真的停下：见 §4 Phase 6d（顺带修掉「一点开始转录就崩」的清单合并 bug）。
- [x] 底部标签在带参数的转录页上仍能切走（去掉 `saveState`/`restoreState` 之后 navA→navE 逐步复核）：见 §4 Phase 6d。

复现步骤：安装 debug 包 → 按 `requirements.md` 对应 FR 编号逐条操作，结果与截图追加到本节。

## 7. 性能与资源自查项

- 进度写库频率（应 ≤1 次/10s）：**已实测**（Phase 4）——采 App 自己的 `updated_at_ms`（`elapsedRealtime`）差值，稳定在 ~10s；500ms tick 只推 `StateFlow`，不碰库。
- 字幕列表只在当前句变化时重组：**逻辑半边已证**（Phase 5：`PlayerViewModelTest.同一句内部的位置推进不重发窗口` 断言同一句内推进零次重发、跨句才发一次；链路上还有 `distinctUntilChanged` 与 `StateFlow` 的内容相等去重两道）。**帧重计数没测**：Compose recomposition 计数工具未接入，Phase 7 一并做。
- 转录期间主线程无阻塞：**仍然没有数据**（Phase 6d）。这一轮转录终于是**从界面发起**的了（Activity 在渲染），但我没采 `dumpsys gfxinfo`；能说的只有观察级证据——转录跑的那 ~40 s 里底部标签还能切、卡片还能点取消、没有 ANR 弹窗。已有的间接证据仍是两条：`SherpaOnnxEngine.open()` 与 `VadSegmenter` 构造里各有 `check(Looper.myLooper() != Looper.getMainLooper())`（主线程调就直接抛，两条仪器化用例证过），以及 worker 全程 `withContext(Dispatchers.IO)`。要补的是一次「播放 + 转录同时跑」的 gfxinfo/掉帧采样。
- 不整本 PCM 进内存：**采样做过，但要说清它能证明什么**（Phase 6，12:31 那一轮，37 次 `dumpsys meminfo`）：PSS 呈「~260 MB 台阶 + 之后一条平线」，24 段识别的 ~50 s 里在 414~442 MB 之间抖动，峰值 PSS 442 MB / RSS 559 MB（机器总内存 2.42 GB）。⚠ **平线本身不能证明「没整本载入」**——148 s @16 kHz 单声道 float 整本也只有 **9.5 MB**，淹在模型那点噪声里；这条结构性保证来自代码（VAD 流式、codec 单缓冲上界实测 4,608 B、同时驻留的只有当前一段 ≤20 s ≈ 1.28 MB，见 §4 Phase 6 各轮），采样只补充了「装载确实是那笔大头、且跑完会掉下去」。
