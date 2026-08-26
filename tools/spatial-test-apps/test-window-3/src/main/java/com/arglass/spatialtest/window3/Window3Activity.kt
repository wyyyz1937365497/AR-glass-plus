package com.arglass.spatialtest.window3

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import com.arglass.spatialtest.common.TestPatternView

/** Spatial calibration card #3 — see TestPatternView for the element map. */
class Window3Activity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(TestPatternView(this, windowIndex = 3))
    }
}
