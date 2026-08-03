package com.caesiumstudio.pinstream.webview

import android.os.Bundle
import android.view.KeyEvent
import androidx.fragment.app.FragmentActivity
import com.caesiumstudio.pinstream.R

class WebViewActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_webview)
        if (savedInstanceState == null) {
            val url = intent.getStringExtra(EXTRA_URL) ?: return
            supportFragmentManager.beginTransaction()
                .replace(R.id.webview_container, WebViewFragment.newInstance(url))
                .commit()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event == null) return super.onKeyDown(keyCode, event)
        val fragment = supportFragmentManager.findFragmentById(R.id.webview_container) as? WebViewFragment
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // If keyboard is open, dismiss it and restore D-pad control
            fragment?.dismissKeyboardAndRestoreFocus()
            if (fragment?.canGoBack() == true) {
                fragment.goBack()
                return true
            }
        }
        if (fragment?.onKeyDown(keyCode, event) == true) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        val fragment = supportFragmentManager.findFragmentById(R.id.webview_container) as? WebViewFragment
        if (fragment?.onKeyUp(keyCode) == true) return true
        return super.onKeyUp(keyCode, event)
    }

    companion object {
        const val EXTRA_URL = "extra_url"
    }
}
