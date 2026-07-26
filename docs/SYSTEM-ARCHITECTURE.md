# Truvend Cam — System Architecture (single overview)

**Read this first** if you want the whole system in one place: tools, settings, data flow, and how the pieces fit.

Step-by-step install guides still live under [`docs/`](README.md) (01–06). This page is the **holistic map**.

**Status (July 2026):** Local live view ✓ · In-app RTSP relay ✓ · WireGuard = standalone app on the box · MediaMTX + WG on VPS ✓

---

## 1. What we are building

Watch Hikvision DVR cameras:

1. **On site** — on an Android phone/TV box on a private Wi‑Fi (no customer internet required).
2. **Off site** — in a browser, via a private WireGuard tunnel to our VPS (DVR never exposed to the public internet).

```mermaid
flowchart LR
  subgraph Site["Customer site"]
    CAM["Cameras"] --> DVR["Hikvision DVR"]
    DVR -->|"Ethernet"| RTR["Private router<br/>(no SIM needed)"]
    RTR -->|"Wi‑Fi / LAN"| BOX["Android phone / TV box"]
  end

  subgraph BoxApps["On the Android box"]
    BOX --> WG["WireGuard app<br/>(standalone)"]
    BOX --> APP["Truvend Cam<br/>live view + RTSP relay"]
  end

  subgraph Cloud["VPS"]
    WG -->|"UDP tunnel<br/>10.8.0.0/16"| WGS["WireGuard server<br/>10.8.0.1"]
    APP -->|"TCP :8554<br/>over tunnel"| MTX["MediaMTX"]
    MTX -->|"WebRTC :8889"| BR["Browser"]
  end

  APP -->|"TCP :554 LAN"| DVR
```

**Core idea:** the box dials *out* to the VPS. Firewalls allow outbound. The VPS never needs an inbound hole to the DVR.

---

## 2. Architecture in one picture (layers)

```mermaid
flowchart TB
  subgraph L5["Layer 5 — Viewers"]
    V1["On-site: Truvend Cam live / grid"]
    V2["Off-site: Browser via MediaMTX WebRTC"]
  end

  subgraph L4["Layer 4 — Delivery"]
    MTX2["MediaMTX on VPS<br/>pulls RTSP/TCP, serves WebRTC"]
  end

  subgraph L3["Layer 3 — Private tunnel"]
    WG2["WireGuard<br/>box 10.8.0.N ↔ server 10.8.0.1"]
  end

  subgraph L2["Layer 2 — Relay on the box"]
    REL["Truvend Cam ForwarderService<br/>listen 0.0.0.0:8554 → DVR:554<br/>(replaces Termux socat)"]
  end

  subgraph L1["Layer 1 — Site LAN"]
    DVR2["DVR RTSP :554 + ISAPI :80"]
    LAN["Private router 192.168.0.0/24"]
  end

  V1 --> REL
  V1 --> DVR2
  V2 --> MTX2
  MTX2 -->|"rtsp://10.8.0.N:8554/..."| WG2
  WG2 --> REL
  REL --> DVR2
  DVR2 --> LAN
```

| Layer | Job | If it fails |
|---|---|---|
| 1 Site LAN | Box can reach DVR | Fix cable / DHCP / Wi‑Fi / mobile data |
| 2 Relay | Box accepts `:8554` and copies bytes to DVR | **SERVER: LISTENING?** Stop socat conflict |
| 3 Tunnel | VPS can reach `10.8.0.N` | Handshake, AllowedIPs, UDP 51820, battery |
| 4 MediaMTX | Browser gets playable video | Path config, credentials, `rtspTransport: tcp` |
| 5 Viewers | Human sees picture | Everything above must be green first |

---

## 3. End-to-end data flow (remote viewing)

```mermaid
sequenceDiagram
  participant BR as Browser
  participant MTX as MediaMTX (VPS)
  participant WG as WireGuard
  participant REL as Truvend Cam relay
  participant DVR as Hikvision DVR

  BR->>MTX: Open http://VPS:8889/site001_ch1
  MTX->>WG: RTSP/TCP to 10.8.0.2:8554/.../102
  WG->>REL: TCP connect (tunnel)
  REL->>DVR: Separate TCP connect :554
  Note over REL: Bytes copied both ways<br/>paths unchanged
  DVR-->>REL: H.264 (+ H.264+) RTSP stream
  REL-->>WG: Same bytes
  WG-->>MTX: Same bytes
  MTX-->>BR: WebRTC video
```

