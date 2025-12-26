package net.typeblob.socks.socks.util;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.*;
import java.util.List;

import net.typeblob.socks.R;
import net.typeblob.socks.SocksVpnService;
import static net.typeblob.socks.socks.util.Constants.*;

public class Utility {
    private static final String TAG = "VPNUtility";

    /**
     * Executes a command and logs both stdout and stderr.
     */
    public static int exec(String cmd) {
        Log.d(TAG, "Executing: " + cmd);
        try {
            Process p = Runtime.getRuntime().exec(cmd);

            // Thread to read Error Stream
            new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        Log.e(TAG, "[Native Error] " + line);
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Error reading stderr", e);
                }
            }).start();

            // Thread to read Input Stream (stdout)
            new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        Log.d(TAG, "[Native Out] " + line);
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Error reading stdout", e);
                }
            }).start();

            int exitCode = p.waitFor();
            Log.d(TAG, "Command exited with code: " + exitCode);
            return exitCode;
        } catch (Exception e) {
            Log.e(TAG, "Failed to execute command: " + cmd, e);
            return -1;
        }
    }

    public static void killPidFile(String filePath) {
        File file = new File(filePath);
        if (!file.exists()) return;

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line = br.readLine();
            if (line != null) {
                int pid = Integer.parseInt(line.trim());
                Log.i(TAG, "Killing process PID: " + pid + " from " + filePath);
                Runtime.getRuntime().exec("kill " + pid).waitFor();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to kill PID from " + filePath, e);
        } finally {
            if (file.exists()) file.delete();
        }
    }

    public static void makePdnsdConf(Context context, String dns, int port) {
        Log.d(TAG, "Generating pdnsd.conf for DNS: " + dns);
        // Simplified for brevity, assume R.string.pdnsd_conf exists
        String rawConf = context.getString(net.typeblob.socks.R.string.pdnsd_conf);
        String conf = rawConf.replace("{DIR}", context.getFilesDir().toString())
                             .replace("{IP}", dns)
                             .replace("{PORT}", Integer.toString(port));

        File f = new File(context.getFilesDir(), "pdnsd.conf");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(conf.getBytes());
            Log.d(TAG, "pdnsd.conf written to " + f.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "Failed to write pdnsd.conf", e);
        }

        File cache = new File(context.getFilesDir(), "pdnsd.cache");
        if (!cache.exists()) {
            try { cache.createNewFile(); } catch (IOException e) { Log.e(TAG, "Cache error", e); }
        }
    }

    public static void startVpn(Context context, String dns) {
        Intent i = new Intent(context, SocksVpnService.class)
                .putExtra(INTENT_NAME, "profile name")
                .putExtra(INTENT_SERVER, "127.0.0.1")
                .putExtra(INTENT_PORT, 3080)
                .putExtra(INTENT_ROUTE, "all")
                .putExtra(INTENT_DNS, dns)
                .putExtra(INTENT_DNS_PORT, 53)
                .putExtra(INTENT_PER_APP, false)
                .putExtra(INTENT_IPV6_PROXY, false);

        context.startService(i);
    }

    public static void stopVpn(Context context) {
        Intent i = new Intent(context, SocksVpnService.class);
        // We add an extra to tell the service we want to stop
        i.putExtra("ACTION_STOP", true);
        context.startService(i);
    }
}
