package io.github.zeehan2005.scoremuse.components

import android.content.Context
import io.github.zeehan2005.scoremuse.global.AppVersion
import io.github.zeehan2005.scoremuse.global.UpdateChannel
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

private const val REPO_OWNER = "Zeehan2005"
private const val REPO_NAME = "ScoreMuse"
private const val RELEASES_API = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases"
private const val ALPHA_STABLE_OVERRIDE_MINUTES = 15L

private val stableRegex = Regex("^v?(\\d+)\\.(\\d+)(?:\\.(\\d+))?(?:[-.].*)?$")
// Alpha: vX.X.X-alpha<suffix>，suffix 可选，可为任意内容或空
private val alphaRegex = Regex("(?i)^v?(\\d+)\\.(\\d+)(?:\\.(\\d+))?[-_.]?alpha(.*)$")
// Beta: vX.X.X-beta<suffix>，suffix 可选，可为任意内容或空
private val betaRegex = Regex("(?i)^v?(\\d+)\\.(\\d+)(?:\\.(\\d+))?[-_.]?beta(.*)$")

private enum class VersionChannel { STABLE, ALPHA, BETA }

data class UpdateCheckResult(
    val hasUpdate: Boolean,
    val currentVersionName: String,
    val selectedChannel: UpdateChannel,
    val resolvedReleaseTag: String? = null,
    val resolvedReleaseUrl: String? = null,
    val resolvedReleaseNotes: String? = null,
    val resolvedPublishedAt: Instant? = null,
    val reason: String? = null
)

private data class InstalledVersion(
    val raw: String,
    val normalized: String,
    val channel: VersionChannel,
    val stable: SemVer? = null
)

private data class ReleaseCandidate(
    val tagName: String,
    val isPrerelease: Boolean,
    val htmlUrl: String,
    val notes: String,
    val publishedAt: Instant,
    val channel: VersionChannel,
    val stable: SemVer? = null
)

private data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        return patch.compareTo(other.patch)
    }

    override fun toString(): String = "v$major.$minor.$patch"
}

@Serializable
private data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String,
    @SerialName("prerelease") val prerelease: Boolean = false,
    @SerialName("draft") val draft: Boolean = false,
    @SerialName("body") val body: String? = null,
    @SerialName("html_url") val htmlUrl: String,
    @SerialName("published_at") val publishedAt: String? = null
)

/**
 * GitHub 版本更新检查器
 *
 * 这个对象负责从 GitHub Releases API 检查应用是否有新版本可用。
 * 支持三种更新渠道：
 * - Stable（稳定版）：正式发布版本，格式如 "v1.2.3"
 * - Alpha（开放版）：Alpha 测试版本，格式如 "v1.2.3-alpha"（后缀任意或为空）
 * - Beta（测试版）：Beta 测试版本，格式如 "v1.2.3-beta"（后缀任意或为空）
 *
 * 更新检查逻辑：
 * 1. 解析当前安装版本的版本号
 * 2. 获取 GitHub 上的所有 Release
 * 3. 根据用户选择的渠道筛选合适的版本
 * 4. 比较版本号判断是否需要更新
 * 5. 返回更新信息（新版本号、下载地址、更新说明等）
 */
object GitHubUpdateChecker {

    suspend fun check(context: Context, channel: UpdateChannel): UpdateCheckResult {
        val currentVersionName = getCurrentVersionName(context)
        val installed = parseInstalledVersion(currentVersionName)

        val client = ServiceLocator.provideHttpClient(context)
        return try {
            val releases = client.get(RELEASES_API) {
                header(HttpHeaders.Accept, "application/vnd.github+json")
                header(HttpHeaders.UserAgent, "$REPO_OWNER-$REPO_NAME-UpdateChecker")
                header("X-GitHub-Api-Version", "2022-11-28")
            }.body<List<GitHubReleaseDto>>()

            val candidates = releases
                .asSequence()
                .filter { !it.draft }
                .mapNotNull { toCandidate(it) }
                .toList()

            val resolved = resolveLatestByChannel(candidates, channel)
                ?: return UpdateCheckResult(
                    hasUpdate = false,
                    currentVersionName = currentVersionName,
                    selectedChannel = channel,
                    reason = "未找到可用发布版本"
                )

            val hasUpdate = isRemoteNewer(installed, resolved)
            val sameVersion = isSameVersion(installed, resolved)
            UpdateCheckResult(
                hasUpdate = hasUpdate,
                currentVersionName = installed.normalized,
                selectedChannel = channel,
                resolvedReleaseTag = resolved.tagName,
                resolvedReleaseUrl = resolved.htmlUrl,
                resolvedReleaseNotes = resolved.notes,
                resolvedPublishedAt = resolved.publishedAt,
                reason = if (hasUpdate) {
                    "发现新版本"
                } else if (sameVersion) {
                    "已是最新版本，版本号是 ${resolved.tagName}"
                } else {
                    "当前版本更领先，符合条件的版本是 ${resolved.tagName}"
                }
            )
        } catch (e: Exception) {
            UpdateCheckResult(
                hasUpdate = false,
                currentVersionName = currentVersionName,
                selectedChannel = channel,
                reason = "检查失败: ${e.message ?: "未知错误"}"
            )
        }
    }

