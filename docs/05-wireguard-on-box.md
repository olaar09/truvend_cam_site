# 5 — WireGuard on the phone or TV box

**The most important step in the whole system. If this is wrong, nothing else works.**

Written for someone who has never used a VPN. Follow in order.

**Related:** server side is [04 — VPS server setup](04-vps-server-setup.md). After the tunnel is up, turn on the [RTSP relay](02-rtsp-relay.md).

---

## What this step does

The DVR sits on a private network inside a building. Nothing on the internet can reach it, and we do not want to change that — opening it up would expose the DVR to attack.

So instead of the internet reaching *in*, we make a device inside the building reach *out* to our server and hold that connection open. Traffic can then travel back down the same connection.

WireGuard is what creates and holds that connection.

```
Building                                    Internet
────────                                    ────────

[ DVR ] ── [ Router ] ── [ Box ] ═══════════▶ [ Server ]
                            │                      │
                            └── dials outward ─────┘
                                (firewalls allow this)
```

After this step, the server can reach the box as if they were in the same room.

---

## Before you start

You need three things from the server. Running `sitectl add site001 --dvr-pass '…'` produces all of them (also under `/etc/relay/client-configs/`):

| Item | Looks like | Where from |
|---|---|---|
| Client private key | `aFq3...=` (44 characters) | `/etc/relay/client-configs/site001.conf` |
| Server public key | `lBHF...=` (44 characters) | Same file |
| Server address and port | `relay.example.com:51820` | Same file (`Endpoint`) |

The command also prints a **QR code**. If you can scan it, skip most of this guide.

Use the real values from **your** `site-configs` file — examples below are illustrative only.

---

## Method 1 — QR code (easiest)

Best for phones. Takes about 30 seconds.

1. On the server: `qrencode -t ansiutf8 < /root/site-configs/site001.conf`
2. Install **WireGuard** from the Play Store on the phone
3. Open it, tap **+**, choose **Scan from QR code**
4. Point the camera at the terminal
5. Give the tunnel a name, tap the toggle to connect

