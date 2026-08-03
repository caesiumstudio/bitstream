package com.caesiumstudio.pinstream

import android.os.Bundle
import android.view.KeyEvent
import androidx.fragment.app.FragmentActivity

class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_browse_fragment, MainFragment())
                .commitNow()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val fragment =
            supportFragmentManager.findFragmentById(R.id.main_browse_fragment) as? MainFragment
        if (keyCode == KeyEvent.KEYCODE_ENTER && event?.isShiftPressed == true) {
            fragment?.simulateLongPress()
            return true
        }
        if (fragment?.handleKeyDown(keyCode) == true) return true
        return super.onKeyDown(keyCode, event)
    }
}