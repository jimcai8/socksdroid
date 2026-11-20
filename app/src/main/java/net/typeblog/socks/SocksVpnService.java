package net.typeblog.socks;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Log;

import net.typeblog.socks.util.Profile;
import net.typeblog.socks.util.ProfileManager;
import net.typeblog.socks.util.Routes;
import net.typeblog.socks.util.Utility;

import java.io.File;
import java.util.Locale;
import java.util.Objects;

import static net.typeblog.socks.util.Constants.*;
import static net.typeblog.socks.BuildConfig.DEBUG;

public class SocksVpnService extends VpnService {
    class VpnBinder extends IVpnService.Stub {
        @Override
        public boolean isRunning() {
            return mRunning;
        }

        @Override
        public void stop() {
            stopMe();
        }
    }

    private static final String TAG = SocksVpnService.class.getSimpleName();
    private static final int NOTIFICATION_ID = 1;
    private static final String NOTIFICATION_CHANNEL_ID = "net.typeblog.socks";

    private ParcelFileDescriptor mInterface;
    private boolean mRunning = false;
    private boolean mStarting = false;
    private String mLastServer;
    private int mLastPort = -1;
    private final IBinder mBinder = new VpnBinder();

    /** All parameters needed to bring the tunnel up; comes either from
     *  intent extras (UI / ControlReceiver) or from the saved default
     *  profile (boot, always-on restart, sticky restart). */
    private static class Params {
        String name, server, username, passwd, route, dns, udpgw;
        int port = 1080, dnsPort = 53;
        boolean perApp, appBypass, ipv6;
        String[] appList;

        static Params fromExtras(Intent intent) {
            Params p = new Params();
            p.name = intent.getStringExtra(INTENT_NAME);
            p.server = intent.getStringExtra(INTENT_SERVER);
            p.port = intent.getIntExtra(INTENT_PORT, 1080);
            p.username = intent.getStringExtra(INTENT_USERNAME);
            p.passwd = intent.getStringExtra(INTENT_PASSWORD);
            p.route = intent.getStringExtra(INTENT_ROUTE);
            p.dns = intent.getStringExtra(INTENT_DNS);
            p.dnsPort = intent.getIntExtra(INTENT_DNS_PORT, 53);
            p.perApp = intent.getBooleanExtra(INTENT_PER_APP, false);
            p.appBypass = intent.getBooleanExtra(INTENT_APP_BYPASS, false);
            p.appList = intent.getStringArrayExtra(INTENT_APP_LIST);
            p.ipv6 = intent.getBooleanExtra(INTENT_IPV6_PROXY, false);
            p.udpgw = intent.getStringExtra(INTENT_UDP_GW);
            return p;
        }