Done. Skip to [Testing](#testing) below.

---

## Method 2 — Type it in (TV boxes)

TV boxes usually have no camera, so the config goes in by hand.

### 2.1 Install WireGuard

**If the box has the Play Store:** search for WireGuard, published by *WireGuard Development Team*, and install.

**If it does not:** download the APK from [wireguard.com](https://www.wireguard.com/install/) on a laptop, copy it to a USB stick, plug that into the box, and open it with a file manager. You may need to allow "install from unknown sources".

> ### Plug in a USB mouse first
>
> Every TV box has USB ports. A cheap mouse turns awkward remote-control navigation into simple clicking, and you will be typing long keys. Keep one in the install kit.

### 2.2 Create the tunnel

Open WireGuard → **+** → **Create from scratch**.

**Interface section** (this device):

| Field | What to enter |
|---|---|
| Name | `camera-relay` — any name |
| Private key | Paste from the config file |
| Public key | Fills in automatically. Leave it |
| Addresses | `10.8.0.2/32` — **this box’s** tunnel address from `sitectl` |
| Listen port | Leave blank |
| DNS servers | Leave blank |
| MTU | Leave blank |

**Peer section** (the server) — tap *Add peer*:

| Field | What to enter |
|---|---|
| Public key | The **server’s** public key |
| Pre-shared key | Leave blank |
| Endpoint | e.g. `102.89.69.47:51820` — server public IP and port |
| Allowed IPs | `10.8.0.0/16` |
| Persistent keepalive | `25` |

Save.

> ### The two settings people get wrong
>
> **Allowed IPs must be `10.8.0.0/16`, not `0.0.0.0/0`.**
>
> This says: *only send tunnel-network traffic through the tunnel*. Everything else — normal internet, and crucially the DVR on the LAN — goes out normally.
>
> Set it to `0.0.0.0/0` and **everything** goes through the tunnel, including traffic meant for the DVR. The box loses the ability to reach the DVR at all, and the fault is confusing because the tunnel itself looks perfectly healthy.
>
> **Persistent keepalive must be 25.**
>
> Routers forget about idle connections after a few minutes. This sends a tiny packet every 25 seconds so the path stays open. Without it the tunnel works, then quietly dies when nobody is watching, and appears to work again as soon as you test it. Very hard to diagnose.

### 2.3 Connect

Tap the toggle. Android shows a **connection request** dialog once — tap **OK** or **Allow**.

That prompt is Android asking permission to route traffic. It cannot be skipped and appears only once per install.

---

## Making it permanent

By default the tunnel stops when the box reboots. For an unattended site that is unacceptable.

### Always-on VPN

*Settings → Network & Internet → VPN* (or *Settings → Connections → VPN*)

Tap the gear beside WireGuard and enable:

- **Always-on VPN** — reconnects automatically, including after reboot
- **Block connections without VPN** — optional, and be careful: it also blocks traffic when the tunnel is down, which can prevent recovery. Leave it **off** unless you have a specific reason

### If the VPN menu is missing

Some TV box builds hide it. Enable over ADB from a laptop:

```bash
adb connect <box-ip>:5555
adb shell settings put global always_on_vpn_app com.wireguard.android
```

Developer options and USB debugging must be on.

### Battery optimisation

*Settings → Apps → WireGuard → Battery → Unrestricted*

Android aggressively suspends background apps. Without this the tunnel dies overnight and reconnects when someone picks up the device — which makes it look like it was working all along.

**This is the most common cause of tunnels that “randomly” stop.**

Also set **Truvend Cam** to Unrestricted battery — the [RTSP relay](02-rtsp-relay.md) has the same overnight-death failure mode.

---

## Testing

Work through these in order. Do not skip ahead — each proves a different layer.

### Test 1 — Is the tunnel up?

In the WireGuard app, tap the tunnel name. Look for **Latest handshake**.

- **A few seconds or minutes ago** → connected
- **Never / nothing** → not connected

On the Truvend Cam **RTSP relay** screen, **This device IPs** should also show a `10.8.0.x` address when the tunnel is up.

### Test 2 — Does the server see it?

On the server:

```bash
wg show
```

Expected shape:

```
peer: …
  endpoint: <box-public-or-nat>:…
  allowed ips: 10.8.0.2/32
  latest handshake: 21 seconds ago
  transfer: … received, … sent
```

Check that **allowed ips** matches the address you gave the box. A missing peer means the key was never added on the server side (`sitectl add`).

### Test 3 — Can the server reach the box?

```bash
ping -c 3 10.8.0.2
```

Replies mean the tunnel carries traffic in both directions. (Use the real tunnel IP for this site.)

### Test 4 — Can the box still reach the DVR?

On the box, open a browser and go to `http://<dvr-lan-ip>` (e.g. `http://192.168.0.60`).

The DVR login page must still appear **with the tunnel connected**. If it does not, Allowed IPs is wrong — see the warning above.

### Test 5 — The whole path

Start the [RTSP relay](02-rtsp-relay.md) on the box (**SERVER: LISTENING** — stop Termux socat if it was still using 8554), then on the server:

```bash
ffprobe -rtsp_transport tcp \
  rtsp://admin:PASSWORD@10.8.0.2:8554/Streaming/Channels/102
```

Success looks like:

```
Stream #0:0: Video: h264 (High), yuv420p, 704x576, 15 fps
```

That is the full chain working: server → tunnel → box → DVR.

---

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| No handshake, ever | Wrong endpoint address, or UDP port blocked | Check the IP, and `ufw allow 51820/udp` (plus cloud firewall) |
| No handshake, ever | Keys mismatched | The box’s *private* key pairs with the *public* key on the server. Easy to swap |
| Handshake works, ping fails | Allowed IPs wrong on the server | Server needs `AllowedIPs = 10.8.0.2/32` for that peer |
| Tunnel up, DVR unreachable from the box | Allowed IPs is `0.0.0.0/0` on the box | Change to `10.8.0.0/16` |
| Works, then dies after minutes | Missing keepalive | Set Persistent keepalive to 25 |
| Works, then dies overnight | Battery optimisation | Set WireGuard (and Truvend Cam) to Unrestricted |
| Dies after reboot | Always-on VPN not enabled | Enable it, or use ADB |
| Handshake keeps restarting | Same keys used on two devices | Every device needs its own keypair |
| Everything correct, still nothing | Some cheap Android builds have broken VPN support | Test early on the exact box model. This is a hardware decision |

### Two commands worth knowing

Watch connections live on the server:

```bash
watch -n 1 wg show
```

Restart the tunnel on the server without dropping others:

```bash
wg syncconf wg0 <(wg-quick strip wg0)
```

---

## What to check at install

- [ ] WireGuard installed
- [ ] Config entered, private key on the box, public key on the server
- [ ] Allowed IPs is `10.8.0.0/16` — **not** `0.0.0.0/0`
- [ ] Persistent keepalive set to 25
- [ ] Permission prompt accepted
- [ ] Handshake recent in the app
- [ ] `wg show` on the server lists this site
- [ ] `ping 10.8.0.x` from the server replies
- [ ] Box can still open the DVR page with the tunnel on
- [ ] Always-on VPN enabled
- [ ] Battery optimisation disabled (WireGuard **and** Truvend Cam)
- [ ] Truvend Cam relay shows **SERVER: LISTENING**
- [ ] Rebooted the box and it reconnected by itself

That last one is the real test. Everything else can pass while the site still fails after the first power cut.

---

## You are done when

- [ ] Handshake is recent on phone **and** on `wg show`
- [ ] Ping to the box tunnel IP works from the VPS
- [ ] DVR web UI still loads on the box with VPN on
- [ ] `ffprobe` via `10.8.0.x:8554` works with the app relay
- [ ] Box survives reboot with Always-on VPN

## Next / related

- ← [04 — VPS server setup](04-vps-server-setup.md)
- → [02 — RTSP relay](02-rtsp-relay.md) (turn this on after the tunnel)
- Provision sites: [06 — sitectl](06-sitectl.md)
- VPS ops: [08 — Server operations](08-server-operations.md)
- Technical notes: [WireGuard client technical](technical/WIREGUARD-CLIENT.md)
- Docs home: [README](README.md)
