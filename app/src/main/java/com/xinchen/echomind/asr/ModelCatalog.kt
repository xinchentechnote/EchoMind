package com.xinchen.echomind.asr

import com.xinchen.echomind.domain.model.TranscriptModelRef

/**
 * 一个模型文件：下载源 + 期望大小 + 校验值。
 *
 * 每个数值都有出处（`docs/asr-design.md` §3、`docs/environment.md` §8），不许凭记忆改：
 * 大小是落地后 `ls -l` 核对的，哈希是 `shasum -a 256` 算出来再与官方值逐字符比对的。
 *
 * [officialSha256] 为 null 表示**官方没有发布校验值**（`tokens.txt` 就是这种），
 * 此时只能以 [pinnedSha256]（本机首次下载钉住）为准；两者都有时以官方值为准，
 * [pinnedSha256] 只是本机那份文件的指纹，便于「官方值哪天变了」时能发现。
 */
data class ModelFileSpec(
    val fileName: String,
    val url: String,
    val backupUrls: List<String>,
    val expectedBytes: Long,
    val officialSha256: String?,
    val pinnedSha256: String,
) {
    val expectedSha256: String get() = officialSha256 ?: pinnedSha256

    /** 校验值是否来自官方发布，用于 UI 与日志如实标注「本机钉住，非官方」 */
    val hasOfficialChecksum: Boolean get() = officialSha256 != null

    /** 首选源失败后依次尝试的地址 */
    val candidateUrls: List<String> get() = listOf(url) + backupUrls
}

enum class ModelRole { Recognizer, VoiceActivityDetector }

/** 识别引擎的种类：决定 `SherpaOnnxEngine` 工厂怎么装配 sherpa-onnx 的模型配置（D41）。 */
enum class ModelKind {
    /** 中文 Paraformer（单文件 model.int8.onnx，逐字时间戳） */
    PARAFORMER,

    /** OpenAI Whisper（encoder + decoder 双文件，多语种；sherpa-onnx 导出无注意力权重 → 无 token 时间戳，D41） */
    WHISPER,

    /** SenseVoice（单文件 model.int8.onnx，中英日韩粤，CTC 时间戳，2025-09-09 版起支持） */
    SENSEVOICE,
}

/**
 * 一个可独立下载/删除的模型包。设备上的目录名就是 [id]（`filesDir/models/<id>/`，见 `docs/asr-design.md` §7）。
 *
 * [kind] 只对 `role = Recognizer` 有意义（VAD 不经 `SherpaOnnxEngine` 装载）。
 */
data class ModelSpec(
    val id: String,
    val displayName: String,
    val role: ModelRole,
    val files: List<ModelFileSpec>,
    val kind: ModelKind = ModelKind.PARAFORMER,
) {
    val totalBytes: Long get() = files.sumOf { it.expectedBytes }
}

/**
 * ASR 模型清单的唯一出处（AGENTS.md §3：常量只许一处定义）。
 *
 * 这里刻意只放**中文离线 Paraformer `zh-2023-09-14`** 这一个识别模型：
 * 官方只有这个型号声明支持逐字时间戳（`docs/asr-design.md` §1-A4），
 * 换型号就等于放弃句级时间，清单不给它留口子。
 */
object ModelCatalog {

    const val PARAFORMER_ID = "paraformer-zh-2023-09-14-int8"
    const val SILERO_VAD_ID = "silero-vad"

    /**
     * App 私有目录下模型根目录的名字：`filesDir/<这里>/spec.id/<fileName>`（`docs/asr-design.md` §7）。
     * 生产（`AppGraph`）与仪器化测试（`DeviceAsrFixtures`）共用这一处，路径不该有两个说法。
     */
    const val MODELS_DIR_NAME = "models"

    /** 包内文件名也是清单的一部分：引擎按名字取文件，别处不许再抄一遍字符串。 */
    const val PARAFORMER_MODEL_FILE = "model.int8.onnx"
    const val PARAFORMER_TOKENS_FILE = "tokens.txt"
    const val SILERO_VAD_MODEL_FILE = "silero_vad.onnx"