        static Params fromProfile(Context context) {
            Profile profile = new ProfileManager(context).getDefault();
            Params p = new Params();
            p.name = profile.getName();
            p.server = profile.getServer();
            p.port = profile.getPort();
            p.route = profile.getRoute();
            p.dns = profile.getDns();
            p.dnsPort = profile.getDnsPort();
            p.perApp = profile.isPerApp();
            p.ipv6 = profile.hasIPv6();
            if (profile.isUserPw()) {
                p.username = profile.getUsername();
                p.passwd = profile.getPassword();
            }
            if (profile.isPerApp()) {
                p.appBypass = profile.isBypassApp();
                p.appList = profile.getAppList().split("\n");
            }
            if (profile.hasUDP()) {
                p.udpgw = profile.getUDPGW();
            }
            return p;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String action = (intent != null) ? intent.getAction() : null;

        if (DEBUG) {
            Log.d(TAG, "starting, action=" + action + ", running=" + mRunning);
        }

        // Explicit disconnect (from ControlReceiver). When delivered through
        // startForegroundService() the service MUST call startForeground()
        // before stopping, otherwise Android 8/9 raises the
        // "did not call startForeground" exception.
        if (ACTION_DISCONNECT.equals(action)) {
            startForeground(NOTIFICATION_ID, buildNotification(""));
            stopMe();
            return START_NOT_STICKY;
        }

        // Does this start command carry a full parameter set?
        final boolean hasServer = intent != null && intent.getStringExtra(INTENT_SERVER) != null;

        if ((mRunning || mStarting) && !hasServer) {
            // Redundant always-on delivery / no-parameter start while the
            // tunnel is up or being brought up: nothing to do. (The starting
            // window matters: mRunning only turns true after the fd was handed
            // to tun2socks, which takes 1-3s; without the mStarting guard a
            // second delivery would kill the freshly spawned daemons.)
            return START_STICKY;
        }

        if ((mRunning || mStarting) && hasServer) {
            // Duplicate connect for the SAME server (e.g. a broadcast mirrored
            // by the vendor's multi-instance sync service, or a repeated
            // command): keep the running tunnel instead of tearing it down
            // and racing ourselves. Only a genuinely different server/port
            // triggers a hot reconnect.
            String server = intent.getStringExtra(INTENT_SERVER);
            int port = intent.getIntExtra(INTENT_PORT, 1080);
            if (mLastServer != null && server != null
                    && server.equals(mLastServer) && port == mLastPort) {
                if (DEBUG)
                    Log.d(TAG, "duplicate connect for " + server + ":" + port + ", ignoring");
                return START_STICKY;
            }
        }

        mStarting = true;
        try {
            // Kill leftover tun2socks/pdnsd recorded in the pidfiles from a
            // previous life of this process: when the app is replaced or killed
            // while running, the native children survive as orphans and keep the
            // socks socket / pdnsd port busy, which makes the new ones fail.
            stopTunnel();

            // Extras first; without extras (system always-on start, sticky restart
            // with null intent, or a parameterless ACTION_CONNECT) fall back to
            // the saved default profile.
            final Params p = hasServer ? Params.fromExtras(intent) : Params.fromProfile(this);

            // Remember what we are connecting to, for duplicate suppression.
            mLastServer = p.server;
            mLastPort = p.port;

            startForeground(NOTIFICATION_ID, buildNotification(p.name));

            // Create an fd.
            configure(p.name, p.route, p.perApp, p.appBypass, p.appList, p.ipv6);

            if (mInterface == null) {
                // establish() failed (VPN not prepared / revoked): stop for real
                // instead of leaving an idle foreground service that would just
                // get restarted again.
                Log.e(TAG, "failed to establish VPN interface");
                stopMe();
                return START_NOT_STICKY;
            }

            if (DEBUG)
                Log.d(TAG, "fd: " + mInterface.getFd());

            start(mInterface.getFd(), p.server, p.port, p.username, p.passwd, p.dns, p.dnsPort, p.ipv6, p.udpgw);
        } finally {
            mStarting = false;
        }

        return START_STICKY;
    }

    @Override
    public void onRevoke() {
        super.onRevoke();
        stopMe();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        // Only clean up the child processes; calling stopMe() here would
        // call stopSelf() from within destruction, which is pointless.
        stopTunnel();
    }

    private void stopMe() {
        stopTunnel();
        stopForeground(true);
        stopSelf();
    }

    /** Tear down the tunnel (kill tun2socks/pdnsd, close the fd) but keep
     *  the service process alive. Safe to call repeatedly. */
    private void stopTunnel() {
        Utility.killPidFileWait(getFilesDir() + "/tun2socks.pid");
        Utility.killPidFileWait(getFilesDir() + "/pdnsd.pid");

        // Remove the stale socks socket left behind (a surviving tun2socks
        // orphan is killed above; the socket FILE itself has to go too,
        // otherwise the new tun2socks cannot bind it)
        File sockPath = new File(getApplicationInfo().dataDir + "/sock_path");
        if (sockPath.exists() && !sockPath.delete())
            Log.w(TAG, "failed to delete sock_path");

        try {
            if (mInterface != null) {
                System.jniclose(mInterface.getFd());
                mInterface.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        mInterface = null;
        mRunning = false;
    }

    private Notification buildNotification(String name) {
        // Notifications on Oreo and above need a channel
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID,
                    getString(R.string.channel_name), NotificationManager.IMPORTANCE_NONE);
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            Objects.requireNonNull(notificationManager).createNotificationChannel(channel);
            builder = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        int intentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            intentFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), intentFlags);

