package com.rpm.player.core.diag

import android.content.Context
import android.os.Build
import java.util.UUID

/** What a report says about the app and the device. The server has no field for the model, so it travels in
 * [platform]. */
data class DeviceFacts(
    /** "android" for the phone app, "androidtv" for the TV app. */
    val head: String,
    /** "versionName (versionCode)", as the About screen shows it. */
    val appVersion: String,
    /** For example "Android 15 (API 35); Google Pixel 8". */
    val platform: String,
    /** The first ABI the device runs, for example "arm64-v8a". */
    val architecture: String,
) {
    companion object {
        fun of(context: Context, head: String): DeviceFacts {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            return DeviceFacts(
                head = head,
                appVersion = "${info.versionName} (${info.longVersionCode})",
                platform = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}); ${Build.MANUFACTURER} ${Build.MODEL}",
                architecture = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            )
        }
    }
}

/** A random id for this installation, used only to tell machines apart in the report queue. Kept in its own file
 * and across sign-outs, unlike the play log's device id, because crashes from a signed-out TV must still count as
 * one machine. */
object InstallId {
    fun get(context: Context): String {
        val prefs = context.getSharedPreferences("rpm.diag", Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.takeIf { it.isNotEmpty() }?.let { return it }
        val made = UUID.randomUUID().toString()
        prefs.edit().putString(KEY, made).apply()
        return made
    }

    private const val KEY = "install"
}
