package com.xinchen.echomind.asr

import java.security.MessageDigest

/**
 * 测试用的假模型清单。**不是 ASR 产物、也不是真实下载地址**（域名是 `example.invalid`，
 * RFC 2606 保留、解析不出去），只用来验证「下载 → 校验 → 落盘」这条管线本身。
 * 真实清单见 [ModelCatalog]，它由 `ModelCatalogTest` 钉住本机实测数值。
 */
fun sha256Hex(bytes: ByteArray): String = ModelVerifier.toHex(MessageDigest.getInstance("SHA-256").digest(bytes))

fun fileSpecOf(
    name: String,
    content: ByteArray,
    url: String = "https://example.invalid/$name",
    backupUrls: List<String> = emptyList(),
    expectedBytes: Long = content.size.toLong(),
    expectedSha256: String = sha256Hex(content),
    officialSha256: String? = expectedSha256,
): ModelFileSpec = ModelFileSpec(
    fileName = name,
    url = url,
    backupUrls = backupUrls,
    expectedBytes = expectedBytes,
    officialSha256 = officialSha256,
    pinnedSha256 = expectedSha256,
)

fun specOf(vararg files: ModelFileSpec, id: String = "fake-model"): ModelSpec = ModelSpec(
    id = id,
    displayName = "假模型（仅测试）",
    role = ModelRole.Recognizer,
    files = files.toList(),
)
