package com.rpm.player.core.diag

import com.rpm.player.core.BuildConfig

/**
 * Whether a feedback or crash report carries the person who sent it.
 *
 * A full report carries the app's recent log, the screenshot and the reply address, and the server keeps the
 * account. An anonymous report carries the message or the stack, the version, the OS, the device model and the
 * install id, and nothing else. The server applies the same rule to the `scope` field: a report that arrives
 * without "full" is stored anonymously whatever it carries.
 *
 * A bundle cannot tell at run time which Play track it came from, so the build decides: a debug build and a bundle
 * that `bundle-play.sh --beta` made are full, everything else is anonymous.
 */
enum class ReportScope(val wire: String?) {
    Full("full"),
    Anonymous(null);

    val isFull: Boolean get() = this == Full

    companion object {
        val ofBuild: ReportScope get() = if (BuildConfig.RPM_BETA) Full else Anonymous
    }
}
