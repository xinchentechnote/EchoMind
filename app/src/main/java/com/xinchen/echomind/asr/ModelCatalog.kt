package com.xinchen.echomind.asr

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

/**
 * 一个可独立下载/删除的模型包。设备上的目录名就是 [id]（`filesDir/models/<id>/`，见 `docs/asr-design.md` §7）。
 */
data class ModelSpec(
    val id: String,
    val displayName: String,
    val role: ModelRole,
    val files: List<ModelFileSpec>,
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

    private const val HF_MIRROR = "https://hf-mirror.com/csukuangfj/sherpa-onnx-paraformer-zh-2023-09-14/resolve/main"
    private const val HUGGINGFACE = "https://huggingface.co/csukuangfj/sherpa-onnx-paraformer-zh-2023-09-14/resolve/main"

    val paraformer: ModelSpec = ModelSpec(
        id = PARAFORMER_ID,
        displayName = "Paraformer 中文离线（int8，2023-09-14）",
        role = ModelRole.Recognizer,
        files = listOf(
            ModelFileSpec(
                fileName = "model.int8.onnx",
                url = "$HF_MIRROR/model.int8.onnx",
                backupUrls = listOf("$HUGGINGFACE/model.int8.onnx"),
                expectedBytes = 243_371_218L,
                // HuggingFace LFS 发布的就是这个 sha256（tree API 的 oid 字段），算官方给的
                officialSha256 = "f36a0433bcf096bd6d6f11b80a3ac8bed110bdca632fe0d731df8d1a84475945",
                pinnedSha256 = "f36a0433bcf096bd6d6f11b80a3ac8bed110bdca632fe0d731df8d1a84475945",
            ),
            ModelFileSpec(
                fileName = "tokens.txt",
                url = "$HF_MIRROR/tokens.txt",
                backupUrls = listOf("$HUGGINGFACE/tokens.txt"),
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
                fileName = "silero_vad.onnx",
                url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
                backupUrls = emptyList(),
                expectedBytes = 643_854L,
                officialSha256 = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
                pinnedSha256 = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
            ),
        ),
    )

    val all: List<ModelSpec> = listOf(paraformer, sileroVad)

    /** 找不到就抛，清单是封闭集合：调用方拿到 null 也做不了什么，不如让它立刻炸在日志里 */
    fun byId(id: String): ModelSpec = all.firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("模型清单里没有 id=$id")
}
