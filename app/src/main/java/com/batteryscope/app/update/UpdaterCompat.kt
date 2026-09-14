package com.batteryscope.app.ui

/** Compatibility type used by the settings UI for GitHub release metadata. */
object GithubUpdateManager {
    interface Release {
        val versionName: String
        val downloadUrl: String
    }
}