    private fun resolveLatestByChannel(
        candidates: List<ReleaseCandidate>,
        channel: UpdateChannel
    ): ReleaseCandidate? {
        val latestStable = candidates
            .filter { it.channel == VersionChannel.STABLE }
            .maxWithOrNull(compareBy<ReleaseCandidate> { it.stable ?: SemVer(0, 0, 0) }.thenBy { it.publishedAt })

        val latestBeta = candidates
            .filter { it.channel == VersionChannel.BETA }
            .maxWithOrNull(compareBy<ReleaseCandidate> { it.stable ?: SemVer(0, 0, 0) }.thenBy { it.publishedAt })

        val latestAlpha = candidates
            .filter { it.channel == VersionChannel.ALPHA }
            .maxWithOrNull(compareBy<ReleaseCandidate> { it.stable ?: SemVer(0, 0, 0) }.thenBy { it.publishedAt })

        return when (channel) {
            UpdateChannel.STABLE -> latestStable
            UpdateChannel.BETA -> {
                if (latestBeta == null) return latestStable
                latestBeta
            }
            UpdateChannel.ALPHA -> {
                if (latestAlpha == null) return latestStable
                if (latestStable == null) return latestAlpha

                val stableAheadMillis = latestStable.publishedAt.toEpochMilli() - latestAlpha.publishedAt.toEpochMilli()
                val thresholdMillis = ALPHA_STABLE_OVERRIDE_MINUTES * 60 * 1000
                if (stableAheadMillis >= thresholdMillis) latestStable else latestAlpha
            }
        }
    }

    private fun isRemoteNewer(installed: InstalledVersion, remote: ReleaseCandidate): Boolean {
        val remoteStable = remote.stable ?: return false
        val installedStable = installed.stable ?: return true
        return remoteStable > installedStable
    }

    private fun isSameVersion(installed: InstalledVersion, remote: ReleaseCandidate): Boolean {
        val remoteStable = remote.stable ?: return false
        val installedStable = installed.stable ?: return false
        return remoteStable == installedStable
    }

    private fun toCandidate(dto: GitHubReleaseDto): ReleaseCandidate? {
        val published = dto.publishedAt?.let {
            runCatching { Instant.parse(it) }.getOrNull()
        } ?: return null

        val tag = dto.tagName.trim()
        val stable = parseStable(tag) ?: return null
        val channel = detectChannel(tag, dto.prerelease)

        return ReleaseCandidate(
            tagName = normalizeVersionName(tag),
            isPrerelease = dto.prerelease,
            htmlUrl = dto.htmlUrl,
            notes = dto.body.orEmpty().trim(),
            publishedAt = published,
            channel = channel,
            stable = stable
        )
    }

    private fun getCurrentVersionName(context: Context): String {
        return AppVersion.current(context)
    }

    private fun parseInstalledVersion(versionName: String): InstalledVersion {
        val trimmed = versionName.trim()
        return InstalledVersion(
            raw = trimmed,
            normalized = normalizeVersionName(trimmed),
            channel = detectChannel(trimmed),
            stable = parseStable(trimmed)
        )
    }

    private fun parseStable(input: String): SemVer? {
        val match = stableRegex.matchEntire(input.trim()) ?: return null
        val major = match.groupValues[1].toIntOrNull() ?: return null
        val minor = match.groupValues[2].toIntOrNull() ?: return null
        val patch = match.groupValues[3].toIntOrNull() ?: 0
        return SemVer(major, minor, patch)
    }

    private fun detectChannel(tag: String): VersionChannel {
        val trimmed = tag.trim()
        if (alphaRegex.matches(trimmed)) return VersionChannel.ALPHA
        if (betaRegex.matches(trimmed)) return VersionChannel.BETA
        return VersionChannel.STABLE
    }

    private fun detectChannel(tag: String, isPrerelease: Boolean): VersionChannel {
        val byTag = detectChannel(tag)
        if (byTag != VersionChannel.STABLE) return byTag
        // 标签无 alpha/beta 后缀时，按 GitHub 的 prerelease 标记回退：
        // 旧的纯 vX.X.X 预览版被视为 BETA（保留历史行为）。
        return if (isPrerelease) VersionChannel.BETA else VersionChannel.STABLE
    }

    private fun normalizeVersionName(input: String): String {
        val trimmed = input.trim()
        val semver = parseStable(trimmed) ?: return trimmed
        alphaRegex.find(trimmed)?.let { m ->
            val suffix = m.groupValues[4]
            return "v${semver.major}.${semver.minor}.${semver.patch}-alpha$suffix"
        }
        betaRegex.find(trimmed)?.let { m ->
            val suffix = m.groupValues[4]
            return "v${semver.major}.${semver.minor}.${semver.patch}-beta$suffix"
        }
        return semver.toString()
    }
}
