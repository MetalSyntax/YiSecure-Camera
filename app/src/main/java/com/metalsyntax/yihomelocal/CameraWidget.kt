package com.metalsyntax.yihomelocal

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.RemoteViews
import com.metalsyntax.yihomelocal.data.Snapshots
import com.metalsyntax.yihomelocal.data.Store
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Widget de la pantalla de inicio: última imagen guardada de la cámara principal (la última abierta). No abre P2P:
 * se actualiza cuando la app guarda una imagen nueva (al salir del vivo) y cada 30 min.
 */
class CameraWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = ids.forEach { mgr.updateAppWidget(it, views(ctx)) }

    companion object {
        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, CameraWidget::class.java))
            if (ids.isNotEmpty()) ids.forEach { mgr.updateAppWidget(it, views(ctx)) }
        }

        private fun views(ctx: Context): RemoteViews {
            val store = Store(ctx)
            val cams = store.cameras
            val cam = cams.firstOrNull { it.uid == store.lastCamera } ?: cams.firstOrNull()
            val v = RemoteViews(ctx.packageName, R.layout.widget_camera)
            v.setTextViewText(R.id.widget_name, cam?.name ?: "YiSecure")
            val snap = cam?.let { Snapshots.load(ctx, it.uid) }
            if (snap != null) {
                // RemoteViews tiene tope de memoria: se reduce a 640 px de ancho
                val b = snap.first
                val small = if (b.width > 640) Bitmap.createScaledBitmap(b, 640, 640 * b.height / b.width, true) else b
                v.setImageViewBitmap(R.id.widget_image, small)
                v.setTextViewText(R.id.widget_time, SimpleDateFormat("dd/MM HH:mm", Locale.US).format(Date(snap.second)))
            } else v.setTextViewText(R.id.widget_time, "Abre una cámara")
            v.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(ctx, 1,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            return v
        }
    }
}