**Two TCP connections, not a route.** Android will not forward VPN packets onto Wi‑Fi without root. The app *is* the destination on `:8554`, then opens a normal LAN socket to the DVR.

---

## 4. Inventory — tools on the **server (VPS)**

| Tool | What it is | Role in this system |
|---|---|---|
| **Ubuntu 22.04 / 24.04** | OS | Host for tunnel + MediaMTX |
| **WireGuard** (`wireguard`, `wireguard-tools`) | VPN | Private `10.8.0.0/16` network; server = `10.8.0.1` |
| **wg-quick / systemd** | Service | Brings up `wg0` on boot |
| **MediaMTX** | Streaming server | Pulls RTSP from each site box; serves **WebRTC** to browsers |
| **ffmpeg / ffprobe** | CLI media tools | Diagnose streams from the VPS (`ffprobe …@10.8.0.2:8554`) |
| **ufw** | Firewall | Opens SSH, WG UDP, WebRTC; **keeps RTSP :8554 closed** to the internet |
| **qrencode** | QR generator | Print WireGuard client config for phone scan |
| **curl / wget / tar** | Utilities | Download MediaMTX, detect public IP |
| **`scripts/setup-server.sh`** | Our installer | One-time: packages, WG server, firewall, MediaMTX binary/service |
| **`scripts/sitectl`** | Site provisioner | **Day-to-day:** add/remove/list/sync (WG + registry + MediaMTX) |
| **`/etc/relay/relay.env`** | Env config | `RELAY_ENDPOINT`, tunnel CIDR, WebRTC host (edit once) |
| **`/etc/relay/sites.db`** | Site registry | **Single source of truth** for deployed sites |
| `/root/add-site.sh` | *Legacy* helper | Prefer `sitectl` for new deployments |

### Server ports (remember these)

| Port | Proto | Public? | Purpose |
|---|---|---|---|
| 22 | TCP | Yes | SSH |
| 51820 | UDP | Yes | WireGuard |
| 8889 | TCP | Yes | MediaMTX WebRTC signalling |
| 8189 | UDP | Yes | MediaMTX WebRTC media |
| 8554 | TCP | **No** | MediaMTX RTSP (local / over WG only) |
| 9997 | TCP | No (localhost) | MediaMTX API |

### Server files that matter

| Path | Contents |
|---|---|
| `/etc/relay/relay.env` | Global relay settings (`RELAY_ENDPOINT`, etc.) |
| `/etc/relay/sites.db` | Site registry (source of truth for `sitectl`) |
| `/etc/relay/keys/` | Server + per-site WireGuard keys |
| `/etc/relay/client-configs/*.conf` | Per-box WireGuard configs to import on site |
| `/etc/relay/backups/` | MediaMTX YAML backups before regenerate |
| `/etc/wireguard/wg0.conf` | Live WG interface (updated by `sitectl` / `wg-quick save`) |
| `/root/mediamtx.yml` | MediaMTX config (**generated by `sitectl`** — do not hand-edit) |
| `/root/mediamtx` | MediaMTX binary (as used by `sitectl`; older installs may use `/usr/local`) |

Docs: [04 — VPS setup](04-vps-server-setup.md) · [06 — sitectl](06-sitectl.md) · [08 — Server operations](08-server-operations.md) · [Server technical](technical/SERVER.md) · [sitectl technical](technical/SITECTL.md)

---

## 5. Inventory — tools on the **phone / Android TV box**

| Tool | What it is | Role |
|---|---|---|
| **Truvend Cam** (`com.app.truvend_cam`) | Our Kotlin app | Live view, grid, Setup, **RTSP relay** (socat replacement) |
| **WireGuard** (official app) | Standalone VPN client | Outbound tunnel to VPS (not embedded in our app yet) |
| **LibVLC** (inside Truvend Cam) | Player | Decodes RTSP over TCP on device |
| **EncryptedSharedPreferences** | Secure storage | DVR credentials + relay settings |
| **Foreground Service + wake lock** | Android APIs | Keep relay alive screen-off / background |
| **Termux + socat** | *Legacy* | Old manual relay — **stop using** once app relay is on |
| **Browser** (optional) | Chrome etc. | Quick check: `http://<dvr-ip>` still loads with VPN on |
| **adb** (laptop) | Android debug bridge | Install APKs, Always-on VPN fallback, FD leak checks |

