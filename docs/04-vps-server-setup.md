# 4 — VPS server setup (WireGuard + MediaMTX)

**Goal:** Prepare a fresh Ubuntu VPS once, then add each camera site with a helper script.  
**Who runs this:** You (or whoever owns the cloud server), **as root**, on Ubuntu **22.04 or 24.04**.  
**What it installs:** WireGuard · MediaMTX · ffmpeg · firewall rules · `add-site.sh`

Script in this repo: [`scripts/setup-server.sh`](../scripts/setup-server.sh)

---

## What you end up with (plain English)

```
Internet browser ──▶ VPS :8889 (WebRTC / MediaMTX)
                         │
                         │ pulls RTSP over the private tunnel
                         ▼
                    10.8.0.2:8554  (Android box relay)
                         │
                         ▼
                      DVR :554
```

| Piece | Job |
|---|---|
| **WireGuard** | Private encrypted tunnel. Server is `10.8.0.1`. Each site box gets `10.8.0.2`, `.3`, … |
| **MediaMTX** | Pulls camera RTSP from the tunnel and serves it to browsers (WebRTC). |
| **ffmpeg / ffprobe** | Tools to test streams from the server itself. |
| **ufw firewall** | Opens only what must be public (SSH, WireGuard, WebRTC). Keeps raw RTSP **closed** to the internet. |
| **add-site.sh** | Adds one site: WG peer + phone config + MediaMTX camera paths + QR code. |

---

## Before you start

- [ ] Fresh Ubuntu 22.04 or 24.04 VPS with a public IP
- [ ] You can SSH in as root (or `sudo`)
- [ ] Copy `scripts/setup-server.sh` onto the server (scp, paste, etc.)
- [ ] Know you will need DVR username/password later when adding a site

Optional settings at the top of the script (change before running if you want):

