/*
 * ZG: glue between the Rust proxy core (ZgCore) and Telegram's proxy settings.
 *
 * The core is an in-process SOCKS5 listener on 127.0.0.1 that tunnels everything through the
 * user's VLESS/REALITY server. It holds no wakelock and runs no foreground service: it lives and
 * dies with the application process, exactly like tgnet itself. When it is up we point tgnet at it
 * through the regular proxy machinery (SharedConfig.currentProxy + "mainconfig" prefs +
 * ConnectionsManager.setProxySettings), so the stock proxy UI, ping check and proxy rotation keep
 * working and the state survives process restarts (ConnectionsManager.init() re-reads the prefs).
 *
 * The user can keep several servers (ZgConfig) in the "zgconfig" prefs and switch between them
 * from the proxy list; exactly one of them is active at a time, or none.
 */

package org.zsudo.zg;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;

import java.util.ArrayList;

public final class ZgProxyController {

    public static final String PREFS_NAME = "zgconfig";
    public static final String LOCAL_HOST = "127.0.0.1";
    public static final String DEFAULT_FRAGMENT_SPEC = "tlshello,40-80,5-10,4-6";

    private static final String ERROR_INVALID_LINK = "invalid vless:// link";

    private static final String KEY_CONFIGS = "configs";
    private static final String KEY_ACTIVE_ID = "active_id";
    private static final String KEY_FRAGMENT_SPEC = "fragment_spec";
    private static final String KEY_LISTEN_PORT = "listen_port";
    private static final String KEY_LAST_PORT = "last_port";
    // single-server layout used before the config list; read once, then dropped
    private static final String KEY_LEGACY_URL = "vless_url";
    private static final String KEY_LEGACY_ENABLED = "enabled";

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
    private final Object configLock = new Object();
    private ArrayList<ZgConfig> configs;
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

    /** Default spec new servers start with; also used when a server has none of its own. */
    public String getDefaultFragmentSpec() {
        String spec = prefs().getString(KEY_FRAGMENT_SPEC, DEFAULT_FRAGMENT_SPEC);
        return TextUtils.isEmpty(spec) ? DEFAULT_FRAGMENT_SPEC : spec;
    }

    public void setDefaultFragmentSpec(String spec) {
        prefs().edit().putString(KEY_FRAGMENT_SPEC, TextUtils.isEmpty(spec) ? DEFAULT_FRAGMENT_SPEC : spec.trim()).apply();
    }

    /** Spec of the active server, or the default one when nothing is active. */
    public String getActiveFragmentSpec() {
        ZgConfig config = getActiveConfig();
        if (config != null && !TextUtils.isEmpty(config.fragmentSpec)) {
            return config.fragmentSpec;
        }
        return getDefaultFragmentSpec();
    }

