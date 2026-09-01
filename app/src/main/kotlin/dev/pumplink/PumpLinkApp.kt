package dev.pumplink

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.pumplink.data.PumpLinkService
import dev.pumplink.data.PumpSession
import dev.pumplink.data.SessionKeepAlive
import javax.inject.Inject

/**
 * Starts the session service. The graph itself is Hilt's
 * [dagger.hilt.components.SingletonComponent].
 */
@HiltAndroidApp
class PumpLinkApp : Application() {
    @Inject lateinit var session: PumpSession

    override fun onCreate() {
        super.onCreate()
        session.holdForInFlightJournal()
        PumpLinkService.start(
            this,
            foreground = SessionKeepAlive.shouldBeForeground(
                session.sessionRequested.value,
                session.journal.value,
            ),
        )
    }
}
