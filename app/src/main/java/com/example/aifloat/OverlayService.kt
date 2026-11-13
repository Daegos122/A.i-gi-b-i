package com.example.aifloat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.abs

private const val MODEL = "claude-sonnet-5-5"
private const val SYSTEM_PROMPT =
    "Bạn là gia sư. Người dùng gửi ảnh chụp màn hình một bài tập. " +
    "Hãy giải bài bằng tiếng Việt, từng bước ngắn gọn, rồi nêu đáp án cuối cùng. " +
    "Nếu có nhiều câu, giải từng câu. Chỉ dùng văn bản thuần, KHÔNG dùng markdown " +
    "hay LaTeX (viết phân số như 3/4, mũ như x^2)."

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var bubble: TextView
    private var panel: View? = null
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private val ui = Handler(Looper.getMainLooper())
    private var sw = 0
    private var sh = 0

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        if (projection != null) return START_NOT_STICKY

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("ai", "AI Float", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "ai")
            .setContentTitle("AI Float đang chạy")
            .setContentText("Chạm bong bóng AI để giải bài")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(1, n)

        val code = intent.getIntExtra("code", 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra("data", Intent::class.java)
        else intent.getParcelableExtra("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(code, data)
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, ui)

        val m = resources.displayMetrics
        sw = m.widthPixels
        sh = m.heightPixels
        val r = ImageReader.newInstance(sw, sh, PixelFormat.RGBA_8888, 2)
        reader = r
        display = p.createVirtualDisplay(
            "aifloat", sw, sh, m.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null
        )

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        addBubble()
        return START_NOT_STICKY
    }

    private fun addBubble() {
        bubble = TextView(this).apply {
            text = "AI"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            alpha = 0.9f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF2962FF.toInt())
            }
        }
        val lp = WindowManager.LayoutParams(
            dp(56), dp(56),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8); y = dp(200)
        }

        var startX = 0; var startY = 0
        var touchX = 0f; var touchY = 0f
        var moved = false
        bubble.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y
                    touchX = e.rawX; touchY = e.rawY
                    moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt()
                    val dy = (e.rawY - touchY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    lp.x = startX + dx; lp.y = startY + dy
                    wm.updateViewLayout(v, lp); true
                }
                MotionEvent.ACTION_UP -> { if (!moved) onTap(); true }
                else -> false
            }
        }
        wm.addView(bubble, lp)
    }

    private fun onTap() {
        closePanel()
        bubble.visibility = View.INVISIBLE          // ẩn nút để không lọt vào ảnh chụp
        ui.postDelayed({
            val b64 = grab()
            bubble.visibility = View.VISIBLE
            if (b64 == null) {
                showPanel("Không chụp được màn hình, hãy thử lại.")
                return@postDelayed
            }
            showPanel("Đang giải...")
            thread {
                val result = try { ask(b64) } catch (e: Exception) { "Lỗi: ${e.message}" }
                ui.post { showPanel(result) }
            }
        }, 350)
    }

    private fun grab(): String? {
        val img = reader?.acquireLatestImage() ?: return null
        try {
            val plane = img.planes[0]
            val rowPad = plane.rowStride - plane.pixelStride * img.width
            val full = Bitmap.createBitmap(
                img.width + rowPad / plane.pixelStride, img.height, Bitmap.Config.ARGB_8888
            )
            full.copyPixelsFromBuffer(plane.buffer)
            val cropped = Bitmap.createBitmap(full, 0, 0, img.width, img.height)
            val scale = 1400f / maxOf(cropped.width, cropped.height)
            val out = if (scale < 1f)
                Bitmap.createScaledBitmap(cropped, (cropped.width * scale).toInt(),
                    (cropped.height * scale).toInt(), true)
            else cropped
            val bos = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, 80, bos)
            return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        } finally {
            img.close()
        }
    }

    private fun ask(b64: String): String {
        val key = getSharedPreferences("cfg", 0).getString("key", "") ?: ""
        val content = JSONArray()
            .put(JSONObject().put("type", "image").put("source", JSONObject()
                .put("type", "base64").put("media_type", "image/jpeg").put("data", b64)))
            .put(JSONObject().put("type", "text")
                .put("text", "Giải bài tập trong ảnh chụp màn hình này."))
        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 1500)
            .put("system", SYSTEM_PROMPT)
            .put("messages", JSONArray().put(
                JSONObject().put("role", "user").put("content", content)))

        val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15000
        conn.readTimeout = 90000
        conn.doOutput = true
        conn.setRequestProperty("content-type", "application/json")
        conn.setRequestProperty("x-api-key", key)
        conn.setRequestProperty("anthropic-version", "2023-06-01")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }

        val ok = conn.responseCode in 200..299
        val text = (if (ok) conn.inputStream else conn.errorStream).bufferedReader().readText()
        if (!ok) return "Lỗi ${conn.responseCode}: $text"
        val arr = JSONObject(text).getJSONArray("content")
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val b = arr.getJSONObject(i)
            if (b.optString("type") == "text") sb.append(b.getString("text"))
        }
        return sb.toString().trim()
    }

    private fun showPanel(message: String) {
        closePanel()
        val tv = TextView(this).apply {
            text = message
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF2202124.toInt())
            addView(ScrollView(this@OverlayService).apply { addView(tv) },
                LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(Button(this@OverlayService).apply {
                text = "Đóng"
                setOnClickListener { closePanel() }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        val lp = WindowManager.LayoutParams(
            MATCH_PARENT, (sh * 0.55).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }
        wm.addView(root, lp)
        panel = root
    }

    private fun closePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
    }

    override fun onDestroy() {
        closePanel()
        if (::bubble.isInitialized) runCatching { wm.removeView(bubble) }
        display?.release()
        reader?.close()
        projection?.stop()
        super.onDestroy()
    }
}
