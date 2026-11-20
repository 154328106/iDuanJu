package app.iduanju.debug

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.View
import java.io.File
import java.lang.ref.WeakReference

/**
 * Debug-only window snapshot via PixelCopy, for emulators (e.g. MEmu) whose `screencap` returns blank frames
 * while an app is in front. Captures exactly what the GPU rendered into the resumed activity's window.
 *   adb shell am broadcast -n <applicationId>/app.iduanju.debug.QaSnapshotReceiver [--es name shot.png]   (written to getExternalFilesDir)
 */
object QaSnapshot {
    @Volatile var resumed: WeakReference<Activity>? = null
}

class QaSnapshotInit : ContentProvider() {
    override fun onCreate(): Boolean {
        (context?.applicationContext as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { QaSnapshot.resumed = WeakReference(activity) }
            override fun onActivityPaused(activity: Activity) { if (QaSnapshot.resumed?.get() === activity) QaSnapshot.resumed = null }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        return true
    }
    override fun query(uri: android.net.Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) = null
    override fun getType(uri: android.net.Uri): String? = null
    override fun insert(uri: android.net.Uri, values: ContentValues?): android.net.Uri? = null
    override fun delete(uri: android.net.Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: android.net.Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

class QaSnapshotReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val activity = QaSnapshot.resumed?.get()
        if (activity == null) { Log.w("IDJ-QA", "snapshot: no resumed activity"); return }
        val out = File(ctx.getExternalFilesDir(null), intent.getStringExtra("name") ?: "qa-snapshot.png")
        val pending = goAsync()
        activity.runOnUiThread {
            val view = activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(activity.window, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) drawOverlayWindows(view, bitmap)
                Thread {
                    if (result == PixelCopy.SUCCESS) runCatching { out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }.onFailure { Log.w("IDJ-QA", "snapshot write failed", it) }
                    Log.i("IDJ-QA", "snapshot result=$result ${view.width}x${view.height} -> $out")
                    pending.finish()
                }.start()
            }, Handler(Looper.getMainLooper()))
        }
    }

    /** Sheets, dialogs and popups live in their own windows, which PixelCopy of the activity window leaves out. */
    private fun drawOverlayWindows(base: View, bitmap: Bitmap) = runCatching {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val views = global.getDeclaredField("mViews").apply { isAccessible = true }.get(global.getMethod("getInstance").invoke(null)) as List<*>
        val origin = IntArray(2).also(base::getLocationOnScreen)
        val canvas = Canvas(bitmap)
        views.filterIsInstance<View>().filter { it !== base && it.isAttachedToWindow && it.isShown && it.width > 0 }.forEach { root ->
            val at = IntArray(2).also(root::getLocationOnScreen)
            canvas.save()
            canvas.translate((at[0] - origin[0]).toFloat(), (at[1] - origin[1]).toFloat())
            root.draw(canvas)
            canvas.restore()
        }
    }.onFailure { Log.w("IDJ-QA", "overlay windows skipped", it) }
}