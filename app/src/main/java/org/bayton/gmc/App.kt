package org.bayton.gmc

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import org.bayton.gmc.notify.Notifier
import org.bayton.gmc.notify.NotificationScheduler
import org.bayton.gmc.ui.theme.ThemeController
import org.bayton.gmc.data.auth.Session
import javax.inject.Inject

@HiltAndroidApp
class App : Application() {
    @Inject lateinit var session: Session
    override fun onCreate() {
        super.onCreate()
        ThemeController.load(this)
        Notifier.ensureChannels(this)
        session.snapshot() // Attribute or reset legacy notification stores before scheduling owned work.
        NotificationScheduler.schedulePeriodic(this)
        NotificationScheduler.runNow(this) // debug builds only: exercise the sync on launch
    }
}
