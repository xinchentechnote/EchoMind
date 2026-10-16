package com.xinchen.echomind

import android.app.Application
import android.content.Intent
import androidx.room.Room
import com.xinchen.echomind.asr.ModelCatalog
import com.xinchen.echomind.asr.ModelManager
import com.xinchen.echomind.asr.TranscriptCoordinator
import com.xinchen.echomind.core.time.AndroidTimeSource
import com.xinchen.echomind.core.time.TimeSource
import com.xinchen.echomind.data.db.EchoMindDatabase
import com.xinchen.echomind.data.db.Migrations
import com.xinchen.echomind.data.repo.AudioRepository
import com.xinchen.echomind.data.repo.AudioRepositoryImpl
import com.xinchen.echomind.data.repo.PlaybackRepository
import com.xinchen.echomind.data.repo.PlaybackRepositoryImpl
import com.xinchen.echomind.data.repo.SubtitleRepository
import com.xinchen.echomind.data.repo.SubtitleRepositoryImpl
import com.xinchen.echomind.data.repo.SafTreeGrantStore
import com.xinchen.echomind.data.repo.TranscriptRepositoryImpl
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
        val playbackRepository = PlaybackRepositoryImpl(db, timeSource)
        val grants = SafTreeGrantStore(this)
        val audioRepository = AudioRepositoryImpl(
            audioBookDao = db.audioBookDao(),
            folderDao = db.importedFolderDao(),
            shelfDao = db.shelfDao(),
            treeBrowser = SafTreeBrowser(this),
            metadataReader = SystemAudioMetadataReader(this),
            grants = grants,
            nowMs = timeSource::wallMs,
        )
        val transcriptRepository = TranscriptRepositoryImpl(db, timeSource)
        val modelsRoot = File(filesDir, ModelCatalog.MODELS_DIR_NAME)
        val liveTranscriptStore = LiveTranscriptStore()
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
            ),
            transcriptWork = TranscriptWorkScheduler(this, transcriptRepository),
            liveTranscriptStore = liveTranscriptStore,
            playbackController = Media3PlaybackController(
                player = PlayerHolder.get(this),
                repository = playbackRepository,
                clock = timeSource,
                scope = playbackScope,
                // MediaSessionService 只在有 controller 绑定时才被系统创建；本 App 的 UI 不绑 controller，
                // 所以每场会话开始时自己拉一次，否则没有会话注册也没有通知栏（实测）。
                startSessionService = { startService(Intent(this, EchoMindMediaService::class.java)) },
            ),
            appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            playbackScope = playbackScope,
        )
        // 上次进程被杀留下的 ACTIVE 会话在这里结算，否则它会永远占着「正在听」的位置
        playbackScope.launch { graph.playbackRepository.recoverCrashedSessions() }
        // 同一条规矩管转录：挂着的任务行标成 INTERRUPTED，用户在转录页看到它并点重试（FR-507）。
        // WorkManager 可能会自己把 worker 重跑一遍，那一轮会在 markRunning 的守卫前收手——
        // 这里刻意**不**去 cancelAllWorkByTag：那个调用是异步的，会连用户刚点的「转录」一起取消。
        graph.appScope.launch { graph.transcriptRepository.recoverInterrupted() }
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
