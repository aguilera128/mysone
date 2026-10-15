package dev.javieraguilera.mysone.capture

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** Servicio en primer plano de tipo microphone (§5.1). Lo implementa B1. */
class ListeningService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
