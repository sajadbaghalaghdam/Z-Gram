/*
 * ZG: one saved VLESS server, as shown in Telegram's proxy list.
 *
 * The raw vless:// link is the source of truth: the Rust core parses it. Java only validates the
 * essentials (scheme, uuid, host, port, and a security/transport combination the core implements)
 * so the user gets a clear error instead of a generic "start failed", and reads the #fragment for
 * the display name.
 *
 * Supported stacks, kept in step with zg-core's config.rs:
 *
 *   security=reality + type=tcp|raw + headerType=none    (needs pbk; flow may be xtls-rprx-vision)
 *   security=none    + type=tcp|raw + headerType=none|http
 *   security=none    + type=ws                            (path/host are read by the core)
 */

package org.zsudo.zg;

import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.R;

import java.util.Locale;
import java.util.UUID;

public final class ZgConfig {

    public long id;
    public String name;
    public String uri;
    public String fragmentSpec;

    public ZgConfig(long id, String name, String uri, String fragmentSpec) {
        this.id = id;
        this.name = name == null ? "" : name;
        this.uri = uri == null ? "" : uri.trim();
        this.fragmentSpec = TextUtils.isEmpty(fragmentSpec) ? ZgProxyController.DEFAULT_FRAGMENT_SPEC : fragmentSpec.trim();
    }

    // ---------------------------------------------------------------- validation

    /** Lower-cased, trimmed query parameter, or {@code fallback} when absent/blank. */
    private static String param(Uri uri, String key, String fallback) {
        String v;
        try {
            v = uri.getQueryParameter(key);
        } catch (Exception e) {
            // Opaque uri: no query component at all.
            return fallback;
        }
        if (v == null) {
            return fallback;
        }
        v = v.trim();
        // Locale.ROOT: a Turkish locale would otherwise fold "REALITY" to "realıty".
        return v.isEmpty() ? fallback : v.toLowerCase(Locale.ROOT);
    }

    /**
     * Checks the essentials a link cannot work without, and that its security/transport
     * combination is one the Rust core actually implements.
     *
     * @return 0 when the link looks usable, otherwise the string resource describing what is wrong.
     */
    public static int checkUri(String rawUri) {
        if (rawUri == null) {
            return R.string.ZgLinkErrorScheme;
        }
        final String trimmed = rawUri.trim();
        if (trimmed.length() <= 8 || !trimmed.regionMatches(true, 0, "vless://", 0, 8)) {
            return R.string.ZgLinkErrorScheme;
        }
        final Uri parsed;
        try {
            parsed = Uri.parse(trimmed);
        } catch (Exception e) {
            return R.string.ZgLinkErrorScheme;
        }
        String userInfo;
        String host;
        int port;
        try {
            userInfo = parsed.getUserInfo();
            host = parsed.getHost();
            port = parsed.getPort();
        } catch (Exception e) {
            return R.string.ZgLinkErrorScheme;
        }
        if (TextUtils.isEmpty(userInfo)) {
            return R.string.ZgLinkErrorUuid;
        }
        try {
            UUID.fromString(userInfo);
        } catch (Exception e) {
            return R.string.ZgLinkErrorUuid;
        }
        if (TextUtils.isEmpty(host)) {
            return R.string.ZgLinkErrorHost;
        }
        if (port <= 0 || port > 65535) {
            return R.string.ZgLinkErrorPort;
        }
        // v2rayN/v2rayNG omit a parameter rather than writing its default, so absent == default.
        final String security = param(parsed, "security", "none");
        final String encryption = param(parsed, "encryption", "none");
        final String type = param(parsed, "type", "tcp");
        final String headerType = param(parsed, "headerType", "none");
        final String flow = param(parsed, "flow", "");
        String publicKey;
        try {
            publicKey = parsed.getQueryParameter("pbk");
        } catch (Exception e) {
            publicKey = null;
        }

        if (!"none".equals(encryption)) {
            return R.string.ZgLinkErrorReality;
        }
        final boolean reality = "reality".equals(security);
        if (!reality && !"none".equals(security)) {
            // security=tls and anything else: not implemented by the core.
            return R.string.ZgLinkErrorReality;
        }
        final boolean tcp = "tcp".equals(type) || "raw".equals(type);
        final boolean ws = "ws".equals(type) || "websocket".equals(type);
        if (!tcp && !ws) {
            // grpc, kcp, httpupgrade, xhttp/splithttp, h2, ...
            return R.string.ZgLinkErrorReality;
        }
        final boolean httpHeader = "http".equals(headerType);
        if (!httpHeader && !"none".equals(headerType)) {
            return R.string.ZgLinkErrorReality;
        }
        if (httpHeader && !tcp) {
            // The fake-HTTP header obfuscation only exists on a raw TCP stream.
            return R.string.ZgLinkErrorReality;
        }
        if (reality) {
            // REALITY is TCP-only here, carries no header obfuscation, and needs its public key.
            if (ws || httpHeader) {
                return R.string.ZgLinkErrorReality;
            }
            if (TextUtils.isEmpty(publicKey)) {
                return R.string.ZgLinkErrorPbk;
            }
        } else if (!flow.isEmpty()) {
            // XTLS-Vision needs a TLS-like layer underneath; xray refuses it too.
            return R.string.ZgLinkErrorReality;
        }
        if (!flow.isEmpty() && !"xtls-rprx-vision".equals(flow)) {
            return R.string.ZgLinkErrorReality;
        }
        return 0;
    }

    public static boolean isValidUri(String rawUri) {
        return checkUri(rawUri) == 0;
    }

    public boolean isValid() {
        return isValidUri(uri);
    }

    // ---------------------------------------------------------------- display

    /** "host:port" of the link, never the uuid. Empty when the link cannot be parsed. */
    public static String hostPort(String rawUri) {
        if (TextUtils.isEmpty(rawUri)) {
            return "";
        }
        try {
            Uri parsed = Uri.parse(rawUri.trim());
            String host = parsed.getHost();
            if (TextUtils.isEmpty(host)) {
                return "";
            }
            int port = parsed.getPort();
            return port > 0 ? host + ":" + port : host;
        } catch (Exception e) {
            return "";
        }
    }

    public String hostPort() {
        return hostPort(uri);
    }

    /** The #fragment of the link, or host:port when it has none. */
    public static String nameFromUri(String rawUri) {
        if (!TextUtils.isEmpty(rawUri)) {
            try {
                String fragment = Uri.parse(rawUri.trim()).getFragment();
                if (!TextUtils.isEmpty(fragment) && !TextUtils.isEmpty(fragment.trim())) {
                    return fragment.trim();
                }
            } catch (Exception ignore) {
            }
        }
        String hostPort = hostPort(rawUri);
        return TextUtils.isEmpty(hostPort) ? "VLESS" : hostPort;
    }

    public String getTitle() {
        return TextUtils.isEmpty(name) ? nameFromUri(uri) : name;
    }

    // ---------------------------------------------------------------- persistence

    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("id", id);
        object.put("name", name);
        object.put("uri", uri);
        object.put("fragment", fragmentSpec);
        return object;
    }

    public static ZgConfig fromJson(JSONObject object) {
        long id = object.optLong("id", 0);
        String uri = object.optString("uri", "");
        if (id == 0 || TextUtils.isEmpty(uri)) {
            return null;
        }
        return new ZgConfig(id, object.optString("name", ""), uri, object.optString("fragment", ZgProxyController.DEFAULT_FRAGMENT_SPEC));
    }
}
