package com.batteryscope.app.ui

import android.content.Context

/** Compatibility facade for the GitHub release updater used by the settings UI. */
object GithubUpdateManager {
    data class Release(
        val versionName: String,
        val downloadUrl: String,
    )

    suspend fun checkLatest(currentVersion: String): Release? =
        com.batteryscope.app.update.GitHubUpdateManager.checkLatest(currentVersion)
            ?.let { Release(it.versionName, it.downloadUrl) }

    suspend fun downloadAndInstall(context: Context, release: Release) {
        com.batteryscope.app.update.GitHubUpdateManager.downloadAndInstall(
            context,
            com.batteryscope.app.update.GitHubUpdateManager.Release(
                release.versionName,
                release.downloadUrl,
            ),
        )
    }
}
