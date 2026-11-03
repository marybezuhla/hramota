package ua.hramota

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import ua.hramota.data.Vault

/** Locks the locked storage a minute after the last Hramota window leaves the screen. */
class HramotaApp : Application() {
    private var started = 0
    private val main = Handler(Looper.getMainLooper())
    private val lock: Runnable = Runnable {
        // Work holding the key (sealing, a backup) finishes first; locking is tried again shortly
        if (started == 0 && !Vault.lock(this)) main.postDelayed(lock, 5_000)
    }

    override fun onCreate() {
        super.onCreate()
        // The PDF engine's sandboxed process has nothing of this to do
        if (android.os.Process.isIsolated()) return
        // Temporary copies left by a process that was stopped are removed at once
        ua.hramota.data.Shelf.clearOpened(this)
        java.io.File(cacheDir, "restore").deleteRecursively()
        cacheDir.listFiles { f -> f.name.startsWith("notes-") && f.name.endsWith(".pdf") }?.forEach { it.delete() }
        // Half-written kept copies (Hramota stopped while one was put in place)
        java.io.File(filesDir, "documents").listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { started++; main.removeCallbacks(lock) }
            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                if (started == 0) main.postDelayed(lock, LOCK_AFTER)
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    companion object { const val LOCK_AFTER = 60_000L }
}
