/*
 * ZG: JNI bridge to the Rust proxy core (libzgcore.so).
 *
 * Symbol contract implemented on the Rust side (crate zg-core):
 *   Java_org_zsudo_zg_ZgCore_start / _stop / _isRunning / _lastError / _version / _stats
 *   Java_org_zsudo_zg_ZgCore_notifyResume
 *
 * Do not call these directly from app code; go through ZgProxyController, which
 * loads the library lazily and degrades gracefully when it is missing.
 */

package org.zsudo.zg;

public final class ZgCore {
    static { System.loadLibrary("zgcore"); }

    private ZgCore() {
    }

    /**
     * Starts the local SOCKS5 listener on 127.0.0.1 and the VLESS/REALITY tunnel behind it.
     *
     * @param vlessUrl     full vless:// link (uuid@host:port?security=reality&...)
     * @param listenPort   port to bind, or 0 to let the core pick a free one
     * @param fragmentSpec TLS ClientHello fragmentation spec, e.g. "tlshello,10-20,10-20"
     * @return bound port (> 0) on success, negative error code otherwise (see lastError())
     */
    public static native int start(String vlessUrl, int listenPort, String fragmentSpec);

    public static native void stop();

    public static native boolean isRunning();

    public static native String lastError();

    public static native String version();

    /** @return [active_connections, bytes_up, bytes_down] */
    public static native long[] stats();

    /**
     * The app just came to the foreground and tgnet is about to reconnect through the listener.
     * The core drops tunnels it considers stale and pre-dials one REALITY tunnel so the first
     * SOCKS5 CONNECT is answered in ~0 RTT. Must return immediately (UI thread caller); the
     * work happens on the core runtime.
     */
    public static native void notifyResume();
}
