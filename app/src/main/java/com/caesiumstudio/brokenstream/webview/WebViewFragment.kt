package com.caesiumstudio.bitstream.webview

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
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.caesiumstudio.bitstream.MainFragment
import com.caesiumstudio.bitstream.R
import kotlinx.coroutines.launch

class WebViewFragment : Fragment() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var cursor: View
    private var siteUrl: String = ""

    private var cachedUserAgent: String = ""

    // Cached prefs values — read once in onViewCreated, not on every D-pad event
    private var cursorSpeedPref: Float = 1.0f
    private var scrollSpeedPref: Float = 1.0f

    // Cursor position in pixels
    private var cursorX = 0f
    private var cursorY = 0f

    // How fast the cursor moves per D-pad repeat event (dp converted to px at runtime)
    private val CURSOR_STEP_DP = 8f

    // Speed multipliers read from settings on each move (so changes take effect immediately)
    private fun cursorSpeedMultiplier(): Float = cursorSpeedPref

    private fun scrollSpeedMultiplier(): Float = scrollSpeedPref

    // Auto-repeat while key held: fires every REPEAT_INTERVAL_MS after REPEAT_DELAY_MS
    private val REPEAT_DELAY_MS = 300L
    private val REPEAT_INTERVAL_MS = 50L  // ~20fps

    // Edge scroll: fires every EDGE_SCROLL_INTERVAL_MS while key held at edge
    private val EDGE_SCROLL_DELAY_MS = 500L   // wait before first scroll when edge is reached
    private val EDGE_SCROLL_INTERVAL_MS = 300L

    private var atTopEdge = false
    private var atBottomEdge = false

    // Acceleration: track when the current direction was first pressed
    private var pressStartTime: Long = 0L
    private val MAX_ACCEL = 4f   // top speed is 4× base at full acceleration

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

        // Cache prefs once here instead of reading on every D-pad event
        val prefs = requireContext().getSharedPreferences(MainFragment.PREFS_NAME, Context.MODE_PRIVATE)
        cursorSpeedPref = prefs.getFloat(MainFragment.KEY_CURSOR_SPEED, 1.0f)
        scrollSpeedPref = prefs.getFloat(MainFragment.KEY_SCROLL_SPEED, 1.0f)

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
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportZoom(false)
            builtInZoomControls = false
            setGeolocationEnabled(false)
            allowFileAccess = false
            @Suppress("DEPRECATION")
            mediaPlaybackRequiresUserGesture = false
            userAgentString =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        }

        cachedUserAgent = webView.settings.userAgentString
        webView.webViewClient = BitStreamWebViewClient()
        webView.webChromeClient = BitStreamWebChromeClient()

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
                    pressStartTime = System.currentTimeMillis()
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
        val density = resources.displayMetrics.density
        val elapsed = System.currentTimeMillis() - pressStartTime
        val accel = (elapsed / 1000f).coerceIn(0f, 1f)
        val multiplier = 1f + accel * (MAX_ACCEL - 1f)
        val step = CURSOR_STEP_DP * density * cursorSpeedMultiplier() * multiplier
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

        val wasAtTopEdge = atTopEdge
        val wasAtBottomEdge = atBottomEdge
        atTopEdge = cursorY <= step
        atBottomEdge = cursorY >= parent.height - step

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

    private inner class BitStreamWebViewClient : WebViewClient() {

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
            // Inject scriptlets early — before page scripts run (best effort)
            injectScriptlets()
        }

        override fun onPageFinished(view: WebView, url: String) {
            progressBar.visibility = View.GONE
            injectAdBlockCss()
            injectScriptlets()
        }

        // Catch SPA navigations that don't trigger onPageStarted/onPageFinished
        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            super.doUpdateVisitedHistory(view, url, isReload)
            injectAdBlockCss()
        }
    }

    private inner class BitStreamWebChromeClient : WebChromeClient() {

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
            @Suppress("DEPRECATION")
            requireActivity().window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
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
            @Suppress("DEPRECATION")
            requireActivity().window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
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

    private fun injectScriptlets() {
        val js = """
            (function() {
                if (window.__psScriptletsInjected) return;
                window.__psScriptletsInjected = true;

                // Block pop-ups and new windows
                window.open = function() { return null; };

                // Neutralize document.write (used to inject ad iframes)
                var _origWrite = document.write.bind(document);
                document.write = function(s) {
                    if (typeof s === 'string' && (
                        s.indexOf('googlesyndication') !== -1 ||
                        s.indexOf('doubleclick') !== -1 ||
                        s.indexOf('adservice') !== -1 ||
                        s.indexOf('adnxs') !== -1 ||
                        s.indexOf('<script') !== -1 && s.indexOf('ad') !== -1
                    )) return;
                    _origWrite(s);
                };

                // Freeze anti-adblock property reads
                var noopFn = function() {};
                try { Object.defineProperty(window, 'googletag', { get: function() { return { cmd: { push: noopFn }, defineSlot: noopFn, pubads: function() { return { enableSingleRequest: noopFn, collapseEmptyDivs: noopFn, addEventListener: noopFn, setTargeting: noopFn, refresh: noopFn, disableInitialLoad: noopFn }; }, enableServices: noopFn, display: noopFn }; }, configurable: true }); } catch(e) {}
                try { Object.defineProperty(window, '__cmp', { get: function() { return noopFn; }, configurable: true }); } catch(e) {}
                try { Object.defineProperty(window, '__tcfapi', { get: function() { return noopFn; }, configurable: true }); } catch(e) {}

                // Block setInterval/setTimeout used by anti-adblock detectors
                var _origSetInterval = window.setInterval;
                window.setInterval = function(fn, delay) {
                    if (typeof fn === 'function') {
                        var src = fn.toString();
                        if (src.indexOf('adblock') !== -1 || src.indexOf('AdBlock') !== -1 ||
                            src.indexOf('adblocker') !== -1 || src.indexOf('ad_blocker') !== -1) {
                            return 0;
                        }
                    }
                    return _origSetInterval.apply(this, arguments);
                };
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun injectAdBlockCss() {
        val css = """
            (function() {
                if (document.getElementById('__ps_adblock_css')) return;
                var style = document.createElement('style');
                style.id = '__ps_adblock_css';
                style.innerHTML = `
                    /* Generic ad containers by attribute patterns */
                    [id*="ad-"],[class*="ad-"],[id*="-ad"],[class*="-ad"],
                    [id*="ads-"],[class*="ads-"],[id*="-ads"],[class*="-ads"],
                    [id*="advert"],[class*="advert"],
                    [id*="banner"],[class*="banner-ad"],[class*="ad-banner"],
                    [id*="sponsor"],[class*="sponsor"],
                    [data-ad],[data-ad-unit],[data-adslot],[data-google-query-id],

                    /* Common ad classes */
                    .advertisement,.ad-banner,.banner-ad,.ad-container,
                    .ad-wrapper,.ad-slot,.ad-unit,.ad-block,.ad-box,
                    .adsbygoogle,.ads-container,.ads-wrapper,.ads-block,
                    .adsbox,.ad_unit,.ad_container,.ad_wrapper,
                    ins.adsbygoogle,

                    /* Google Ads */
                    [id*="google_ads"],[id*="GoogleAd"],[id*="google-ad"],
                    [class*="google-ad"],[class*="GoogleAd"],
                    iframe[src*="doubleclick"],iframe[src*="googlesyndication"],
                    iframe[src*="adservice"],iframe[src*="googleadservices"],
                    iframe[src*="google_ads"],iframe[src*="tpc.googlesyndication"],

                    /* Taboola / Outbrain / content recommendation widgets */
                    [id*="taboola"],[class*="taboola"],
                    [id*="outbrain"],[class*="outbrain"],
                    [id*="revcontent"],[class*="revcontent"],
                    [class*="trc_related"],[id*="trc_related"],
                    .OUTBRAIN,.taboola-widget,

                    /* Overlays, popups, modals used for ads */
                    .popup-overlay,.modal-overlay,.sticky-ad,.fixed-ad,
                    [class*="popup-"],[class*="overlay-ad"],
                    [class*="interstitial"],[id*="interstitial"],
                    [class*="adoverlay"],[class*="ad-overlay"],

                    /* Sticky / floating ad bars */
                    [class*="sticky-bottom"],[id*="sticky-bottom"],
                    [class*="floating-ad"],[class*="float-ad"],
                    [class*="adhesion"],[id*="adhesion"],

                    /* YouTube-specific */
                    ytd-promoted-sparkles-web-renderer,
                    ytd-ad-slot-renderer,
                    ytd-in-feed-ad-layout-renderer,
                    ytd-display-ad-renderer,
                    .ytd-promoted-video-renderer,
                    #player-ads,#masthead-ad,
                    .ytp-ad-module,.ytp-ad-overlay-container,
                    .ytp-ad-text-overlay,

                    /* Reddit-specific */
                    [data-adtype],[class*="promoted-link"],
                    .promoted,.promoted-post,

                    /* Twitter/X-specific */
                    [data-testid="placementTracking"],
                    article[data-testid*="ad"],

                    /* Amazon-specific */
                    [cel_widget_id*="ad"],[data-component-type*="ad"],
                    .AdHolder,.s-sponsored-list-header,

                    /* Generic tracker pixels */
                    img[width="1"][height="1"],
                    img[src*="pixel"],img[src*="beacon"],
                    img[src*="track"],

                    /* Cookie consent / GDPR popups (often block content) */
                    #onetrust-consent-sdk,#cookieConsent,
                    .cookie-notice,.cookie-banner,.cookie-overlay,
                    [class*="gdpr-"],[id*="gdpr-"],
                    [class*="cookie-wall"],[id*="cookie-wall"] {
                        display: none !important;
                        visibility: hidden !important;
                        height: 0 !important;
                        max-height: 0 !important;
                        overflow: hidden !important;
                        pointer-events: none !important;
                    }

                    /* Prevent layout shift from removed elements */
                    body { overflow-x: hidden !important; }
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