### Truvend Cam — major pieces

| Piece | Job |
|---|---|
| Setup / Live / Grid | On-site viewing via ISAPI discovery + LibVLC |
| `ForwarderServer` | TCP listen `:8554` → connect DVR `:554` → copy bytes |
| `ForwarderService` | Foreground service, notification, wake lock, `START_STICKY` |
| `BootReceiver` | Restart relay after reboot if it was enabled |
| Relay status UI | **SERVER: LISTENING**, socat line, device IPs, DVR reachable |

### Box settings that must be right

| Setting | Value | Why |
|---|---|---|
| WireGuard **Allowed IPs** | `10.8.0.0/16` (or `/24`) — **not** `0.0.0.0/0` | Split tunnel — keep LAN/DVR reachable. `sitectl` writes `WG_CIDR` (default `/16`) |
| WireGuard **Persistent keepalive** | `25` | Survive idle NAT timeouts |
| Always-on VPN | On | Survive reboot |
| Block without VPN | **Off** | Avoid lockout when tunnel is down |
| Battery — WireGuard | Unrestricted | Otherwise dies overnight |
| Battery — Truvend Cam | Unrestricted | Same for the relay |
| Notifications | Allowed (API 33+) | Foreground relay notification |

Docs: [05 — WireGuard on box](05-wireguard-on-box.md) · [02 — RTSP relay](02-rtsp-relay.md)

---

## 6. Inventory — **site hardware / network**

| Piece | Role |
|---|---|
| Hikvision DVR | Records + serves RTSP / ISAPI |
| Small Wi‑Fi router (**no SIM required**) | Private DHCP + Wi‑Fi + Ethernet switch — isolates from customer IT |
| Ethernet cable | DVR → router |
| Android phone or TV box | Always-on relay + optional local viewer |
| USB mouse (for TV boxes) | Typing long WireGuard keys |

Example LAN (one unit): gateway `192.168.0.1`, DVR often `192.168.0.100` via DHCP.

---

## 7. DVR settings (required checklist)

Configure via DVR local UI or web UI (`http://<dvr-ip>`). Do this **before** relying on the app or MediaMTX.

### 7.1 Network

| Setting | Where | Value |
|---|---|---|
| DHCP | Configuration → Network → TCP/IP | **ON** first (note the address). Prefer later DHCP reservation on the router by MAC rather than a blind static IP |
| HTTP port | Network → More Settings | **80** (LAN). Ignore NAT “external” ports |
| RTSP port | Network → More Settings | **554** |

### 7.2 Integration / security

| Setting | Where | Value |
|---|---|---|
| ISAPI / Hikvision-CGI | Network → Advanced → Integration Protocol | **Enabled** |
| Illegal Login Lock | System → Security | Off while commissioning; **re-enable** before handoff |
| Admin password | System → User | Changed from default; never commit to git |

### 7.3 Encoding (per channel — main and sub)

Path is typically **Configuration → Record → Encoding** (wording varies by firmware). Apply on **every** camera channel you use. Remote viewing (`sitectl`) pulls **sub-streams** (`…/102`, `…/202`, …) — those must be correct or WebRTC will fail or burn bandwidth.

| Setting | Value | Why |
|---|---|---|
| **Stream type** | **Video** (not Video & Audio, unless you need audio and have tested it) | Keeps the pipe simple; matches how we pull/play today |
| **Video encoding / codec** | **H.264** | Required for LibVLC, MediaMTX, and browser WebRTC. Do **not** leave sub-streams on H.265 / HEVC |
| **H.264+** | **Enabled** (Smart Codec / H.264+ — wording varies by firmware) | Keeps bitrate and file/stream size down on the tunnel and on DVR storage. Plain H.264 without H.264+ is often too large for remote viewing |
| **Sub-stream** | **Enabled** | Grid view + remote paths use sub (`…/102`, `…/202`, …) |
| Resolution / bitrate (sub) | Modest (e.g. CIF/D1-class, low bitrate) | Saves DVR sessions and tunnel bandwidth |
| Frame rate | As needed (e.g. 15–25 fps) | Match site requirements |
| **I-frame interval (GOV)** | **Equal to frame rate** (25 fps → 25) | Faster startup / cleaner seeks after connect |
| Audio | Off unless required | Fewer moving parts |

