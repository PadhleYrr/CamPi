package com.gkk.app

import android.os.Bundle
import com.getcapacitor.BridgeActivity

class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Register our local plugin BEFORE super.onCreate() builds the bridge
        registerPlugin(DualCamRecorderPlugin::class.java)
        super.onCreate(savedInstanceState)
    }
}
