package com.xinchen.echomind.ui.common

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContract

/**
 * 选择 SAF 文件夹，并且**要求可持久化授权**。
 *
 * 为什么不用 [androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree]：
 * 反编译 activity 1.13.0 看到它的 createIntent 只设 action 与 EXTRA_INITIAL_URI，一个授权标志都不加，
 * 于是系统返回的 URI 不可持久化，`takePersistableUriPermission()` 会抛 IllegalArgumentException
 * （API 36 模拟器实测）。听书 App 必须跨启动记住文件夹，所以这里显式要求 READ + PERSISTABLE。
 *
 * 配套的取授权代码在 `SafTreeGrantStore`，那里的参数只能带模式位。
 */
class OpenPersistableDocumentTree : ActivityResultContract<String?, Uri?>() {

    override fun createIntent(context: Context, input: String?): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
            if (input != null) putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(input))
        }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode != Activity.RESULT_OK) null else intent?.data
}
