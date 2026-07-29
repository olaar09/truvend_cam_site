# Server technical reference (WireGuard + MediaMTX)

Companion to the novice guide: [04 — VPS server setup](../04-vps-server-setup.md).  
Source script: [`scripts/setup-server.sh`](../../scripts/setup-server.sh).

---

## 1. Roles and traffic

```
Browser ──WebRTC──▶ :8889 / :8189  MediaMTX (public)
                         │
                         │ sourceOnDemand RTSP/TCP
                         ▼
                   <client_ip>:8554   via WG (private)
                         │
                         ▼
              Android ForwarderServer → DVR:554
```

| Listener | Bind | Public? | Purpose |
|---|---|---|---|
| WireGuard | UDP `WG_PORT` (51820) | Yes | Tunnel |
| MediaMTX RTSP | TCP `:8554` | **No** (ufw closed) | Server-local / path sources |
| MediaMTX WebRTC | TCP `:8889`, UDP `:8189` | Yes | Browser playback |
| MediaMTX API | `127.0.0.1:9997` | No | Local control |
| SSH | TCP 22 | Yes | Admin |

MediaMTX path `source` points at the **Android tunnel address**, not the DVR LAN IP. The box relay is mandatory.

---

## 2. `setup-server.sh` behaviour

### Safety / idempotence

- `set -e` — abort on first failure.
- Root required.
- **Does not overwrite** existing:
  - `/etc/wireguard/keys/server_*.key`
  - `/etc/wireguard/wg0.conf`
  - `/usr/local/etc/mediamtx.yml` (if already marked / present — see script branches)
- MediaMTX base YAML is rewritten only when not already tagged `# MANAGED BY SETUP SCRIPT`.

### IP forwarding

```
/etc/sysctl.d/99-wireguard.conf
net.ipv4.ip_forward=1
```

Required so the kernel can forward between interfaces when peers use the server as a hub.

### WireGuard server interface

```
[Interface]
Address    = 10.8.0.1/24
ListenPort = 51820
PrivateKey = <server>
```

Peers appended by `add-site.sh`:

```
[Peer]
PublicKey  = <site>
AllowedIPs = 10.8.0.N/32
```

`AllowedIPs` on the **server** is per-peer `/32` (only that client’s tunnel address).  
On the **client** config, `AllowedIPs = 10.8.0.0/16` (`WG_CIDR` from `relay.env`) so the box can reach the server and other tunnel hosts, without capturing all Internet traffic (split tunnel). `PersistentKeepalive = 25` keeps NAT mappings alive from site networks.

> Prefer **`sitectl`** for day-to-day provisioning. The `add-site.sh` section below documents the legacy helper from early `setup-server.sh` runs. See [08 — Server operations](../08-server-operations.md).

### MediaMTX base config (critical knobs)

| Key | Value | Why |
|---|---|---|
| `rtspTransport` | `tcp` | Android relay is a single TCP byte pipe; UDP RTP would not traverse it |
| `rtsp` / `rtspAddress` | `:8554` | Local RTSP on VPS |
| `webrtc` | yes `:8889` / UDP `:8189` | Browser delivery |
| `hls` / `rtmp` / `srt` | no | Unused |
| `apiAddress` | `127.0.0.1:9997` | Not exposed |

#### HTTPS (nginx in front of MediaMTX)

**Full guide:** [`backend/plexity-ai-chat/bash/HTTPS-RELAY.md`](../../../../backend/plexity-ai-chat/bash/HTTPS-RELAY.md)

```yaml
webrtc: yes
webrtcAddress: 127.0.0.1:8889
webrtcLocalUDPAddress: :8189
webrtcAdditionalHosts: [relay.truvend.online]
```

```bash
ufw allow 8189/udp
```

nginx terminates TLS → `127.0.0.1:8889`; UDP **8189** remains directly reachable on the VPS. Also bake these knobs into the `sitectl` `mtx_regenerate` HEADER so `sync` does not wipe them.


systemd unit: `Restart=always`, `RestartSec=5`, runs as root with explicit config path.

### Firewall rationale

