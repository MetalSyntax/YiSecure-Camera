package com.metalsyntax.yihomelocal.data

import android.content.Context
import com.metalsyntax.yihomelocal.p2p.YiP2p
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Perfiles del diseño "Cámaras" (En casa / Fuera de casa / Modo noche). Cada uno abre una conexión P2P corta por
 * cámara (sin video), manda los comandos y la cierra; las alertas push van por la nube.
 * - En casa: cámaras marcadas como interiores en privacidad y sin push; las exteriores encendidas con push.
 * - Fuera: todas encendidas y con push.
 * - Noche: todas encendidas, visión nocturna automática, sensibilidad alta y push.
 */
enum class Profile(val label: String) { HOME("EN CASA"), AWAY("FUERA DE CASA"), NIGHT("MODO NOCHE") }

object Profiles {
    /** Aplica [p] a todas las cámaras; devuelve los nombres de las que no respondieron. */
    suspend fun apply(ctx: Context, p: Profile): List<String> = withContext(Dispatchers.IO) {
        val store = Store(ctx)
        val s = store.session ?: return@withContext emptyList()
        val cloud = YiCloud(store.region)
        val failed = coroutineScope {
            store.cameras.map { cam ->
                async {
                    val interior = store.isInterior(cam.uid)
                    val on = !(p == Profile.HOME && interior)
                    val p2pOk = withTimeoutOrNull(25_000) {
                        val c = runCatching {
                            YiP2p.open(cam, YiP2p.QUALITY_SD, false, object : YiP2p.Listener() {
                                override fun onState(text: String, connected: Boolean) {}
                                override fun onFrame(f: com.tutk.IOTC.AVFrame) {}
                                override fun onAudio(f: com.tutk.IOTC.AVFrame) {}
                            })
                        }.getOrNull() ?: return@withTimeoutOrNull false
                        try {
                            delay(2500) // que el SDK autentique
                            var last = YiP2p.setCameraOn(c, on) ?: return@withTimeoutOrNull false
                            if (p == Profile.NIGHT) {
                                YiP2p.setNightVision(c, 1)?.let { last = it }
                                YiP2p.setSensitivity(c, 0)?.let { last = it }
                            }
                            CameraStatus.save(ctx, cam.uid, last)
                            true
                        } finally { runCatching { YiP2p.close(c) } }
                    } == true
                    val pushOk = runCatching { cloud.setAlertPush(s, cam.uid, on) }.isSuccess
                    if (p2pOk && pushOk) null else cam.name
                }
            }.awaitAll().filterNotNull()
        }
        store.profile = p.name
        store.silent = false
        store.notifiedUntil = maxOf(store.notifiedUntil, System.currentTimeMillis())
        failed
    }
}
