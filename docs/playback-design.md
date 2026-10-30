# 播放、后台播放与统计口径（playback-design）

代码位置：`playback/`（系统适配）+ `domain/playback/`（判定逻辑，纯 Kotlin）+ `core/conf/PlaybackPolicy.kt`（阈值唯一来源）。

## 1. 播放器实例拓扑

```
ExoPlayer（全 App 唯一实例）
   ▲
   │ 只由 Media3PlaybackController 操作
   │
PlaybackController（接口）◄── PlaybackUiState(StateFlow) ──► ViewModel ──► Compose
   ▲
EchoMindMediaService（MediaSessionService）── 用同一个 player 建 MediaSession
```

- `PlayerHolder`：`object` 惰性持有 `ExoPlayer`，绑定 `AudioAttributes(USAGE_MEDIA, CONTENT_TYPE_SPEECH, handleAudioBecomingNoisy=true)`（耳机拔出自动暂停，FR-306）、`setWakeMode(WAKE_MODE_LOCAL)`。**不提供 `release()`**：进程活着播放器就活着，服务 `onDestroy` 只 `removeSession`。理由是 ExoPlayer 释放后不可复用（留在 IDLE），而 `playWhenReady` 会被置回 true——页面按钮看起来是「该播了」，点下去什么也不发生。这一条与下面 §4 的「末尾重开」是同一个坑的两面，都由 `resume()` 里显式 `prepare()` 兜住。
- `EchoMindMediaService : MediaSessionService` 复用 `PlayerHolder` 的实例（同进程），因此通知栏控制、媒体键、`MediaBrowser` 客户端（车机/手表将来可用）都作用在同一个播放器上；`onDestroy` 只 `removeSession(this)`，不释放播放器。
- **必须调用 `addSession(session)`**：只把 `MediaSession` 赋给字段，会话本身是能工作（媒体键有效），但**没有媒体通知**，`startForeground()` 也拿不到通知 → 系统按「无前台通知」处理，实测日志是 `AudioHardening background playback would be muted`。`onCreate` 里 `addSession`，`onDestroy` 里 `removeSession`。
- UI **不接触** ExoPlayer 类型；只发命令、收 `PlaybackUiState`。这是需求里「不允许 Compose 直接管理播放器实例」的落点（FR-301）。
- 统计不另设装饰层：`Media3PlaybackController` 自己注册 Media3 listener，把事件翻译成 `SessionTracker` 的输入，并把结算结果投递到 `writes` 通道串行落库。可测试性由 `SessionTracker` 的纯 Kotlin 边界保证（§3.2），而不是靠再包一层控制器——实测多包一层会让「谁持有位置」再次变成两个答案。

## 2. 为什么位置更新不用 100ms 轮询

- UI 进度条：`ProgressRenderer` 每 500ms 读一次 `player.currentPosition`（Media3 自带插值），仅驱动 Compose 状态。
- 字幕同步：见 §5，位置来源同样是 500ms tick + 显式事件（seek/play/pause 立刻推一次）。
- 持久化：每 10s（`POSITION_FLUSH_INTERVAL_MS`）或状态跃迁时写库（FR-412）。

## 3. 统计口径（本项目的核心约定）

### 3.1 两种「时长」，用途不同

| 名称 | 定义 | 用途 | 时钟 |
| --- | --- | --- | --- |
| `listened_ms` | 播放器**真正处于播放状态**的墙上时长，倍速不放大 | 累计收听时长、统计页、有效播放次数判定 | 单调时钟（`TimeSource.monotonicMs()`） |
| `consumed_audio_ms` | 播放期间音频位置**正向推进**之和（拖动/跳段不计） | 完播判定（防「拖到结尾算听完」） | 由 `currentPosition` 差分 |

需求里「倍速播放按实际经过的播放时间计算，而不是乘以倍速后的音频时长」→ 由 `listened_ms` 承担；而「手动拖到结尾不应认定完整收听」→ 由 `consumed_audio_ms` 把关。两个口径分开，才能同时满足这两条。

### 3.2 什么算「播放状态」

