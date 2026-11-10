package com.metalsyntax.yihomelocal.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.metalsyntax.yihomelocal.MainActivity
import com.metalsyntax.yihomelocal.R
import com.metalsyntax.yihomelocal.data.AlertNotifier
import com.metalsyntax.yihomelocal.data.CameraDevice
import com.metalsyntax.yihomelocal.data.LocalEvents
import com.metalsyntax.yihomelocal.data.Store
import com.metalsyntax.yihomelocal.p2p.FrameGrabber
import com.metalsyntax.yihomelocal.p2p.YiP2p
import com.tutk.IOTC.AVFrame
import com.xiaoyi.camera.sdk.AntsCamera
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Vigilancia con la IA del teléfono sin tener la app abierta: servicio en primer plano que mantiene una conexión
 * P2P (calidad SD, sin audio) con cada cámara, toma ≈1 cuadro por segundo y pasa la misma IA que el vivo
 * (SceneAnalyzer + Detector + Sentinel, respetando la zona de vigilancia). Los hallazgos se guardan en
 * [LocalEvents] (100 por cámara) y se avisan con una notificación.
 *
 * El SDK comparte un AntsCamera por cámara: cuando la app abre esa cámara en pantalla ([hold]) el servicio suelta
 * su conexión y la retoma al cerrarla ([unhold]); mientras tanto la IA la hace la pantalla del vivo.
 */
class WatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val watchers = HashMap<String, Pair<Job, CameraDevice>>()
    private val conns = ConcurrentHashMap<String, Pair<AntsCamera, FrameGrabber>>()
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        channel(this)
        val n = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notify).setColor(0xFFE53935.toInt())
            .setContentTitle("Vigilancia IA activa").setContentText("Analizando las cámaras en el teléfono")
            .setOngoing(true).setSilent(true).setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra(AlertNotifier.EXTRA_TAB, 2),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(ID, n)
        // CPU despierta con la pantalla apagada: sin esto el análisis se detiene en reposo
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YiSecure:watch").apply { acquire() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val store = Store(this)
        if (!store.aiBackground || store.session == null) { stopSelf(); return START_NOT_STICKY }
        val cams = store.cameras
        // cámaras quitadas de la cuenta: cortar su vigilancia
        (watchers.keys - cams.map { it.uid }.toSet()).forEach { watchers.remove(it)?.first?.cancel(); drop(it) }
        cams.forEach { c ->
            val w = watchers[c.uid]
            // datos nuevos de la nube (contraseña P2P cambiada, otra IP…): reconectar con ellos
            if (w != null && w.first.isActive && w.second.copy(online = c.online) == c) return@forEach
            w?.first?.cancel(); drop(c.uid)
            watchers[c.uid] = scope.launch { watch(c) } to c
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        conns.keys.toList().forEach(::drop)
        wake?.takeIf { it.isHeld }?.release()
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Bucle de una cámara: conectar, analizar ≈1 cuadro/s, reconectar con espera creciente si deja de llegar video. */
    private suspend fun watch(cam: CameraDevice) {
        val store = Store(this)
        val analyzer = SceneAnalyzer()
        val sentinel = Sentinel()
        var retries = 0
        while (scope.isActive) {
            if (cam.uid in held) { drop(cam.uid); delay(2_000); continue }
            val conn = conns[cam.uid] ?: open(cam) ?: run { delay(backoff(retries++)); null } ?: continue
            delay(1_000)
            val (_, grab) = conn
            if (System.currentTimeMillis() - grab.lastFrameAt > 30_000 && grab.lastFrameAt != 0L || stalledSinceOpen(cam.uid)) {
                Log.i(TAG, "${cam.name}: sin video, reconectando")
                drop(cam.uid); delay(backoff(retries++)); continue
            }
            val frame = grab.latest() ?: continue
            if (retries > 0 || !analyzing.contains(cam.uid)) { analyzing += cam.uid; Log.i(TAG, "${cam.name}: analizando ${frame.width}×${frame.height}") }
            retries = 0
            val zone = store.zone(cam.uid)
            val now = System.currentTimeMillis()
            val (dets, found, night) = withContext(Dispatchers.Default) {
                val bmp = Bitmap.createScaledBitmap(frame, 480, 270, true)
                val sc = analyzer.analyze(bmp).let { raw ->
                    if (zone != null && raw.motion?.let { android.graphics.RectF.intersects(it, zone) } == false) raw.copy(motion = null) else raw
                }
                val d = runCatching { Detector.detect(this@WatchService, bmp) }.getOrDefault(emptyList())
                    .filter { zone == null || zone.contains(it.box.centerX(), it.box.centerY()) }
                val motion = sc.motion?.takeIf { d.isEmpty() }?.let { listOf(Det(if (sc.shadow) Kind.SHADOW else Kind.MOTION, "", 1f, it)) }.orEmpty()
                val f = sentinel.update(now, d, sc)
                if (f.isNotEmpty()) f.forEach { LocalEvents.add(this@WatchService, cam.uid, now, it, bmp, d + motion, sc.night) }
                Triple(d, f, sc.night)
            }
            found.firstOrNull()?.let { notifyFinding(cam, it, dets, night) }
        }
    }

    private val openedAt = ConcurrentHashMap<String, Long>()
    private val analyzing = ConcurrentHashMap.newKeySet<String>()
    private fun stalledSinceOpen(uid: String): Boolean {
        val g = conns[uid]?.second ?: return false
        return g.lastFrameAt == 0L && System.currentTimeMillis() - (openedAt[uid] ?: 0L) > 40_000
    }

    private fun backoff(n: Int) = 1000L * minOf(120, 5 shl minOf(n, 5))

    private fun open(cam: CameraDevice): Pair<AntsCamera, FrameGrabber>? {
        val grab = FrameGrabber()
        val c = runCatching {
            YiP2p.open(cam, YiP2p.QUALITY_SD, false, object : YiP2p.Listener() {
                override fun onState(text: String, connected: Boolean) { Log.i(TAG, "${cam.name}: $text") }
                override fun onFrame(f: AVFrame) = grab.feed(f)
                override fun onAudio(f: AVFrame) {}
            })
        }.onFailure { Log.w(TAG, "${cam.name}: no se pudo abrir", it) }.getOrNull() ?: run { grab.release(); return null }
        openedAt[cam.uid] = System.currentTimeMillis()
        return (c to grab).also { conns[cam.uid] = it }
    }

    /** Suelta la conexión del servicio con [uid] (en el hilo principal, como el resto de llamadas al SDK). */
    private fun drop(uid: String) {
        analyzing -= uid
        val (c, g) = conns.remove(uid) ?: return
        runCatching { YiP2p.close(c) }
        g.release()
    }

    private fun notifyFinding(cam: CameraDevice, f: Finding, dets: List<Det>, night: Boolean) {
        val store = Store(this)
        if (!store.notify || store.silent || !AlertNotifier.canNotify(this)) return
        // la foto guardada del evento (la más reciente de esta cámara)
        val img = LocalEvents.list(this, cam.uid).firstOrNull()?.file?.let { android.graphics.BitmapFactory.decodeFile(it) }
        val n = NotificationCompat.Builder(this, EVENTS).setSmallIcon(R.drawable.ic_notify).setColor(0xFFE53935.toInt())
            .setContentTitle("${f.title} · ${cam.name}").setContentText(f.desc + if (night) " · IR nocturno" else "")
            .setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_EVENT)
            .setContentIntent(PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java).putExtra(AlertNotifier.EXTRA_TAB, 2)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        img?.let { n.setLargeIcon(it).setStyle(NotificationCompat.BigPictureStyle().bigPicture(it).bigLargeIcon(null as Bitmap?)) }
        runCatching { NotificationManagerCompat.from(this).notify((cam.uid + System.currentTimeMillis()).hashCode(), n.build()) }
    }

    companion object {
        private const val TAG = "YiWatch"
        private const val ID = 7001
        private const val CHANNEL = "watch"
        private const val EVENTS = "ai_events"
        private val held = ConcurrentHashMap.newKeySet<String>()
        @Volatile private var instance: WatchService? = null

        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, WatchService::class.java))
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, WatchService::class.java))

        /** La pantalla abre [uid]: el servicio suelta su conexión ya (antes de que la pantalla llame a YiP2p.open). */
        fun hold(uid: String) { held += uid; instance?.drop(uid) }

        fun unhold(uid: String) { held -= uid }

        private fun channel(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Vigilancia IA en segundo plano", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(EVENTS, "Detecciones de la IA", NotificationManager.IMPORTANCE_HIGH))
        }
    }
}
