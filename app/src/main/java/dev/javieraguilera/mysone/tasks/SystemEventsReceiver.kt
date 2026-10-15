package dev.javieraguilera.mysone.tasks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** BOOT_COMPLETED, MY_PACKAGE_REPLACED, TIME_SET, TIMEZONE_CHANGED → reprogramar y avisar (§6). Lo implementa B4. */
class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {}
}
