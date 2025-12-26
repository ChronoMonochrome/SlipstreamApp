package net.typeblob.socks;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.util.Log;

import net.typeblob.socks.socks.util.Utility;
import static net.typeblob.socks.BuildConfig.DEBUG;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = BootReceiver.class.getSimpleName();

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            if (DEBUG) {
                Log.d(TAG, "starting VPN service on boot");
            }
        }
    }
}