只在 `player.isPlaying == true` 且 `playbackState == Player.STATE_READY` 时累加。以下一律**不计时**（FR-402）：

- `STATE_BUFFERING`（缓冲/加载）
- `STATE_ENDED`、`STATE_IDLE`
- 暂停（`isPlaying == false`）
- 音频焦点丢失后（我们主动 `pause()`，因此自然落入「暂停」分支）
- `playWhenReady == true` 但被 Media3 抑制播放（`player.suppressionReason != NONE`）

实现：状态机 `SessionTracker` 只认显式事件（`start` / `onPlayStarted` / `onPlayStopped` / `onDuration` / `onSeek` / `onPosition` / `reachedEnd` / `endSession`），不直接读 ExoPlayer，也不自己读时钟以外的系统能力，因此可在 JVM 单测里用假时钟完整验证（`SessionTrackerTest` 22 项）。控制器侧的计时门条件是 `player.isPlaying && playbackState == STATE_READY && suppressionReason == NONE`，三个条件同源判据与页面按钮文案一致。

### 3.3 会话（ListeningSession）生命周期

```
启动/切歌/首次播放 → startSession(audioId, startPos, duration)         status=ACTIVE
   ├─ tick：isPlaying&&READY → 累加 listenedMs；位置推进 → 累加 consumedAudioMs
   ├─ 每 10s / 每次状态跃迁 → flush（写 last_position_ms + 会话累计值，同一事务）
   └─ endSession(reason)：
        FINISHED      正常播到末尾 **或用户主动停止**（`completed` 列才表示是否真听完）
        INTERRUPTED   焦点丢失 / 耳机拔出 / 用户切歌 / App 到后台且被停
        SKIPPED_TOO_SHORT  listened_ms < 阈值 → is_counted=0（不占播放次数）
        CRASHED       启动时发现的 ACTIVE 残留行，按最后 flush 值结算
```

`session_status` 记的是**怎么结束的**，不是**听完了没有**：用户中途点「停止」也落 `FINISHED`，但同一行的 `completed=0`。所有完播统计（增量与重算两条路径）一律读 `completed=1`，不读 `session_status`。想按状态查历史可以，想按状态数完播次数不行。

规则：
1. **播放次数**：`is_counted = (listened_ms ≥ MIN_VALID_SESSION_LISTENED_MS)`，默认 5000ms。误触 3 秒不算一次（FR-401）。
2. **完播**：`max_reached_position_ms ≥ duration_ms - END_TOLERANCE_MS` **且** `consumed_audio_ms ≥ duration_ms × COMPLETED_CONSUMED_RATIO`。默认 `END_TOLERANCE_MS=2000`、`COMPLETED_CONSUMED_RATIO=0.9`（≈95% 完成率 + 真实听过 90% 内容）。`duration_ms ≤ 0`（时长未知）时**不判完播**，只记进度。
3. **重复完播**：完播判定只在会话内累计，同一首再次听完整程 → `completed_count` 再 +1（FR-408）。
4. **切歌**：`endSession` 旧会话（flush + 结算）先于 `startSession` 新会话，两者在不同事务里但顺序由控制器保证（FR-409）。
5. **聚合缓存**：`playback_state` 的 `play_count/completed_count/listened_ms_total` 在会话结算事务内增量更新；`recomputeAggregates()` 从 `listening_session` 全量重算（SQL 见 `database-design.md` §10），设置页提供入口，用作崩溃后的对账（FR-410）。
6. **进度恢复**：`last_position_ms` 超过 `duration - END_TOLERANCE` 时，UI 视为「已听完」，重新点击播放从头开始（并把 `is_finished` 置位）；这避免每次进入都停在末尾。
7. **位置推进的步长上限**：`consumed_audio_ms` 只累计 `0 < Δposition ≤ MAX_COUNTED_POSITION_STEP_MS` 的正向推进。超过 2s 的跳变当作外部跳转（通知栏进度条拖动、Media3 在 seek 后的插值跳变、缓冲后回放点重定位），只更新基准不计时——否则一次跳跃就能把完播骗过去（FR-407 的实现细节）。我们自己发起的 ±15s / 拖动 / 点字幕走 `onSeek`，语义相同但入口分开，便于单测区分「用户跳」和「系统跳」。
8. **自动切歌的会话归属**：队列里一首播完、Media3 触发 `onMediaItemTransition` 时，控制器先按 `SwitchedAudio` 结算**上一首**，再为**这一首**开新会话（`SessionTracker.start()` 内部也会兜底结算未关闭的上一场，返回给调用方落库，FR-409）。实测跨 6 首连续播放，每首各自的 `play_count/completed_count` 与逐场会话一致。
9. **单曲循环不改口径（FR-310，2026-10-24）**：`REPEAT_MODE_ONE` 下播到末尾 Media3 发的 `onMediaItemTransition(reason=REPEAT)` 还是同一本书，`target.audioId == tracker.activeAudioId` 直接早退——**不结算、不开新会话**，同一场会话跨圈继续累计 `listened_ms`；位置从末尾跳回 0 被第 7 条的步长上限当成跳转、不计 `consumed_audio_ms`，第二圈的正向推进继续计入。于是 `consumed_audio_ms` 可能超过时长、完播判定可能在第二圈才满足——这是「真听过」，与口径一致；一场会话无论循环几圈，`play_count/completed_count` 各只记 1。