        return builder
                .setContentTitle(getString(R.string.notify_title))
                .setContentText(String.format(getString(R.string.notify_msg), name))
                .setPriority(Notification.PRIORITY_MIN)
                .setSmallIcon(R.drawable.ic_vpn)
                .setContentIntent(contentIntent)
                .build();
    }

    private void configure(String name, String route, boolean perApp, boolean bypass, String[] apps, boolean ipv6) {
        Builder b = new Builder();
        b.setMtu(1500)
                .setSession(name)
                .addAddress("26.26.26.1", 24)
                .addDnsServer("8.8.8.8");

        if (ipv6) {
            // Route all IPv6 traffic
            b.addAddress("fdfe:dcba:9876::1", 126)
                    .addRoute("::", 0);
        }

        Routes.addRoutes(this, b, route);

        // Add the default DNS
        // Note that this DNS is just a stub.
        // Actual DNS requests will be redirected through pdnsd.
        b.addRoute("8.8.8.8", 32);

        // Do app routing
        if (!perApp) {
            // Just bypass myself
            try {
                b.addDisallowedApplication("net.typeblog.socks");
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            if (bypass) {
                // First, bypass myself
                try {
                    b.addDisallowedApplication("net.typeblog.socks");
                } catch (Exception e) {
                    e.printStackTrace();
                }

                for (String p : apps) {
                    if (TextUtils.isEmpty(p))
                        continue;

                    try {
                        b.addDisallowedApplication(p.trim());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            } else {
                for (String p : apps) {
                    if (TextUtils.isEmpty(p) || p.trim().equals("net.typeblog.socks")) {
                        continue;
                    }

                    try {
                        b.addAllowedApplication(p.trim());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        mInterface = b.establish();
    }

    private void start(int fd, String server, int port, String user, String passwd, String dns, int dnsPort, boolean ipv6, String udpgw) {
        // Start DNS daemon first
        Utility.makePdnsdConf(this, dns, dnsPort);

        Utility.exec(String.format(Locale.US, "%s/libpdnsd.so -c %s/pdnsd.conf",
                getApplicationInfo().nativeLibraryDir, getFilesDir()));

        String command = String.format(Locale.US,
                "%s/libtun2socks.so --netif-ipaddr 26.26.26.2"
                        + " --netif-netmask 255.255.255.0"
                        + " --socks-server-addr %s:%d"
                        + " --tunfd %d"
                        + " --tunmtu 1500"
                        + " --loglevel 3"
                        + " --pid %s/tun2socks.pid"
                        + " --sock %s/sock_path"
                , getApplicationInfo().nativeLibraryDir, server, port, fd, getFilesDir(), getApplicationInfo().dataDir);

        if (user != null) {
            command += " --username " + user;
            command += " --password " + passwd;
        }

        if (ipv6) {
            command += " --netif-ip6addr fdfe:dcba:9876::2";
        }

        command += " --dnsgw 26.26.26.1:8091";

        if (udpgw != null) {
            command += " --udpgw-remote-server-addr " + udpgw;
        }

        if (DEBUG) {
            Log.d(TAG, command);
        }

        if (Utility.exec(command) != 0) {
            Log.e(TAG, "tun2socks exited with error, stopping");
            stopMe();
            return;
        }

        // Try to send the Fd through socket.
        int i = 0;
        while (i < 5) {
            if (System.sendfd(fd, getApplicationInfo().dataDir + "/sock_path") != -1) {
                Log.i(TAG, "tunnel established, fd sent to tun2socks");
                mRunning = true;
                return;
            }

            i++;

            try {
                Thread.sleep(1000L * i);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // Should not get here. Must be a failure.
        Log.e(TAG, "sendfd failed after retries, stopping");
        stopMe();
    }
}