    const val WHISPER_BASE_ID = "whisper-base-multilingual"
    const val WHISPER_ENCODER_FILE = "base-encoder.int8.onnx"
    const val WHISPER_DECODER_FILE = "base-decoder.int8.onnx"
    const val WHISPER_TOKENS_FILE = "base-tokens.txt"

    const val SENSEVOICE_ID = "sense-voice-zh-en-ja-ko-yue-int8"
    const val SENSEVOICE_MODEL_FILE = "model.int8.onnx"
    const val SENSEVOICE_TOKENS_FILE = "tokens.txt"

    private const val HF_MIRROR = "https://hf-mirror.com/csukuangfj/sherpa-onnx-paraformer-zh-2023-09-14/resolve/main"
    private const val HUGGINGFACE = "https://huggingface.co/csukuangfj/sherpa-onnx-paraformer-zh-2023-09-14/resolve/main"

    val paraformer: ModelSpec = ModelSpec(
        id = PARAFORMER_ID,
        displayName = "Paraformer 中文离线（int8，2023-09-14）",
        role = ModelRole.Recognizer,
        files = listOf(
            ModelFileSpec(
                fileName = PARAFORMER_MODEL_FILE,
                url = "$HF_MIRROR/$PARAFORMER_MODEL_FILE",
                backupUrls = listOf("$HUGGINGFACE/$PARAFORMER_MODEL_FILE"),
                expectedBytes = 243_371_218L,
                // HuggingFace LFS 发布的就是这个 sha256（tree API 的 oid 字段），算官方给的
                officialSha256 = "f36a0433bcf096bd6d6f11b80a3ac8bed110bdca632fe0d731df8d1a84475945",
                pinnedSha256 = "f36a0433bcf096bd6d6f11b80a3ac8bed110bdca632fe0d731df8d1a84475945",
            ),
            ModelFileSpec(
                fileName = PARAFORMER_TOKENS_FILE,
                url = "$HF_MIRROR/$PARAFORMER_TOKENS_FILE",
                backupUrls = listOf("$HUGGINGFACE/$PARAFORMER_TOKENS_FILE"),
                expectedBytes = 75_756L,
                officialSha256 = null,
                pinnedSha256 = "59aba8873a2ed1e122c25fee421e25f283b63290efbde85c1f01a853d83cb6e6",
            ),
        ),
    )

    val sileroVad: ModelSpec = ModelSpec(
        id = SILERO_VAD_ID,
        displayName = "Silero VAD（语音段切分）",
        role = ModelRole.VoiceActivityDetector,
        files = listOf(
            ModelFileSpec(
                fileName = SILERO_VAD_MODEL_FILE,
                url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
                backupUrls = emptyList(),
                expectedBytes = 643_854L,
                officialSha256 = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
                pinnedSha256 = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
            ),
        ),
    )

    /**
     * Whisper Base 多语种。⚠ **不在 [all]（不进下载列表）**：sherpa-onnx 的 whisper 导出不含
     * attention 权重，`enableTokenTimestamps` 实测拿不到时间戳（D41 打样）——出不了带时间的字幕，
     * 对本产品等于不可用。保留 spec 供打样测试（`WhisperPilotTest`）与未来自建导出后复评。
     * 哈希为本机下载实测值（hf-mirror，2026-10-16）；官方对 int8 导出文件不发布校验值 → 本机钉住。
     */
    val whisperBase: ModelSpec = ModelSpec(
        id = WHISPER_BASE_ID,
        displayName = "Whisper Base 多语种（英文书解/歌曲）",
        role = ModelRole.Recognizer,
        kind = ModelKind.WHISPER,
        files = listOf(
            ModelFileSpec(
                fileName = WHISPER_ENCODER_FILE,
                url = "https://hf-mirror.com/csukuangfj/sherpa-onnx-whisper-base/resolve/main/$WHISPER_ENCODER_FILE",
                backupUrls = listOf(
                    "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/main/$WHISPER_ENCODER_FILE",
                ),
                expectedBytes = 29_120_534L,
                officialSha256 = null,
                pinnedSha256 = "0b8fb1304b6109976038efff5ace81720e00386f3ff6b54ee8c75291ca0a1e11",
            ),
            ModelFileSpec(
                fileName = WHISPER_DECODER_FILE,
                url = "https://hf-mirror.com/csukuangfj/sherpa-onnx-whisper-base/resolve/main/$WHISPER_DECODER_FILE",
                backupUrls = listOf(
                    "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/main/$WHISPER_DECODER_FILE",
                ),
                expectedBytes = 130_672_026L,
                officialSha256 = null,
                pinnedSha256 = "9759d217388a01b3a4c7c15533201067b48ae819c4daafc8624e64b9409dc02d",
            ),
            ModelFileSpec(
                fileName = WHISPER_TOKENS_FILE,
                url = "https://hf-mirror.com/csukuangfj/sherpa-onnx-whisper-base/resolve/main/$WHISPER_TOKENS_FILE",
                backupUrls = listOf(
                    "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/main/$WHISPER_TOKENS_FILE",
                ),
                expectedBytes = 816_730L,
                officialSha256 = null,
                pinnedSha256 = "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126",
            ),
        ),
    )

