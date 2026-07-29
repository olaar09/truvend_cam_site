# 1 — Local live view (Phase 1)

**Goal:** See cameras on the Android app while you are on the same private Wi‑Fi as the DVR.  
**Internet:** Not required.  
**Tunnel / relay:** Not required for this step.

---

## What you need

- Hikvision DVR with cameras
- Small Wi‑Fi router (a “4G” router with **no SIM** is fine — it is just a private access point)
- Ethernet cable: DVR → router
- Android phone or TV box with **Truvend Cam** installed
- DVR admin username and password

---

## Why the weird router-with-no-SIM?

It gives you a private mini-network:

- DHCP (automatic IPs)
- Ethernet for the DVR
- Wi‑Fi for phone / TV box
- No dependence on the customer’s internet or firewall

Every install can look the same.

---

## Novice steps

### A. Plug things in

1. Ethernet: DVR LAN port → any LAN port on the router.
2. Power on router and DVR.
3. Confirm a **link light** on the DVR Ethernet port.

### B. Let the DVR get an IP

On the DVR (monitor + mouse): **Configuration → Network → TCP/IP**

1. Turn **DHCP ON**.
2. Apply, wait ~30 seconds.
3. Note the IPv4 address shown (example: `192.168.0.100`).

> Do **not** set a random static IP from an old site. Wrong subnet = “cable looks fine but nothing responds.” Prefer DHCP, then optionally a DHCP reservation on the router by MAC.

### C. Prove the network with a browser

1. Join the router’s Wi‑Fi on a laptop or phone.
2. Open `http://<dvr-ip>` (e.g. `http://192.168.0.100`).
3. You should see the DVR login page.

If this fails, fix networking before touching the app.

**Tip:** On phones, turn **mobile data off** while testing. Android may otherwise send traffic over cellular and never reach `192.168.x.x`.

### D. Enable what the app needs on the DVR

| Setting | Where | Value |
|---|---|---|
| ISAPI / Hikvision-CGI | Network → Advanced → Integration Protocol | Enabled |
| **Time zone** | System → Time Settings | **Site local zone** (e.g. Africa/Lagos / GMT+1) |
| **Date & time** | System → Time Settings | **Correct current time** (prefer NTP) |
| Stream type | Record → Encoding | **Video** |
| Video encoding | Record → Encoding | **H.264** (not H.265) |
| **H.264+** | Record → Encoding | **Enabled** — keeps stream size manageable |
| Sub-stream | Record → Encoding | Enabled per channel |
| I-frame interval | Record → Encoding | Match frame rate (e.g. 25 fps → 25) |

Wrong DVR timezone or clock makes recording search / playback day queries miss clips — see [SYSTEM-ARCHITECTURE §7.3](SYSTEM-ARCHITECTURE.md#73-time-and-timezone-required-for-playback).

See the full DVR checklist in [SYSTEM-ARCHITECTURE.md](SYSTEM-ARCHITECTURE.md#7-dvr-settings-required-checklist).

### E. Configure the Android app

1. Open **Truvend Cam**.
2. Enter:

   | Field | Typical value |
   |---|---|
   | DVR IP | e.g. `192.168.0.100` |
   | HTTP port | `80` |
   | RTSP port | `554` |
   | Username / password | DVR account |
   | Default stream | Sub for grid; Main for fullscreen |

3. Tap **Test connection** → should list channels.
4. Tap **Save & open live view**.

You should see live video. Grid view uses up to 4 sub-streams.

---

## You are done with Phase 1 when

- [ ] Browser opens the DVR login page on the private Wi‑Fi
- [ ] App test finds channels
- [ ] Live view plays at least one camera
- [ ] Grid plays (optional but recommended)

---

## Common failures (plain English)

| Symptom | Likely cause |
|---|---|
| Timeout / no response | Wrong IP, not on camera Wi‑Fi, mobile data on, or cable/DHCP issue |
| Wrong username/password | Credentials |
| Video fails after a while | Too many RTSP sessions (DVR limit ~6–10) |
| Works on laptop VLC, not phone VLC | Ignore phone VLC — use our app |

---

## Optional: prove the stream outside the app

On a laptop on the same Wi‑Fi:

```bash
ffprobe -rtsp_transport tcp \
  rtsp://admin:PASSWORD@192.168.0.100:554/Streaming/Channels/102
```

Channel ID reminder: camera × 100 + stream (`1` main, `2` sub) → cam 1 sub = `102`.

---

## Next page

→ [2 — RTSP relay (replaces socat)](02-rtsp-relay.md)

Technical detail for Phase 1 also lives in the root [README.md](../README.md) (lessons learned, rejected approaches).
