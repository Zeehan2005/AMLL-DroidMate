package io.github.zeehan2005.scoremuse.global

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * 应用版本号读取工具
 *
 * 版本号规则（与 `GitHubUpdateChecker` 的解析规则保持一致）：
 * - 正式版：`vX.Y.Z`，可选 `-alpha<后缀>` / `-beta<后缀>` 渠道后缀
 * - 开发版（app/build.gradle.kts 中 `customVersion` 留空时）：`v0.0.0-alpha<时间戳>`
 *
 * 统一从这里取值，避免多处各写一份导致行为不一致。
 */
object AppVersion {

    /** 取不到有效版本号时的兜底值 */
    const val UNKNOWN = "unknown"

    /**
     * 获取当前已安装应用的版本号
     *
     * 注意：`packageInfo.versionName` 在 AGP 写入空字符串时会返回空串而非 null，
     * 因此不能只靠 `?:` 空合并兜底，必须先 trim 再判空，否则会拿到空版本号。
     */
    fun current(context: Context): String {
        return readRawVersionName(context)?.trim()?.ifEmpty { null } ?: UNKNOWN
    }

    @Suppress("DEPRECATION")
    private fun readRawVersionName(context: Context): String? {
        return try {
            val packageName = context.packageName
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                context.packageManager.getPackageInfo(packageName, 0)
            }
            packageInfo.versionName
        } catch (e: Exception) {
            null
        }
    }
}
