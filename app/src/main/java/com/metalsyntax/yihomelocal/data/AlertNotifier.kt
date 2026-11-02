package com.metalsyntax.yihomelocal.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.metalsyntax.yihomelocal.MainActivity
import com.metalsyntax.yihomelocal.R
import java.util.concurrent.TimeUnit

/**
 * Avisos de alertas nuevas de la nube de Yi: WorkManager consulta /v2/alert/list cada 15 min (el mínimo de Android)
 * y publica una notificación por alerta nueva (con la imagen descifrada), o un resumen si hay más de [MAX].
 * ponytail: es sondeo, no push real (el push de Yi va por FCM con la firma de su app); retraso de hasta ~15 min.
 */
object AlertNotifier {
    private const val CHANNEL = "alerts"
    private const val WORK = "yi_alerts"
    private const val MAX = 4
    const val EXTRA_TAB = "tab"

    fun schedule(ctx: Context, on: Boolean) {
        val wm = WorkManager.getInstance(ctx)
        if (!on) { wm.cancelUniqueWork(WORK); return }
        val store = Store(ctx)
        if (store.notifiedUntil == 0L) store.notifiedUntil = System.currentTimeMillis() // no avisar lo viejo
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<Worker>(15, TimeUnit.MINUTES).build())
    }

    fun canNotify(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Revisa la nube y notifica lo posterior a la última revisión. Devuelve cuántas alertas nuevas hubo. */
    fun check(ctx: Context): Int {
        val store = Store(ctx)
        val s = store.session ?: return 0
        if (!store.notify || store.silent || !canNotify(ctx)) return 0
        val since = store.notifiedUntil
        val now = System.currentTimeMillis()
        val fresh = YiCloud(store.region).alerts(s, since / 1000, now / 1000).filter { it.time > since }.sortedBy { it.time }
        store.notifiedUntil = maxOf(since, fresh.maxOfOrNull { it.time } ?: since)
        if (fresh.isEmpty()) return 0
        channel(ctx)
        val names = store.cameras.associate { it.uid to it.name }
        val nm = NotificationManagerCompat.from(ctx)
        if (fresh.size > MAX) {
            nm.notify(1, base(ctx).setContentTitle("${fresh.size} alertas nuevas")
                .setContentText(fresh.groupBy { names[it.uid] ?: it.uid }.entries.joinToString(" · ") { "${it.key}: ${it.value.size}" })
                .build())
        } else fresh.forEach { a ->
            val img = runCatching { Alert.download(a.picUrl, a.picPwd).let { BitmapFactory.decodeByteArray(it, 0, it.size) } }.getOrNull()
            val n = base(ctx).setContentTitle("${a.kind} · ${names[a.uid] ?: a.uid}")
                .setContentText(java.text.SimpleDateFormat("dd/MM HH:mm:ss", java.util.Locale.US).format(java.util.Date(a.time)))
                .setWhen(a.time).setShowWhen(true)
            img?.let { n.setLargeIcon(it).setStyle(NotificationCompat.BigPictureStyle().bigPicture(it).bigLargeIcon(null as android.graphics.Bitmap?)) }
            nm.notify((a.id.ifEmpty { "${a.uid}${a.time}" }).hashCode(), n.build())
        }
        return fresh.size
    }

    /**
     * Modo silencioso global ("Silenciosa" del diseño): apaga o vuelve a encender el push de la nube en todas las
     * cámaras (/v5/alert/push_prop) y los avisos de la app. Bloqueante (llamar en IO). Devuelve las que fallaron.
     */
    fun setSilent(ctx: Context, silent: Boolean): List<String> {
        val store = Store(ctx)
        val s = store.session ?: return emptyList()
        val cloud = YiCloud(store.region)
        val failed = store.cameras.filter { c -> runCatching { cloud.setAlertPush(s, c.uid, !silent) }.isFailure }.map { it.name }
        store.silent = silent
        if (!silent) store.notifiedUntil = System.currentTimeMillis() // no avisar lo ocurrido mientras estaba en silencio
        return failed
    }

    private fun base(ctx: Context): NotificationCompat.Builder {
        val open = PendingIntent.getActivity(ctx, 0,
            Intent(ctx, MainActivity::class.java).putExtra(EXTRA_TAB, 2).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_notify).setColor(0xFFE53935.toInt())
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true).setContentIntent(open)
    }

    private fun channel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        ctx.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Alertas de cámaras", NotificationManager.IMPORTANCE_HIGH))
    }

    class Worker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
        override suspend fun doWork(): Result = runCatching { check(applicationContext) }
            .fold({ Result.success() }, { Result.retry() })
    }
}
