# 2 — RTSP relay (replaces Termux socat)

**Goal:** Keep a durable “pipe” open so something on the WireGuard tunnel (usually the VPS) can reach the DVR through this Android device.  
**Status:** Working in the Truvend Cam app (July 2026).  
**Replaces:** typing `socat …` in Termux by hand every time.

---

## What does **SERVER: LISTENING** mean?

In plain English:

> The app has successfully opened port **8554** on this device and is waiting for incoming connections. When one arrives, it opens a second connection to the DVR and copies video bytes both ways.

| Status on screen | Meaning |
|---|---|
| **SERVER: LISTENING** (green) | Port bound. Relay is ready. This is what you want. |
| **SERVER: NOT LISTENING** (red) | Service tried to start but could not bind the port (often still busy). |
| **SERVER: OFF** | Toggle is off; nothing is listening. |

Also on that screen:

| Field | Meaning |
|---|---|
| **socat: TCP-LISTEN:8554 → TCP:x.x.x.x:554** | Exact job the app is doing (same idea as the old Termux command). |
| **Listen: 0.0.0.0:8554** | Accepts connections on **all** network interfaces (Wi‑Fi LAN **and** WireGuard, when present). |
| **Forward to DVR** | Where bytes go next (from Setup — one source of truth). |
| **This device IPs** | Addresses on this box. Look for `192.168…` (LAN) and `10.8…` (tunnel). |
| **DVR reachable** | Periodic TCP check to the DVR RTSP port from the phone. |
| **Active / total connections** | How many clients are using the relay right now / since start. |

**Listening ≠ tunnel up.**  
LISTENING only means the socat-replacement is ready. If WireGuard is down, remote machines still cannot reach `10.8.0.2`. Fix WireGuard separately; then use this listen port.

---

## What we used to do (Termux)

```bash
socat TCP-LISTEN:8554,fork,reuseaddr TCP:192.168.0.60:554
```

Problems with that approach:

- Dies when Termux closes
- Dies when the screen sleeps
- Dies on reboot
- Easy to forget after a power cut

The app relay is the same behaviour, but as a **foreground service** with a notification, wake lock, boot restart, and battery-unrestricted prompt.

---

## Novice steps — turn the relay on

### 1. Stop Termux socat (if running)

Only one listener can use port 8554. In Termux: `Ctrl+C` on the socat process.

### 2. Open the relay screen

**Truvend Cam → Settings (or Setup) → RTSP relay**

### 3. Confirm DVR target

The screen should show something like:

`Forward to DVR: 192.168.0.100:554`

If wrong, tap **Change DVR address**, fix Setup, come back.

### 4. Allow notifications + battery unrestricted

Android will kill background relays otherwise.

- Allow notifications when asked (needed for the persistent “relay active” notification).
- If you see the red battery warning → tap **Allow battery unrestricted** and accept.

### 5. Flip the relay switch ON

Wait until you see:

- **SERVER: LISTENING**
- Notification: something like “Listening · 0 connections”

### 6. Local test (no VPS needed)

From a laptop on the **same Wi‑Fi**, use the phone/box **LAN** IP (from “This device IPs”, not the DVR IP):

```bash
ffprobe -rtsp_transport tcp \
  rtsp://admin:PASSWORD@<box-lan-ip>:8554/Streaming/Channels/102
```

You want video stream info (e.g. h264). That proves the relay alone is correct.

### 7. Remote / parity test (with WireGuard)

1. WireGuard connected (box shows a `10.8.0.x` address).
2. App relay **LISTENING**.
3. From the VPS (same command you used with socat):

```bash
ffprobe -rtsp_transport tcp \
  rtsp://admin:PASSWORD@10.8.0.2:8554/Streaming/Channels/102
```

Same result as before = relay has replaced socat successfully. MediaMTX should keep working with **no config change** on the VPS.

---

## You are done with this step when

- [ ] Termux socat is **stopped**
- [ ] App shows **SERVER: LISTENING**
- [ ] Battery unrestricted is granted (or you accept overnight risk)
- [ ] Local `ffprobe` via `<box-lan-ip>:8554` works
- [ ] (If tunnel is up) VPS `ffprobe` via `10.8.0.2:8554` works
- [ ] After reboot, relay comes back without typing socat again

---

## Reliability checklist (installer)

| Test | Pass if |
|---|---|
| Screen off 30 minutes | Stream still works |
| App sent to background | Stream still works |
| Swipe app away from recents | Service returns within ~30s |
| Reboot the box | Relay running again, LISTENING |
| Ninth simultaneous connection | Rejected; other streams keep going |
| Unplug/replug DVR | Next client reconnects without restarting the app |

---

## Troubleshooting

| Symptom | What to try |
|---|---|
| NOT LISTENING | Stop socat / anything on 8554; toggle relay off/on; check **Last error** on screen |
| LISTENING but remote fails | WireGuard down? No `10.8.x.x` in device IPs? Wrong tunnel IP? |
| LISTENING, local LAN ffprobe fails | Firewall? Wrong device IP? DVR reachable = NO? |
| DVR reachable NO | Same Wi‑Fi as DVR? Correct host in Setup? Mobile data off? |
| Dies overnight | Battery optimisation still on |
| Works a week then refuses connections | Rare socket leak — check logs; restart relay |

---

## Next page

→ [3 — Remote path (WireGuard + VPS)](03-remote-tunnel.md)

For class names, bind rules, and the full test matrix → [Technical reference](technical/REFERENCE.md)
