/*
 * ZG: glue between the Rust proxy core (ZgCore) and Telegram's proxy settings.
 *
 * The core is an in-process SOCKS5 listener on 127.0.0.1 that tunnels everything through the
 * user's VLESS/REALITY server. It holds no wakelock and runs no foreground service: it lives and
 * dies with the application process, exactly like tgnet itself. When it is up we point tgnet at it
 * through the regular proxy machinery (SharedConfig.currentProxy + "mainconfig" prefs +
 * ConnectionsManager.setProxySettings), so the stock proxy UI, ping check and proxy rotation keep
 * working and the state survives process restarts (ConnectionsManager.init() re-reads the prefs).
 */

package org.zsudo.zg;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;

public final class ZgProxyController {

    public static final String PREFS_NAME = "zgconfig";
    public static final String LOCAL_HOST = "127.0.0.1";
    public static final String DEFAULT_FRAGMENT_SPEC = "tlshello,40-80,5-10,4-6";

    private static final String KEY_VLESS_URL = "vless_url";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_FRAGMENT_SPEC = "fragment_spec";
    private static final String KEY_LISTEN_PORT = "listen_port";
    private static final String KEY_LAST_PORT = "last_port";

    public interface ResultCallback {
        void onResult(boolean running, String error);
    }

    private static volatile ZgProxyController instance;

    public static ZgProxyController getInstance() {
        ZgProxyController localInstance = instance;
        if (localInstance == null) {
            synchronized (ZgProxyController.class) {
                localInstance = instance;
                if (localInstance == null) {
                    instance = localInstance = new ZgProxyController();
                }
            }
        }
        return localInstance;
    }

    private final Object lock = new Object();
    private Boolean nativeAvailable;
    private String nativeLoadError = "";
    private volatile boolean running;
    private volatile int boundPort;
    private volatile String lastError = "";
    private volatile boolean busy;
    private volatile boolean notifyResumeUnsupported;
    private volatile long lastResumeNotifyTime;

    private ZgProxyController() {
    }

    // ---------------------------------------------------------------- settings

