# Technical reference

Deep detail for debugging and future work. Novice walkthroughs live in the parent `docs/` folder.

---

## 1. End-to-end architecture

```
                    ┌─────────────────────────────────────┐
                    │           Android device            │
  VPS/client ──────▶│  :8554  ForwarderServer (listen)    │
  (WG or LAN)       │           │                         │
                    │           ▼                         │
                    │  TCP connect → dvrHost:rtspPort     │
                    │           │                         │
                    └───────────┼─────────────────────────┘
                                ▼
                         Hikvision DVR :554
```

Two independent TCP sockets; user-space byte copy. Not IP forwarding. Works unrooted because the app is the connection destination.

### Why a relay (not routing)

Android does not forward packets from a VPN interface onto Wi‑Fi/Ethernet without root. If the app owns `:8554`, inbound tunnel traffic terminates in-process; the app then opens a normal LAN socket to the DVR.

### Binding rules

| Socket | Bind / protect |
|---|---|
| Listen `ServerSocket` | `0.0.0.0:listenPort` — **all interfaces**. Do not bind only to the tunnel IP (interface may be absent at boot). |
| Outbound DVR `Socket` | Optional LAN `Network.bindSocket` today; later `VpnService.protect(socket)` via `prepareDvrSocket`. |

---

## 2. Ports and addresses

| Role | Typical value | Notes |
|---|---|---|
| DVR HTTP / ISAPI | `80` | Digest auth |
| DVR RTSP | `554` | Real LAN port (ignore NAT “external” ports in DVR UI) |
| App relay listen | `8554` | socat replacement |
| WireGuard tunnel (example) | `10.8.0.2` | Box address on WG; VPS connects here |
| Site LAN (example) | `192.168.0.0/24` | Router `192.168.0.1`, DVR often `.100` |

### RTSP URL shapes

Direct to DVR (LAN only):

```
rtsp://user:pass@<dvr-ip>:554/Streaming/Channels/<id>
```

Via relay (LAN test or tunnel):

```
rtsp://user:pass@<box-ip>:8554/Streaming/Channels/<id>
```

`<id> = cameraNumber * 100 + streamType` (`1` main, `2` sub). Example: camera 1 sub-stream → `102`.

Credentials must be URL-encoded in players; the app encodes when building URLs for LibVLC.

---

## 3. Android components (relay)

| Class | Package | Responsibility |
|---|---|---|
| `ForwarderServer` | `forwarder` | Accept loop, connect to DVR, bidirectional `pipe`, cap 8 sessions |
| `ForwarderService` | `service` | Foreground service, wake lock, notification, `START_STICKY` |
| `BootReceiver` | `service` | Start relay after `BOOT_COMPLETED` if previously enabled |
| `RelayActivity` | `ui` | Installer status UI |
| `ConfigRepository` | `data` | EncryptedSharedPreferences; DVR + relay settings |
| `RelaySettings` | `data` | `listenPort` (default 8554), `enabled` |
| `DvrConfig` | `data` | Single source of truth for DVR host / RTSP port |

### Manifest permissions (relay-related)

- `INTERNET`
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE`
- `WAKE_LOCK`
- `RECEIVE_BOOT_COMPLETED`
- `POST_NOTIFICATIONS` (runtime on API 33+)
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`

Service type: `specialUse` with property explaining continuous local relay.

### ForwarderServer constants

| Constant | Value | Rationale |
|---|---|---|
| `MAX_CONNECTIONS` | 8 | DVR session budget; reject rather than queue |
| `BUFFER_SIZE` | 32 KiB | Fixed buffer; never accumulate video in memory/storage |
| `SOCKET_TIMEOUT_MS` | 60_000 | Idle/dead peer recovery |
| `DVR_CONNECT_TIMEOUT_MS` | 5_000 | Fail fast if DVR down |
| `tcpNoDelay` | true | Avoid Nagle latency on small RTSP messages |
| Half-close | `shutdownOutput` | RTSP teardown expects directional close |

### Settings persistence

Same encrypted store as Phase 1 (`dvr_secure_prefs`):

| Key | Default |
|---|---|
| `listen_port` | `8554` |
| `relay_enabled` | `false` |

DVR host/port are **not** duplicated — loaded from existing `DvrConfig`.

---

## 4. Phase 1 app (live view) — short map

| Area | Notes |
|---|---|
| Player | LibVLC `libvlc-all:3.6.5`, `:rtsp-tcp` |
| Discovery | ISAPI `/ISAPI/Streaming/channels`, HTTP Digest |
| Wi‑Fi binding | `WifiNetworkBinder` — avoid cellular when camera Wi‑Fi has no internet |
| UI | Views + View Binding; Setup → LiveView / Grid; Logs |

Build:

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 5. Reliability requirements (non-negotiable)

1. **Foreground notification** — otherwise Android kills the relay.
2. **`START_STICKY`** — restart after unexpected kill.
3. **Partial wake lock** — CPU available screen-off.
4. **Ignore battery optimisations** — without this, classic “dies overnight, works when touched.”
5. **Always close both sockets in `finally`** — leaked FDs exhaust the process over days.
6. **Never write video to eMMC** — destroy flash lifespan.
7. **Cap concurrency** — clear reject > mysterious hang.

### Acceptance tests

| # | Test | Pass |
|---|---|---|
| 1 | Parity with socat | Same `ffprobe` output via tunnel IP |
| 2 | MediaMTX unchanged | Browser still plays |
| 3 | Screen off 30m | Still works |
| 4 | App backgrounded | Still works |
| 5 | Kill from recents | Restarts ≤ ~30s |
| 6 | Reboot | Auto-starts if enabled |
| 7 | 50 open/close cycles | FD count returns to baseline (`adb shell ls /proc/$(pidof …)/fd \| wc -l`) |
| 8 | Four cameras | All play |
| 9 | Ninth connection | Rejected cleanly |
| 10 | DVR unplug/replug | Recovers on next connect |
| 11 | Overnight untouched | Still up (battery test) |

---

## 6. Operational commands

Local relay test:

```bash
ffprobe -rtsp_transport tcp \
  rtsp://admin:PASSWORD@<box-lan-ip>:8554/Streaming/Channels/102
```

Tunnel parity (from VPS):

```bash
ffprobe -rtsp_transport tcp \
  rtsp://admin:PASSWORD@10.8.0.2:8554/Streaming/Channels/102
```

Install debug build:

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 7. Security notes

- Prefer a non-admin DVR account if ISAPI/RTSP allow it; change default passwords.
- Never log passwords; `AppLog` redacts URL userinfo and `password=` patterns.
- EncryptedSharedPreferences for credentials.
- Do not expose DVR RTSP/HTTP on the public internet; use the tunnel + relay path.

---

## 8. Roadmap hooks

| Future work | Hook already present |
|---|---|
| Embed WireGuard / `VpnService` | `ForwarderServer(prepareDvrSocket = …)` → call `protect(socket)` |
| Snapshots | `IsapiClient.snapshotUrl` stub |
| Alternate players | `VideoSource` interface |

Out of scope for the relay milestone: WireGuard embedding, motion, cloud APIs, provisioning, live-view UI redesign.