Public RTSP to the VPS would invite scanning and credential stuffing against every `source` URL embedded in config. WebRTC is the public surface; RTSP stays on-box / over WG.

---

## 3. `add-site.sh` algorithm

Generated at `/root/add-site.sh` by setup (also conceptually documented here).

### Address allocation

```
NEXT = (lines in allocated-ips.txt) + 2
CLIENT_IP = 10.8.0.NEXT
```

`.1` reserved for server; first site → `.2`. Hard stop at `.254`.

Record format: `siteName 10.8.0.N` in `allocated-ips.txt`.

### Keying

- `wg genkey` → `keys/<site>_private.key` + pubkey.
- Server peer entry uses **client public** key only.
- Client conf file holds **client private** + **server public** + endpoint `PUBLIC_IP:WG_PORT`.

### Live reload

```bash
wg syncconf wg0 <(wg-quick strip wg0)
```

Applies peer changes without tearing down the whole interface (existing sessions stay up).

### MediaMTX path template

For cameras `1..N`:

```yaml
  <site>_ch<n>:
    source: rtsp://USER:PASS@<CLIENT_IP>:8554/Streaming/Channels/<n>02
    sourceOnDemand: yes
    sourceOnDemandCloseAfter: 10s
```

| Piece | Meaning |
|---|---|
| `<n>02` | Hikvision sub-stream for camera `n` (`102`, `202`, …). DVR encoding must be **H.264** with **H.264+** enabled (not H.265) — see [SYSTEM-ARCHITECTURE §7.4](../SYSTEM-ARCHITECTURE.md#74-encoding-per-channel--main-and-sub) |
| `sourceOnDemand` | Pull only while a viewer is connected |
| `sourceOnDemandCloseAfter` | Drop idle pull after 10s (saves DVR sessions) |

Credentials: interactive prompt or `DVR_USER` / `DVR_PASS` environment variables. Password ends up in `mediamtx.yml` — protect file perms / disk access on the VPS.

Browser URL pattern:

```
http://<PUBLIC_IP>:8889/<site>_ch<n>
```

---

## 4. Alignment with the Android relay

| VPS / MediaMTX | Android app |
|---|---|
| Source host = tunnel IP of box | WireGuard assigns that IP |
| Source port `8554` | `ForwarderServer` listen port (default 8554) |
| RTSP over TCP | Relay is TCP-only byte copy |
| Path `/Streaming/Channels/x02` | Same as direct DVR URLs; relay does not rewrite paths |

Do not run Termux `socat` on 8554 while the app relay listens.

When WireGuard is later embedded in-app, outbound DVR sockets need `VpnService.protect` (hook already on `ForwarderServer`). With standalone WG + `AllowedIPs=10.8.0.0/16`, LAN DVR traffic is unaffected.

---

## 5. Operational verification

```bash
# Peers / handshakes
wg show

# MediaMTX
systemctl status mediamtx
journalctl -u mediamtx -e

# Parity with old socat path (from VPS)
ffprobe -rtsp_transport tcp \
  rtsp://USER:PASS@10.8.0.2:8554/Streaming/Channels/102
```

Handshake age in `wg show` should be seconds/minutes while the box is online. If handshake is hours old, the tunnel is stale (box offline, wrong endpoint, UDP blocked).

---

## 6. Threat / ops notes

- Server private key and all `keys/*_private.key` are root-readable secrets.
- Site confs under `/root/site-configs/` contain client private keys — treat like passwords.
- Rotating a site: remove peer from `wg0.conf`, free IP in `allocated-ips.txt`, remove MediaMTX paths, re-run add (or manual equivalent).
- Upgrading MediaMTX: bump `MEDIAMTX_VERSION`, replace binary carefully; preserve `mediamtx.yml` camera paths.
- Re-running `setup-server.sh` is safe for packages/services but will not rebuild peers; use **`sitectl`** for sites.

---

## 7. Related docs

- [04 — VPS server setup (novice)](../04-vps-server-setup.md)
- [02 — RTSP relay](../02-rtsp-relay.md)
- [Technical reference (Android + overall)](REFERENCE.md)
