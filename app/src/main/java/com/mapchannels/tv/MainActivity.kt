package com.mapchannels.tv

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.ByteArrayInputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * Keliling Dunia
 *
 * Halaman depan: pemandangan senja (digambar lewat kode, menyesuaikan portrait/landscape) + tombol "Mulai".
 * Di belakang layar, peta sudah dimuat dan halaman perantara (iklan + link "View Map") dilewati otomatis,
 * jadi setelah "Mulai" pengguna langsung melihat halaman kolom peta.
 *
 * Kontrol ganda:
 * - Mouse USB/Bluetooth: bekerja langsung (klik, geser, scroll roda untuk zoom).
 * - Remote TV: kursor virtual oranye (muncul saat tombol panah ditekan).
 *     Panah        = gerakkan kursor (makin lama ditahan makin cepat)
 *     OK (tekan)   = klik
 *     OK (tahan) + panah = geser peta (drag)
 *     CH+/CH-, Page Up/Down, FF/Rewind = zoom in/out
 *     Menu         = mode zoom (kursor jadi biru; panah atas/bawah = zoom). Tekan Menu lagi untuk keluar.
 * Iklan Google (AdSense) diblokir: permintaan ke server iklan dibatalkan + elemen iklan disembunyikan.
 */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var cursor: View
    private lateinit var splash: FrameLayout
    private lateinit var scenery: SceneryView
    private lateinit var titleView: TextView
    private lateinit var startBtn: TextView

    private val uiHandler = Handler(Looper.getMainLooper())

    private var cx = 0f
    private var cy = 0f
    private var maxX = 0f
    private var maxY = 0f
    private var size = 0
    private var baseStep = 0f
    private var dragging = false
    private var zoomMode = false
    private var downTime = 0L

    // 0 = menunggu halaman pertama, 1 = mencari link "View Map", 2 = menunggu halaman peta, 3 = selesai
    private var phase = 0
    private var tries = 0
    private var mapReady = false
    private var startRequested = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buttonBg(focused: Boolean) = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(0xFFFCAF45.toInt(), 0xFFE1306C.toInt(), 0xFF833AB4.toInt())
    ).apply {
        cornerRadius = dp(40).toFloat()
        if (focused) setStroke(dp(4), Color.WHITE)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val dm = resources.displayMetrics
        maxX = dm.widthPixels.toFloat()
        maxY = dm.heightPixels.toFloat()
        cx = maxX / 2f
        cy = maxY / 2f
        size = dp(26)
        baseStep = dp(6).toFloat()

        // ---- WebView (dimuat di belakang halaman depan) ----
        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val host = request?.url?.host ?: return null
                if (AD_HOSTS.any { host == it || host.endsWith(".$it") }) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript(HIDE_ADS_JS, null)
                when (phase) {
                    0 -> {
                        phase = 1
                        tries = 0
                        tryClickViewMap()
                    }
                    2 -> {
                        phase = 3
                        view?.clearHistory()
                        markReady()
                    }
                }
            }
        }
        web.webChromeClient = WebChromeClient()

        // ---- Kursor virtual ----
        cursor = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCCFF5722.toInt())
                setStroke(dp(2), Color.WHITE)
            }
            layoutParams = FrameLayout.LayoutParams(size, size)
            isClickable = false
            isFocusable = false
            visibility = View.GONE
        }

        // ---- Halaman depan ----
        scenery = SceneryView(this)

        titleView = TextView(this).apply {
            text = "Keliling Dunia"
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            letterSpacing = 0.03f
            setShadowLayer(dp(8).toFloat(), 0f, dp(2).toFloat(), 0xAA000000.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(40).toFloat())
        }

        startBtn = TextView(this).apply {
            text = "Mulai"
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), buttonBg(true))
                addState(intArrayOf(android.R.attr.state_pressed), buttonBg(true))
                addState(intArrayOf(), buttonBg(false))
            }
            setOnClickListener { onStartTapped() }
            setOnFocusChangeListener { v, has ->
                v.animate().scaleX(if (has) 1.1f else 1f).scaleY(if (has) 1.1f else 1f).setDuration(120).start()
            }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        content.addView(titleView, LinearLayout.LayoutParams(-2, -2))
        content.addView(startBtn, LinearLayout.LayoutParams(-2, -2))

        splash = FrameLayout(this).apply {
            isClickable = true
            addView(scenery, FrameLayout.LayoutParams(-1, -1))
            addView(content, FrameLayout.LayoutParams(-1, -1))
        }

        val root = FrameLayout(this)
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        root.addView(cursor)
        root.addView(splash, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        updateCursor()

        // Menyesuaikan ukuran saat layar diputar (portrait <-> landscape).
        root.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val w = (r - l).toFloat()
            val h = (b - t).toFloat()
            if (w > 0f && h > 0f) {
                maxX = w
                maxY = h
                cx = cx.coerceIn(0f, w - 1f)
                cy = cy.coerceIn(0f, h - 1f)
                updateCursor()
                val m = min(w, h)
                titleView.setTextSize(TypedValue.COMPLEX_UNIT_PX, m * 0.115f)
                titleView.setPadding(0, 0, 0, (m * 0.07f).toInt())
                startBtn.setTextSize(TypedValue.COMPLEX_UNIT_PX, m * 0.05f)
                startBtn.setPadding((m * 0.13f).toInt(), (m * 0.03f).toInt(), (m * 0.13f).toInt(), (m * 0.03f).toInt())
            }
        }

        startBtn.requestFocus()
        web.loadUrl(URL)
    }

    // ---- Alur halaman depan -> peta ----

    /** Cari link "View Map" di halaman perantara lalu buka langsung. */
    private fun tryClickViewMap() {
        web.evaluateJavascript(CLICK_VIEW_MAP_JS) { r ->
            when {
                r != null && r.contains("clicked") -> {
                    phase = 2
                    // Cadangan: kalau halaman peta lambat/selesai tanpa sinyal, tetap tampilkan setelah 8 detik.
                    uiHandler.postDelayed({ markReady() }, 8000)
                }
                ++tries < 40 -> uiHandler.postDelayed({ tryClickViewMap() }, 250)
                else -> markReady()
            }
        }
    }

    private fun markReady() {
        if (mapReady) return
        mapReady = true
        if (startRequested) hideSplash()
    }

    private fun onStartTapped() {
        if (startRequested) return
        startRequested = true
        if (mapReady) {
            hideSplash()
        } else {
            startBtn.text = "Memuat peta…"
            startBtn.alpha = 0.75f
        }
    }

    private fun hideSplash() {
        scenery.stop()
        splash.animate().alpha(0f).setDuration(500).withEndAction {
            splash.visibility = View.GONE
        }.start()
        web.requestFocus()
    }

    override fun onPause() {
        super.onPause()
        web.onPause()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        scenery.stop()
        super.onDestroy()
    }

    // ---- Kursor & input ----

    private fun updateCursor() {
        cursor.x = cx - size / 2f
        cursor.y = cy - size / 2f
    }

    private fun showCursor() {
        if (cursor.visibility != View.VISIBLE) cursor.visibility = View.VISIBLE
    }

    private fun sendTouch(action: Int) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val e = MotionEvent.obtain(downTime, now, action, cx, cy, 0)
        web.dispatchTouchEvent(e)
        e.recycle()
    }

    /** Putar roda mouse virtual: positif = zoom in, negatif = zoom out. */
    private fun sendWheel(amount: Float) {
        val now = SystemClock.uptimeMillis()
        val props = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        }
        val coords = MotionEvent.PointerCoords().apply {
            x = cx
            y = cy
            setAxisValue(MotionEvent.AXIS_VSCROLL, amount)
        }
        val e = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_SCROLL, 1,
            arrayOf(props), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
        )
        web.dispatchGenericMotionEvent(e)
        e.recycle()
    }

    private fun setZoomMode(on: Boolean) {
        zoomMode = on
        (cursor.background as GradientDrawable).setColor(if (on) 0xCC2196F3.toInt() else 0xCCFF5722.toInt())
    }

    // Mouse asli dipakai -> sembunyikan kursor virtual.
    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.isFromSource(InputDevice.SOURCE_MOUSE)) cursor.visibility = View.GONE
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.isFromSource(InputDevice.SOURCE_MOUSE) || ev.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) {
            cursor.visibility = View.GONE
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        // Saat halaman depan tampil, tombol remote dipakai untuk tombol "Mulai".
        if (splash.visibility == View.VISIBLE) return super.dispatchKeyEvent(e)

        val down = e.action == KeyEvent.ACTION_DOWN
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!down) return true
                showCursor()
                val vertical = e.keyCode == KeyEvent.KEYCODE_DPAD_UP || e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                if (zoomMode && vertical) {
                    if (e.repeatCount % 3 == 0) sendWheel(if (e.keyCode == KeyEvent.KEYCODE_DPAD_UP) 1f else -1f)
                    return true
                }
                val step = baseStep * (1f + min(e.repeatCount, 24) / 3f)
                when (e.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> cy -= step
                    KeyEvent.KEYCODE_DPAD_DOWN -> cy += step
                    KeyEvent.KEYCODE_DPAD_LEFT -> cx -= step
                    else -> cx += step
                }
                cx = cx.coerceIn(0f, maxX - 1f)
                cy = cy.coerceIn(0f, maxY - 1f)
                updateCursor()
                if (dragging) sendTouch(MotionEvent.ACTION_MOVE)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (cursor.visibility != View.VISIBLE && !dragging) {
                    if (down) showCursor()
                    return true
                }
                if (down && e.repeatCount == 0) {
                    sendTouch(MotionEvent.ACTION_DOWN)
                    dragging = true
                } else if (!down && dragging) {
                    sendTouch(MotionEvent.ACTION_UP)
                    dragging = false
                }
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (down && e.repeatCount % 3 == 0) sendWheel(1f)
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (down && e.repeatCount % 3 == 0) sendWheel(-1f)
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                if (down && e.repeatCount == 0) {
                    showCursor()
                    setZoomMode(!zoomMode)
                }
                return true
            }
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (splash.visibility != View.VISIBLE && keyCode == KeyEvent.KEYCODE_BACK && web.canGoBack()) {
            web.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        const val URL = "https://www.mapchannels.com/routemaps3/map.htm"

        // Server iklan yang diblokir (Google Maps tidak ikut terblokir).
        val AD_HOSTS = listOf(
            "googlesyndication.com",
            "doubleclick.net",
            "googleadservices.com",
            "googletagservices.com",
            "adservice.google.com",
            "adtrafficquality.google",
            "2mdn.net"
        )

        // Sembunyikan/hapus elemen iklan, termasuk yang muncul belakangan.
        val HIDE_ADS_JS = """
            (function() {
              if (window.__noAds) return;
              window.__noAds = true;
              var sel = 'ins.adsbygoogle,.adsbygoogle,iframe[id^="google_ads"],div[id^="google_ads"],' +
                        '[id^="aswift"],iframe[src*="googlesyndication"],iframe[src*="doubleclick"],' +
                        '[id^="div-gpt-ad"],.google-auto-placed,[data-ad-status],[data-anchor-status],' +
                        '[data-vignette-loaded]';
              var st = document.createElement('style');
              st.textContent = sel + '{display:none!important;visibility:hidden!important}';
              (document.head || document.documentElement).appendChild(st);
              function sweep() {
                var list = document.querySelectorAll(sel);
                for (var i = 0; i < list.length; i++) { list[i].remove(); }
              }
              sweep();
              new MutationObserver(sweep).observe(document.documentElement, {childList: true, subtree: true});
            })();
        """.trimIndent()

        // Cari link "View Map" di halaman perantara, lalu buka.
        val CLICK_VIEW_MAP_JS = """
            (function() {
              var links = document.querySelectorAll('a');
              for (var i = 0; i < links.length; i++) {
                if (/view\s*map/i.test(links[i].textContent || '')) {
                  var h = links[i].href || '';
                  if (h.indexOf('http') === 0 && h.split('#')[0] !== location.href.split('#')[0]) {
                    location.href = h;
                  } else {
                    links[i].click();
                  }
                  return 'clicked';
                }
              }
              return 'none';
            })();
        """.trimIndent()
    }
}