> **H.264 + H.264+ (required for deployments)**
>
> - **H.264** — the codec browsers and MediaMTX expect. If `ffprobe` reports `hevc`, WebRTC will not play; fix under *Record → Encoding → Sub-Stream*.
> - **H.264+** — Hikvision’s smarter H.264 mode. Leave it **on** so stream size stays manageable over WireGuard. H.264 without H.264+ often produces streams that are too large for remote sites.
> - **H.265** — do not use on this stack unless every hop is proven with HEVC end-to-end (today: not supported for browser playback).

### 7.4 RTSP channel IDs (Hikvision)

```
rtsp://USER:PASS@HOST:PORT/Streaming/Channels/<id>
```

`<id> = cameraNumber × 100 + streamType`  
`1` = main, `2` = sub → camera 1 sub = **102**.

| Camera | Main | Sub (used by MediaMTX / `sitectl` / grid) |
|---|---|---|
| 1 | 101 | **102** |
| 2 | 201 | **202** |
| 3 | 301 | **302** |
| 4 | 401 | **402** |

Direct LAN example: `rtsp://admin:***@192.168.0.100:554/Streaming/Channels/102`  
Via relay / tunnel: same path, host = box LAN IP or `10.8.0.N`, port **8554**.

### 7.5 ISAPI (used by the Android app)

| Endpoint | Use |
|---|---|
| `GET /ISAPI/Streaming/channels` | Discover channels (HTTP Digest) |
| `GET /ISAPI/Streaming/channels/<id>/picture` | Snapshot (Phase 2 hook) |
| `PUT /ISAPI/ContentMgmt/record/control/manual/stop/tracks/<id>` | Periodic segmentation (close open file) |
| `PUT /ISAPI/ContentMgmt/record/control/manual/start/tracks/<id>` | Periodic segmentation (open new file) |

See [09-dvr-segmentation.md](09-dvr-segmentation.md). Runs on the box on a timer (LAN only); does not depend on the tunnel.

---

## 8. How MediaMTX finds each camera

**Prefer `sitectl`** — it regenerates MediaMTX from `/etc/relay/sites.db`:

```bash
sudo ./sitectl add site001 --dvr-user admin --dvr-pass 'SECRET' --channels 4
```

Generated path shape:

```yaml
site001_ch1:
  source: rtsp://USER:PASS@10.8.0.2:8554/Streaming/Channels/102
```

(`pathDefaults` set `rtspTransport: tcp`, on-demand pull, etc.)

| Piece | Meaning |
|---|---|
| Host `10.8.0.2` | Android box tunnel address — **not** the DVR LAN IP |
| Port `8554` | Truvend Cam relay (socat replacement) |
| `…/102` | Camera 1 **sub-stream** |
| `rtspTransport: tcp` | Required — relay is one TCP pipe |
| Browser URL | `http://<VPS-ip>:8889/site001_ch1` (use **Firefox** until HTTPS; Chrome blocks WebRTC on plain HTTP) |

---

## 9. Addresses cheat sheet

| Who | Typical address |
|---|---|
| Site router | `192.168.0.1` |
| DVR on LAN | `192.168.0.x` (DHCP) |
| Box on LAN | another `192.168.0.x` |
| VPS on WireGuard | `10.8.0.1` |
| First site box on WireGuard | `10.8.0.2` (then `.3`, …) |
| DVR RTSP | `:554` |
| Box relay | `:8554` |
| Browser | `http://<public-ip>:8889/<site>_chN` (Firefox until HTTPS) |

---

## 10. Install order (holistic)