| Setting | Default | Meaning |
|---|---|---|
| `WG_PORT` | `51820` | UDP port WireGuard listens on (must be open in cloud firewall too) |
| `WG_SUBNET` | `10.8.0` | Tunnel network → server becomes `10.8.0.1` |
| `MEDIAMTX_VERSION` | `v1.19.3` | Release from [bluenviron/mediamtx](https://github.com/bluenviron/mediamtx/releases) |

Also open these in your **cloud provider** firewall / security group if they have one separate from ufw:

- UDP `51820` (WireGuard)
- TCP `22` (SSH)
- TCP `8889` (WebRTC)
- UDP `8189` (WebRTC media)

---

## Step A — Run the one-time server setup

On the VPS:

```bash
chmod +x setup-server.sh
sudo ./setup-server.sh
```

The script stops on the first error (`set -e`). It must be root.

### What each part does (novice)

#### 1. System packages

Installs WireGuard tools, ufw, curl/wget, ffmpeg, nano, **qrencode** (QR codes for phone configs).

Turns on **IP forwarding** so the server can move traffic between interfaces (needed for tunnel behaviour).

#### 2. WireGuard server

- Creates `/etc/wireguard/keys/` (private).
- Generates a **server key pair** (keeps existing keys if you re-run).
- Writes `/etc/wireguard/wg0.conf` with:
  - Address `10.8.0.1/24`
  - Listen port `51820`
  - Server private key
- Enables and starts `wg-quick@wg0`.
- Creates empty `allocated-ips.txt` (tracks which `.2`, `.3`, … are used).

#### 3. MediaMTX

- Downloads the release tarball, installs binary to `/usr/local/bin/mediamtx`.
- Writes a known-good `/usr/local/etc/mediamtx.yml`:
  - **`rtspTransport: tcp`** — required because our Android relay is one TCP pipe (no classic RTSP UDP).
  - RTSP listen on the server at `:8554` (for **local testing on the VPS only**).
  - WebRTC on `:8889` / UDP `:8189` (what browsers use).
  - API on `127.0.0.1:9997` (local only).
- Installs a systemd service so MediaMTX restarts on reboot / crash.

#### 4. Firewall (ufw)

| Opens | Purpose |
|---|---|
| TCP 22 | SSH |
| UDP 51820 | WireGuard |
| TCP 8889 | WebRTC signalling |
| UDP 8189 | WebRTC video |

**Deliberately closed to the internet:** TCP `8554` (RTSP).  
Use RTSP only from the server itself (e.g. `ffprobe` to `10.8.0.2:8554`). Browsers use WebRTC, not public RTSP.

#### 5. Creates `/root/add-site.sh`

Helper for every new customer site (next section).

### Success output looks like

```
Server setup complete
Public IP        : x.x.x.x
WireGuard port   : 51820/udp
Server public key: …
Next step — add your first site:
    ./add-site.sh site001 192.168.0.60 4
```

### You are done with Step A when

- [ ] Script finished without ERROR
- [ ] `wg show` runs (interface up)
- [ ] `systemctl status mediamtx` is active
- [ ] `/root/add-site.sh` exists and is executable

Useful checks:

```bash
wg show
systemctl status mediamtx
journalctl -u mediamtx -f
```

---

## Step B — Add a site (`add-site.sh`)

One site = one Android box (+ its DVR).

```bash
./add-site.sh <site-name> <dvr-ip> [number-of-cameras]
```

Example:

```bash
./add-site.sh site001 192.168.0.60 4
```

| Argument | Example | Meaning |
|---|---|---|
| `site-name` | `site001` | Short label (no spaces). Used in config filenames and MediaMTX path names. |
| `dvr-ip` | `192.168.0.60` | DVR address **on the site LAN** (recorded for humans; MediaMTX talks to the **box tunnel IP**, not this IP directly). |
| `cameras` | `4` (default) | How many MediaMTX paths to create (`ch1`…`ch4`). |

It will ask for **DVR username** and **password** (or use `DVR_USER` / `DVR_PASS` env vars).

### What add-site does (novice)

1. Picks the next free tunnel IP (`.1` = server → first site `.2`, then `.3`, …).
2. Generates WireGuard keys for that site.
3. Appends a `[Peer]` to `wg0.conf` and reloads with `wg syncconf` (does not kick other sites offline).
4. Writes `/root/site-configs/<site>.conf` for the Android WireGuard app.
5. Appends MediaMTX paths like `site001_ch1` … that pull:

   `rtsp://USER:PASS@10.8.0.2:8554/Streaming/Channels/102`  
   (channel `N` → stream id `N02` = camera N **sub-stream**)

6. Restarts MediaMTX.
7. Prints browser URLs and a **QR code** to scan in the WireGuard app.

### On the Android box after add-site

1. Install / open the **WireGuard** app.
2. Scan the QR (or import `/root/site-configs/<site>.conf`).
3. Turn the tunnel **ON**.
4. In Truvend Cam → **RTSP relay** → toggle ON → wait for **SERVER: LISTENING**.
5. Confirm the relay screen shows a `10.8.0.x` address matching what add-site printed.

### Browser test

Open (use your real public IP and site name):

```
http://<PUBLIC_IP>:8889/site001_ch1
```

### You are done with Step B when

- [ ] `wg show` lists the new peer (handshake recent when the box is online)
- [ ] Box relay shows **LISTENING** + correct `10.8.0.x`
- [ ] From the VPS: `ffprobe -rtsp_transport tcp rtsp://…@10.8.0.2:8554/Streaming/Channels/102` works
- [ ] Browser URL for `*_ch1` plays

---

## Day-2 commands (cheat sheet)

```bash
wg show                         # who is connected
systemctl status mediamtx       # MediaMTX up?
journalctl -u mediamtx -f       # live MediaMTX log
systemctl restart mediamtx
systemctl restart wg-quick@wg0

# Test stream from the server (RTSP not public)
ffprobe -rtsp_transport tcp \
  rtsp://admin:PASSWORD@10.8.0.2:8554/Streaming/Channels/102
```

Configs to know:

| Path | What |
|---|---|
| `/etc/wireguard/wg0.conf` | Server + all peers |
| `/etc/wireguard/keys/` | Keys (keep secret) |
| `/etc/wireguard/allocated-ips.txt` | Which tunnel IPs are used |
| `/root/site-configs/*.conf` | Per-site configs for phones/boxes |
| `/usr/local/etc/mediamtx.yml` | MediaMTX + camera paths |
| `/root/add-site.sh` | Add another site |

---

## Common mistakes

| Mistake | Result |
|---|---|
| Cloud firewall blocks UDP 51820 | WireGuard never connects |
| Forgot Android relay / still using socat conflict | MediaMTX cannot pull `:8554` |
| `AllowedIPs` too wide on the phone | Can break LAN/DVR routing — script uses `10.8.0.0/24` only (good) |
| Expecting public `http://VPS:8554` | Port is intentionally closed; use `:8889` WebRTC |
| Wrong DVR password in add-site | Paths added but streams fail — check `journalctl -u mediamtx` |
| Re-run setup thinking it resets everything | Script **keeps** existing WG keys and `wg0.conf` / MediaMTX config if present |

---

## Next / related

- On-site relay: [02 — RTSP relay](02-rtsp-relay.md)
- How remote pieces fit: [03 — Remote path](03-remote-tunnel.md)
- Install WireGuard on the box: [05 — WireGuard on phone / TV box](05-wireguard-on-box.md)
- Deep technical: [Server technical reference](technical/SERVER.md)
- Docs home: [README](README.md)
