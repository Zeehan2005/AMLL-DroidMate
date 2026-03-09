package com.amll.droidmate.update

import android.content.Context
import com.amll.droidmate.ui.UpdateChannel
import com.amll.droidmate.data.network.HttpClientFactory
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private const val REPO_OWNER = "Zeehan2005"
private const val REPO_NAME = "AMLL-DroidMate"
private const val RELEASES_API = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases"
private const val PREVIEW_STABLE_OVERRIDE_MINUTES = 15L

private val stableRegex = Regex("^v?(\\d+)\\.(\\d+)(?:\\.(\\d+))?(?:[-.].*)?$")
private val previewRegex = Regex("(?i).*alpha[\\s-]+(\\d{14})(?:[-.].*)?$")
private val previewFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

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
    val stable: SemVer? = null,
    val previewInstant: Instant? = null
)

private data class ReleaseCandidate(
    val tagName: String,
    val isPrerelease: Boolean,
    val htmlUrl: String,
    val notes: String,
    val publishedAt: Instant,
    val stable: SemVer? = null,
    val previewInstant: Instant? = null
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

object GitHubUpdateChecker {

    suspend fun check(context: Context, channel: UpdateChannel): UpdateCheckResult {
        val currentVersionName = getCurrentVersionName(context)
        val installed = parseInstalledVersion(currentVersionName)

        val client = HttpClientFactory.create(context)
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
            UpdateCheckResult(
                hasUpdate = hasUpdate,
                currentVersionName = currentVersionName,
                selectedChannel = channel,
                resolvedReleaseTag = resolved.tagName,
                resolvedReleaseUrl = resolved.htmlUrl,
                resolvedReleaseNotes = resolved.notes,
                resolvedPublishedAt = resolved.publishedAt,
                reason = if (hasUpdate) {
                    "发现新版本"
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
        } finally {
            client.close()
        }
    }

    private fun resolveLatestByChannel(
        candidates: List<ReleaseCandidate>,
        channel: UpdateChannel
    ): ReleaseCandidate? {
        val latestStable = candidates
            .filter { !it.isPrerelease && it.stable != null }
            .maxWithOrNull(compareBy<ReleaseCandidate> { it.stable!! }.thenBy { it.publishedAt })

        val latestPreview = candidates
            .filter { it.isPrerelease && it.previewInstant != null }
            .maxWithOrNull(compareBy<ReleaseCandidate> { it.previewInstant!! }.thenBy { it.publishedAt })

        return when (channel) {
            UpdateChannel.STABLE -> latestStable
            UpdateChannel.PREVIEW -> {
                if (latestPreview == null) return latestStable
                if (latestStable == null) return latestPreview

                val stableAheadMillis = latestStable.publishedAt.toEpochMilli() - latestPreview.publishedAt.toEpochMilli()
                val thresholdMillis = PREVIEW_STABLE_OVERRIDE_MINUTES * 60 * 1000
                if (stableAheadMillis >= thresholdMillis) latestStable else latestPreview
            }
        }
    }

    private fun isRemoteNewer(installed: InstalledVersion, remote: ReleaseCandidate): Boolean {
        remote.stable?.let { remoteStable ->
            installed.stable?.let { return remoteStable > it }
            installed.previewInstant?.let { return remote.publishedAt.isAfter(it) }
            return true
        }

        remote.previewInstant?.let { remotePreview ->
            installed.previewInstant?.let { return remotePreview.isAfter(it) }
            installed.stable?.let { return remote.publishedAt.isAfter(parseStableAsApproxInstant(it)) }
            return true
        }

        return false
    }

    private fun toCandidate(dto: GitHubReleaseDto): ReleaseCandidate? {
        val published = dto.publishedAt?.let {
            runCatching { Instant.parse(it) }.getOrNull()
        } ?: return null

        val tag = dto.tagName.trim()
        val stable = parseStable(tag)
        val preview = parsePreview(tag)
        if (stable == null && preview == null) return null

        return ReleaseCandidate(
            tagName = tag,
            isPrerelease = dto.prerelease,
            htmlUrl = dto.htmlUrl,
            notes = dto.body.orEmpty().trim(),
            publishedAt = published,
            stable = stable,
            previewInstant = preview
        )
    }

    private fun getCurrentVersionName(context: Context): String {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return packageInfo.versionName ?: "unknown"
    }

    private fun parseInstalledVersion(versionName: String): InstalledVersion {
        val trimmed = versionName.trim()
        return InstalledVersion(
            raw = trimmed,
            stable = parseStable(trimmed),
            previewInstant = parsePreview(trimmed)
        )
    }

    private fun parseStable(input: String): SemVer? {
        val match = stableRegex.matchEntire(input.trim()) ?: return null
        val major = match.groupValues[1].toIntOrNull() ?: return null
        val minor = match.groupValues[2].toIntOrNull() ?: return null
        val patch = match.groupValues[3].toIntOrNull() ?: 0
        return SemVer(major, minor, patch)
    }

    private fun parsePreview(input: String): Instant? {
        val match = previewRegex.matchEntire(input.trim()) ?: return null
        val raw = match.groupValues[1]
        val localDateTime = runCatching {
            LocalDateTime.parse(raw, previewFormatter)
        }.getOrNull() ?: return null

        // Preview version timestamp is defined as UTC+8 by project convention.
        return localDateTime.atOffset(ZoneOffset.ofHours(8)).toInstant()
    }

    private fun parseStableAsApproxInstant(stable: SemVer): Instant {
        // Fallback ordering when installed version is stable but remote is preview.
        val syntheticYear = 2000 + stable.major.coerceIn(0, 99)
        val syntheticMonth = (stable.minor.coerceIn(0, 11) + 1)
        val syntheticDay = (stable.patch.coerceIn(0, 27) + 1)
        return LocalDateTime.of(syntheticYear, syntheticMonth, syntheticDay, 0, 0)
            .toInstant(ZoneOffset.UTC)
    }
}
