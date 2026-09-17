# Z Gram

A fork of [Telegram for Android](https://github.com/DrKLO/Telegram) (v12.10.1) that fixes several
long-standing upstream bugs, cuts battery drain and UI jank, and embeds a VLESS/REALITY proxy
client written in Rust so the app can tunnel its own traffic without a VPN service.

Everything here is built on Telegram's GPLv2 source. The fixes below are described in enough detail
to be re-implemented, ported, or argued with.

---

## The headline fix: half your chat list disappears after a fresh login

This is the bug that motivated the fork. On an account with many chats, logging in on a new device
leaves the chat list permanently incomplete — and it stays incomplete. A chat only appears once
someone sends you a new message, because the update creates the dialog locally. Pinned chats and
old chats are present, while people you talk to every day are missing.

**Mechanism.** `messages.getDialogs` returns dialogs ordered by top-message date, and the client
pages through them by sending the previous page's oldest position as `offset_date` / `offset_id` /
`offset_peer`. Upstream picks that cursor as *the oldest dialog in the page*.

That is wrong for **monoforum peers** — the "direct messages" peer belonging to a channel. Such a
dialog is placed in the list by its channel's activity, while its own `top_message` can be months
older. One of them near the top of the list therefore lands on page 1 of every fresh sweep, and the
client hands the server a cursor months older than the page actually reached. The server resumes
from there, and **every dialog in between is skipped and never requested again**.

On the account this was diagnosed on: page 1 returned 100 dialogs and ended at a message dated "a
minute ago", but entry 65 was a monoforum peer whose top message was six months old. The cursor
jumped there, the remaining pages walked down contiguously from that point, and the sweep finished
"successfully" with 657 of the 1221 dialogs the server itself reported. The 564-dialog gap is
exactly the window that was jumped.

**Fix.** Page from the last dialog the server actually returned, in the server's own array order,
restricted to the folder being loaded. The array's last element is by definition no older than the
array's minimum, so the new cursor can only ever fetch *more* dialogs, never fewer. If it fails to
move strictly backwards, the old rule is used as a fallback, so end-of-list detection is unchanged.
This matches what TDLib does in `MessagesManager::on_get_dialogs`.

**Plus a repair for accounts already poisoned.** An account that finished a broken sweep has
`Integer.MAX_VALUE` persisted as its offset and never asks the server again, so the fix alone would
not help it. Such a folder now re-pages once with the corrected cursor, driving itself to the end
of the list instead of stopping at upstream's 400-dialog auto-continue cap, and then backfills the
stragglers with `messages.getPeerDialogs` over contacts and chat-folder members that have no local
dialog. The backfill merges only dialogs with a real `top_message`, is bounded by a persisted flag,
and can only add rows.

### Other dialog-loading defects fixed along the way

| Defect | Effect |
|---|---|
| End-of-list inferred from a **short page** | `messages.getDialogs` never promises to fill `limit`; with `exclude_pinned` short pages are routine mid-list, so the list truncated at an arbitrary point |
| End-of-list compared **message ids only** | Ids are per-peer, so an unrelated channel whose top message shared a numeric id was read as "end of list", and the truncation was persisted |
| The archive's end-of-list read from **folder 0's offsets** | Archive paging stopped according to the wrong folder |
| `resetDialogs()` wrote folder 0's offset into **folder 1** | The archive's paging cursor was clobbered after a reset |
| A failed dialogs reset never cleared `resetingDialogs` | `loadDialogs()` stayed wedged for the rest of the process |

---

## Other upstream bugs fixed

**Endless "Connected / Connecting" through a proxy.** `Connection::connect` chooses IPv4 or IPv6
from the strategy computed for the *local* network, picking IPv6 for roughly one socket in three
whenever the phone has it. Behind a proxy, the address family that matters is the one the *proxy*
can reach, and most proxy servers have no IPv6 upstream. It also fails in the worst possible way: a
SOCKS5 CONNECT is answered before the far end is known to be reachable, so tgnet counts the socket
as connected, sends its handshake into a black hole, and waits out its timeout before retrying.
IPv4 is now pinned whenever a proxy is configured; unproxied behaviour is untouched.

---

## Responsiveness

**~30 s before new messages appear after resuming the app**, measured against Telegram X on the
same account and network, which takes 3–4 s. Nothing is idle-waiting: tgnet starts connecting
immediately, and the time is spent *inside* the connect attempts. Against a stale path the ladder
is a 12 s socket timeout, 1 s, 8 s, 1 s, 8 s before the third attempt even starts, and nothing calls
`getDifference` on resume at all.

* A bounded "fast" window after a resume or network change: a 5 s connect budget for the first two
  attempts, 300 ms between them instead of 1 s, and no address rotation while the tunnel is still
  being probed.
* On resume the generic socket is pinged immediately with a 3 s watchdog, and a dead push socket is
  noticed in 3 s instead of 30 s.
* `getDifference` is forced on resume, debounced to 5 s — the same effect TDLib gets by injecting a
  synthetic `updatesTooLong`.

Nothing in the background path changed, and the only push-related change makes a dead push socket
be noticed sooner, so notifications can only arrive earlier.

**Folder switching and typing.** On a 120 Hz panel the frame budget is 8.3 ms, and both paths blew
through it:

* per tab, per frame during the switch animation: a new `PorterDuffColorFilter`, a `String.format`
  and a `measureText`, all inside `onDraw`
* per keystroke: six full copies of the message text, an O(N) code-point scan, three whole-message
  span scans whose result was discarded, a `SharedPreferences` read, and a **quadratic**
  `StringBuilder.insert(0, ch)` backward scan in mention/hashtag search — about 125,000 character
  moves on a 500-character draft, getting worse as the message grows
* a blocking `SharedPreferences.commit()` on the UI thread while the keyboard opens
* `DiffUtil.calculateDiff` running on the frame thread in release builds, because the background
  path was gated on a debug-only flag

All of the above are fixed. The folder-switch re-sort and the blanket `notifyDataSetChanged` were
deliberately **not** changed: no behaviour-identical variant was found, and a mis-ordered chat list
is worse than a dropped frame.

---

## Battery

Two audit rounds, 27 changes. The honest headline first: on a modern phone much of "Telegram used
N% of your battery" is the *display*, attributed to whichever app was in the foreground. What is
genuinely avoidable:

* **A frame callback that never stopped.** `Choreographer60FpsContent` re-armed itself
  unconditionally, so once any animated sticker registered once, the main thread was woken at the
  panel refresh rate for the entire process lifetime — including while the user was in another app.
  It is now demand-driven, and released while the app has no started activities.
* **A stalled transfer pinned the app awake.** `dontSleep` had no liveness condition, so a single
  stuck upload or download kept the process out of its paused state indefinitely. It is now bounded
  by observed transfer progress.
* **Reconnect backoff capped at 400 ms.** On a captive portal or a DPI-blocked network, every
  connection object fired a SYN every 400 ms indefinitely, pinning the radio. Now real exponential
  backoff with jitter, reset instantly on network-available/resume so recovery stays immediate.
* **A ping every 3 minutes with a broken jitter.** The jitter was ±20 *milliseconds* where seconds
  were clearly intended, and the client pinged more than twice as often as its own 7-minute
  disconnect window required.
* **`help.getAppConfig` polled every 4 minutes, forever**, each poll waking the radio for config
  that changes weekly.
* **Three motion sensors at ~33 Hz whenever any chat was open**, whether or not a voice message was
  anywhere in sight.
* **Live location requested GNSS fixes at 1 Hz** while transmitting every 20–30 s.
* **The app forced the panel to its maximum refresh rate**, defeating adaptive refresh while you
  read a static screen.
* Defaults: a balanced LiteMode preset the server cannot overwrite, power saving from 35% instead
  of 10%, mobile autodownload photos-only, nothing on roaming, and "Repeat notifications"
  defaulting to Never.

Every change was made under one rule: **notification delivery must never get slower**. The push
connection, its ping cadence, its disconnect window and the internal push path are untouched.

Worth knowing for builds without Google Play Services: the app keeps a foreground service for
notifications, so the process is never frozen by the OS and stray timers really do run 24/7. The
cadence fixes matter more there than in stock Telegram.

---

## Built-in proxy (`zg-core`)

A pure-Rust VLESS client that runs **inside the app process** and exposes a local SOCKS5 endpoint
that Telegram's own proxy setting points at. No VpnService, no tun device, no second app — only
Telegram's own traffic is tunnelled.

Supported: **REALITY** (hand-rolled TLS 1.3 with a Chrome-like ClientHello, X25519 + ML-KEM-768,
certificate binding verified), **XTLS-Vision**, **WebSocket**, plaintext VLESS (`security=none`),
fake-HTTP header obfuscation, and ClientHello **fragmentation**.

Two findings worth repeating:

* **Fragmentation needs a piece-count cap.** The ClientHello is ~1.5 KB because it carries an
  ML-KEM key share, so 10–20 byte pieces with a 10–20 ms pause each means ~100 sleeping writes —
  over three seconds per connection, and Telegram opens several. xray caps this with `maxSplit`, and
  so does this client. Default `tlshello,40-80,5-10,4-6`, measured at 0.55 s per connection against
  0.47 s with no fragmentation at all.
* **The TLS record reader must be cancellation-safe.** The relay races both directions in a
  `tokio::select!`, and select drops the losing future. A reader built on `read_exact` lost the
  bytes it had already consumed, and record framing was gone permanently — surfacing in the app as
  `TLParseException: can't parse magic ...`. Partial records now live in the reader struct. This
  only reproduces under sustained *bidirectional, non-TLS* traffic: HTTPS through the tunnel takes
  the Vision splice path and never exercises it, which is why it survived ordinary testing.

In the app: **Settings → Data and Storage → Proxy → Add Proxy → Xray (VLESS / REALITY)**. Several
servers can be saved and switched with one tap, alongside normal SOCKS5/MTProto proxies.

---

## Settings added

| Setting | Where | Default |
|---|---|---|
| Xray (VLESS / REALITY) servers | Proxy settings | none |
| Fragmentation spec, per server | ZG Proxy details | `tlshello,40-80,5-10,4-6` |
| Display refresh rate | ZG Proxy → Battery | Adaptive (system) |
| Device performance class | ZG Proxy → Battery | Automatic |

---

## Building

Android Studio 2025.1.4, Android SDK 36, NDK 27.2.12479018, JDK 17, Gradle 8.11.1, and for the
proxy core a Rust toolchain with `cargo-ndk`.

```bash
# proxy core -> TMessagesProj/src/main/jniLibs/<abi>/libzgcore.so
cd zg-core && cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 \
    -o ../TMessagesProj/src/main/jniLibs build --release -p zg_core --features jni

# app
gradle :TMessagesProj_AppStandalone:assembleAfatStandalone
```

**You must supply your own credentials.** `BuildVars.APP_ID` / `APP_HASH` are placeholders here;
get your own at <https://core.telegram.org/api/obtaining_api_id>. Signing is read from an untracked
`keystore.properties` at the repo root (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`),
falling back to upstream's public debug key when absent — note that Google Play Protect flags APKs
signed with that key, which is why release builds here use their own.

## Licence and attribution

GPLv2, as upstream. This is an unofficial fork: not affiliated with or endorsed by Telegram, it does
not use Telegram's name or logo as its own identity, and per Telegram's requirements it ships with
its own `api_id`. If you redistribute a build, publish your source too.