    private SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static SharedPreferences mainPrefs() {
        // Same file ConnectionsManager.init()/SharedConfig.loadProxyList() read; avoids touching
        // MessagesController from Application.onCreate().
        return ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Context.MODE_PRIVATE);
    }

    public boolean isEnabled() {
        return prefs().getBoolean(KEY_ENABLED, false);
    }

    public String getVlessUrl() {
        return prefs().getString(KEY_VLESS_URL, "");
    }

    public void setVlessUrl(String url) {
        prefs().edit().putString(KEY_VLESS_URL, url == null ? "" : url.trim()).apply();
    }

    public String getFragmentSpec() {
        String spec = prefs().getString(KEY_FRAGMENT_SPEC, DEFAULT_FRAGMENT_SPEC);
        return TextUtils.isEmpty(spec) ? DEFAULT_FRAGMENT_SPEC : spec;
    }

    public void setFragmentSpec(String spec) {
        prefs().edit().putString(KEY_FRAGMENT_SPEC, TextUtils.isEmpty(spec) ? DEFAULT_FRAGMENT_SPEC : spec.trim()).apply();
    }

    /** 0 means "let the core pick a free port (re-using the previous one when possible)". */
    public int getListenPort() {
        return prefs().getInt(KEY_LISTEN_PORT, 0);
    }

    public void setListenPort(int port) {
        prefs().edit().putInt(KEY_LISTEN_PORT, port < 0 || port > 65535 ? 0 : port).apply();
    }

    private int getLastPort() {
        return prefs().getInt(KEY_LAST_PORT, 0);
    }

    public static boolean isValidVlessUrl(String url) {
        if (url == null) {
            return false;
        }
        url = url.trim();
        if (!url.startsWith("vless://")) {
            return false;
        }
        int q = url.indexOf('?');
        if (q < 0) {
            return false;
        }
        String query = url.substring(q + 1);
        int hash = query.indexOf('#');
        if (hash >= 0) {
            query = query.substring(0, hash);
        }
        for (String param : query.split("&")) {
            if ("security=reality".equals(param)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- native state

    public boolean isNativeAvailable() {
        synchronized (lock) {
            if (nativeAvailable == null) {
                try {
                    ZgCore.version();
                    nativeAvailable = true;
                } catch (Throwable e) {
                    // UnsatisfiedLinkError (no libzgcore.so for this ABI), NoClassDefFoundError on
                    // later attempts, or anything else: feature disabled, app keeps working.
                    nativeAvailable = false;
                    nativeLoadError = e.getClass().getSimpleName() + ": " + e.getMessage();
                    FileLog.e("ZG: native core unavailable: " + nativeLoadError);
                }
            }
            return nativeAvailable;
        }
    }

    public String getNativeLoadError() {
        return nativeLoadError;
    }

    public String getVersion() {
        if (!isNativeAvailable()) {
            return "";
        }
        try {
            String v = ZgCore.version();
            return v == null ? "" : v;
        } catch (Throwable e) {
            FileLog.e(e);
            return "";
        }
    }

    public long[] getStats() {
        if (!isRunning()) {
            return null;
        }
        try {
            long[] stats = ZgCore.stats();
            return stats != null && stats.length >= 3 ? stats : null;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    public boolean isRunning() {
        if (!running || !isNativeAvailable()) {
            return false;
        }
        try {
            return ZgCore.isRunning();
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    public boolean isBusy() {
        return busy;
    }

    public int getBoundPort() {
        return boundPort;
    }

    public String getLastError() {
        return lastError == null ? "" : lastError;
    }

    private String readNativeError() {
        try {
            String err = ZgCore.lastError();
            return err == null ? "" : err;
        } catch (Throwable e) {
            return e.toString();
        }
    }

    // ---------------------------------------------------------------- lifecycle

    /**
     * ZG resume-latency: called from ConnectionsManager.setAppPaused(false) on the UI thread right
     * before native_resumeNetwork, so the core can pre-dial a tunnel while tgnet is still waking
     * up. No-op when the core is not running; a missing native symbol (older libzgcore.so) is
     * logged once and then ignored. Debounced because several activities/accounts resume together.
     */
    public void onAppResumed() {
        if (notifyResumeUnsupported || !isRunning()) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (lastResumeNotifyTime != 0 && now - lastResumeNotifyTime < 1000) {
            return;
        }
        lastResumeNotifyTime = now;
        try {
            ZgCore.notifyResume();
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("ZG resume: core notified");
            }
        } catch (Throwable e) {
            // UnsatisfiedLinkError when the core predates notifyResume, or anything the core threw.
            notifyResumeUnsupported = true;
            FileLog.e("ZG resume: notifyResume unavailable: " + e);
        }
    }

    /** Called once per process from ApplicationLoader.postInitApplication(). */
    public void onApplicationStart() {
        if (!isEnabled()) {
            return;
        }
        if (!isNativeAvailable()) {
            lastError = nativeLoadError;
            AndroidUtilities.runOnUIThread(this::detachTelegramProxy);
            return;
        }
        if (!isValidVlessUrl(getVlessUrl())) {
            lastError = "invalid vless:// link";
            AndroidUtilities.runOnUIThread(this::detachTelegramProxy);
            return;
        }
        startAsync(null);
    }

    public void setEnabled(boolean enabled, ResultCallback callback) {
        if (enabled) {
            if (!isNativeAvailable()) {
                lastError = nativeLoadError;
                if (callback != null) {
                    callback.onResult(false, lastError);
                }
                return;
            }
            if (!isValidVlessUrl(getVlessUrl())) {
                lastError = "invalid vless:// link";
                if (callback != null) {
                    callback.onResult(false, lastError);
                }
                return;
            }
            prefs().edit().putBoolean(KEY_ENABLED, true).apply();
            startAsync((ok, error) -> {
                if (!ok) {
                    prefs().edit().putBoolean(KEY_ENABLED, false).apply();
                }
                if (callback != null) {
                    callback.onResult(ok, error);
                }
            });
        } else {
            prefs().edit().putBoolean(KEY_ENABLED, false).apply();
            stopAsync(callback);
        }
    }

    /** Re-applies the current settings (link / fragment spec / port) if the core is enabled. */
    public void restartIfEnabled(ResultCallback callback) {
        if (isEnabled()) {
            setEnabled(true, callback);
        } else if (callback != null) {
            callback.onResult(false, "");
        }
    }

    private void startAsync(ResultCallback callback) {
        busy = true;
        Utilities.globalQueue.postRunnable(() -> {
            final int previousPort = getLastPort();
            final int port = startCore();
            AndroidUtilities.runOnUIThread(() -> {
                busy = false;
                if (port > 0) {
                    applyTelegramProxy(port, previousPort);
                } else {
                    // Never leave tgnet pointed at a listener that is not there.
                    detachTelegramProxy();
                }
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.zgProxyStateChanged);
                if (callback != null) {
                    callback.onResult(port > 0, port > 0 ? "" : getLastError());
                }
            });
        });
    }

    private void stopAsync(ResultCallback callback) {
        busy = true;
        Utilities.globalQueue.postRunnable(() -> {
            stopCore();
            AndroidUtilities.runOnUIThread(() -> {
                busy = false;
                detachTelegramProxy();
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.zgProxyStateChanged);
                if (callback != null) {
                    callback.onResult(false, "");
                }
            });
        });
    }

    /** @return bound port > 0 on success; lastError is set on failure. Runs on globalQueue. */
    private int startCore() {
        synchronized (lock) {
            if (!isNativeAvailable()) {
                lastError = nativeLoadError;
                return -1;
            }
            stopCoreLocked();
            final String url = getVlessUrl();
            final String spec = getFragmentSpec();
            int wanted = getListenPort();
            if (wanted <= 0) {
                wanted = getLastPort();
            }
            int port;
            try {
                port = ZgCore.start(url, wanted, spec);
                if (port <= 0 && wanted > 0 && getListenPort() <= 0) {
                    // previous ephemeral port is taken now; let the core choose another one
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("ZG: port " + wanted + " unavailable (" + readNativeError() + "), retrying with 0");
                    }
                    port = ZgCore.start(url, 0, spec);
                }
            } catch (Throwable e) {
                FileLog.e(e);
                lastError = e.toString();
                return -1;
            }
            if (port > 0) {
                running = true;
                boundPort = port;
                lastError = "";
                prefs().edit().putInt(KEY_LAST_PORT, port).apply();
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("ZG: core " + getVersion() + " listening on " + LOCAL_HOST + ":" + port);
                }
            } else {
                running = false;
                boundPort = 0;
                lastError = readNativeError();
                if (TextUtils.isEmpty(lastError)) {
                    lastError = "start failed (" + port + ")";
                }
                FileLog.e("ZG: core failed to start: " + lastError);
            }
            return port;
        }
    }

    private void stopCore() {
        synchronized (lock) {
            stopCoreLocked();
        }
    }

    private void stopCoreLocked() {
        if (!running) {
            return;
        }
        try {
            ZgCore.stop();
        } catch (Throwable e) {
            FileLog.e(e);
        }
        running = false;
        boundPort = 0;
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("ZG: core stopped");
        }
    }

    // ---------------------------------------------------------------- Telegram proxy glue

    private static boolean isLocalSocksEntry(SharedConfig.ProxyInfo info) {
        return info != null && LOCAL_HOST.equals(info.address) && TextUtils.isEmpty(info.secret) && TextUtils.isEmpty(info.username) && TextUtils.isEmpty(info.password);
    }

    /** True if this proxy list entry is the one ZG created (current or previous port). */
    public boolean isZgProxy(SharedConfig.ProxyInfo info) {
        if (!isLocalSocksEntry(info)) {
            return false;
        }
        return info.port == boundPort || info.port == getLastPort();
    }

    /** True if tgnet is currently configured to go through the running ZG listener. */
    public boolean isTelegramRoutedThroughZg() {
        if (!isRunning()) {
            return false;
        }
        SharedPreferences preferences = mainPrefs();
        return preferences.getBoolean("proxy_enabled", false)
                && LOCAL_HOST.equals(preferences.getString("proxy_ip", ""))
                && preferences.getInt("proxy_port", 0) == boundPort
                && TextUtils.isEmpty(preferences.getString("proxy_secret", ""));
    }

    /** UI thread. Makes 127.0.0.1:port the active Telegram proxy and keeps the proxy list in sync. */
    private void applyTelegramProxy(int port, int previousPort) {
        SharedConfig.loadProxyList();
        if (previousPort > 0 && previousPort != port) {
            for (int a = SharedConfig.proxyList.size() - 1; a >= 0; a--) {
                SharedConfig.ProxyInfo info = SharedConfig.proxyList.get(a);
                if (isLocalSocksEntry(info) && info.port == previousPort) {
                    SharedConfig.deleteProxy(info);
                }
            }
        }
        SharedConfig.ProxyInfo info = SharedConfig.addProxy(new SharedConfig.ProxyInfo(LOCAL_HOST, port, "", "", ""));

        SharedPreferences.Editor editor = mainPrefs().edit();
        editor.putString("proxy_ip", LOCAL_HOST);
        editor.putInt("proxy_port", port);
        editor.putString("proxy_user", "");
        editor.putString("proxy_pass", "");
        editor.putString("proxy_secret", "");
        editor.putBoolean("proxy_enabled", true);
        editor.commit();

        SharedConfig.currentProxy = info;
        SharedConfig.saveProxyList();
        ConnectionsManager.setProxySettings(true, LOCAL_HOST, port, "", "", "");
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
    }

    /** UI thread. Removes ZG's entry from the proxy list; if it was active, tgnet goes direct again. */
    private void detachTelegramProxy() {
        SharedConfig.loadProxyList();
        boolean changed = false;
        for (int a = SharedConfig.proxyList.size() - 1; a >= 0; a--) {
            SharedConfig.ProxyInfo info = SharedConfig.proxyList.get(a);
            if (isZgProxy(info)) {
                SharedConfig.deleteProxy(info); // clears "mainconfig" and calls setProxySettings(false) when it was current
                changed = true;
            }
        }
        if (changed) {
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
        }
    }
}
