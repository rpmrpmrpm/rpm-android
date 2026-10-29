package com.rpm.player.core.diag

import android.content.Context
import android.content.SharedPreferences
import com.rpm.player.core.auth.AuthClient
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * The app's side of Send feedback and crash reports: the scope, the waiting crashes, the consent prompt. One per app,
 * in [com.rpm.player.core.Services].
 *
 * The scope comes from the build, so it is known before anything is sent. [start] adds the system's exit records to
 * the outbox and then sends the waiting crashes, or asks about them the first time.
 */
class Diagnostics(
    private val reports: ReportsClient,
    private val outbox: CrashOutbox,
    private val prefs: SharedPreferences,
    /** The crashes the system recorded since the last launch ([ExitReasons]). */
    private val exits: () -> List<CrashDraft>,
    val scope: ReportScope,
    private val appScope: CoroutineScope,
) {
    private val asks = MutableStateFlow(false)

    /** True while the consent prompt should be on screen. */
    val asksAboutCrashes: StateFlow<Boolean> = asks

    /** Send feedback is offered only by a build whose reports are full. */
    val offersFeedback: Boolean get() = scope.isFull

    /** The Send crash reports switch in the settings. */
    var sendsCrashReports: Boolean
        get() = CrashConsent.read(prefs) == CrashConsent.Send
        set(on) = CrashConsent.write(prefs, if (on) CrashConsent.Send else CrashConsent.DontSend)

    /** At launch. The files are read off the main thread. */
    fun start() {
        appScope.launch(Dispatchers.IO) {
            exits().forEach(outbox::put)
            when (CrashConsent.next(CrashConsent.read(prefs), outbox.pending().size)) {
                CrashConsent.Step.Ask -> asks.value = true
                CrashConsent.Step.Send -> sendCrashes()
                CrashConsent.Step.Nothing -> Unit
            }
        }
    }

    /** The answer to the consent prompt. */
    fun answer(send: Boolean) {
        asks.value = false
        sendsCrashReports = send
        appScope.launch(Dispatchers.IO) { if (send) sendCrashes() else outbox.removeAll() }
    }

    suspend fun sendFeedback(draft: FeedbackDraft): FeedbackSent = withContext(Dispatchers.IO) {
        reports.sendFeedback(draft, if (scope.isFull) Diag.tail() else null, scope, System.currentTimeMillis())
    }

    private suspend fun sendCrashes() = outbox.send { reports.sendCrash(it, scope) }

    companion object {
        fun folder(context: Context) = File(context.filesDir, "diag")

        fun outbox(context: Context) = CrashOutbox(File(folder(context), "crashes"))

        fun prefs(context: Context): SharedPreferences = context.getSharedPreferences("rpm.diag", Context.MODE_PRIVATE)

        fun make(context: Context, head: String, http: OkHttpClient, auth: AuthClient, appScope: CoroutineScope): Diagnostics {
            val facts = DeviceFacts.of(context, head)
            val prefs = prefs(context)
            return Diagnostics(
                reports = ReportsClient(apiPoster(http, auth), facts, InstallId.get(context)),
                outbox = outbox(context),
                prefs = prefs,
                exits = { ExitReasons.collect(context, prefs, facts.appVersion, Diag.previousRun()) },
                scope = ReportScope.ofBuild,
                appScope = appScope,
            )
        }
    }
}
