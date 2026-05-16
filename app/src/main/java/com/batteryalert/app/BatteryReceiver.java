package com.batteryalert.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Manifest-registered receiver — kept for legacy Android versions.
 * On Android 8+, battery events are handled inside the Service directly
 * via a dynamically-registered receiver (which is more reliable).
 */
public class BatteryReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // Handled by BatteryMonitorService's internal receiver on Android 8+
    }
}