### 3.4 崩溃与异常（FR-405）

- 只依赖两件事：(a) 每 ≤10s 落盘一次；(b) 会话行有 `ACTIVE` 状态。
- App 启动（`PlaybackRepository.recoverCrashedSessions`，幂等且在任何写之前自动先跑）把所有 `ended_at_ms = 0` 的行按最后 flush 的数值结算为 `CRASHED`，`is_counted` 按其 `listened_ms` 判定。
- **崩溃路径不改标 `SKIPPED_TOO_SHORT`**：进程内结算会把不足阈值的行标成 `SKIPPED_TOO_SHORT`，启动恢复却一律标 `CRASHED`（`is_counted` 仍按阈值算）。这是有意的——`CRASHED` 的信息量是「这行不是正常关掉的」，而是否计次由 `is_counted` 单独承担；两条路的 `is_counted` 口径一致，所以统计数字不受影响。
- 恢复时 `ended_at_ms` 用最后一次 flush 的 `updated_at_ms`（为 0 才退回 `started_at_ms`），不用「现在」：最后一次 flush 到进程死亡之间那段无法可信度量，宁可少算。
- 不重复累计：flush 是「覆盖写当前累计值」而不是「累加」，所以崩溃后恢复不会双算；聚合缓存的重算路径以会话表为真源，即使崩溃前缓存写坏也能修正。
- 系统时间跳变/时区变化不影响任何时长计算（时长一律来自单调时钟；墙钟只用于「什么时候」的展示与排序）。

## 4. 后台播放与焦点

