package com.gkk.app;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.Plugin;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Register DualCamRecorderPlugin via reflection so Java doesn't need
        // to resolve the Kotlin class at compile time — avoids cross-language
        // classpath ordering issues in the Gradle build.
        try {
            @SuppressWarnings("unchecked")
            Class<? extends Plugin> pluginClass =
                (Class<? extends Plugin>) Class.forName("com.gkk.app.DualCamRecorderPlugin");
            registerPlugin(pluginClass);
        } catch (ClassNotFoundException e) {
            android.util.Log.e("GKK", "DualCamRecorderPlugin not found", e);
        }
        super.onCreate(savedInstanceState);
    }
}
