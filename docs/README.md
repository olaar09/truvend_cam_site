# Truvend Cam — Documentation

**Last updated:** July 2026  
**Status today:** Local live view works · In-app RTSP relay works (replaces Termux `socat`) · WireGuard still a separate app

---

## Start with the whole picture

**→ [SYSTEM-ARCHITECTURE.md](SYSTEM-ARCHITECTURE.md)** — single document: architecture diagrams, all server tools, all phone/Android tools, DVR settings (including **Stream type = Video**, **encoding = H.264**, **H.264+ enabled**), ports, install order, and proof ladder.

---

## Step-by-step (novice path)

Read these in order after the architecture page. Each page ends with a short “you are done when…” checklist.

| # | Page | What it covers |
|---|---|---|
| 0 | [Overview — the big picture](00-overview.md) | Short intro + status cheat sheet |
| 1 | [Local live view](01-local-live-view.md) | DVR + router + phone on the same Wi‑Fi; video in the app |
| 2 | [RTSP relay (replaces socat)](02-rtsp-relay.md) | What **SERVER: LISTENING** means; how to turn the relay on; how to test |
| 3 | [Remote path (WireGuard + VPS)](03-remote-tunnel.md) | How video reaches a browser off-site |
| 4 | [VPS server setup](04-vps-server-setup.md) | One-time `setup-server.sh` (WireGuard + MediaMTX + firewall) |
| 5 | [WireGuard on the phone / TV box](05-wireguard-on-box.md) | Install tunnel on site device (QR or manual) — most important step |
| 6 | [`sitectl` — provision a site](06-sitectl.md) | Add/remove/list sites (WG + registry + MediaMTX) |
| 7 | [`sitectl` usage & API integration](07-sitectl-usage-and-api.md) | Full usage, migration, future HTTP API wrapper |
| 8 | [Server operations reference](08-server-operations.md) | Day-to-day VPS ops, diagnostics, backup, known gaps |
| 9 | [DVR periodic segmentation](09-dvr-segmentation.md) | Box-side stop→start so playback segments stay bounded |
| — | [HTTPS for the Relay (MediaMTX behind nginx)](../../../backend/plexity-ai-chat/bash/HTTPS-RELAY.md) | TLS for Chrome/Safari WebRTC on `relay.truvend.online` |

## Technical deep-dive

Same topics, more detail for later you (or a developer).

| Page | Contents |
|---|---|
| [Technical reference](technical/REFERENCE.md) | Architecture, ports, RTSP URLs, Android classes, reliability rules, test matrix |
| [Server technical reference](technical/SERVER.md) | WireGuard/MediaMTX script behaviour, ports, path templates, ops |
| [WireGuard client technical](technical/WIREGUARD-CLIENT.md) | Split tunnel, keepalive, always-on VPN, relay interaction |
| [`sitectl` technical](technical/SITECTL.md) | Registry, regenerate MediaMTX, API-shaped CLI |
| [Phase 1 as-built (archive)](../README.md) | Original Phase 1 write-up (lessons learned, rejected approaches) |

## Quick map of the system

```
On site                          Off site
───────                          ────────
DVR ──LAN──▶ Android box         WireGuard VPS ──▶ MediaMTX ──▶ Browser
                 │                      ▲
                 │   WireGuard tunnel   │
                 └──────────────────────┘
                 │
                 └── RTSP relay in our app
                     (was: Termux socat)
```

**Rule of thumb**

- **No tunnel needed** → watch cameras on the phone/TV on site (Phase 1).
- **Tunnel + relay needed** → watch from elsewhere.
- **Install order for remote:** [04 VPS](04-vps-server-setup.md) → [06 sitectl](06-sitectl.md) → [05 WireGuard on box](05-wireguard-on-box.md) → [02 RTSP relay](02-rtsp-relay.md).