    /** UI thread. Stores the spec on the active server, or as the default when none is active. */
    public void setActiveFragmentSpec(String spec) {
        String value = TextUtils.isEmpty(spec) ? DEFAULT_FRAGMENT_SPEC : spec.trim();
        ZgConfig config = getActiveConfig();
        if (config == null) {
            setDefaultFragmentSpec(value);
            return;
        }
        config.fragmentSpec = value;
        addOrUpdateConfig(config);
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

    // ---------------------------------------------------------------- server list

    private void loadConfigsLocked() {
        if (configs != null) {
            return;
        }
        configs = new ArrayList<>();
        SharedPreferences preferences = prefs();
        String json = preferences.getString(KEY_CONFIGS, null);
        if (json == null) {
            migrateLegacyLocked(preferences);
            return;
        }
        try {
            JSONArray array = new JSONArray(json);
            for (int a = 0, count = array.length(); a < count; a++) {
                JSONObject object = array.optJSONObject(a);
                if (object == null) {
                    continue;
                }
                ZgConfig config = ZgConfig.fromJson(object);
                if (config != null) {
                    configs.add(config);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /** Turns the pre-list single "vless_url" into the first entry of the list, once. */
    private void migrateLegacyLocked(SharedPreferences preferences) {
        String legacyUrl = preferences.getString(KEY_LEGACY_URL, "");
        long activeId = 0;
        if (ZgConfig.isValidUri(legacyUrl)) {
            ZgConfig config = new ZgConfig(nextIdLocked(), ZgConfig.nameFromUri(legacyUrl), legacyUrl, getDefaultFragmentSpec());
            configs.add(config);
            if (preferences.getBoolean(KEY_LEGACY_ENABLED, false)) {
                activeId = config.id;
            }
        }
        saveConfigsLocked();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putLong(KEY_ACTIVE_ID, activeId);
        editor.remove(KEY_LEGACY_URL);
        editor.remove(KEY_LEGACY_ENABLED);
        editor.apply();
    }

    private void saveConfigsLocked() {
        JSONArray array = new JSONArray();
        for (int a = 0, count = configs.size(); a < count; a++) {
            try {
                array.put(configs.get(a).toJson());
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        prefs().edit().putString(KEY_CONFIGS, array.toString()).apply();
    }

    private long nextIdLocked() {
        long id = System.currentTimeMillis();
        boolean taken = true;
        while (taken) {
            taken = false;
            for (int a = 0, count = configs.size(); a < count; a++) {
                if (configs.get(a).id == id) {
                    taken = true;
                    id++;
                    break;
                }
            }
        }
        return id;
    }

    /** Copies of the saved servers, in the order the user added them. */
    public ArrayList<ZgConfig> getConfigs() {
        ArrayList<ZgConfig> result = new ArrayList<>();
        synchronized (configLock) {
            loadConfigsLocked();
            for (int a = 0, count = configs.size(); a < count; a++) {
                ZgConfig config = configs.get(a);
                result.add(new ZgConfig(config.id, config.name, config.uri, config.fragmentSpec));
            }
        }
        return result;
    }

    public ZgConfig getConfig(long id) {
        if (id == 0) {
            return null;
        }
        synchronized (configLock) {
            loadConfigsLocked();
            for (int a = 0, count = configs.size(); a < count; a++) {
                ZgConfig config = configs.get(a);
                if (config.id == id) {
                    return new ZgConfig(config.id, config.name, config.uri, config.fragmentSpec);
                }
            }
        }
        return null;
    }

    public long getActiveConfigId() {
        return prefs().getLong(KEY_ACTIVE_ID, 0);
    }

    public ZgConfig getActiveConfig() {
        return getConfig(getActiveConfigId());
    }

    /** True when one of the saved servers is selected, i.e. the ZG tunnel should be up. */
    public boolean isEnabled() {
        return getActiveConfig() != null;
    }

    /**
     * UI thread. Adds a server (id 0) or replaces the stored copy of an existing one.
     *
     * @return the stored server, with its id filled in.
     */
    public ZgConfig addOrUpdateConfig(ZgConfig config) {
        synchronized (configLock) {
            loadConfigsLocked();
            if (config.id == 0) {
                config.id = nextIdLocked();
                configs.add(config);
            } else {
                boolean found = false;
                for (int a = 0, count = configs.size(); a < count; a++) {
                    if (configs.get(a).id == config.id) {
                        configs.set(a, config);
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    configs.add(config);
                }
            }
            saveConfigsLocked();
        }
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.zgProxyStateChanged);
        return config;
    }

    /** UI thread. Removes a server; stops the core first when it was the active one. */
    public void deleteConfig(long id, ResultCallback callback) {
        boolean wasActive = getActiveConfigId() == id;
        synchronized (configLock) {
            loadConfigsLocked();
            for (int a = configs.size() - 1; a >= 0; a--) {
                if (configs.get(a).id == id) {
                    configs.remove(a);
                }
            }
            saveConfigsLocked();
        }
        if (wasActive) {
            deactivate(callback);
        } else {
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.zgProxyStateChanged);
            if (callback != null) {
                callback.onResult(isRunning(), "");
            }
        }
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
        ZgConfig config = getActiveConfig();
        if (config == null) {
            return;
        }
        if (!isNativeAvailable()) {
            lastError = nativeLoadError;
            AndroidUtilities.runOnUIThread(this::detachTelegramProxy);
            return;
        }
        if (!config.isValid()) {
            lastError = ERROR_INVALID_LINK;
            AndroidUtilities.runOnUIThread(this::detachTelegramProxy);
            return;
        }
        startAsync(null);
    }

    /**
     * UI thread. Makes this server the active one and points tgnet at the local listener. On
     * failure nothing stays selected, so the list never shows a checked server that is not up.
     */
    public void activate(ZgConfig config, ResultCallback callback) {
        if (config == null) {
            deactivate(callback);
            return;
        }
        if (!isNativeAvailable()) {
            lastError = nativeLoadError;
            if (callback != null) {
                callback.onResult(false, lastError);
            }
            return;
        }
        if (!config.isValid()) {
            lastError = ERROR_INVALID_LINK;
            if (callback != null) {
                callback.onResult(false, lastError);
            }
            return;
        }
        prefs().edit().putLong(KEY_ACTIVE_ID, config.id).apply();
        startAsync((ok, error) -> {
            if (!ok) {
                prefs().edit().putLong(KEY_ACTIVE_ID, 0).apply();
            }
            if (callback != null) {
                callback.onResult(ok, error);
            }
        });
    }

    /**
     * UI thread. Stops the core and takes its entry out of the proxy list. Called when the user
     * picks another kind of proxy, turns proxies off, or deletes the active server.
     */
    public void deactivate(ResultCallback callback) {
        boolean wasSelected = getActiveConfigId() != 0;
        prefs().edit().putLong(KEY_ACTIVE_ID, 0).apply();
        if (!running && !busy) {
            detachTelegramProxy();
            if (wasSelected) {
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.zgProxyStateChanged);
            }
            if (callback != null) {
                callback.onResult(false, "");
            }
            return;
        }
        stopAsync(callback);
    }

    /** Re-applies the current settings (link / fragment spec / port) if a server is selected. */
    public void restartActive(ResultCallback callback) {
        ZgConfig config = getActiveConfig();
        if (config != null) {
            activate(config, callback);
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
            final ZgConfig config = getActiveConfig();
            if (config == null || !config.isValid()) {
                lastError = ERROR_INVALID_LINK;
                return -1;
            }
            final String url = config.uri;
            final String spec = TextUtils.isEmpty(config.fragmentSpec) ? getDefaultFragmentSpec() : config.fragmentSpec;
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
