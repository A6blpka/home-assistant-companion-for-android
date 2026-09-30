package io.homeassistant.companion.android.webview

import android.content.Context
import android.content.res.AssetManager
import android.os.Build
import android.util.Log
import com.norman.webviewup.lib.UpgradeCallback
import com.norman.webviewup.lib.WebViewUpgrade
import com.norman.webviewup.lib.source.UpgradePathSource
import java.io.File
import java.io.IOException

private const val TAG = "WebViewUpgrader"
private const val WEBVIEW_ASSET_NAME = "webview.apk"

/**
 * An [UpgradePathSource] that completes synchronously on the calling thread.
 *
 * The standard [UpgradeAssetSource] and [UpgradeFileSource] both spawn a background thread for
 * file I/O, then post the result back via `Handler`. That defers `WebViewReplace.replace()` until
 * after the main looper drains its queue — by which point other ContentProviders may already have
 * triggered WebView initialization. This source skips the background thread: the APK is already
 * extracted to [file], so [onPrepare] calls [success] directly on the main thread.
 */
private class SyncFileUpgradeSource(
    context: Context,
    private val file: File,
) : UpgradePathSource(context, file.absolutePath) {

    override fun onPrepare(params: Any?) {
        if (file.exists()) {
            success()
        } else {
            error(IOException("WebView APK not found at ${file.absolutePath}"))
        }
    }
}

/**
 * Replaces the system WebView implementation with a newer Chromium build bundled in assets.
 *
 * Must run from an early [android.content.ContentProvider] before any other provider initializes
 * WebView. See [WebViewUpgradeInitProvider].
 */
internal object WebViewUpgrader {

    /**
     * Upgrades the in-process WebView provider when a bundled APK is available.
     *
     * No-ops when [WEBVIEW_ASSET_NAME] is absent from assets.
     */
    fun upgradeIfNeeded(context: Context) {
        val appContext = context.applicationContext
        val assetName = findWebViewAssetName(appContext.assets) ?: return
        val cacheDir = File(appContext.filesDir, "webview/${assetName.removeSuffix(".apk")}")
        val destinationApk = File(cacheDir, WEBVIEW_ASSET_NAME)

        if (!destinationApk.exists()) {
            extractAssetToFile(appContext.assets, assetName, destinationApk)
        }

        if (!destinationApk.exists()) return

        val upgradeSource = SyncFileUpgradeSource(appContext, destinationApk)
        WebViewUpgrade.addUpgradeCallback(object : UpgradeCallback {
            override fun onUpgradeProcess(percent: Float) {}

            override fun onUpgradeComplete() {
                Log.i(
                    TAG,
                    "WebView upgrade completed: ${WebViewUpgrade.getUpgradeWebViewPackageName()} " +
                        WebViewUpgrade.getUpgradeWebViewVersion(),
                )
            }

            override fun onUpgradeError(error: Throwable) {
                Log.e(TAG, "WebView upgrade failed, falling back to system WebView", error)
            }
        })
        WebViewUpgrade.upgrade(upgradeSource)
    }

    private fun findWebViewAssetName(assetManager: AssetManager): String? {
        for (abi in Build.SUPPORTED_ABIS) {
            val candidate = "webview-$abi.apk"
            if (assetExists(assetManager, candidate)) return candidate
        }
        return WEBVIEW_ASSET_NAME.takeIf { assetExists(assetManager, it) }
    }

    private fun assetExists(assetManager: AssetManager, assetName: String): Boolean = try {
        assetManager.openFd(assetName).close()
        true
    } catch (_: IOException) {
        false
    }

    private fun extractAssetToFile(assetManager: AssetManager, assetName: String, target: File) {
        try {
            target.parentFile?.mkdirs()
            assetManager.openFd(assetName).use { afd ->
                afd.createInputStream().use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract WebView APK from assets", e)
            target.delete()
        }
    }
}
