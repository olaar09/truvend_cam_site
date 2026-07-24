# 3 — Remote path (WireGuard + VPS)

**Goal:** Watch cameras from off-site (browser / MediaMTX) without port-forwarding the DVR to the internet.  
**App status:** Relay is inside Truvend Cam. **WireGuard is still the standalone app** (embedding it is a later task).

---

## How remote viewing fits together

```
Browser
   ▲
MediaMTX (on VPS)
   ▲
VPS connects to  10.8.0.2:8554   ← tunnel address of the Android box
   ▲
WireGuard tunnel
   ▲
Android box — Truvend Cam RTSP relay LISTENING on 8554
   ▲
LAN connection to DVR :554
```

Nothing on the public internet talks directly to the DVR. The VPS only reaches the box’s private tunnel IP.

---

## Novice checklist (current working stack)

### On the Android box

1. Joined to the **camera Wi‑Fi** (can reach the DVR).
2. **WireGuard app** connected (you should see a `10.8.0.x` address on the relay screen).
3. Truvend Cam **RTSP relay** = **SERVER: LISTENING**.
4. Battery unrestricted for Truvend Cam.
5. Do **not** also run Termux socat on 8554.

### On the VPS

1. WireGuard peer for this site is up.
2. MediaMTX (or `ffprobe`) points at:

   `rtsp://admin:PASSWORD@10.8.0.2:8554/Streaming/Channels/102`

   (Use the real tunnel IP for that box if it is not `.2`.)

3. No change needed to MediaMTX when switching from socat → app relay, as long as host/port stay the same.

---

## What “tunnel is down” looks like

| On the relay screen | Meaning |
|---|---|
| No `10.8.x.x` in **This device IPs** | WireGuard interface not up |
| LISTENING, but VPS cannot connect | Tunnel / peer / AllowedIPs / wrong IP — not the relay |
| LISTENING + `10.8.0.2` shown, VPS still fails | Firewall on VPS/box, wrong listen port, or WG routing |

Fix WireGuard first. The relay cannot invent a tunnel.

---

## Why not just port-forward the DVR?

Hikvision devices are heavily scanned on the public internet. An outbound WireGuard tunnel + local relay keeps the DVR private.

---

## What is deliberately not in the app yet

| Item | Status |
|---|---|
| WireGuard inside Truvend Cam | Later task |
| Motion detection / snapshots / uploads | Later |
| Cloud heartbeat / enrolment | Later |
| Changes to live-view playback screens | Out of scope for the relay work |

### Forward note (for when WireGuard is embedded)

When this app becomes the VPN provider, outbound sockets to the DVR must call `VpnService.protect(socket)` so LAN traffic to the DVR does not loop into the VPN. The relay already has a `prepareDvrSocket` hook for that. **Not needed** while WireGuard is the standalone app with a narrow `AllowedIPs` (e.g. `10.8.0.0/16`).

---

## You are done with the remote path when

- [ ] WireGuard shows connected on the box
- [ ] Relay shows **SERVER: LISTENING**
- [ ] VPS `ffprobe` (or MediaMTX) gets the stream via `10.8.x.x:8554`
- [ ] Browser playback still works after stopping Termux socat forever

---

## Next / related

- Full walkthrough of the setup script: [04 — VPS server setup](04-vps-server-setup.md)
- Install WireGuard on the box: [05 — WireGuard on phone / TV box](05-wireguard-on-box.md)
- On-site relay: [02 — RTSP relay](02-rtsp-relay.md)
- Deep technical (server): [Server technical reference](technical/SERVER.md)
- Docs home: [README](README.md)
