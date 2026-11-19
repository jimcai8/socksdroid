package net.typeblog.socks;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import net.typeblog.socks.util.Profile;
import net.typeblog.socks.util.ProfileManager;
import net.typeblog.socks.util.Utility;

import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URLDecoder;
import java.util.Locale;

import static net.typeblog.socks.util.Constants.*;

/**
 * Shell / adb control interface:
 *
 *   adb shell am broadcast -a net.typeblog.socks.ACTION_CONNECT \
 *          --es proxy "socks5://[user:pass@]host:port" [--es auto true]
 *   adb shell am broadcast -a net.typeblog.socks.ACTION_CONNECT \
 *          --es server 1.2.3.4 --ei port 1080 [--es username u --es password p]
 *   adb shell am broadcast -a net.typeblog.socks.ACTION_DISCONNECT
 *   adb shell am broadcast -a net.typeblog.socks.ACTION_STATUS
 *
 * The receiver is guarded by android:permission="android.permission.WRITE_SECURE_SETTINGS"
 * in the manifest (sender-side check): the adb shell uid holds that permission,
 * ordinary apps cannot obtain it. (Binder.getCallingUid() is NOT usable inside
 * onReceive() - it always reports the system server.)
 *
 * CONNECT always forwards the full parameter set through intent extras, because
 * SocksVpnService runs in the ":vpn" process whose SharedPreferences cache may
 * be stale with respect to writes done in this (main) process.
 */
public class ControlReceiver extends BroadcastReceiver {
    private static final String TAG = "ControlReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = (intent != null) ? intent.getAction() : null;
        if (action == null)
            return;

