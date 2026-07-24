# `sitectl` — technical notes

Companion to [06 — sitectl](../06-sitectl.md). Source: [`scripts/sitectl`](../../scripts/sitectl).

---

## Design goals

1. **One CLI owns three layers** — WireGuard peer, registry row, MediaMTX paths — so they cannot drift.
2. **Registry is authoritative** — MediaMTX YAML is generated; never the source of truth.
3. **API-shaped subcommands + `--json`** — future HTTP wrapper stays thin (`POST /api/sites`, etc.).
4. **Domain endpoint** — `RELAY_ENDPOINT` uses a hostname so VPS IP moves do not require re-provisioning every box.

---

## Data model

### `relay.env`

| Key | Default template | Notes |
|---|---|---|
| `RELAY_ENDPOINT` | `relay.example.com:51820` | Must be changed before use |
| `WG_SUBNET` | `10.8.0` | Fourth octet allocated from registry |
| `WG_CIDR` | `10.8.0.0/16` | Client `AllowedIPs` |
| `KEEPALIVE` | `25` | PersistentKeepalive |
| `WEBRTC_HOST` / `WEBRTC_PORT` | example / `8889` | URL printing only |

### `sites.db`

```
name|tunnel_ip|dvr_user|dvr_pass|channels|public_key|created
```

IP allocation: max fourth octet in registry → `+1`, starting after `.1` (server). Removed IPs are **not** reused (script message on remove).

---

## WireGuard operations

| Action | Mechanism |
|---|---|
| Add peer | `wg set wg0 peer <pub> allowed-ips <ip>/32` then `wg-quick save wg0` |
| Remove peer | `wg set … remove` + save |
| Sync | Re-`wg set` every registry peer |

Live `wg set` avoids full interface bounce. Persistence via `wg-quick save`.

Server keys: prefer `/etc/relay/keys/`; bootstrap will copy from `/etc/wireguard/keys/` if present.

---

## MediaMTX generation

`mtx_regenerate`:

1. Writes header with `pathDefaults.rtspTransport: tcp` (mandatory for Android relay).
2. For each registry row and channel `1..N`, emits:

   ```yaml
   <name>_ch<n>:
     source: rtsp://user:pass@<tunnel_ip>:8554/Streaming/Channels/<n*100+2>
   ```

3. Backs up previous `/root/mediamtx.yml` under `/etc/relay/backups/`.
4. **Writes the new config in place** (`cat tmp > mediamtx.yml`) so MediaMTX’s file watcher hot-reloads. Do **not** `mv` onto the path — that replaces the inode and the watcher silently misses the change.
5. Restarts via `systemctl restart mediamtx` as a fallback, or `pkill` + `nohup /root/mediamtx`.

Paths assume MediaMTX binary/config under `/root/` (may differ from `/usr/local` layout in older `setup-server.sh` — align paths on the host or symlink).

Day-to-day ops: [08 — Server operations](../08-server-operations.md).
---

## Client config contract

Generated `/etc/relay/client-configs/<name>.conf` must contain non-empty:

`PrivateKey`, `Address`, `PublicKey`, `Endpoint`, `AllowedIPs`, `PersistentKeepalive`

Validated before print/QR. Treat as secret (site private key).

---

## Failure / rollback notes

- `add`: if `wg set` fails after keygen, site keys are deleted; registry not written.
- `set -euo pipefail` — unset vars and failed commands abort.
- Credentials live in `sites.db` and regenerated MediaMTX YAML — protect with `chmod 600` and root-only access.

---

## Future API mapping

Full guide (install, migration, Express wrapper, security): [07 — sitectl usage and API](../07-sitectl-usage-and-api.md).

| CLI | Method | Path |
|---|---|---|
| `add` | POST | `/api/sites` |
| `remove` | DELETE | `/api/sites/:name` |
| `list` | GET | `/api/sites` |
| `show` | GET | `/api/sites/:name` |
| `sync` | POST | `/api/sync` |

`--json` responses are the intended wire format for that wrapper.

---

## Related

- [06 — Novice sitectl guide](../06-sitectl.md)
- [SYSTEM-ARCHITECTURE.md](../SYSTEM-ARCHITECTURE.md)
- [SERVER.md](SERVER.md)
- [04 — VPS setup](../04-vps-server-setup.md)