/** Pemandangan senja yang digambar lewat kode: menyesuaikan ukuran layar, dengan awan dan pesawat bergerak pelan. */
class SceneryView(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private var t = 0f

    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 60000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            t = it.animatedValue as Float
            invalidate()
        }
    }

    fun start() {
        if (!anim.isRunning) anim.start()
    }

    fun stop() {
        anim.cancel()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }

    private fun drawCloud(c: Canvas, x: Float, y: Float, s: Float) {
        rect.set(x - s, y - s * 0.28f, x + s, y + s * 0.28f)
        c.drawOval(rect, p)
        rect.set(x - s * 0.6f, y - s * 0.5f, x + s * 0.3f, y + s * 0.1f)
        c.drawOval(rect, p)
        rect.set(x - s * 0.1f, y - s * 0.42f, x + s * 0.7f, y + s * 0.12f)
        c.drawOval(rect, p)
    }

    private fun ridge(
        c: Canvas, w: Float, h: Float, base: Float, amp: Float,
        f1: Float, p1: Float, f2: Float, p2: Float, color: Int
    ) {
        val pi = PI.toFloat()
        path.reset()
        path.moveTo(0f, h)
        val step = w / 60f
        var x = 0f
        while (x <= w + step) {
            val u = x / w
            val n = 0.65f * (1f - abs(sin(pi * (u * f1 + p1)))) +
                    0.35f * (1f - abs(sin(pi * (u * f2 + p2))))
            path.lineTo(x, base - amp * n)
            x += step
        }
        path.lineTo(w + step, h)
        path.close()
        p.color = color
        c.drawPath(path, p)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val m = min(w, h)
        val horizon = h * 0.62f
        val pi = PI.toFloat()
        val twoPi = (2.0 * PI).toFloat()

        // Langit senja
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(
            0f, 0f, 0f, horizon,
            intArrayOf(0xFF141E5A.toInt(), 0xFF5B3A9E.toInt(), 0xFFFF6F61.toInt(), 0xFFFFC371.toInt()),
            floatArrayOf(0f, 0.4f, 0.78f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null

        // Bintang berkedip
        p.color = Color.WHITE
        for (i in 0 until 45) {
            val sxp = ((i * 97) % 101) / 101f * w
            val syp = ((i * 53) % 67) / 67f * horizon * 0.55f
            val tw = 0.5f + 0.5f * sin(twoPi * (t * 12f + i * 0.13f))
            p.alpha = (60 + 150 * tw).toInt()
            c.drawCircle(sxp, syp, m * 0.0025f + (i % 3) * m * 0.0012f, p)
        }
        p.alpha = 255

        // Matahari terbenam
        val sx = w * 0.68f
        val sy = horizon - m * 0.02f
        val sr = m * 0.13f
        p.shader = RadialGradient(
            sx, sy, sr * 3f,
            intArrayOf(0xFFFFF4C9.toInt(), 0x88FFC371.toInt(), 0x00FF6F61),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP
        )
        c.drawCircle(sx, sy, sr * 3f, p)
        p.shader = null
        p.color = 0xFFFFF6DA.toInt()
        c.drawCircle(sx, sy, sr, p)

        // Awan bergerak pelan
        for (i in 0 until 5) {
            val k = (1 + i % 2).toFloat()
            val prog = (i * 0.21f + t * k) % 1f
            val ccx = -0.2f * w + prog * 1.4f * w
            val ccy = h * (0.10f + 0.07f * i)
            val s = m * (0.09f + 0.02f * (i % 3))
            p.color = if (i % 2 == 0) 0x55FFFFFF.toInt() else 0x44FFD6E0.toInt()
            drawCloud(c, ccx, ccy, s)
        }

        // Pegunungan berlapis
        ridge(c, w, h, horizon, m * 0.26f, 1.5f, 0.2f, 3.3f, 0.7f, 0xFF5B4B9A.toInt())
        ridge(c, w, h, horizon + m * 0.03f, m * 0.20f, 2.2f, 0.6f, 4.7f, 0.1f, 0xFF3B3577.toInt())
        ridge(c, w, h, horizon + m * 0.08f, m * 0.14f, 3.0f, 0.35f, 6.5f, 0.9f, 0xFF1F2250.toInt())

        // Laut
        val shore = horizon + m * 0.10f
        p.shader = LinearGradient(0f, shore, 0f, h, 0xFF2F7DA6.toInt(), 0xFF0B2545.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, shore, w, h, p)
        p.shader = null

        // Pantulan matahari di laut
        for (i in 0 until 9) {
            val yy = shore + (h - shore) * (i / 9f)
            val ww = sr * (1.6f - i * 0.12f) * (0.85f + 0.15f * sin(twoPi * (t * 20f + i * 0.2f)))
            p.color = 0x55FFD08A.toInt()
            rect.set(sx - ww, yy, sx + ww, yy + (h - shore) * 0.045f)
            c.drawRoundRect(rect, 8f, 8f, p)
        }

        // Pesawat terbang melintas
        val prog = (t * 3f) % 1f
        val px = -0.12f * w + prog * 1.24f * w
        val py = h * 0.2f - sin(pi * prog) * h * 0.05f
        val s = m * 0.035f
        p.style = Paint.Style.STROKE
        p.strokeWidth = m * 0.004f
        p.shader = LinearGradient(px - s * 14f, py, px, py, 0x00FFFFFF, 0x99FFFFFF.toInt(), Shader.TileMode.CLAMP)
        c.drawLine(px - s * 14f, py + s * 0.15f, px - s, py + s * 0.15f, p)
        p.shader = null
        p.style = Paint.Style.FILL
        c.save()
        c.translate(px, py)
        c.rotate(-6f)
        p.color = 0xF2FFFFFF.toInt()
        rect.set(-s, -s * 0.2f, s, s * 0.2f)
        c.drawOval(rect, p)
        path.reset()
        path.moveTo(-s * 0.15f, 0f)
        path.lineTo(-s * 0.6f, s * 0.8f)
        path.lineTo(-s * 0.3f, s * 0.8f)
        path.lineTo(s * 0.3f, 0f)
        path.close()
        c.drawPath(path, p)
        path.reset()
        path.moveTo(-s * 0.8f, -s * 0.1f)
        path.lineTo(-s * 1.1f, -s * 0.6f)
        path.lineTo(-s * 0.7f, -s * 0.6f)
        path.lineTo(-s * 0.4f, -s * 0.1f)
        path.close()
        c.drawPath(path, p)
        c.restore()

        // Lapisan gelap tipis agar tulisan mudah dibaca
        p.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(0x55000000, 0x33000000, 0x44000000, 0x88000000.toInt()),
            floatArrayOf(0f, 0.3f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null
    }
}
