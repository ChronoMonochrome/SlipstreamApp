package net.typeblob.socks;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Log;

import net.typeblob.socks.socks.util.Routes;
import net.typeblob.socks.socks.util.Utility;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static net.typeblob.socks.socks.util.Constants.*;

public class SocksVpnService extends VpnService {
    private static final String TAG = "SocksVpnService";

    private boolean proxy_only = false;

    class VpnBinder extends IVpnService.Stub {
        @Override
        public boolean isRunning() { return mRunning; }
        @Override
        public void stop() {
            Log.i(TAG, "Stop requested via Binder interface");
            stopMe();
        }
    }

    private ParcelFileDescriptor mInterface;
    private boolean mRunning = false;
    private final IBinder mBinder = new VpnBinder();

    private void loadProxyConfiguration() {
        proxy_only = getSharedPreferences("SlipstreamPrefs", Context.MODE_PRIVATE)
                .getBoolean("current_proxy_only", true);
        Log.d(TAG, "Loaded proxy_only configuration state: " + proxy_only);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand called");

        loadProxyConfiguration();

        if (intent == null) {
            Log.w(TAG, "Sticky start with null intent, ignoring");
            return START_STICKY;
        }

        if (intent.getBooleanExtra("ACTION_STOP", false)) {
            Log.i(TAG, "Stop signal received via Intent");
            stopMe();
            return START_NOT_STICKY;
        }

        if (mRunning) {
            Log.i(TAG, "Service already running, ignoring start request");
            return START_STICKY;
        }

        // Защита от Null: если параметры отсутствуют, ставим дефолтный локальный адрес
        String name = intent.getStringExtra(INTENT_NAME);
        if (TextUtils.isEmpty(name)) name = "SocksVPN";

        String server = intent.getStringExtra(INTENT_SERVER);
        if (TextUtils.isEmpty(server)) server = "127.0.0.1";

        final int port = intent.getIntExtra(INTENT_PORT, 1080);

        String route = intent.getStringExtra(INTENT_ROUTE);
        if (TextUtils.isEmpty(route)) route = "all";

        String dns = intent.getStringExtra(INTENT_DNS);
        if (TextUtils.isEmpty(dns)) dns = "77.88.8.8";

        Log.d(TAG, String.format("Params: name=%s, server=%s:%d, route=%s, dns=%s", name, server, port, route, dns));

        setupNotification();

        try {
            Log.i(TAG, "Configuring VPN Interface...");
            configure(name, route,
                    intent.getBooleanExtra(INTENT_PER_APP, false),
                    intent.getBooleanExtra(INTENT_APP_BYPASS, false),
                    intent.getStringArrayExtra(INTENT_APP_LIST),
                    intent.getBooleanExtra(INTENT_IPV6_PROXY, false));

            if (mInterface != null) {
                int fd = mInterface.getFd();
                Log.i(TAG, "VPN Interface established. FD: " + fd);

                startNative(fd, server, port,
                        intent.getStringExtra(INTENT_USERNAME),
                        intent.getStringExtra(INTENT_PASSWORD),
                        dns,
                        intent.getIntExtra(INTENT_DNS_PORT, 53),
                        intent.getBooleanExtra(INTENT_IPV6_PROXY, false),
                        intent.getStringExtra(INTENT_UDP_GW));
            } else {
                Log.e(TAG, "Failed to establish VPN interface (mInterface is null)");
                stopMe();
            }
        } catch (Exception e) {
            Log.e(TAG, "Critical error during startup", e);
            stopMe();
        }

        return START_STICKY;
    }