    /**
     * SenseVoice 多语种打样模型（2025-09-09 版：中英日韩粤 + 时间戳，D41）。
     * ⚠ 只保留给设备选型测试，不在 [all]，不进入产品模型页、启动就位或转录入口。
     * 哈希为本机下载实测值（hf-mirror，2026-10-16）；官方未发布校验值 → 本机钉住。
     */
    val senseVoice: ModelSpec = ModelSpec(
        id = SENSEVOICE_ID,
        displayName = "SenseVoice 多语种（中英日韩粤）",
        role = ModelRole.Recognizer,
        kind = ModelKind.SENSEVOICE,
        files = listOf(
            ModelFileSpec(
                fileName = SENSEVOICE_MODEL_FILE,
                url = "https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09/resolve/main/$SENSEVOICE_MODEL_FILE",
                backupUrls = listOf(
                    "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09/resolve/main/$SENSEVOICE_MODEL_FILE",
                ),
                expectedBytes = 237_115_547L,
                officialSha256 = null,
                pinnedSha256 = "12ca1a2ae7ecf3e0019ef2822307ee0b5cadc9196569e379b4c4026f8205276d",
            ),
            ModelFileSpec(
                fileName = SENSEVOICE_TOKENS_FILE,
                url = "https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09/resolve/main/$SENSEVOICE_TOKENS_FILE",
                backupUrls = listOf(
                    "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09/resolve/main/$SENSEVOICE_TOKENS_FILE",
                ),
                expectedBytes = 315_894L,
                officialSha256 = null,
                pinnedSha256 = "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc",
            ),
        ),
    )

    /**
     * 产品实际管理的模型包。当前产品范围只保证普通话听书/书解：
     * Paraformer 负责识别，Silero VAD 负责语音段切分。多语种模型只保留打样 spec，不进入这里。
     */
    val all: List<ModelSpec> = listOf(paraformer, sileroVad)

    /**
     * 当前识别模型的身份，写进 `transcript_job` 的 `model_id` / `model_version`。
     *
     * `version` 取权重文件的权威校验值（官方发布值，无官方值时是本机钉住的那个）前 12 位：
     * 它回答的是「这批字幕是哪份权重跑出来的」，文件名和日期回答不了这个。
     * 只存前 12 位是够辨认又不至于把整张表撑宽，全值随时能从清单再算出来。
     */
    val transcriptRef: TranscriptModelRef
        get() = TranscriptModelRef(
            id = paraformer.id,
            version = paraformer.files
                .first { it.fileName == PARAFORMER_MODEL_FILE }
                .expectedSha256
                .take(12),
        )

    /** 找不到就抛，清单是封闭集合：调用方拿到 null 也做不了什么，不如让它立刻炸在日志里 */
    fun byId(id: String): ModelSpec = all.firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("模型清单里没有 id=$id")

    /** 模型身份（写进 transcript_job 的 model_id/version）。version = 主权重文件的 sha256 前 12 位 */
    fun refOf(spec: ModelSpec): TranscriptModelRef {
        val weight = spec.files.maxByOrNull { it.expectedBytes }
            ?: throw IllegalArgumentException("模型包 ${spec.id} 没有文件")
        return TranscriptModelRef(id = spec.id, version = weight.expectedSha256.take(12))
    }

}
