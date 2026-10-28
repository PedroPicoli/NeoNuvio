package com.nuvio.tv.updater

import android.content.Context
import android.os.Build
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.updater.model.AppUpdate
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the downloaded update APK in app storage and remembers which release it belongs to, so a download made in the
 * background can be installed later, from the banner or by the idle install job.
 */
@Singleton
class UpdateDownloadStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val updatePreferences: UpdatePreferences,
    private val apkDownloader: ApkDownloader
) {
    // Files storage instead of cache, which the system may clear before the update is installed.
    private val updatesDir: File get() = File(context.filesDir, "updates")

    // The banner and the background job can ask for the same download; the second one waits and reuses the file.
    private val downloadMutex = Mutex()

    fun destinationFor(update: AppUpdate): File {
        val safeName = update.assetName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(updatesDir, safeName)
    }

    private fun getInstalledVersionCode(): Long {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toLong()
            }
        }.getOrDefault(BuildConfig.VERSION_CODE.toLong())
    }

    private fun getArchiveVersionCode(file: File): Long? {
        return runCatching {
            val archiveInfo = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0) ?: return null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                archiveInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                archiveInfo.versionCode.toLong()
            }
        }.getOrNull()
    }

    /** Returns the already downloaded APK for this release, or null when it is missing, incomplete, or stale. */
    suspend fun downloadedFileFor(update: AppUpdate): File? {
        val (tag, path) = updatePreferences.downloadedUpdate.first() ?: return null
        if (tag != update.tag) return null
        val file = File(path)
        val expectedSize = update.assetSizeBytes
        val matchesSize = file.exists() && (expectedSize == null || file.length() == expectedSize)
        if (!matchesSize) return null

        val archiveCode = getArchiveVersionCode(file)
        if (archiveCode != null && archiveCode <= getInstalledVersionCode()) {
            file.delete()
            clear()
            return null
        }
        return file
    }

    /** Returns the downloaded APK when it is for a version newer than the installed one. */
    suspend fun pendingInstall(): Pair<String, File>? {
        val (tag, path) = updatePreferences.downloadedUpdate.first() ?: return null
        val file = File(path)
        if (!file.exists() || !VersionUtils.isRemoteNewer(tag, BuildConfig.VERSION_NAME)) return null
        val archiveCode = getArchiveVersionCode(file)
        if (archiveCode != null && archiveCode <= getInstalledVersionCode()) {
            clear()
            return null
        }
        return tag to file
    }

    suspend fun download(
        update: AppUpdate,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> }
    ): Result<File> = downloadMutex.withLock {
        downloadedFileFor(update)?.let { return@withLock Result.success(it) }
        val destination = destinationFor(update)
        apkDownloader.download(update.assetUrl, destination, onProgress)
            .onSuccess { file ->
                updatesDir.listFiles()?.filter { it != file }?.forEach { it.delete() }
                updatePreferences.setDownloadedUpdate(update.tag, file.absolutePath)
            }
    }

    /** Drops the downloaded APK once it is installed, outdated, or no longer wanted. */
    suspend fun clear() {
        updatesDir.deleteRecursively()
        updatePreferences.setDownloadedUpdate(null, null)
    }

    suspend fun clearIfInstalled() {
        val (tag, path) = updatePreferences.downloadedUpdate.first() ?: return
        if (!VersionUtils.isRemoteNewer(tag, BuildConfig.VERSION_NAME)) {
            clear()
            return
        }
        val file = path.let(::File)
        if (file.exists()) {
            val archiveCode = getArchiveVersionCode(file)
            if (archiveCode != null && archiveCode <= getInstalledVersionCode()) {
                clear()
            }
        }
    }
}