        switch (action) {
            case ACTION_CONNECT:
                handleConnect(context, intent);
                break;
            case ACTION_DISCONNECT:
                handleDisconnect(context);
                break;
            case ACTION_STATUS:
                handleStatus(context);
                break;
            default:
                break;
        }
    }

    private void handleConnect(Context context, Intent intent) {
        Profile p = new ProfileManager(context).getDefault();

        String proxy = intent.getStringExtra(EXTRA_PROXY);
        boolean ok = true;

        if (proxy != null) {
            ok = applyProxy(p, proxy);
        } else if (intent.hasExtra(EXTRA_SERVER)) {
            applyDiscrete(p, intent);
        }
        // Neither present: plain "connect with the saved profile".

        // Optional overrides
        String route = intent.getStringExtra(EXTRA_ROUTE);
        if (route != null)
            p.setRoute(route);

        String dns = intent.getStringExtra(EXTRA_DNS);
        if (dns != null)
            p.setDns(dns);

        if (intent.hasExtra(EXTRA_DNS_PORT)) {
            int dnsPort = getFlexibleInt(intent, EXTRA_DNS_PORT, p.getDnsPort());
            if (dnsPort > 0)
                p.setDnsPort(dnsPort);
        }

        if (intent.hasExtra(EXTRA_AUTO))
            p.setAutoConnect(getFlexibleBool(intent, EXTRA_AUTO, p.autoConnect()));

        if (!ok) {
            Log.e(TAG, "invalid proxy url, NOT starting vpn");
            return;
        }

        Log.i(TAG, "connect: server=" + p.getServer() + ":" + p.getPort()
                + " auth=" + p.isUserPw() + (p.isUserPw() ? " user=" + p.getUsername() + " password=***" : "")
                + " route=" + p.getRoute() + " auto=" + p.autoConnect());

        startServiceSafely(context, Utility.startVpnIntent(context, p));
    }

    private void handleDisconnect(Context context) {
        Log.i(TAG, "disconnect");
        Intent i = new Intent(context, SocksVpnService.class).setAction(ACTION_DISCONNECT);
        startServiceSafely(context, i);
    }

    private void handleStatus(Context context) {
        boolean up = false;
        try {
            NetworkInterface nif = NetworkInterface.getByName("tun0");
            up = nif != null && nif.isUp();
        } catch (SocketException ignored) {
        }

        Profile p = new ProfileManager(context).getDefault();
        String msg = String.format(Locale.US, "server=%s:%d tun0=%s",
                p.getServer(), p.getPort(), up ? "up" : "down");
        Log.i(TAG, msg);
        setResultData(msg);
    }

    /** Parse "socks5://[user:pass@]host:port" (scheme optional) and write it
     *  into the profile. Manual parsing instead of java.net.URI so that
     *  passwords containing ':' or '@' work without percent-encoding;
     *  percent-encoded sequences are still decoded. */
    private boolean applyProxy(Profile p, String proxy) {
        String s = proxy.trim();
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            String sch = s.substring(0, scheme).toLowerCase(Locale.US);
            if (!"socks5".equals(sch) && !"socks".equals(sch)) {
                Log.e(TAG, "unsupported scheme: " + sch);
                return false;
            }
            s = s.substring(scheme + 3);
        }

        String userinfo = null;
        int at = s.lastIndexOf('@');
        if (at >= 0) {
            userinfo = s.substring(0, at);
            s = s.substring(at + 1);
        }

        String host;
        int port = 1080;
        int colon = s.lastIndexOf(':');
        if (colon >= 0) {
            host = s.substring(0, colon);
            try {
                port = Integer.parseInt(s.substring(colon + 1));
            } catch (NumberFormatException e) {
                Log.e(TAG, "bad port in proxy url: " + proxy);
                return false;
            }
            if (port < 1 || port > 65535) {
                Log.e(TAG, "port out of range: " + port);
                return false;
            }
        } else {
            host = s;
        }

        if (host == null || host.trim().isEmpty()) {
            Log.e(TAG, "no host in proxy url: " + proxy);
            return false;
        }

        if (userinfo != null) {
            int ucolon = userinfo.indexOf(':');
            String user = ucolon >= 0 ? userinfo.substring(0, ucolon) : userinfo;
            String pass = ucolon >= 0 ? userinfo.substring(ucolon + 1) : "";
            p.setIsUserpw(true);
            p.setUsername(maybeDecode(user));
            p.setPassword(maybeDecode(pass));
        } else {
            p.setIsUserpw(false);
        }

        p.setServer(host.trim());
        p.setPort(port);
        return true;
    }

    private void applyDiscrete(Profile p, Intent intent) {
        p.setServer(intent.getStringExtra(EXTRA_SERVER).trim());
        int port = getFlexibleInt(intent, EXTRA_PORT, p.getPort());
        if (port > 0)
            p.setPort(port);

        String user = intent.getStringExtra(EXTRA_USERNAME);
        if (user != null) {
            p.setIsUserpw(true);
            p.setUsername(user);
            p.setPassword(intent.getStringExtra(EXTRA_PASSWORD));
        }
    }

    /** am broadcast may deliver numbers/booleans as --es (String), --ei
     *  (Integer) or --ez (Boolean); accept all three. */
    private static int getFlexibleInt(Intent i, String key, int def) {
        Object v = (i.getExtras() != null) ? i.getExtras().get(key) : null;
        if (v instanceof Integer)
            return (Integer) v;
        if (v instanceof String) {
            try {
                return Integer.parseInt(((String) v).trim());
            } catch (NumberFormatException e) {
                Log.w(TAG, "bad int for " + key + ": " + v);
            }
        }
        return def;
    }

    private static boolean getFlexibleBool(Intent i, String key, boolean def) {
        Object v = (i.getExtras() != null) ? i.getExtras().get(key) : null;
        if (v instanceof Boolean)
            return (Boolean) v;
        if (v instanceof String)
            return Boolean.parseBoolean((String) v);
        return def;
    }

    private static String maybeDecode(String s) {
        if (!s.contains("%"))
            return s;
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static void startServiceSafely(Context context, Intent i) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(i);
            } else {
                context.startService(i);
            }
        } catch (IllegalStateException e) {
            // e.g. background start restrictions on newer Androids
            Log.e(TAG, "failed to start service: " + e);
        }
    }
}