```mermaid
flowchart TD
  A["1. Site physical<br/>DVR + router + box on Wi‑Fi"] --> B["2. DVR settings<br/>DHCP, ISAPI, H.264 + H.264+, Video, sub-stream"]
  B --> C["3. Truvend Cam Setup<br/>Test + live view on LAN"]
  C --> D["4. VPS setup-server.sh<br/>WG + MediaMTX + firewall"]
  D --> E["5. Configure /etc/relay/relay.env<br/>then sitectl add"]
  E --> F["6. WireGuard on box<br/>import client conf, keepalive 25"]
  F --> G["7. Truvend Cam RTSP relay<br/>SERVER: LISTENING"]
  G --> H["8. Prove<br/>sitectl list → ping → ffprobe → browser"]
```

Detailed pages: [01](01-local-live-view.md) → [04](04-vps-server-setup.md) → [06](06-sitectl.md) → [05](05-wireguard-on-box.md) → [02](02-rtsp-relay.md).

---

## 11. Prove the whole system (one ladder)

Run in order; do not skip.

| # | Where | Command / check | Proves |
|---|---|---|---|
| 1 | Laptop on site Wi‑Fi | Browser `http://<dvr-ip>` | LAN + DVR alive |
| 2 | Box | Truvend Cam live view | App + RTSP to DVR |
| 3 | Box | WireGuard handshake recent; IP `10.8.0.x` on relay screen | Tunnel up |
| 4 | VPS | `sitectl list` / `wg show` | Server sees peer |
| 5 | VPS | `ping -c 3 10.8.0.2` | Bidirectional tunnel |
| 6 | Box | Browser still opens DVR with VPN **on** | AllowedIPs not full-tunnel |
| 7 | Box | Relay **SERVER: LISTENING** | socat replacement up |
| 8 | VPS | `ffprobe -rtsp_transport tcp rtsp://…@10.8.0.2:8554/Streaming/Channels/102` | Full path to video |
| 9 | Laptop anywhere | `http://<public-ip>:8889/site001_ch1` (**Firefox** until HTTPS) | MediaMTX → browser |
| 10 | Box | Reboot; WG + relay return alone | Unattended site |

---

## 12. What is in / out of scope today

| In scope / working | Not yet |
|---|---|
| Local live + grid | WireGuard embedded inside Truvend Cam |
| In-app TCP relay (FGS) | Motion / snapshots / cloud upload |
| Box-side DVR periodic segmentation | Enrolment / box config refresh from site record |
| Standalone WireGuard on box | On-box event clip buffer (deferred — server uses dumb playback) |
| VPS WG + MediaMTX + `sitectl` | Changing live-view UX |

When WG is embedded later: call `VpnService.protect()` on the outbound DVR socket (`prepareDvrSocket` hook already exists).

---

## 13. Where to go next

| Need | Document |
|---|---|
| **This page** | Holistic architecture (you are here) |
| Novice step list | [docs/README.md](README.md) |
| DVR + local app | [01-local-live-view.md](01-local-live-view.md) |
| Relay UI / LISTENING | [02-rtsp-relay.md](02-rtsp-relay.md) |
| Remote overview | [03-remote-tunnel.md](03-remote-tunnel.md) |
| VPS scripts | [04-vps-server-setup.md](04-vps-server-setup.md) |
| **Site provision (`sitectl`)** | [06-sitectl.md](06-sitectl.md) |
| **`sitectl` usage + future API** | [07-sitectl-usage-and-api.md](07-sitectl-usage-and-api.md) |
| **Server operations (VPS day-to-day)** | [08-server-operations.md](08-server-operations.md) |
| WireGuard on box | [05-wireguard-on-box.md](05-wireguard-on-box.md) |
| Deep Android / ports | [technical/REFERENCE.md](technical/REFERENCE.md) |
| Deep server | [technical/SERVER.md](technical/SERVER.md) |
| Deep WG client | [technical/WIREGUARD-CLIENT.md](technical/WIREGUARD-CLIENT.md) |
| Deep `sitectl` | [technical/SITECTL.md](technical/SITECTL.md) |
| Phase 1 lessons / rejects | [../README.md](../README.md) |
| One-time installer | [../scripts/setup-server.sh](../scripts/setup-server.sh) |
| Site provisioner | [../scripts/sitectl](../scripts/sitectl) |
