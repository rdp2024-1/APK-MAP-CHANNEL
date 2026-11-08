package com.mapchannels.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlin.math.min

/**
 * Kontrol ganda:
 * - Mouse USB/Bluetooth: bekerja langsung (klik, geser, scroll roda untuk zoom).
 * - Remote TV: kursor virtual oranye.
 *     Panah        = gerakkan kursor (makin lama ditahan makin cepat)
 *     OK (tekan)   = klik
 *     OK (tahan) + panah = geser peta (drag)
 *     CH+/CH-, Page Up/Down, FF/Rewind = zoom in/out
 *     Menu         = mode zoom (kursor jadi biru; panah atas/bawah = zoom). Tekan Menu lagi untuk keluar.
 * Kursor virtual otomatis disembunyikan saat mouse asli dipakai.
 */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var cursor: View
    private var cx = 0f
    private var cy = 0f
    private var maxX = 0f
    private var maxY = 0f
    private var size = 0
    private var baseStep = 0f
    private var dragging = false
    private var zoomMode = false
    private var downTime = 0L

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

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

        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        web.webViewClient = WebViewClient()
        web.webChromeClient = WebChromeClient()

        cursor = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCCFF5722.toInt())
                setStroke(dp(2), Color.WHITE)
            }
            layoutParams = FrameLayout.LayoutParams(size, size)
            isClickable = false
            isFocusable = false
        }

        val root = FrameLayout(this)
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        root.addView(cursor)
        setContentView(root)
        updateCursor()

        if (savedInstanceState == null) web.loadUrl(URL) else web.restoreState(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

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
        if (ev.isFromSource(InputDevice.SOURCE_MOUSE)) cursor.visibility = View.GONE
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
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
                if (down && e.repeatCount == 0) {
                    showCursor()
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
        if (keyCode == KeyEvent.KEYCODE_BACK && web.canGoBack()) {
            web.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        const val URL = "https://www.mapchannels.com/routemaps3/map.htm"
    }
}
