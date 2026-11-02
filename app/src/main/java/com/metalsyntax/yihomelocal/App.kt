package com.metalsyntax.yihomelocal

import android.app.Application
import android.content.Context

/** Carga el SDK de Yi antes de que cualquier pantalla toque sus clases. */
class App : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        sdkReady = YiSdk.load(base)
    }

    companion object {
        /** false = release sin Kami Home/Yi Home instalada: la app muestra cómo conseguirla. */
        var sdkReady = false
            private set
    }
}
