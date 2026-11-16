package com.example.tkrec

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/* ------------------------------------------------------------------ */
/*  TikTok lookup: username -> live stream URL                         */
/*  NOTE: these are TikTok's unofficial web endpoints. They can change */
/*  or be blocked at any time; if recording stops working, this is     */
/*  the only part that needs updating.                                 */
/* ------------------------------------------------------------------ */

class NotLiveException : Exception("not live")

data class LiveStream(val quality: String, val url: String, val hevc: Boolean)

object TikTok {
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    val api: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // The live stream sends data continuously; 60 s of silence means it ended.
    val streamClient: OkHttpClient = api.newBuilder()
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun request(url: String): Request = Request.Builder()
        .url(url)
        .header("User-Agent", UA)
        .header("Referer", "https://www.tiktok.com/")
        .header("Accept", "*/*")
        .build()

    /** Accepts "name", "@name" or a link like https://www.tiktok.com/@name/live */
    fun parseUser(input: String): String {
        val fromLink = Regex("@([A-Za-z0-9._]+)").find(input)?.groupValues?.get(1)
        return (fromLink ?: input.trim().removePrefix("@")).trim().lowercase(Locale.ROOT)
    }

    private fun getJson(url: String): JSONObject {
        val body = api.newCall(request(url)).execute().use { r ->
            if (!r.isSuccessful) throw IOException("TikTok answered HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
        return try {
            JSONObject(body)
        } catch (e: JSONException) {
            throw IOException("TikTok blocked the request (non-JSON reply). Try another network or region.")
        }
    }

    fun findStream(userInput: String, preferHevc: Boolean): LiveStream {
        val user = URLEncoder.encode(parseUser(userInput), "UTF-8")
        val root = getJson("https://www.tiktok.com/api-live/user/room/?aid=1988&sourceType=54&uniqueId=$user")
        val data = root.optJSONObject("data") ?: throw IOException("User not found, or TikTok blocked the request")
        val userObj = data.optJSONObject("user")
        val roomObj = data.optJSONObject("liveRoom")
        if (userObj == null && roomObj == null) throw IOException("User not found, or TikTok blocked the request")

        // status 2 = live, 4 = ended (TikTok keeps stale stream URLs for ended rooms)
        val live = userObj?.optInt("status") == 2 || roomObj?.optInt("status") == 2
        if (!live) throw NotLiveException()

        var streams = extract(data)
        if (streams.isEmpty()) {
            val roomId = userObj?.optString("roomId").orEmpty()
            if (roomId.isNotEmpty() && roomId != "0") {
                streams = extract(getJson("https://webcast.tiktok.com/webcast/room/info/?aid=1988&room_id=$roomId"))
            }
        }
        return pick(streams, preferHevc)
            ?: throw IOException("Live, but no stream link was returned (may be region or login restricted)")
    }

    // Finds every "stream_data" JSON string in the response, whatever the nesting.
    private fun walk(node: Any?, path: String, out: MutableList<Pair<String, String>>) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k == "push_data") continue
                    val v = node.opt(k)
                    if (k == "stream_data" && v is String) {
                        if (v.isNotBlank()) out.add(path to v)
                    } else {
                        walk(v, "$path/$k", out)
                    }
                }
            }
            is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), path, out)
        }
    }

    private fun extract(root: JSONObject): List<LiveStream> {
        val found = mutableListOf<Pair<String, String>>()
        walk(root, "", found)
        val out = mutableListOf<LiveStream>()
        for ((path, raw) in found) {
            val parsed = try {
                JSONObject(raw).optJSONObject("data")
            } catch (e: JSONException) {
                null
            }
            if (parsed == null) continue
            val hevc = path.contains("hevc", ignoreCase = true)
            val qualities = parsed.keys()
            while (qualities.hasNext()) {
                val q = qualities.next()
                val flv = parsed.optJSONObject(q)?.optJSONObject("main")?.optString("flv").orEmpty()
                if (flv.isNotBlank()) out.add(LiveStream(q, flv.replaceFirst("http://", "https://"), hevc))
            }
        }
        return out
    }

    private val ladder = listOf("origin", "uhd_60", "hd_60", "hd", "sd", "ld")

    private fun pick(all: List<LiveStream>, preferHevc: Boolean): LiveStream? {
        val order = if (preferHevc) listOf(true, false) else listOf(false, true)
        for (hevc in order) {
            val group = all.filter { it.hevc == hevc }
            for (q in ladder) {
                val hit = group.firstOrNull { it.quality == q }
                if (hit != null) return hit
            }
        }
        return all.firstOrNull { it.quality == "ao" } // audio-only, last resort
    }
}

