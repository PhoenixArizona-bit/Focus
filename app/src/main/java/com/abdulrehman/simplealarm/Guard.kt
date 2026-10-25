package com.abdulrehman.simplealarm

import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Calendar

object Cfg {
    private fun sp(c: Context) = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    // pkg -> daily limit in minutes. 0 means "focus mode only".
    fun limits(c: Context): Map<String, Int> =
        (sp(c).getStringSet("limits", emptySet()) ?: emptySet()).mapNotNull {
            val p = it.split("|")
            if (p.size == 2) p[0] to (p[1].toIntOrNull() ?: 0) else null
        }.toMap()

    fun setLimit(c: Context, pkg: String, min: Int?) {
        val m = limits(c).toMutableMap()
        if (min == null) m.remove(pkg) else m[pkg] = min
        sp(c).edit().putStringSet("limits", m.map { "${it.key}|${it.value}" }.toSet()).apply()
    }

    fun focusUntil(c: Context): Long = sp(c).getLong("focusUntil", 0L)
    fun setFocusUntil(c: Context, v: Long) = sp(c).edit().putLong("focusUntil", v).apply()
    fun focusActive(c: Context) = focusUntil(c) > System.currentTimeMillis()

    fun strict(c: Context) = sp(c).getBoolean("strict", false)
    fun setStrict(c: Context, v: Boolean) = sp(c).edit().putBoolean("strict", v).apply()

    fun sessionMin(c: Context) = sp(c).getInt("sessionMin", 0)
    fun setSessionMin(c: Context, v: Int) = sp(c).edit().putInt("sessionMin", v).apply()

    fun prayerFocus(c: Context) = sp(c).getInt("prayerFocus", 0)
    fun setPrayerFocus(c: Context, v: Int) = sp(c).edit().putInt("prayerFocus", v).apply()

    fun guard(c: Context) = sp(c).getBoolean("guard", false)
    fun setGuard(c: Context, v: Boolean) = sp(c).edit().putBoolean("guard", v).apply()
}

object Perms {
    @Suppress("DEPRECATION")
    fun usage(c: Context): Boolean {
        val ops = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            c.packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    fun overlay(c: Context) = Settings.canDrawOverlays(c)
}

object Usage {
    /** Returns (foreground ms today per package, current foreground package). */
    @Suppress("DEPRECATION")
    fun today(c: Context): Pair<Map<String, Long>, String?> {
        val usm = c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val events = usm.queryEvents(cal.timeInMillis, now)
        val e = UsageEvents.Event()
        val total = HashMap<String, Long>()
        val open = HashMap<String, Long>()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val p = e.packageName ?: continue
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                if (!open.containsKey(p)) open[p] = e.timeStamp
            } else if (e.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                val start = open.remove(p)
                if (start != null) total[p] = (total[p] ?: 0L) + (e.timeStamp - start)
            }
        }
        var fg: String? = null
        var fgStart = 0L
        for ((p, s) in open) if (s > fgStart) { fg = p; fgStart = s }
        if (fg != null) total[fg] = (total[fg] ?: 0L) + (now - fgStart)
        return total to fg
    }
}

object Guard {
    fun start(c: Context) =
        ContextCompat.startForegroundService(c, Intent(c, GuardService::class.java))

    fun stop(c: Context) {
        c.stopService(Intent(c, GuardService::class.java))
    }
}

private class Reason(val title: String, val msg: String, val allowBreak: Boolean)

class GuardService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val snooze = HashMap<String, Long>()
    private var overlay: View? = null
    private var curPkg: String? = null
    private var sessionStart = 0L
    private var ignoreUntil = 0L
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("guard", "Scroll guard", NotificationManager.IMPORTANCE_LOW)
        )
        val n = NotificationCompat.Builder(this, "guard")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("Scroll guard is on")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            running = true
            handler.post(tick)
        }
        return START_STICKY
    }

    private val tick = object : Runnable {
        override fun run() {
            val next = try { step() } catch (e: Exception) { 5000L }
            handler.postDelayed(this, next)
        }
    }

    private fun step(): Long {
        if (!Perms.overlay(this) || !Perms.usage(this)) {
            removeOverlay()
            return 10000L
        }
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isInteractive) {
            removeOverlay()
            curPkg = null
            return 10000L
        }
        val (usage, fg) = Usage.today(this)
        val now = System.currentTimeMillis()
        if (fg != curPkg) {
            curPkg = fg
            sessionStart = now
            removeOverlay()
        }
        val reason = if (fg == null || now < ignoreUntil) null else check(fg, usage, now)
        if (reason == null) removeOverlay()
        else if (overlay == null && fg != null) showOverlay(fg, reason, now)
        return 2000L
    }

    private fun label(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    private fun check(pkg: String, usage: Map<String, Long>, now: Long): Reason? {
        if (pkg == packageName) return null
        val lim = Cfg.limits(this)[pkg] ?: return null
        if ((snooze[pkg] ?: 0L) > now) return null
        val name = label(pkg)

        if (Cfg.focusActive(this)) {
            val left = (Cfg.focusUntil(this) - now) / 60000L + 1
            return Reason(
                "Focus mode is on",
                "$name is paused while you focus. $left min left.",
                !Cfg.strict(this)
            )
        }
        val used = (usage[pkg] ?: 0L) / 60000L
        if (lim > 0 && used >= lim) {
            return Reason(
                "Time to take a break",
                "You've spent $used min on $name today. Your daily limit is $lim min.",
                true
            )
        }
        val s = Cfg.sessionMin(this)
        val session = (now - sessionStart) / 60000L
        if (s > 0 && session >= s) {
            return Reason("Still scrolling?", "You've been on $name for $session min straight.", true)
        }
        return null
    }

    private fun showOverlay(pkg: String, r: Reason, now: Long) {
        val wm = getSystemService(WindowManager::class.java)
        fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F20B1F17"))
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }

        fun tv(t: String, size: Float, bold: Boolean): TextView {
            val v = TextView(this)
            v.text = t
            v.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            v.setTextColor(Color.parseColor("#F5EBD0"))
            v.gravity = Gravity.CENTER
            if (bold) v.setTypeface(v.typeface, Typeface.BOLD)
            v.setPadding(0, dp(8), 0, dp(8))
            return v
        }

        fun button(t: String, onClick: () -> Unit): Button {
            val b = Button(this)
            b.text = t
            b.setTextColor(Color.parseColor("#0B1F17"))
            b.setBackgroundColor(Color.parseColor("#D4AF37"))
            b.setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(12)
            b.layoutParams = lp
            return b
        }

        root.addView(tv(r.title, 28f, true))
        root.addView(tv(r.msg, 16f, false))
        root.addView(button("Return to home") {
            ignoreUntil = System.currentTimeMillis() + 3000L
            removeOverlay()
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        })
        if (r.allowBreak) {
            root.addView(button("Start a 5-minute break") {
                snooze[pkg] = System.currentTimeMillis() + 5 * 60000L
                sessionStart = System.currentTimeMillis()
                removeOverlay()
            })
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        try {
            wm.addView(root, params)
            overlay = root
        } catch (e: Exception) { }
    }

    private fun removeOverlay() {
        val v = overlay ?: return
        try { getSystemService(WindowManager::class.java).removeView(v) } catch (e: Exception) { }
        overlay = null
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        super.onDestroy()
    }
}
