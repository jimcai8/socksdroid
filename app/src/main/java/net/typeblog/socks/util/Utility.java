package net.typeblog.socks.util;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.util.List;

import net.typeblog.socks.R;
import net.typeblog.socks.SocksVpnService;
import static net.typeblog.socks.util.Constants.*;

public class Utility {
    private static final String TAG = Utility.class.getSimpleName();

    public static int exec(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(cmd);

            return p.waitFor();
        } catch (Exception e) {
            return -1;
        }
    }

    public static void killPidFile(String f) {
        File file = new File(f);

        if (!file.exists()) {
            return;
        }

        InputStream i;
        try {
            i = new FileInputStream(file);
        } catch (Exception e) {
            return;
        }

        byte[] buf = new byte[512];
        int len;
        StringBuilder str = new StringBuilder();

        try {
            while ((len = i.read(buf, 0, 512)) > 0) {
                str.append(new String(buf, 0, len));
            }
            i.close();
        } catch (Exception e) {
            return;
        }

        try {
            int pid = Integer.parseInt(str.toString().trim().replace("\n", ""));
            Runtime.getRuntime().exec("kill " + pid).waitFor();
            if(!file.delete())
                Log.w(TAG, "failed to delete pidfile");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static String join(List<String> list, String separator) {
        if (list.isEmpty()) return "";

        StringBuilder ret = new StringBuilder();

        for (String s : list) {
            ret.append(s).append(separator);
        }

        return ret.substring(0, ret.length() - separator.length());
    }

    public static void makePdnsdConf(Context context, String dns, int port) {
        String conf = context.getString(R.string.pdnsd_conf)
                .replace("{DIR}", context.getFilesDir().toString())
                .replace("{IP}", dns)
                .replace("{PORT}", Integer.toString(port));

        File f = new File(context.getFilesDir() + "/pdnsd.conf");

        if (f.exists()) {
            if(!f.delete())
                Log.w(TAG, "failed to delete pdnsd.conf");
        }

        try {
            OutputStream out = new FileOutputStream(f);
            out.write(conf.getBytes());
            out.flush();
            out.close();
        } catch (Exception e) {
            e.printStackTrace();
        }

        File cache = new File(context.getFilesDir() + "/pdnsd.cache");

        if (!cache.exists()) {
            try {
                if(!cache.createNewFile())
                    Log.w(TAG, "failed to create pdnsd.cache");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static void startVpn(Context context, Profile profile) {
        // Android 8+ : background callers (e.g. BootReceiver) are not allowed to
        // call startService(); must go through startForegroundService(). The
        // service calls startForeground() at the top of onStartCommand(), so the
        // foreground-service contract is satisfied.
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(startVpnIntent(context, profile));
        } else {
            context.startService(startVpnIntent(context, profile));
        }
    }

    /** Build the explicit start intent carrying the full profile as extras. */
    public static Intent startVpnIntent(Context context, Profile profile) {
        Intent i = new Intent(context, SocksVpnService.class)
                .putExtra(INTENT_NAME, profile.getName())
                .putExtra(INTENT_SERVER, profile.getServer())
                .putExtra(INTENT_PORT, profile.getPort())
                .putExtra(INTENT_ROUTE, profile.getRoute())
                .putExtra(INTENT_DNS, profile.getDns())
                .putExtra(INTENT_DNS_PORT, profile.getDnsPort())
                .putExtra(INTENT_PER_APP, profile.isPerApp())
                .putExtra(INTENT_IPV6_PROXY, profile.hasIPv6());

        if (profile.isUserPw()) {
            i.putExtra(INTENT_USERNAME, profile.getUsername())
                    .putExtra(INTENT_PASSWORD, profile.getPassword());
        }

        if (profile.isPerApp()) {
            i.putExtra(INTENT_APP_BYPASS, profile.isBypassApp())
                    .putExtra(INTENT_APP_LIST, profile.getAppList().split("\n"));
        }

        if (profile.hasUDP()) {
            i.putExtra(INTENT_UDP_GW, profile.getUDPGW());
        }

        return i;
    }

    /**
     * Same as {@link #killPidFile(String)}, but waits until the process is
     * really gone (polls /proc/&lt;pid&gt; for up to ~2s) and escalates to
     * SIGKILL if needed. Prevents the old pdnsd/tun2socks from racing the
     * newly started ones (pdnsd would fail to bind its 8091 port).
     */
    public static void killPidFileWait(String f) {
        File file = new File(f);

        if (!file.exists()) {
            return;
        }

        int pid = -1;
        InputStream i;
        try {
            i = new FileInputStream(file);
            byte[] buf = new byte[512];
            int len;
            StringBuilder str = new StringBuilder();
            while ((len = i.read(buf, 0, 512)) > 0) {
                str.append(new String(buf, 0, len));
            }
            i.close();
            pid = Integer.parseInt(str.toString().trim().replace("\n", ""));
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (pid > 0) {
            try {
                Runtime.getRuntime().exec("kill " + pid).waitFor();

                // Wait up to ~2s for the process to disappear
                File proc = new File("/proc/" + pid);
                for (int t = 0; t < 20 && proc.exists(); t++) {
                    Thread.sleep(100);
                }

                if (proc.exists()) {
                    Runtime.getRuntime().exec("kill -9 " + pid).waitFor();
                    Thread.sleep(300);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        if(!file.delete())
            Log.w(TAG, "failed to delete pidfile");
    }
}
