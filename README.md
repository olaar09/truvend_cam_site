# Truvend Cam

**One document for the whole system:** [`docs/SYSTEM-ARCHITECTURE.md`](docs/SYSTEM-ARCHITECTURE.md)  
(architecture diagrams · server tools · Android tools · DVR settings including H.264 / stream type Video · ports · proof ladder)

**Working path for step-by-step guides:** [`docs/`](docs/README.md)

| Doc | Audience |
|---|---|
| [docs/SYSTEM-ARCHITECTURE.md](docs/SYSTEM-ARCHITECTURE.md) | **Start here** — holistic architecture |
| [docs/README.md](docs/README.md) | Index of all step guides |
| [docs/00-overview.md](docs/00-overview.md) | Short big-picture intro |
| [docs/01-local-live-view.md](docs/01-local-live-view.md) | Phase 1 on-site video |
| [docs/02-rtsp-relay.md](docs/02-rtsp-relay.md) | In-app relay (replaces Termux socat) |
| [docs/03-remote-tunnel.md](docs/03-remote-tunnel.md) | WireGuard + VPS / MediaMTX |
| [docs/04-vps-server-setup.md](docs/04-vps-server-setup.md) | One-time VPS setup script + add-site |
| [docs/05-wireguard-on-box.md](docs/05-wireguard-on-box.md) | WireGuard on phone / TV box |
| [docs/technical/REFERENCE.md](docs/technical/REFERENCE.md) | Deep technical (Android / overall) |
| [docs/technical/SERVER.md](docs/technical/SERVER.md) | Deep technical (WireGuard / MediaMTX server) |
| [docs/technical/WIREGUARD-CLIENT.md](docs/technical/WIREGUARD-CLIENT.md) | Deep technical (WireGuard on the box) |
| [scripts/setup-server.sh](scripts/setup-server.sh) | VPS one-time installer |

The section below is the original **Phase 1 as-built** archive (lessons learned, rejected approaches). Prefer `docs/SYSTEM-ARCHITECTURE.md` for day-to-day understanding.

---

# Phase 1 — As-Built Documentation

**Status:** Working. Android app connects to the Hikvision DVR and plays live video.  
**Date:** July 2026

This documents the setup that actually works, why each piece is there, and the dead ends already ruled out. Read this before changing anything.

---

## 1. The working setup

```
[ Hikvision DVR ]
       │  Ethernet (RJ45)
       ▼
[ MT4 4G router — NO SIM CARD ]
       │  Wi-Fi
       ▼
[ Android phone / TV box running our app ]
```

**Three components. No internet involved.**

### Why a 4G router with no SIM

This is the part that looks odd and is actually the key insight. With no SIM card, the router has no internet connection — but that does not matter, because none is needed. What it still provides:

- **A DHCP server** — hands out addresses automatically, so nothing needs manual IP configuration
- **An Ethernet switch** — the DVR plugs in by cable
- **A Wi-Fi access point** — the phone and TV box join wirelessly
- **A private, isolated subnet** — completely separate from any customer network

The result is a self-contained network in a box. Nothing on the customer's infrastructure is touched, no firewall rules, no router passwords, no IT involvement. Every install is identical.

Any small router works — the "4G" part is irrelevant with no SIM. It was simply the router that was to hand. A basic Wi-Fi router or travel router does the same job.

### Confirmed addresses (this unit)

| Device | Address |
|---|---|
| Router / gateway | 192.168.0.1 |
| Hikvision DVR | 192.168.0.100 |
| Subnet mask | 255.255.255.0 |
| DVR MAC | 88:de:39:fc:23:ca |

The DVR obtained .100 via DHCP from this router.

---

## 2. Setup procedure

### Step 1 — Physical

1. Ethernet cable from the DVR's LAN port to any LAN port on the router
2. Power on both
3. Check for a link light on the DVR's Ethernet port

### Step 2 — DVR network settings

At the DVR itself, with a monitor and mouse attached (*Configuration → Network → TCP/IP*):

1. **Turn DHCP ON**
2. Apply, wait ~30 seconds, return to the page
3. **Read the IPv4 address it now shows** — this is the DVR's address on the router

Do not set a static IP at this stage. See *Lessons learned* for why this caused the biggest delay in Phase 1.

### Step 3 — Verify from a laptop

1. Join the router's Wi-Fi on a laptop
2. Browse to `http://<dvr-address>` — the DVR login page should appear
3. Log in with the admin account

If the page loads, the network is proven and everything downstream is a software question.

### Step 4 — DVR configuration

In the web UI:

