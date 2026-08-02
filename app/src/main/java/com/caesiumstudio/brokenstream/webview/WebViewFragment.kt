package com.caesiumstudio.pinstream.webview

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.caesiumstudio.pinstream.MainFragment
import com.caesiumstudio.pinstream.R
import kotlinx.coroutines.launch

class WebViewFragment : Fragment() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var cursor: View
    private var siteUrl: String = ""

    private var cachedUserAgent: String = ""

    // Cursor position in pixels
    private var cursorX = 0f
    private var cursorY = 0f

    // How fast the cursor moves per D-pad repeat event (dp converted to px at runtime)
    private val CURSOR_STEP_DP = 8f

    // Speed multipliers read from settings on each move (so changes take effect immediately)
    private fun cursorSpeedMultiplier(): Float {
        val prefs =
            requireContext().getSharedPreferences(MainFragment.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getFloat(MainFragment.KEY_CURSOR_SPEED, 1.0f)
    }

    private fun scrollSpeedMultiplier(): Float {
        val prefs =
            requireContext().getSharedPreferences(MainFragment.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getFloat(MainFragment.KEY_SCROLL_SPEED, 1.0f)
    }

    // Auto-repeat while key held: fires every REPEAT_INTERVAL_MS after REPEAT_DELAY_MS
    private val REPEAT_DELAY_MS = 300L
    private val REPEAT_INTERVAL_MS = 50L  // ~20fps

    // Edge scroll: fires every EDGE_SCROLL_INTERVAL_MS while key held at edge
    private val EDGE_SCROLL_DELAY_MS = 500L   // wait before first scroll when edge is reached
    private val EDGE_SCROLL_INTERVAL_MS = 300L

    private var atTopEdge = false
    private var atBottomEdge = false

    private val handler = Handler(Looper.getMainLooper())
    private var activeDirection: Int = 0

    private val repeatRunnable = object : Runnable {
        override fun run() {
            if (activeDirection != 0) {
                moveCursor(activeDirection)
                handler.postDelayed(this, REPEAT_INTERVAL_MS)
            }
        }
    }

    private val edgeScrollRunnable = object : Runnable {
        override fun run() {
            when {
                activeDirection == KeyEvent.KEYCODE_DPAD_DOWN && atBottomEdge -> {
                    simulateScroll(scrollDown = true)
                    handler.postDelayed(this, EDGE_SCROLL_INTERVAL_MS)
                }

                activeDirection == KeyEvent.KEYCODE_DPAD_UP && atTopEdge -> {
                    simulateScroll(scrollDown = false)
                    handler.postDelayed(this, EDGE_SCROLL_INTERVAL_MS)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_webview, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        siteUrl = arguments?.getString(ARG_URL) ?: return
        webView = view.findViewById(R.id.web_view)
        progressBar = view.findViewById(R.id.progress_bar)
        cursor = view.findViewById(R.id.cursor)

        lifecycleScope.launch {
            AdBlocker.initialize(requireContext())
        }

        configureWebView()
        webView.loadUrl(siteUrl)

        // Position cursor at center on first layout
        view.post {
            cursorX = view.width / 2f
            cursorY = view.height / 2f
            updateCursorPosition()
        }
    }

    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = false
            @Suppress("DEPRECATION")
            mediaPlaybackRequiresUserGesture = false
            userAgentString =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        }

        cachedUserAgent = webView.settings.userAgentString
        webView.webViewClient = PinStreamWebViewClient()
        webView.webChromeClient = PinStreamWebChromeClient()

        // WebView must NOT steal focus — cursor view handles key events via Activity
        webView.isFocusable = false
        webView.isFocusableInTouchMode = false
    }

    // Called by WebViewActivity on every key event
    fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (activeDirection != keyCode) {
                    activeDirection = keyCode
                    handler.removeCallbacks(repeatRunnable)
                    handler.postDelayed(repeatRunnable, REPEAT_DELAY_MS)
                }
                moveCursor(keyCode)
                return true
            }

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                simulateClick()
                return true
            }
        }
        return false
    }

    fun onKeyUp(keyCode: Int): Boolean {
        if (keyCode == activeDirection) {
            activeDirection = 0
            handler.removeCallbacks(repeatRunnable)
            handler.removeCallbacks(edgeScrollRunnable)
            // Fire one scroll immediately on key release if at edge
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && atTopEdge) {
                simulateScroll(scrollDown = false)
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && atBottomEdge) {
                simulateScroll(scrollDown = true)
            }
            return true
        }
        return false
    }

    private fun moveCursor(keyCode: Int) {
        val step = CURSOR_STEP_DP * resources.displayMetrics.density * cursorSpeedMultiplier()
        val parent = view ?: return

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> cursorY -= step
            KeyEvent.KEYCODE_DPAD_DOWN -> cursorY += step
            KeyEvent.KEYCODE_DPAD_LEFT -> cursorX -= step
            KeyEvent.KEYCODE_DPAD_RIGHT -> cursorX += step
        }
        cursorX = cursorX.coerceIn(0f, parent.width.toFloat())
        cursorY = cursorY.coerceIn(0f, parent.height.toFloat())
        updateCursorPosition()

        val step2 = CURSOR_STEP_DP * resources.displayMetrics.density * cursorSpeedMultiplier()
        val wasAtTopEdge = atTopEdge
        val wasAtBottomEdge = atBottomEdge
        atTopEdge = cursorY <= step2
        atBottomEdge = cursorY >= parent.height - step2

        // Start edge scroll timer when cursor newly reaches an edge
        if (atBottomEdge && !wasAtBottomEdge && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            handler.removeCallbacks(edgeScrollRunnable)
            handler.postDelayed(edgeScrollRunnable, EDGE_SCROLL_DELAY_MS)
        } else if (atTopEdge && !wasAtTopEdge && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            handler.removeCallbacks(edgeScrollRunnable)
            handler.postDelayed(edgeScrollRunnable, EDGE_SCROLL_DELAY_MS)
        } else if (!atBottomEdge && !atTopEdge) {
            handler.removeCallbacks(edgeScrollRunnable)
        }
    }

    private fun updateCursorPosition() {
        cursor.x = cursorX - cursor.width / 2f
        cursor.y = cursorY - cursor.height / 2f
    }

    private fun simulateClick() {
        val downTime = android.os.SystemClock.uptimeMillis()
        val down =
            MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, cursorX, cursorY, 0)
        val up =
            MotionEvent.obtain(downTime, downTime + 50, MotionEvent.ACTION_UP, cursorX, cursorY, 0)
        webView.dispatchTouchEvent(down)
        webView.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()

        // After touch lands, check if an input/textarea received focus
        handler.postDelayed({
            webView.evaluateJavascript(
                "(document.activeElement && (document.activeElement.tagName === 'INPUT' || document.activeElement.tagName === 'TEXTAREA' || document.activeElement.isContentEditable)) ? 'true' : 'false'"
            ) { result ->
                if (result == "\"true\"") {
                    webView.isFocusable = true
                    webView.isFocusableInTouchMode = true
                    webView.requestFocus()
                    val imm =
                        requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                                as android.view.inputmethod.InputMethodManager
                    imm.showSoftInput(
                        webView,
                        android.view.inputmethod.InputMethodManager.SHOW_FORCED
                    )
                }
            }
        }, 500)
    }

    private fun simulateScroll(scrollDown: Boolean) {
        val density = resources.displayMetrics.density
        val scrollPx = (50f * density * scrollSpeedMultiplier()).toInt()
        val amount = if (scrollDown) scrollPx else -scrollPx
        webView.evaluateJavascript("window.scrollBy(0, $amount)", null)
    }

    fun canGoBack(): Boolean = webView.canGoBack()
    fun goBack() = webView.goBack()

    fun dismissKeyboardAndRestoreFocus() {
        webView.evaluateJavascript("document.activeElement.blur()", null)
    }

    private inner class PinStreamWebViewClient : WebViewClient() {

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            val url = request.url.toString()
            if (AdBlocker.shouldBlock(url)) {
                return AdBlocker.emptyResponse()
            }
            return null
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean = false

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            progressBar.visibility = View.VISIBLE
            // Ensure WebView never intercepts D-pad on new page loads
            webView.isFocusable = false
            webView.isFocusableInTouchMode = false
        }

        override fun onPageFinished(view: WebView, url: String) {
            progressBar.visibility = View.GONE
            injectAdBlockCss()
        }
    }

    private inner class PinStreamWebChromeClient : WebChromeClient() {

        private var customView: View? = null
        private var customViewCallback: CustomViewCallback? = null

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            customView = view
            customViewCallback = callback
            requireActivity().window.decorView.let { decor ->
                (decor as ViewGroup).addView(
                    view, ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }
            webView.visibility = View.GONE
            cursor.visibility = View.GONE
        }

        override fun onHideCustomView() {
            customView?.let { view ->
                (requireActivity().window.decorView as ViewGroup).removeView(view)
            }
            customView = null
            customViewCallback?.onCustomViewHidden()
            customViewCallback = null
            webView.visibility = View.VISIBLE
            cursor.visibility = View.VISIBLE
        }

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: android.os.Message?
        ): Boolean = false
    }

    private fun injectAdBlockCss() {
        val css = """
            (function() {
                if (document.getElementById('__bs_adblock_css')) return;
                var style = document.createElement('style');
                style.id = '__bs_adblock_css';
                style.innerHTML = `
                    [id*="ad-"],[class*="ad-"],[id*="-ad"],[class*="-ad"],
                    [id*="ads-"],[class*="ads-"],[id*="-ads"],[class*="-ads"],
                    .advertisement,.ad-banner,.banner-ad,.ad-container,
                    .ad-wrapper,.ad-slot,.ad-unit,.adsbygoogle,
                    ins.adsbygoogle,[id*="google_ads"],[id*="GoogleAd"],
                    .popup-overlay,.modal-overlay,.sticky-ad,.fixed-ad,
                    [class*="popup-"],[class*="overlay-ad"],
                    iframe[src*="doubleclick"],iframe[src*="googlesyndication"],
                    iframe[src*="adservice"] {
                        display: none !important;
                        visibility: hidden !important;
                        height: 0 !important;
                        max-height: 0 !important;
                    }
                `;
                (document.head || document.documentElement).appendChild(style);
            })();
        """.trimIndent()
        webView.evaluateJavascript(css, null)
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        handler.removeCallbacks(repeatRunnable)
        handler.removeCallbacks(edgeScrollRunnable)
        activeDirection = 0
        atTopEdge = false
        atBottomEdge = false
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroyView() {
        handler.removeCallbacks(repeatRunnable)
        handler.removeCallbacks(edgeScrollRunnable)
        webView.destroy()
        super.onDestroyView()
    }

    companion object {
        private const val TAG = "WebViewFragment"
        private const val ARG_URL = "arg_url"

        fun newInstance(url: String) = WebViewFragment().apply {
            arguments = Bundle().also { it.putString(ARG_URL, url) }
        }
    }
}
