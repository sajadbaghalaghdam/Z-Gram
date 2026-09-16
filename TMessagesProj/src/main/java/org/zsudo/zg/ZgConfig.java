/*
 * ZG: one saved VLESS/REALITY server, as shown in Telegram's proxy list.
 *
 * The raw vless:// link is the source of truth: the Rust core parses it. Java only validates the
 * essentials (scheme, uuid, host, port, security=reality with a pbk) so the user gets a clear
 * error instead of a generic "start failed", and reads the #fragment for the display name.
 */

package org.zsudo.zg;

import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.R;

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

    /**
     * Checks the essentials a REALITY link cannot work without.
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
        String security;
        String publicKey;
        try {
            security = parsed.getQueryParameter("security");
            publicKey = parsed.getQueryParameter("pbk");
        } catch (Exception e) {
            // opaque uri: no query at all
            return R.string.ZgLinkErrorReality;
        }
        if (security == null || !"reality".equalsIgnoreCase(security.trim())) {
            return R.string.ZgLinkErrorReality;
        }
        if (TextUtils.isEmpty(publicKey)) {
            return R.string.ZgLinkErrorPbk;
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
