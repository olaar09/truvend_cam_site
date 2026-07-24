# WireGuard client (phone / TV box) — technical notes

Companion to the novice guide: [05 — WireGuard on the phone or TV box](../05-wireguard-on-box.md).

---

## 1. Why outbound VPN, not inbound port-forward

Site NATs and CGNAT block unsolicited inbound connections. An outbound WireGuard peer from the box initiates UDP to `Endpoint:PORT`; return traffic follows that session. The DVR never needs a public address.

---

## 2. Client config semantics (from `add-site.sh`)

```ini
[Interface]
PrivateKey = <site private>
Address    = 10.8.0.N/32

[Peer]
PublicKey           = <server public>
Endpoint            = <vps-public-ip>:51820
AllowedIPs          = 10.8.0.0/24
PersistentKeepalive = 25
```

| Field | Effect |
|---|---|
| `Address /32` | This peer’s tunnel identity |
| `AllowedIPs = 10.8.0.0/24` | Cryptokey routing: only packets to the WG subnet use the tunnel (split tunnel) |
| `AllowedIPs = 0.0.0.0/0` | Full-tunnel — **breaks LAN DVR access** on typical installs |
| `PersistentKeepalive = 25` | Keep NAT mapping alive through site routers |
| Server peer `AllowedIPs = 10.8.0.N/32` | Server may send to this client only |

Key pairing: client **private** ↔ client **public** listed on server `[Peer]`. Swapping keys produces silent handshake failure.

---

## 3. Interaction with the Android RTSP relay

```
VPS ──WG──▶ 10.8.0.N:8554 (ForwarderServer on box)
                │
                └── LAN TCP ──▶ DVR:554
```

- Relay must listen on **all interfaces** (`0.0.0.0:8554`) so the WG interface can accept connections even if it appears after boot.
- With standalone WireGuard + split tunnel, DVR sockets do **not** need `VpnService.protect`.
- When WireGuard is later embedded via `VpnService`, call `protect()` on the outbound DVR socket (`ForwarderServer.prepareDvrSocket`).

Do not run Termux `socat` on 8554 concurrently with the app relay.

---

## 4. Always-on VPN / battery

| Setting | Package / key | Notes |
|---|---|---|
| Always-on VPN | `com.wireguard.android` | Survives reboot |
| `always_on_vpn_lockdown` | Prefer **off** | Lockdown blocks recovery traffic when WG is down |
| Battery unrestricted | WireGuard + Truvend Cam | Prevents overnight Doze kills |

ADB fallback when Settings UI hides VPN:

```bash
adb shell settings put global always_on_vpn_app com.wireguard.android
```

---

## 5. Layered verification

| Layer | Check |
|---|---|
| L3 tunnel | App handshake; `wg show` handshake age; `ping 10.8.0.N` |
| LAN still works | Browser to `http://<dvr-lan-ip>` with WG **on** |
| Application relay | `ffprobe -rtsp_transport tcp rtsp://…@10.8.0.N:8554/…` |
| Persistence | Reboot box → handshake returns without touch |

`watch -n 1 wg show` on the VPS during install.

---

## 6. Hardware caveat

Some low-cost Android TV firmwares have incomplete `VpnService` support. Validate the exact SKU before bulk purchase; software cannot fix a broken VPN stack.

---

## Related

- [05 — Novice WireGuard-on-box guide](../05-wireguard-on-box.md)
- [04 — VPS setup](../04-vps-server-setup.md)
- [Server technical](SERVER.md)
- [Overall technical reference](REFERENCE.md)