/* ------------------------------------------------------------------ */
/*  Background recorder (foreground service so it survives screen-off) */
/* ------------------------------------------------------------------ */

class RecorderService : Service() {

    companion object {
        const val ACTION_START = "com.example.tkrec.START"
        const val ACTION_STOP = "com.example.tkrec.STOP"
        private const val CHANNEL = "recording"
        private const val NOTIF_ID = 1

        @Volatile var active = false
        @Volatile var status = "Enter a username and tap Start."
    }

    @Volatile private var stopped = false
    @Volatile private var call: Call? = null
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIF_ID, notification("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                if (!active) {
                    active = true
                    stopped = false
                    status = "Starting…"
                    val user = intent?.getStringExtra("user").orEmpty()
                    val wait = intent?.getBooleanExtra("wait", false) ?: false
                    val hevc = intent?.getBooleanExtra("hevc", false) ?: false
                    worker = Thread { work(user, wait, hevc) }.also { it.start() }
                }
            }
            ACTION_STOP -> if (active) halt() else stopSelf()
            else -> if (!active) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        halt()
        super.onDestroy()
    }

    private fun halt() {
        stopped = true
        call?.cancel()
        worker?.interrupt()
    }

    private fun post(text: String) {
        status = text
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text.lineSequence().first()))
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Live Recorder")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop).build()
            )
            .build()
    }

    private fun work(user: String, wait: Boolean, hevc: Boolean) {
        var lastSaved: String? = null
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tkrec:recording").apply {
            acquire(12 * 60 * 60 * 1000L)
        }
        try {
            while (!stopped) {
                var delayMs = 30_000L
                var note = ""
                try {
                    post("Checking @$user …")
                    val stream = TikTok.findStream(user, hevc)
                    lastSaved = record(user, stream)
                    if (stopped) break
                    note = "Saved $lastSaved. Checking if still live…"
                    delayMs = 3_000L
                } catch (e: NotLiveException) {
                    if (!wait) {
                        post(if (lastSaved != null) "Finished. Saved $lastSaved" else "@$user is not live right now.")
                        break
                    }
                    note = if (lastSaved != null) "Saved $lastSaved. Waiting for the next live…"
                    else "@$user is offline. Checking again every 30 s…"
                } catch (e: Exception) {
                    if (stopped) break
                    if (!wait) {
                        post(if (lastSaved != null) "Finished. Saved $lastSaved" else "Couldn't record: ${e.message}")
                        break
                    }
                    note = "Couldn't record: ${e.message}. Retrying in 30 s…"
                }
                post(note)
                try {
                    Thread.sleep(delayMs)
                } catch (e: InterruptedException) {
                    break
                }
            }
        } finally {
            wakeLock?.let { if (it.isHeld) it.release() }
            if (stopped) status = if (lastSaved != null) "Stopped. Saved $lastSaved" else "Stopped."
            active = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** Copies the live FLV stream into Downloads/TikTokLive until it ends or is stopped. */
    private fun record(user: String, s: LiveStream): String {
        val name = "${user}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.flv"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "video/x-flv")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/TikTokLive")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create the output file")

        var bytes = 0L
        var ioError: IOException? = null
        val t0 = System.currentTimeMillis()
        var lastUi = 0L
        var lastNotif = 0L
        try {
            val c = TikTok.streamClient.newCall(TikTok.request(s.url))
            call = c
            c.execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Stream answered HTTP ${resp.code}")
                val src = resp.body?.byteStream() ?: throw IOException("Empty stream")
                val out = contentResolver.openOutputStream(uri) ?: throw IOException("Cannot write the output file")
                out.use { sink ->
                    val buf = ByteArray(64 * 1024)
                    while (!stopped) {
                        val n = src.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        bytes += n
                        val now = System.currentTimeMillis()
                        if (now - lastUi >= 1000) {
                            lastUi = now
                            val codec = if (s.hevc) ", HEVC" else ""
                            status = "● Recording @$user (${s.quality}$codec)\n" +
                                "${fmtTime(now - t0)}  ·  ${fmtMb(bytes)}"
                            if (now - lastNotif >= 5000) {
                                lastNotif = now
                                getSystemService(NotificationManager::class.java).notify(
                                    NOTIF_ID, notification("Recording @$user  ${fmtTime(now - t0)}  ${fmtMb(bytes)}")
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: IOException) {
            ioError = e // a dropped connection or Stop; keep whatever was saved
        } finally {
            call = null
        }

        if (bytes == 0L) {
            contentResolver.delete(uri, null, null)
            throw ioError ?: IOException("Nothing was recorded")
        }
        return "$name (${fmtMb(bytes)})"
    }

    private fun fmtTime(ms: Long): String {
        val s = ms / 1000
        return "%02d:%02d:%02d".format(Locale.US, s / 3600, (s % 3600) / 60, s % 60)
    }

    private fun fmtMb(b: Long): String = "%.1f MB".format(Locale.US, b / 1048576.0)
}

/* ------------------------------------------------------------------ */
/*  Screen                                                             */
/* ------------------------------------------------------------------ */

class MainActivity : Activity() {

    private lateinit var userInput: EditText
    private lateinit var waitBox: CheckBox
    private lateinit var hevcBox: CheckBox
    private lateinit var toggle: Button
    private lateinit var statusView: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val red = Color.parseColor("#FE2C55")
    private val grey = Color.parseColor("#555B66")

    private val tick = object : Runnable {
        override fun run() {
            statusView.text = RecorderService.status
            val running = RecorderService.active
            toggle.text = if (running) "Stop recording" else "Start recording"
            paint(toggle, if (running) grey else red)
            handler.postDelayed(this, 1000)
        }
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun paint(b: Button, color: Int) {
        b.background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(color)
        }
        b.setTextColor(Color.WHITE)
    }

    private fun LinearLayout.add(v: View, top: Int = 12) {
        addView(
            v,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(top) }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("tkrec", MODE_PRIVATE)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val title = TextView(this).apply {
            text = "Live Recorder"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        }
        val sub = TextView(this).apply {
            text = "Record a TikTok live by username. Files go to Downloads/TikTokLive."
            textSize = 14f
            setTextColor(Color.parseColor("#B0B6C0"))
        }
        userInput = EditText(this).apply {
            hint = "Username or live link"
            setSingleLine()
            setText(prefs.getString("user", ""))
        }
        waitBox = CheckBox(this).apply {
            text = "Wait until they go live (checks every 30 s)"
            isChecked = true
        }
        hevcBox = CheckBox(this).apply {
            text = "Highest quality (HEVC, needs VLC to play)"
        }
        toggle = Button(this).apply { isAllCaps = false }
        statusView = TextView(this).apply {
            textSize = 16f
            text = RecorderService.status
        }
        val openBtn = Button(this).apply {
            text = "Open Downloads"
            isAllCaps = false
            setOnClickListener { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) }
        }
        paint(openBtn, grey)

        toggle.setOnClickListener {
            if (RecorderService.active) {
                startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
            } else {
                val user = TikTok.parseUser(userInput.text.toString())
                if (user.isBlank()) {
                    userInput.error = "Enter a TikTok username"
                    return@setOnClickListener
                }
                prefs.edit().putString("user", user).apply()
                RecorderService.status = "Starting…"
                startForegroundService(
                    Intent(this, RecorderService::class.java)
                        .setAction(RecorderService.ACTION_START)
                        .putExtra("user", user)
                        .putExtra("wait", waitBox.isChecked)
                        .putExtra("hevc", hevcBox.isChecked)
                )
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            add(title, 0)
            add(sub, 4)
            add(userInput, 24)
            add(waitBox, 4)
            add(hevcBox, 0)
            add(toggle, 16)
            add(statusView, 24)
            add(openBtn, 24)
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }
}
