package uk.doorway.terminal

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.net.ConnectivityManager
import android.net.Network
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Range
import android.util.Size
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.Calendar
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Doorway: shows the Doorway Terminal dashboard full screen, wakes the screen when the
 * front camera sees movement, and fades to black when nobody has been around for a while.
 * Camera frames are compared in memory only; nothing is saved or sent anywhere.
 */
class MainActivity : ComponentActivity() {

    companion object {
        const val DASHBOARD_URL = "https://charlesedwardtwo-prog.github.io/Doorway-terminal-/"
        const val PREFS = "doorway"
        const val DEFAULT_SLEEP_SECONDS = 120
        const val DEFAULT_SENSITIVITY = 5      // 1 (needs a lot of movement) to 10 (twitchy)
        const val SETTLE_MS = 3000L            // ignore the camera this long after the screen changes
        const val RETRY_MS = 30000L
        const val DEFAULT_SCHEDULE = "07:30-09:30,16:00-18:00"   // awake windows; screen fully off the rest of the time
    }

    private lateinit var web: WebView
    private lateinit var blackout: View
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var cameraExecutor: ExecutorService

    @Volatile private var lastActivity = System.currentTimeMillis()
    @Volatile private var ignoreUntil = 0L
    @Volatile private var lastMotionAt = 0L
    private var asleep = false
    private var deepAsleep = false
    @Volatile private var lastAnalysed = 0L
    private var brightOverride = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE   // set to full while the hologram is on
    private var cameraRunning = false
    private var prevFrame: IntArray? = null
    private var loadFailed = false

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private var sleepSeconds: Int
        get() = prefs.getInt("sleepSeconds", DEFAULT_SLEEP_SECONDS)
        set(v) = prefs.edit().putInt("sleepSeconds", v).apply()
    private var sensitivity: Int
        get() = prefs.getInt("sensitivity", DEFAULT_SENSITIVITY)
        set(v) = prefs.edit().putInt("sensitivity", v).apply()
    private var motionEnabled: Boolean
        get() = prefs.getBoolean("motion", true)
        set(v) = prefs.edit().putBoolean("motion", v).apply()
    private var scheduleOn: Boolean
        get() = prefs.getBoolean("scheduleOn", true)
        set(v) = prefs.edit().putBoolean("scheduleOn", v).apply()
    private var schedule: String
        get() = prefs.getString("schedule", DEFAULT_SCHEDULE) ?: DEFAULT_SCHEDULE
        set(v) = prefs.edit().putString("schedule", v).apply()

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera()
    }

    // ------------------------------------------------------------------ setup

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Lets the wake-up alarm switch the screen on and show the dashboard over the lock screen.
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        cameraExecutor = Executors.newSingleThreadExecutor()

        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            addJavascriptInterface(BatteryBridge(), "fully")   // same names the dashboard already reads
            addJavascriptInterface(AppBridge(), "doorway")
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) showOffline()
                }
                override fun onPageFinished(view: WebView, url: String) {
                    if (url.startsWith("http")) loadFailed = false
                }
            }
        }

        blackout = View(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            isClickable = true
            setOnClickListener { wake() }
        }

        setContentView(FrameLayout(this).apply {
            addView(web, FrameLayout.LayoutParams(-1, -1))
            addView(blackout, FrameLayout.LayoutParams(-1, -1))
        })

        // As the home screen, Back should never leave the dashboard.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { wake() }
        })

        // Reload when the Wi-Fi comes back after a drop-out.
        (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (loadFailed) handler.post { web.loadUrl(DASHBOARD_URL) }
                }
            })

        web.loadUrl(DASHBOARD_URL)
        handler.post(sleepCheck)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        wake()
        if (motionEnabled) ensureCamera()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        lastActivity = System.currentTimeMillis()
        wake()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        lastActivity = System.currentTimeMillis()
        if (asleep) { wake(); return true }
        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun showOffline() {
        loadFailed = true
        web.loadData(
            "<html><body style='background:#03130a;color:#4dff8f;font-family:monospace;display:flex;align-items:center;" +
                "justify-content:center;height:100vh;margin:0;font-size:28px;text-align:center'>" +
                "DOORWAY TERMINAL<br><br>NO CONNECTION<br><span style='font-size:18px;opacity:.7'>RETRYING EVERY 30 SECONDS</span></body></html>",
            "text/html", "utf-8")
        handler.postDelayed({ if (loadFailed) web.loadUrl(DASHBOARD_URL) }, RETRY_MS)
    }

    // ------------------------------------------------------------------ sleep / wake

    private val sleepCheck = object : Runnable {
        override fun run() {
            val idle = System.currentTimeMillis() - lastActivity
            val limit = (if (sleepSeconds > 0) sleepSeconds else 120) * 1000L
            if (!inAwakeWindow()) { if (!deepAsleep && idle > limit) deepSleep() }
            else if (!asleep && sleepSeconds > 0 && idle > limit) sleep()
            handler.postDelayed(this, 1000)
        }
    }

    private fun sleep() {
        asleep = true
        ignoreUntil = System.currentTimeMillis() + SETTLE_MS
        blackout.visibility = View.VISIBLE
        setBrightness(0.0f)
        // Stop drawing and running the dashboard while nobody is looking; it catches up on wake.
        web.visibility = View.INVISIBLE
        web.onPause()
        web.pauseTimers()
    }

    /** Outside the awake windows: camera off and let Android switch the screen fully off. */
    private fun deepSleep() {
        if (!asleep) sleep()
        deepAsleep = true
        stopCamera()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        scheduleWake()
    }

    private fun wake() {
        lastActivity = System.currentTimeMillis()
        if (deepAsleep) {
            deepAsleep = false
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (motionEnabled) ensureCamera()
        }
        if (!asleep) return
        asleep = false
        ignoreUntil = System.currentTimeMillis() + SETTLE_MS
        web.visibility = View.VISIBLE
        web.resumeTimers()
        web.onResume()
        blackout.visibility = View.GONE
        setBrightness(brightOverride)
        web.evaluateJavascript("window.poke && poke(); window.checkReminders && checkReminders(); window.tickClock && tickClock();", null)
    }

    // ------------------------------------------------------------------ awake windows

    /** "07:30-09:30,16:00-18:00" -> list of (startMinute, endMinute). */
    private fun windows(): List<Pair<Int, Int>> = schedule.split(",").mapNotNull { part ->
        val m = Regex("^\\s*(\\d{1,2}):(\\d{2})\\s*-\\s*(\\d{1,2}):(\\d{2})\\s*$").find(part) ?: return@mapNotNull null
        val (h1, m1, h2, m2) = m.destructured
        Pair(h1.toInt() * 60 + m1.toInt(), h2.toInt() * 60 + m2.toInt())
    }

    private fun inAwakeWindow(): Boolean {
        if (!scheduleOn) return true
        val w = windows(); if (w.isEmpty()) return true
        val c = Calendar.getInstance(); val now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return w.any { (s, e) -> if (e > s) now in s until e else now >= s || now < e }
    }

    private fun nextWindowStart(): Long? {
        val w = windows(); if (w.isEmpty()) return null
        val now = System.currentTimeMillis()
        for (day in 0..7) for ((s, _) in w.sortedBy { it.first }) {
            val c = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, day); set(Calendar.HOUR_OF_DAY, s / 60); set(Calendar.MINUTE, s % 60); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            if (c.timeInMillis > now + 30000) return c.timeInMillis
        }
        return null
    }

    /** An alarm that brings the dashboard back and switches the screen on at the next awake window. */
    private fun scheduleWake() {
        val at = nextWindowStart() ?: return
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        val pi = PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try { am.setAlarmClock(AlarmManager.AlarmClockInfo(at, pi), pi) }
        catch (_: SecurityException) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
    }

    private fun setBrightness(value: Float) {
        window.attributes = window.attributes.apply { screenBrightness = value }
    }

    // ------------------------------------------------------------------ camera motion

    private fun ensureCamera() {
        if (cameraRunning) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else askCamera.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                @Suppress("DEPRECATION")
                val builder = ImageAnalysis.Builder()
                    .setTargetResolution(Size(160, 120))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                slowestFpsRange(provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA))?.let { range -> applyFps(builder, range) }
                val analysis = builder.build()
                analysis.setAnalyzer(cameraExecutor) { image -> analyse(image) }
                val selector = when {
                    provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                    else -> CameraSelector.DEFAULT_BACK_CAMERA
                }
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, analysis)
                cameraRunning = true
            } catch (e: Exception) {
                cameraRunning = false
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** The lowest frame-rate range this camera supports (e.g. 7-15 fps instead of 30), to save power. */
    private fun slowestFpsRange(front: Boolean): Range<Int>? = try {
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val want = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want }
        id?.let { cm.getCameraCharacteristics(it).get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) }
            ?.minWithOrNull(compareBy<Range<Int>>({ it.upper }, { it.lower }))
    } catch (_: Exception) { null }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun applyFps(builder: ImageAnalysis.Builder, range: Range<Int>) {
        try { Camera2Interop.Extender(builder).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range) } catch (_: Exception) {}
    }

    private fun stopCamera() {
        try { ProcessCameraProvider.getInstance(this).get().unbindAll() } catch (_: Exception) {}
        cameraRunning = false
        prevFrame = null
    }

    /** Compares a 32x24 grid of brightness samples with the previous frame. */
    private fun analyse(image: ImageProxy) {
        try {
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastAnalysed < (if (asleep) 250L else 500L)) return
            lastAnalysed = nowMs
            val plane = image.planes[0]
            val buf = plane.buffer
            val stride = plane.rowStride
            val w = image.width
            val h = image.height
            val gw = 32
            val gh = 24
            val frame = IntArray(gw * gh)
            for (gy in 0 until gh) {
                val row = (gy * h / gh) * stride
                for (gx in 0 until gw) frame[gy * gw + gx] = buf.get(row + gx * w / gw).toInt() and 0xFF
            }
            val prev = prevFrame
            prevFrame = frame
            if (prev == null || System.currentTimeMillis() < ignoreUntil) return

            // Sensitivity 1..10 maps to: pixel change needed 40..15, share of grid 12%..1.5%
            val s = sensitivity.coerceIn(1, 10)
            val pixelThreshold = 40 - (s - 1) * 25 / 9
            val areaThreshold = (gw * gh) * (12.0 - (s - 1) * 10.5 / 9) / 100.0
            var changed = 0
            for (i in frame.indices) if (abs(frame[i] - prev[i]) > pixelThreshold) changed++
            if (changed > areaThreshold) {
                lastMotionAt = System.currentTimeMillis()
                runOnUiThread { wake() }
            }
        } catch (_: Exception) {
        } finally {
            image.close()
        }
    }

    // ------------------------------------------------------------------ bridges for the web page

    inner class BatteryBridge {
        @JavascriptInterface
        fun getBatteryLevel(): Int =
            (getSystemService(Context.BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        @JavascriptInterface
        fun isPlugged(): Boolean {
            val status = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            return (status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        }
    }

    inner class AppBridge {
        @JavascriptInterface fun getVersion(): String = packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        @JavascriptInterface fun getSleepSeconds(): Int = sleepSeconds
        @JavascriptInterface fun setSleepSeconds(v: Int) { sleepSeconds = v.coerceIn(0, 3600) }
        @JavascriptInterface fun getSensitivity(): Int = sensitivity
        @JavascriptInterface fun setSensitivity(v: Int) { sensitivity = v.coerceIn(1, 10) }
        @JavascriptInterface fun isMotionOn(): Boolean = motionEnabled && cameraRunning
        @JavascriptInterface fun setMotion(on: Boolean) {
            motionEnabled = on
            runOnUiThread { if (on) ensureCamera() else stopCamera() }
        }
        @JavascriptInterface fun secondsSinceMotion(): Int =
            if (lastMotionAt == 0L) -1 else ((System.currentTimeMillis() - lastMotionAt) / 1000).toInt()
        @JavascriptInterface fun sleepNow() { runOnUiThread { sleep() } }
        /** 0..1 forces the screen brightness (hologram uses 1); anything below 0 hands it back to Android. */
        @JavascriptInterface fun setScreenBrightness(v: Float) {
            runOnUiThread {
                brightOverride = if (v < 0f) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else v.coerceIn(0.01f, 1f)
                if (!asleep) setBrightness(brightOverride)
            }
        }
        @JavascriptInterface fun wakeNow() { runOnUiThread { wake() } }
        @JavascriptInterface fun getSchedule(): String = schedule
        @JavascriptInterface fun setSchedule(v: String) { schedule = v }
        @JavascriptInterface fun isScheduleOn(): Boolean = scheduleOn
        @JavascriptInterface fun setScheduleOn(on: Boolean) { scheduleOn = on }
        @JavascriptInterface fun isAwakeWindow(): Boolean = inAwakeWindow()
        @JavascriptInterface fun reload() { runOnUiThread { web.clearCache(false); web.loadUrl(DASHBOARD_URL) } }
        @JavascriptInterface fun openAndroidSettings() {
            runOnUiThread { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        @JavascriptInterface fun chooseHomeApp() {
            runOnUiThread { startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}
