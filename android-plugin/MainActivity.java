package com.gkk.app;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Must register local plugins BEFORE super.onCreate()
        // so they are added to bridgeBuilder before the bridge is built.
        registerPlugin(DualCamRecorderPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
