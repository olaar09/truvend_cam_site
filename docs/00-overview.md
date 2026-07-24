# 0 — Overview: the big picture

## What are we building?

A way to watch a Hikvision DVR’s cameras:

1. **On site** — on an Android phone or TV box joined to a small private Wi‑Fi (no customer IT, no internet required).
2. **Off site** — through a secure tunnel to a VPS, then to a browser (MediaMTX), without exposing the DVR to the public internet.

## The pieces (plain English)

| Piece | Job | Where it lives today |
|---|---|---|
| **Hikvision DVR** | Records cameras; serves RTSP video | Customer site |
| **Small router (no SIM)** | Private Wi‑Fi + Ethernet for the DVR | Customer site |
| **Truvend Cam app** | Live view on screen + **RTSP relay** (socat replacement) | Android phone / TV box |
| **WireGuard** | Encrypted tunnel to the VPS | Still the **standalone WireGuard app** |
| **VPS + MediaMTX** | Pulls the stream and serves it in a browser | Cloud |

## Where we are (progress)

```
[x] Phase 1 — Local network + live view in the app
[x] Phase 1.5 — RTSP relay inside the app (replaces Termux socat)
[ ] Later — Embed WireGuard in the app (not done)
[ ] Later — Motion, snapshots, cloud APIs (not done)
```

### What “it works now” means (July 2026)

You turned on **RTSP relay** in the app and saw **SERVER: LISTENING**.

That means: the phone/box is accepting TCP connections on port **8554** and forwarding bytes to the DVR on port **554** — the same job Termux used to do with:

```bash
socat TCP-LISTEN:8554,fork,reuseaddr TCP:<dvr-ip>:554
```

If WireGuard is also up, a remote machine can hit the box’s tunnel address (e.g. `10.8.0.2:8554`) and get the camera stream.

## Two connections, not one magic route

Android will not forward packets from the VPN onto the LAN without root. So we do **not** “route” to the DVR.

Instead:

```
Remote / VPS  ──connects to──▶  this device :8554   (our app listens)
Our app       ──connects to──▶  DVR :554            (normal LAN)
                    │
              bytes copied both ways
```

That is why the relay exists.

## What each screen status means (cheat sheet)

| You see | Meaning |
|---|---|
| **SERVER: LISTENING** | Port is open; relay is ready. Good. |
| **SERVER: NOT LISTENING** | Service started but bind failed (often: socat still using 8554, or port busy). |
| **SERVER: OFF** | Relay toggle is off. |
| **DVR reachable: YES** | Phone can open TCP to the DVR RTSP port. |
| **DVR reachable: NO** | Wrong IP, wrong Wi‑Fi, DVR off, or mobile data stealing traffic. |
| Device IP like `10.8.0.x` | WireGuard tunnel interface is up. |
| Only `192.168.x.x` IPs | On LAN only — fine for local tests; remote needs WireGuard. |

## One rule that saves hours

**Do not run Termux `socat` and the app relay at the same time on port 8554.**  
Only one process can own that port. Stop socat first, then enable the app relay.

## Next page

→ [1 — Local live view](01-local-live-view.md)
