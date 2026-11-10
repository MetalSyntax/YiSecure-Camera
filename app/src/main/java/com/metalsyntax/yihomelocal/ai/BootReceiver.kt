package com.metalsyntax.yihomelocal.ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.metalsyntax.yihomelocal.data.Store

/** Al encender el teléfono vuelve a arrancar la vigilancia IA si estaba activada (sin abrir la app). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val s = Store(ctx)
        if (s.aiBackground && s.session != null) WatchService.start(ctx)
    }
}