| Setting | Location | Value |
|---|---|---|
| ISAPI / Hikvision-CGI | Network → Advanced → Integration Protocol | **Enabled** |
| Illegal Login Lock | System → Security | **Off** while developing |
| Stream type | Record → Encoding (per channel) | **Video** |
| Video encoding | Record → Encoding (per channel) | **H.264** |
| I-frame interval | Record → Encoding | **= frame rate** (25 fps → 25) |
| Sub-stream | Record → Encoding | **Enabled** on every channel |

Full system map (tools, diagrams, all settings): [`docs/SYSTEM-ARCHITECTURE.md`](docs/SYSTEM-ARCHITECTURE.md).

### Step 5 — Android app

Point the app at the DVR's address with the admin credentials. Video plays.

**App fields that match this install:**

| Field | Value |
|---|---|
| DVR IP | `192.168.0.100` |
| HTTP port | `80` |
| RTSP port | `554` |
| Username | `admin` |
| Password | *(admin password — never commit it)* |
| Default stream | Sub for grid / bandwidth; Main for fullscreen |

---

## 3. RTSP URL format

```
rtsp://<user>:<pass>@<dvr-ip>:554/Streaming/Channels/<id>
```

`<id>` = **channel number × 100 + stream type**, where `1` = main stream, `2` = sub-stream.

| Camera | Main | Sub |
|---|---|---|
| 1 | 101 | 102 |
| 2 | 201 | 202 |
| 3 | 301 | 302 |
| 4 | 401 | 402 |
| 8 | 801 | 802 |

**Confirmed working:** `rtsp://admin:<password>@192.168.0.100:554/Streaming/Channels/102`

If the password contains `@` or other reserved characters, URL-encode them in VLC (e.g. `@` → `%40`). The Android app encodes credentials when building the URL — enter the password normally in the setup form.

One URL returns one camera. Channel switching is a matter of changing that number — this is why the RTSP approach was chosen over HDMI capture, which can only produce the DVR's combined on-screen mosaic.

Use **sub-streams** for grid views and anything bandwidth-sensitive; **main streams** for full-screen viewing.

### Related endpoints (Phase 2)

- Snapshot JPEG: `http://<ip>/ISAPI/Streaming/channels/<id>/picture`
- Channel discovery: `http://<ip>/ISAPI/Streaming/channels` (digest auth)

---

## 4. Lessons learned

### The static IP mistake — biggest time sink of Phase 1

The DVR was initially given a static address of **192.168.3.57**. It became completely unreachable, and this looked like a broken DVR or broken cable for some time.

**Cause:** that address was inherited from a previous network. Once plugged into a router using the 192.168.0.x range, an address on 192.168.3.x is on a different subnet — invisible to every other device, even though the cable is physically connected and the link light is on.

**Fix:** turn DHCP back on and let the router assign an address. Instant.

**Rule going forward:** always use DHCP first. A static address is only safe once it is known to match the router's range, and it should be set via a **DHCP reservation on the router** (binding the MAC) rather than statically on the DVR. If the DVR is set statically and the values are wrong, it can only be recovered with a monitor and mouse plugged directly into the unit.

### VLC on Android is not a reliable test tool

Desktop VLC played the stream immediately. VLC for Android failed on the same URL and the same network. The `rtspt://` scheme, which forces TCP transport, produced "multiple media cannot be played" — the Android build does not recognise that scheme.

This was a red herring. Our own app, using LibVLC with `:rtsp-tcp` set programmatically, worked without trouble.

**Rule:** use desktop VLC to verify a stream. If the phone's browser can load the DVR's login page, the network path is proven — do not chase VLC-for-Android failures beyond that.

### Mobile data must be off on test phones

With mobile data enabled and the phone connected to an internet-less Wi-Fi network, Android marks that Wi‑Fi as unvalidated and routes traffic over cellular, where a 192.168.x.x address is meaningless. Requests fail with a timeout while Wi‑Fi appears connected and healthy.

This applies to the router-with-no-SIM setup by definition, since it never has internet.

*(The app also binds DVR traffic to Wi‑Fi in code; still turn mobile data off when diagnosing.)*

### NAT “external ports” are not the LAN ports

The DVR’s *Configuration → Network → NAT* page lists external ports (e.g. RTSP `62364`, HTTP `26924`). Those are for WAN/UPnP mapping. With NAT Off and LAN use, the real ports are under *More Settings*: **HTTP 80**, **RTSP 554**.

### Diagnostic order that works

1. Link light on the DVR's Ethernet port?
2. Does the **phone or laptop browser** load `http://<dvr-ip>`? → proves the network
3. Does **desktop VLC** play the RTSP URL? → proves the stream
4. Only then debug the app

Splitting network from stream from app at each stage is what makes problems findable.

---

## 5. Approaches evaluated and rejected

Recorded so these are not revisited.

