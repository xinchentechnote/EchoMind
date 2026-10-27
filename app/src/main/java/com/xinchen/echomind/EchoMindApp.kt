package com.xinchen.echomind

import android.app.Application
import android.content.Intent
import androidx.room.Room
import com.xinchen.echomind.asr.ModelCatalog
import com.xinchen.echomind.asr.ModelManager
import com.xinchen.echomind.asr.SharedAsrEngines
import com.xinchen.echomind.asr.TranscriptCoordinator
import com.xinchen.echomind.asr.WmaTranscoder
import com.xinchen.echomind.core.time.AndroidTimeSource
import com.xinchen.echomind.core.time.TimeSource
import com.xinchen.echomind.data.db.EchoMindDatabase
import com.xinchen.echomind.data.db.Migrations
import com.xinchen.echomind.data.repo.AudioRepository
import com.xinchen.echomind.data.repo.AudioRepositoryImpl
import com.xinchen.echomind.data.repo.AppSettings
import com.xinchen.echomind.data.repo.AndroidMediaAudioAccess
import com.xinchen.echomind.data.repo.SharedPreferencesAppSettings
import com.xinchen.echomind.data.repo.AsyncWmaImportPreparer
import com.xinchen.echomind.data.repo.WmaImportPreparer
import com.xinchen.echomind.data.repo.WmaPlaybackStore
import com.xinchen.echomind.data.repo.PlaybackRepository
import com.xinchen.echomind.data.repo.PlaybackRepositoryImpl
import com.xinchen.echomind.data.repo.SubtitleRepository
import com.xinchen.echomind.data.repo.SubtitleRepositoryImpl
import com.xinchen.echomind.data.repo.SafTreeGrantStore
import com.xinchen.echomind.data.repo.TranscriptRepositoryImpl
import com.xinchen.echomind.data.scan.MediaStoreAudioSource
import com.xinchen.echomind.data.scan.SafTreeBrowser
import com.xinchen.echomind.data.scan.SystemAudioMetadataReader
import com.xinchen.echomind.domain.playback.PlaybackController
import com.xinchen.echomind.domain.transcript.LiveTranscriptStore
import com.xinchen.echomind.domain.transcript.TranscriptRepository
import com.xinchen.echomind.playback.EchoMindMediaService
import com.xinchen.echomind.playback.Media3PlaybackController
import com.xinchen.echomind.playback.PlayerHolder
import com.xinchen.echomind.work.TranscriptWorkScheduler
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 手工依赖组装（决策 D2，见 docs/architecture.md）：不引 Hilt/Koin，一张图在 Application 里建好。
 *
 * [playbackScope] 必须是主线程作用域：ExoPlayer 绑定在主线程 looper 上，
 * 播放控制器在它上面跑 tick 与串行落库，别的线程碰播放器就是未定义行为。
 */
class AppGraph(
    val database: EchoMindDatabase,
    val timeSource: TimeSource,
    val audioRepository: AudioRepository,
    val playbackRepository: PlaybackRepository,
    val subtitleRepository: SubtitleRepository,
    val transcriptRepository: TranscriptRepository,
    val playbackController: PlaybackController,
    val modelManager: ModelManager,
    val modelsRoot: File,
    val transcriptCoordinator: TranscriptCoordinator,
    val transcriptWork: TranscriptWorkScheduler,
    val liveTranscriptStore: LiveTranscriptStore,
    val wmaStore: WmaPlaybackStore,
    val appSettings: AppSettings,
    val appScope: CoroutineScope,
    val playbackScope: CoroutineScope,
)

class EchoMindApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        val db = Room.databaseBuilder(this, EchoMindDatabase::class.java, EchoMindDatabase.NAME)
            // 只走显式 Migration：设备上这个库里有真实收听历史，destructive 会把它清零（AGENTS.md §4）
            .addMigrations(*Migrations.ALL)
            .build()
        val timeSource = AndroidTimeSource()
        val playbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val playbackRepository = PlaybackRepositoryImpl(db, timeSource)
        val grants = SafTreeGrantStore(this)
        val wmaTranscoder = WmaTranscoder(this)
        val wmaStore = WmaPlaybackStore(File(filesDir, "transcoded"))
        val appSettings: AppSettings = SharedPreferencesAppSettings(this)
        val wmaPreparer = AsyncWmaImportPreparer(
            scope = appScope,
            store = wmaStore,
            transcode = { uri, output, ext ->
                if (ext == "mp3") {
                    wmaTranscoder.transcodeToMp3(android.net.Uri.parse(uri), output)
                } else {
                    wmaTranscoder.transcodeToM4a(android.net.Uri.parse(uri), output)
                }
            },
            targetExt = { if (appSettings.wmaTranscodeToMp3) "mp3" else "m4a" },
            onPrepared = { audioId, uri, durationMs ->
                db.audioBookDao().setPreparedDuration(audioId, uri, durationMs, timeSource.wallMs()) == 1
            },
            onFailure = { audioId, error ->
                android.util.Log.w("EchoMindImport", "WMA 后台准备失败：$audioId", error)
            },
        )
        val audioRepository = AudioRepositoryImpl(
            audioBookDao = db.audioBookDao(),
            folderDao = db.importedFolderDao(),
            shelfDao = db.shelfDao(),
            treeBrowser = SafTreeBrowser(this),
            metadataReader = SystemAudioMetadataReader(this),
            grants = grants,
            nowMs = timeSource::wallMs,
            wmaStore = wmaStore,
            wmaPrepare = wmaPreparer,
            // 本机音频（MediaStore）：扫描源与权限接缝成对装配（2026-10-27）
            deviceSource = MediaStoreAudioSource(this),
            mediaAudio = AndroidMediaAudioAccess(this),
        )
        val transcriptRepository = TranscriptRepositoryImpl(db, timeSource)
        val modelsRoot = File(filesDir, ModelCatalog.MODELS_DIR_NAME)
        val liveTranscriptStore = LiveTranscriptStore()
        // D46：识别引擎按模型共享一份句柄，多路并发转录共用；放在图里是为了协调器与任何将来的调用方拿到同一个池
        val sharedEngines = SharedAsrEngines(modelsRoot)
        graph = AppGraph(
            database = db,
            timeSource = timeSource,
            audioRepository = audioRepository,
            playbackRepository = playbackRepository,
            subtitleRepository = SubtitleRepositoryImpl(db, timeSource),
            transcriptRepository = transcriptRepository,
            modelManager = ModelManager(
                // 模型放内部 filesDir：不需要任何运行时权限，卸载即清干净，也不出现在别的 App 的读写范围里
                modelsRoot = modelsRoot,
                timeSource = timeSource,
            ),
            modelsRoot = modelsRoot,
            transcriptCoordinator = TranscriptCoordinator(
                context = this,
                audioRepository = audioRepository,
                grants = grants,
                repository = transcriptRepository,
                modelsRoot = modelsRoot,
                clock = timeSource,
                liveStore = liveTranscriptStore,
                // D44：WMA 先转 mp3 再识别，产物与播放共用一个目录；转码出的时长顺手回填给书（与导入准备同一道 uri 守卫）
                wmaStore = wmaStore,
                onWmaPrepared = { audioId, uri, durationMs ->
                    db.audioBookDao().setPreparedDuration(audioId, uri, durationMs, timeSource.wallMs())
                },
                engines = sharedEngines,
                mediaAudio = AndroidMediaAudioAccess(this),
            ),
            transcriptWork = TranscriptWorkScheduler(this, transcriptRepository),
            liveTranscriptStore = liveTranscriptStore,
            wmaStore = wmaStore,
            appSettings = appSettings,
            playbackController = Media3PlaybackController(
                player = PlayerHolder.get(this),
                repository = playbackRepository,
                clock = timeSource,
                scope = playbackScope,
                // MediaSessionService 只在有 controller 绑定时才被系统创建；本 App 的 UI 不绑 controller，
                // 所以每场会话开始时自己拉一次，否则没有会话注册也没有通知栏（实测）。
                startSessionService = { startService(Intent(this, EchoMindMediaService::class.java)) },
                // 单曲循环是全局设置：开机取回上次的值，切换时写回（FR-310）
                initialRepeatOne = appSettings.repeatOne,
                onRepeatOneChanged = { appSettings.repeatOne = it },
            ),
            appScope = appScope,
            playbackScope = playbackScope,
        )
        // 上次进程被杀留下的 ACTIVE 会话在这里结算，否则它会永远占着「正在听」的位置
        playbackScope.launch { graph.playbackRepository.recoverCrashedSessions() }
        // 同一条规矩管转录：挂着的任务行标成 INTERRUPTED，用户在转录页看到它并点重试（FR-507）。
        // WorkManager 可能会自己把 worker 重跑一遍，那一轮会在 markRunning 的守卫前收手——
        // 这里刻意**不**去 cancelAllWorkByTag：那个调用是异步的，会连用户刚点的「转录」一起取消。
        // 随后把升级前「一本书多行」的历史收口成一行（只留最新；正常运行期间入队自己维持一本书一行）。
        graph.appScope.launch {
            graph.transcriptRepository.recoverInterrupted()
            graph.transcriptRepository.collapseHistory()
        }
        // 模型随安装包内置（FR-511）：把缺的文件从 assets 拷进 filesDir 并逐文件校验。
        // 文件都齐时它只是一遍哈希核对（~1-2 秒，后台线程），不阻塞任何界面；
        // assets 缺文件不会炸启动——原因写进该包的状态，设置页会显示出来
        graph.appScope.launch {
            graph.modelManager.ensureProvisioned { path ->
                runCatching { assets.open(path) }.getOrNull()
            }
        }
    }
}