    private void setupNotification() {
        Notification.Builder builder;
        String CHANNEL_ID = "net.typeblob.socks";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "Vpn Service", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;

        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), flags);

        Notification notification = builder
                .setContentTitle("Socks VPN")
                .setContentText("Tunnel is active")
                .setSmallIcon(R.drawable.ic_vpn)
                .setContentIntent(contentIntent)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, notification);
        }
    }

    private void configure(String name, String route, boolean perApp, boolean bypass, String[] apps, boolean ipv6) {
        Builder b = new Builder();
        b.setMtu(1500).setSession(name).addAddress("26.26.26.1", 24).addDnsServer("77.88.8.8");

        if (ipv6) {
            b.addAddress("fdfe:dcba:9876::1", 126).addRoute("::", 0);
        }

        Routes.addRoutes(this, b, route);
        b.addRoute("77.88.8.8", 32);

        // Приложения, трафик которых ЗАПРЕЩЕНО направлять в VPN (Защита от бесконечного цикла петли)
        List<String> criticalPackages = Arrays.asList(
                getPackageName(),                  // Наше приложение
                "com.termux",                      // Termux環境
                "com.v2ray.ang",                   // v2rayNG
                "moe.nb4a",                        // NekoBox
                "io.nekohasekai.sageret",          // SagerNet
                "com.github.dyhkwong.sagernet",
                "com.v2raytun.android",
                "com.singbox.android",             // Sing-box
                "com.github.kr328.clash"           // Clash
        );

        // Исключаем системные прокси-приложения и себя, чтобы не зациклить трафик
        for (String p : criticalPackages) {
            String cleanPkg = p.trim();
            if (TextUtils.isEmpty(cleanPkg)) continue;
            try {
                b.addDisallowedApplication(cleanPkg);
            } catch (Exception e) {
                Log.w(TAG, "Could not add critical application to bypass list: " + cleanPkg);
            }
        }

        // Обработка кастомного Per-App списков пользователя
        if (perApp && apps != null) {
            Log.d(TAG, "Per-app routing: " + (bypass ? "Bypass" : "Allowed") + " list: " + Arrays.toString(apps));
            for (String p : apps) {
                String cleanPkg = p.trim();
                if (TextUtils.isEmpty(cleanPkg) || criticalPackages.contains(cleanPkg)) continue;
                try {
                    if (bypass) {
                        b.addDisallowedApplication(cleanPkg);
                    } else {
                        b.addAllowedApplication(cleanPkg);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Could not apply rule for app: " + cleanPkg);
                }
            }
        }

        mInterface = b.establish();
    }

    private void startNative(int fd, String server, int port, String user, String passwd, String dns, int dnsPort, boolean ipv6, String udpgw) {
        Log.i(TAG, "Starting native components...");

        // 1. PDNSD
        Utility.makePdnsdConf(this, dns, dnsPort);
        String pdnsdCmd = String.format(Locale.US, "%s/libpdnsd.so -c %s/pdnsd.conf",
                getApplicationInfo().nativeLibraryDir, getFilesDir());
        Log.d(TAG, "Exec pdnsd: " + pdnsdCmd);
        Utility.exec(pdnsdCmd);

        // 2. TUN2SOCKS
        String command = String.format(Locale.US,
                "%s/libtun2socks.so --netif-ipaddr 26.26.26.2 --netif-netmask 255.255.255.0"
                        + " --socks-server-addr %s:%d --tunfd %d --tunmtu 1500 --loglevel 3"
                        + " --pid %s/tun2socks.pid --sock %s/sock_path --dnsgw 26.26.26.1:8091",
                getApplicationInfo().nativeLibraryDir, server, port, fd, getFilesDir(), getApplicationInfo().dataDir);

        if (user != null) command += " --username " + user + " --password " + passwd;
        if (ipv6) command += " --netif-ip6addr fdfe:dcba:9876::2";
        if (udpgw != null) command += " --udpgw-remote-server-addr " + udpgw;

        Log.d(TAG, "Exec tun2socks: " + command);
        if (Utility.exec(command) != 0) {
            Log.e(TAG, "Native binary tun2socks failed to start");
            stopMe();
            return;
        }

        // 3. FD Transfer
        String sockPath = getApplicationInfo().dataDir + "/sock_path";
        Log.i(TAG, "Attempting FD transfer to socket: " + sockPath);
        for (int i = 0; i < 5; i++) {
            if (System.sendfd(fd, sockPath) != -1) {
                Log.i(TAG, "FD transfer successful on attempt " + (i + 1));
                mRunning = true;
                return;
            }
            Log.w(TAG, "FD transfer failed, retrying in " + (i + 1) + "s...");
            try { Thread.sleep(1000L * (i + 1)); } catch (Exception ignored) {}
        }

        Log.e(TAG, "FD transfer timed out after 5 attempts");
        stopMe();
    }

    private void stopMe() {
        Log.i(TAG, "Stopping VPN service and cleaning up...");
        mRunning = false;
        stopForeground(true);

        Utility.killPidFile(getFilesDir() + "/tun2socks.pid");
        Utility.killPidFile(getFilesDir() + "/pdnsd.pid");

        if (mInterface != null) {
            try {
                Log.d(TAG, "Closing TUN interface");
                mInterface.close();
                mInterface = null;
            } catch (Exception e) {
                Log.e(TAG, "Error closing interface", e);
            }
        }
        stopSelf();
    }

    @Override public void onRevoke() { Log.i(TAG, "VPN Revoked"); stopMe(); }
    @Override public void onDestroy() { Log.i(TAG, "Service Destroyed"); stopMe(); }
    @Override public IBinder onBind(Intent intent) { return mBinder; }
}