| 场景 | 行为 | 实测状态 |
| --- | --- | --- |
| App 切后台 | 继续播放；`MediaSessionService` 起前台服务（`mediaPlayback`），通知是 `DefaultMediaNotificationProvider` 的默认布局（播放/暂停 + 上下曲 + 进度条），标题来自 `MediaItem` 的 `MediaMetadata` | 模拟器：`isForeground=true foregroundId=1001`，通知显示真实书名，通知上的暂停能把位置冻住 |
| 焦点 `AUDIOFOCUS_LOSS_TRANSIENT`（来电、导航播报） | Media3 对此**不是**把 `playWhenReady` 以 `REASON_AUDIO_FOCUS_LOSS` 置 false，而是置 `playbackSuppressionReason`。`onPlaybackSuppressionReasonChanged`：非 NONE 时结算本场会话（`FocusLost`）并 `pause()`，记 `waitingForUserToResume`；回到 NONE 时**自动续播**（FR-305 的 2026-10-30 口径反转：被电话等中断，挂断后接着听）——本场已结算就按冻结位置重开一场（续播的收听照常记账），再 `player.play()`。**只有瞬时抢占续播**：永久丢失走 `FOCUS_LOSS` 不回来；耳机拔出走下面的 `BECOMING_NOISY`，外放突然出声是事故，绝不自动续 | 模拟器 `adb emu gsm call` → `dumpsys audio` 显示 `loss: LOSS_TRANSIENT -- notified: true`、按钮变「播放」（暂停态）、位置冻结在 -0:55；`gsm cancel` 挂断后**无用户操作**按钮变回「暂停」（在播）、位置走到 -0:51（2026-10-30）。旧口径「恢复不自动播放」（2026-10-15 实测 13s 不续播）作废，历史证据在 `testing.md` §4 Phase 4 |
| 播放页屏幕常亮（FR-311，2026-10-30） | `PlayerScreen` 的 `DisposableEffect(isPlaying)` 对 Activity window 挂/摘 `FLAG_KEEP_SCREEN_ON`：只在「停在播放页且真在播」时不灭屏，暂停/离开页面立即恢复系统休眠节奏；无新权限，不写系统设置 | `screen_off_timeout=15s`：播放中 30s 后 `mWakefulness=Awake`；暂停后 22s `Asleep`（负例也测） |
| 焦点 `AUDIOFOCUS_LOSS`（其他 App 长期占用） | `onPlayWhenReadyChanged(..., REASON_AUDIO_FOCUS_LOSS)` → 结算会话（`INTERRUPTED`）；不主动抢回 | 代码路径与上一条同一实现，未单独造场景 |
| 耳机拔出 `BECOMING_NOISY` | `handleAudioBecomingNoisy=true` 由 Media3 自动暂停，`REASON_AUDIO_BECOMING_NOISY` 回调里结算会话 | **模拟器无法验证**：这是受保护广播，`adb shell am broadcast` 打不进来，`adb emu help` 也没有耳机开关命令 → 只能真机测（`testing.md` §6） |
| Service 被系统杀掉 / 用户划掉任务 | `onTaskRemoved`：正在播则让服务活着（后台播放是设计目标），没在播或队列空则 `stopSelf()`；播放器实例属于进程不属于服务，不释放 | 划掉任务后进程存活、位置与统计不丢（模拟器实测） |
| 播放/暂停判据 | 用 `player.isPlaying`，**不用** `playWhenReady`：整队列播完进入 `STATE_ENDED` 时 Media3 仍留着 `playWhenReady=true`，用它判会把「播放」变成一次无效的暂停（实测） | 见 `testing.md` §4 Bug E |
| Android 14+ 前台服务类型 | manifest 声明 `android:foregroundServiceType="mediaPlayback"` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` 权限（已在 Phase 2 manifest 落地） | `dumpsys` 显示 `types=0x2` |

通知渠道 `echomind_playback`（`IMPORTANCE_LOW`，由服务自己建，不等 Media3）；`POST_NOTIFICATIONS` 在播放页用 `RequestPermission` 契约请求（`PlayerScreen` 里先 `checkSelfPermission` 再发起）。**「被拒绝后后台仍播、只是没有通知」这条没有实测**。

## 5. 字幕同步（Phase 5 已实现）

实现：`domain/subtitle/SubtitleSynchronizer.kt`（纯 Kotlin，无 android import）。输入 = 已按 `segment_index` 排序的字幕列表 + 播放位置；输出 = `SubtitleWindow(before, current, after, currentIndex)`，半径来自 `PlaybackPolicy.SUBTITLE_WINDOW_RADIUS`（默认 3）。

- 定位：对 `startMs` 数组二分，找「最后一个 `start_ms ≤ pos` 的句」。**`end_ms` 完全不参与定位**——ASR 给的 end 常有几百毫秒毛刺，用它判会在两句之间翻成「没有当前句」。
- 停顿迟滞：位置落在两句之间的间隙时保持前一句为当前句；只有越过下一句 `start_ms - GAP_ENTER_MARGIN_MS`（默认 250ms）才切换，避免抖动（FR-704）。
- 位置还在首句起点之前（超过迟滞余量）时 `currentIndex = -1`，窗口里只放接下来的 ≤3 句，不硬凑一个「当前句」。
- 本类**无状态**：窗口只是 (segments, position) 的函数。发射节流由调用方做——`PlayerViewModel` 对映射后的 `SubtitleWindow` 套 `distinctUntilChanged()`（FR-707），所以节流没有藏在算法里。
- seek 后：下一次 tick 立即重算（二分对 5000 句也是 O(log n)，无成本）。
- 换曲：`playingId.flatMapLatest { observeSegments(it) }` 重挂字幕流，位置仍取控制器的实时值，不在 ViewModel 里存第二份 position。
- 设备实测（2026-10-15，`testing.md` §4 Phase 5）：中间句 3+1+3、末句只有 3+1 不补位、拖动进度与倍速切换后窗口跟手、点句跳 `start_ms`（DB 里 `last_position_ms` 精确等于该句 12000/15000/24000）。

## 6. 倍速

- `player.setPlaybackSpeed(speed)`；speed 属 `playback_state`，每首独立（FR-303）。
- 起播时先设倍速再 seek 位置（顺序错了会导致 Media3 重新归一化速率）。
- **每一次换曲都要重新应用该首自己的倍速**——`play()`、`switchTo()`（上一首/下一首）与 `onMediaItemTransition()`（Media3 自动前进）三条路径都要设，Media3 不会替我们改。漏掉自动前进那条的实际后果（2026-10-15 实测抓到）：新一首用着上一首的速率播，而 `sessionSpeed` 已经换成新那本的值，统计里记的速率与真实速率不一致。
- 提供 0.75/1/1.25/1.5/2，来自 `PlaybackPolicy.SPEED_STEPS`；`default_playback_speed`（settings 表）只影响新入库音频的首次播放。

## 7. 配置项一览（`PlaybackPolicy`，唯一真源）

| 常量 | 默认 | 含义 |
| --- | --- | --- |
| `MIN_VALID_SESSION_LISTENED_MS` | 5000 | 有效播放次数阈值（FR-401） |
| `COMPLETED_CONSUMED_RATIO` | 0.90 | 完播所需的真实收听比例 |
| `END_TOLERANCE_MS` | 2000 | 视为「到达末尾」的容差（完成率 ≈95% 时生效） |
| `POSITION_FLUSH_INTERVAL_MS` | 10000 | 进度写库节流（FR-412） |
| `UI_TICK_INTERVAL_MS` | 500 | 进度/字幕 tick |
| `MAX_COUNTED_POSITION_STEP_MS` | 2000 | 超过此步长的位置跳变不计入 `consumed_audio_ms`（§3.3 规则 7） |
| `GAP_ENTER_MARGIN_MS` | 250 | 字幕停顿迟滞 |
| `SKIP_STEP_MS` | 15000 | ±15s |
| `SUBTITLE_WINDOW_RADIUS` | 3 | 前后各 3 句 → 最多 7 句 |
| `SPEED_STEPS` | 0.75…2.0 | 倍速档位 |

统计页的规则说明文案由这些常量渲染（FR-903）。

## 8. 已知限制（如实记录）

- **模拟器音频比墙钟慢**：`emulator-5554` 实测 89785ms 的音频要用 126219ms 墙钟才播完（比值 0.711），较短的文件在 0.91~0.92。因为 `listened_ms` 是墙钟、`consumed_audio_ms` 是音频位置推进，两者在这个模拟器上必然 `listened > consumed`，这是环境现象不是口径 bug；真机上二者应接近 1.0（1x 倍速）。统计页与单测都不依赖这个比值。
- 焦点抢占与后台限制的真实表现只在模拟器上验证（`adb emu gsm call` + `dumpsys audio`/`dumpsys media_session`）；**耳机拔出 `BECOMING_NOISY` 在模拟器上无法验证**（受保护广播 + `adb emu` 无耳机命令），真机验证项单列在 `testing.md` §6。
- 音频时间推进的差分依赖 `currentPosition` 的插值精度，±10ms 级误差；对统计无实质影响，但 `consumed_audio_ms` 不是审计级精度。
- 断点续播位置精度受 flush 周期限制（最坏丢 10s）。
- `session_status` 只有 5 个值，`FINISHED` 同时覆盖「播到末尾」和「用户停止」（§3.3）。要区分只能看 `completed` 与 `end_position_ms`。
