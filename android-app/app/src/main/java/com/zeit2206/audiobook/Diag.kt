package com.zeit2206.audiobook

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Debug
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Starts the diagnostics log before anything else runs (registered in the manifest). */
class AudiobookApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Diag.init(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Only the levels that mean "you may be killed soon" are worth a line.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) Diag.log("memory", "onTrimMemory level=$level · ${Diag.memory()}")
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Diag.log("memory", "onLowMemory · ${Diag.memory()}")
    }
}

/**
 * Small on-device log for problems that only show up on the phone (app killed in the background,
 * crashes, folders that stop loading). Kept in app storage, capped in size, shared from the
 * Videos screen ("Report"). Cheap: a few lines per minute at most.
 */
object Diag {
    private const val MAX_BYTES = 512 * 1024
    private lateinit var file: File
    private lateinit var app: Context
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun init(context: Context) {
        app = context.applicationContext
        file = File(app.filesDir, "diagnostics.log")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            log("crash", "thread=${t.name} · ${memory()}\n${e.stackTraceToString().take(4000)}")
            previous?.uncaughtException(t, e)
        }
        log("start", "pid=${android.os.Process.myPid()} · ${memory()}")
        logExitReasons()
    }

    @Synchronized
    fun log(tag: String, msg: String) {
        if (!::file.isInitialized) return
        try {
            if (file.length() > MAX_BYTES) { // keep the newest half
                val text = file.readText()
                file.writeText(text.substring(text.length / 2).substringAfter('\n'))
            }
            file.appendText("${time.format(Date())} [$tag] $msg\n")
        } catch (_: Exception) {
        }
    }

    fun memory(): String {
        val rt = Runtime.getRuntime()
        val used = (rt.totalMemory() - rt.freeMemory()) / 1_048_576
        val native = Debug.getNativeHeapAllocatedSize() / 1_048_576
        val am = app.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return "heap=${used}/${rt.maxMemory() / 1_048_576} MB native=${native} MB " +
                "device free=${mi.availMem / 1_048_576} MB low=${mi.lowMemory}"
    }

    /** Why the previous runs ended (low memory kill, crash, user swipe…): Android keeps this record. */
    private fun logExitReasons() {
        if (Build.VERSION.SDK_INT < 30) return
        val prefs = app.getSharedPreferences("diag", Context.MODE_PRIVATE)
        val seen = prefs.getLong("last_exit", 0)
        val exits = app.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(app.packageName, 0, 10).filter { it.timestamp > seen }
        for (e in exits.sortedBy { it.timestamp }) {
            log("exit", "at ${time.format(Date(e.timestamp))} reason=${reasonName(e.reason)} " +
                    "importance=${e.importance} pss=${e.pss / 1024} MB rss=${e.rss / 1024} MB · ${e.description ?: ""}")
        }
        exits.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong("last_exit", it).apply() }
    }

    private fun reasonName(r: Int) = when (r) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED (force stop / update)"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "code $r"
    }

    /** Device info + current memory + the log, ready to paste in a chat. */
    fun report(): String {
        val pkg = app.packageManager.getPackageInfo(app.packageName, 0)
        val perms = app.contentResolver.persistedUriPermissions.size
        val header = "Audiobook ${pkg.versionName} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n" +
                "Now: ${memory()} · persisted folder permissions=$perms\n\n"
        val body = try { file.readText().takeLast(60_000) } catch (_: Exception) { "(no log)" }
        return header + body
    }

    fun share(context: Context) {
        log("report", "shared · ${memory()}")
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Audiobook diagnostics")
            .putExtra(Intent.EXTRA_TEXT, report())
        context.startActivity(Intent.createChooser(send, "Share diagnostics"))
    }
}
