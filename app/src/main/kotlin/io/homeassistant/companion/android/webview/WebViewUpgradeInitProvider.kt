package io.homeassistant.companion.android.webview

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Runs [WebViewUpgrader] before other ContentProviders (WorkManager, Sentry, etc.) can initialize
 * the system WebView implementation.
 */
class WebViewUpgradeInitProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val context = context ?: return false
        WebViewUpgrader.upgradeIfNeeded(context)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