| Approach | Why rejected |
|---|---|
| **HDMI capture card → USB (UVC)** | Android's camera framework does not enumerate external USB capture devices — `VideoCapture(0)` cannot open them. Requires native libuvc work, and many cheap boxes ship kernels without USB Video Class support. More fundamentally, HDMI carries the DVR's *screen* (mosaic + clock + mouse overlays), not clean per-channel feeds, so channel switching and per-channel analysis become impossible. Usually takes over the customer's only monitor. |
| **Reading the DVR hard disk directly** | Proprietary filesystem, requires forensic tools, needs the DVR powered down. Post-incident recovery technique, not a live feed. |
| **Phone as USB storage for the DVR** | DVR USB ports are host ports for a mouse or export drive. Modern Android cannot present as USB mass storage. Wrong direction. |
| **Direct cable, DVR to Android box, no router** | Sound in principle but loses DHCP (both ends need static IPs), may need a crossover cable on 10/100M ports without auto-MDI-X, and permits only one device — no laptop for diagnostics, no second viewer. The router-with-no-SIM achieves the same isolation while keeping all of that. |
| **Switching to a Dahua XVR** | Same generation and capability tier as Hikvision Turbo HD, not more modern. Different API strings, identical architecture. No network-free video path — no coax recorder has one. |
| **Port forwarding for remote access** | Exposes the DVR to constant internet scanning. Hikvision DVRs are heavily targeted. Phase 2 will use an outbound tunnel instead. |

---

## 6. Known constraints

**Concurrent streams.** The DVR permits roughly 6–10 simultaneous RTSP sessions across all clients. Cheap Android SoCs handle only about 2–4 hardware decode sessions before falling back to software decoding and stuttering. Grid view is therefore capped at 4 tiles on sub-streams.

**Zero-channel encoding.** If the DVR supports it (enabled in the web UI), it composites all channels into a single stream — one session, one decoder. Worth testing for grid view. Path varies by firmware.

**The DVR has no internet on this network.** Consequences:

- NTP time sync will not work — set the DVR clock manually, and check it periodically
- Hik-Connect and the customer's existing remote app will not work through this path. If they currently use it, raise this **before** install
- Phase 2's cloud upload needs a second network interface on the Android box: Ethernet or Wi‑Fi to the DVR router, and a separate path to the internet. Confirm the target box holds two interfaces up simultaneously — some cheap boxes disable one when the other connects

**Analogue image quality.** Coax cameras through DVR compression give soft frames. Adequate for "person at gate"; unreliable for faces or number plates. Where identification matters, one IP camera on that view beats several analogue ones.

---

## 7. Security

- **Change the DVR admin password.** The password used during development has been shared in plain text through chat logs and notes. It is the single credential the entire system depends on.
- Create a separate operator account for the app rather than using admin, if the firmware permits ISAPI access for non-admin users — test this, as some versions restrict it.
- Credentials in the app must be stored in EncryptedSharedPreferences, never logged, never in crash reports.
- Re-enable **Illegal Login Lock** before handing a unit to a customer.

---

## 8. Per-install checklist

- [ ] Router powered, no SIM needed
- [ ] Ethernet cable: DVR → router, link light confirmed
- [ ] DVR set to DHCP, address noted
- [ ] DVR login page loads in a browser
- [ ] ISAPI enabled
- [ ] Stream type set to **Video** on used channels
- [ ] Video encoding set to **H.264** on used channels
- [ ] Sub-stream enabled on all channels
- [ ] I-frame interval set to match frame rate
- [ ] DVR clock set manually (no NTP)
- [ ] Admin password changed from default
- [ ] Illegal Login Lock re-enabled
- [ ] All channels verified playing in the app
- [ ] Model, firmware, DVR address, and MAC recorded

---

## 9. Open items for Phase 2

1. Confirm the deployment box keeps Ethernet and Wi‑Fi active simultaneously
2. Test whether zero-channel encoding is available on this DVR model
3. Test the DVR's native motion detection quality before building our own diffing
4. Decide whether the box is the customer's TV player or a headless always-on relay — if it is the player, it gets switched off and remote access dies with it
5. Confirm the codec in use (H.264 vs H.265) is recorded for every deployed model

---

## 10. Building the Android app

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`

- `minSdk 24` · `targetSdk 34` · Kotlin
- Player: LibVLC `org.videolan.android:libvlc-all:3.6.5` (3.7.5+ needs compileSdk 36)

### Project layout

```
data/       DvrConfig, RelaySettings, EncryptedPrefs storage
network/    IsapiClient (digest), IsapiXmlParser, RtspUrlBuilder, WifiNetworkBinder
player/     VideoSource, VlcVideoSource, PlayerState
forwarder/  ForwarderServer (TCP relay / socat replacement)
service/    ForwarderService, BootReceiver
ui/         SetupActivity, LiveViewActivity, GridActivity, LogActivity, RelayActivity
docs/       Novice + technical documentation (start at docs/README.md)
```
