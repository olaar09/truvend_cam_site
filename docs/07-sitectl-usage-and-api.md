# sitectl — Usage and Backend Integration

**One command to provision a site across WireGuard, the registry, and MediaMTX.**

Related: script [`scripts/sitectl`](../scripts/sitectl) · short guide [06 — sitectl](06-sitectl.md) · technical [SITECTL.md](technical/SITECTL.md)

---

# Part 1 — Installation

## 1.1 Put it in place

```bash
# copy sitectl to the VPS, then
mv sitectl /usr/local/bin/sitectl
chmod +x /usr/local/bin/sitectl
```

(From this repo: `scripts/sitectl`.)

Now it runs as `sitectl` from anywhere.

## 1.2 First run creates the settings file

```bash
sitectl list
```

It will create `/etc/relay/relay.env` and stop, telling you to edit it. That is intentional — it refuses to generate anything until the endpoint is real.

```bash
nano /etc/relay/relay.env
```

```bash
# Public address boxes dial. A DOMAIN is strongly preferred:
# if the VPS ever moves, every deployed box would otherwise need
# reconfiguring by hand, on site.
RELAY_ENDPOINT="relay.yourdomain.com:51820"

# Tunnel network — must match the Address in /etc/wireguard/wg0.conf
WG_SUBNET="10.8.0"
WG_CIDR="10.8.0.0/16"

# Required. Without it tunnels die when idle and revive on test,
# so they appear to work every time you check.
KEEPALIVE=25

# Public address viewers use for WebRTC
WEBRTC_HOST="relay.yourdomain.com"
WEBRTC_PORT=8889
```

Use the raw VPS IP only as a stopgap.

## 1.3 Server keys

`sitectl` expects the server keypair at `/etc/relay/keys/`. If yours live in `/etc/wireguard/keys/` it copies them automatically on first run. Otherwise:

```bash
mkdir -p /etc/relay/keys
cp /etc/wireguard/keys/server_*.key /etc/relay/keys/
chmod 600 /etc/relay/keys/server_private.key
```

## 1.4 What gets created

```
/etc/relay/
├── relay.env               settings, edit once
├── sites.db                THE SOURCE OF TRUTH
├── keys/                   one keypair per site
├── client-configs/         configs to paste into boxes
└── backups/                mediamtx.yml before each change
```

---

# Part 2 — Using it by hand

## Add a site

```bash
sitectl add site002 --dvr-pass mypassword
```

Options:

| Flag | Default | Notes |
|---|---|---|
| `--dvr-user` | `admin` | DVR login |
| `--dvr-pass` | *required* | DVR password |
| `--channels` | `4` | Number of cameras |
| `--json` | off | Machine-readable output |

Full example:

```bash
sitectl add lekki-office --dvr-user admin --dvr-pass 'Str0ng!Pass' --channels 8
```

Quote passwords containing special characters.

**What it does, in order:**

1. Allocates the next free tunnel IP
2. Generates a keypair for the site
3. Adds the WireGuard peer **live** — existing sites stay connected
4. Records everything in `sites.db`
5. Regenerates MediaMTX paths for all sites
6. Restarts MediaMTX
7. Prints the client config, a QR code, and the camera URLs

**What you do with the output:** paste the config into the WireGuard app on that site's box, or scan the QR code.

## List sites

```bash
sitectl list
```

```
SITE         TUNNEL IP    CHANNELS  LAST HANDSHAKE
site001      10.8.0.3     4         42s ago
lekki-office 10.8.0.4     8         never
```

**Last handshake is your health check.** Under two minutes means connected. "Never" means the box has not been set up yet, or the config was never applied.

## Show one site

```bash
sitectl show site001
```

Prints the raw registry line, including the DVR password. Be aware of who is looking at the screen.

## Remove a site

```bash
sitectl remove site002
```

Removes the WireGuard peer, deletes the keys and config, drops it from the registry, and regenerates MediaMTX.

**Its tunnel IP is never reused** — allocation takes the highest existing address rather than counting entries, so a new site can never inherit a decommissioned one's address.

## Sync

```bash
sitectl sync
```

Rebuilds WireGuard peers and MediaMTX paths from the registry, without changing any keys.

Use it when:

- You edited `sites.db` by hand (changed a password or channel count)
- MediaMTX and reality have drifted
- After restoring a backup
- Migrating an existing hand-made site into the registry

Safe to run any time. It is idempotent.

## Re-print a config

```bash
cat /etc/relay/client-configs/site001.conf
qrencode -t ansiutf8 < /etc/relay/client-configs/site001.conf
```

Useful when reinstalling a box.

---

# Part 3 — Migrating an existing site

If you set a site up by hand before `sitectl` existed, add it to the registry rather than recreating it — that way the box keeps working with its current config.

The format is pipe-separated:

```
name|tunnel_ip|dvr_user|dvr_pass|channels|public_key|created
```

Get the public key from `wg show` (the peer whose allowed-ips match that site), then:

```bash
echo 'site001|10.8.0.3|admin|setup_112|4|IypCBzjqQbAjlwbLRaV+68bLFRt34mQ51ghsGWz+Hig=|2026-07-23' \
  >> /etc/relay/sites.db

sitectl sync
sitectl list
```

The site should appear with a live handshake, and its MediaMTX paths are now generated alongside every other site.

---

# Part 4 — Backend integration

## 4.1 The shape

The API service does one thing: translate HTTP into a shell command and pass the JSON back.

```
Dashboard ──HTTP──▶ API service ──execFile──▶ sitectl ──▶ WireGuard
                                                       └─▶ MediaMTX
                         ◀────── JSON ───────┘
```

Because `sitectl` already emits JSON, the wrapper is roughly a hundred lines and contains no provisioning logic of its own.

## 4.2 Endpoint mapping

| HTTP | Command |
|---|---|
| `POST /api/sites` | `sitectl add <name> --dvr-user U --dvr-pass P --channels N --json` |
| `DELETE /api/sites/:name` | `sitectl remove <name> --json` |
| `GET /api/sites` | `sitectl list --json` |
| `POST /api/sync` | `sitectl sync --json` |

## 4.3 Implementation

```javascript
const express = require('express');
const { execFile } = require('child_process');
const app = express();
app.use(express.json());

const SITECTL = '/usr/local/bin/sitectl';

// Run sitectl and parse its JSON. Never builds a shell string.
function run(args) {
  return new Promise((resolve, reject) => {
    execFile(SITECTL, [...args, '--json'], { timeout: 30000 },
      (err, stdout, stderr) => {
        try {
          resolve(JSON.parse(stdout));
        } catch {
          reject(new Error(stderr || 'sitectl produced no JSON'));
        }
      });
  });
}

app.get('/api/sites', requireAuth, async (req, res) => {
  try {
    res.json(await run(['list']));
  } catch (e) {
    res.status(500).json({ ok: false, error: e.message });
  }
});

app.post('/api/sites', requireAuth, async (req, res) => {
  const { name, dvr_user = 'admin', dvr_pass, channels = 4 } = req.body;

  // Validate before anything reaches the shell
  if (!/^[a-zA-Z0-9_-]{1,32}$/.test(name || '')) {
    return res.status(400).json({ ok: false, error: 'invalid site name' });
  }
  if (!dvr_pass) {
    return res.status(400).json({ ok: false, error: 'dvr_pass required' });
  }
  if (!Number.isInteger(channels) || channels < 1 || channels > 32) {
    return res.status(400).json({ ok: false, error: 'channels must be 1-32' });
  }

  try {
    const result = await run([
      'add', name,
      '--dvr-user', String(dvr_user),
      '--dvr-pass', String(dvr_pass),
      '--channels', String(channels)
    ]);
    res.status(result.ok ? 201 : 400).json(result);
  } catch (e) {
    res.status(500).json({ ok: false, error: e.message });
  }
});

app.delete('/api/sites/:name', requireAuth, async (req, res) => {
  if (!/^[a-zA-Z0-9_-]{1,32}$/.test(req.params.name)) {
    return res.status(400).json({ ok: false, error: 'invalid site name' });
  }
  try {
    res.json(await run(['remove', req.params.name]));
  } catch (e) {
    res.status(500).json({ ok: false, error: e.message });
  }
});

// Bind to localhost only — this service creates VPN peers as root
app.listen(3000, '127.0.0.1', () => console.log('API on 127.0.0.1:3000'));
```

## 4.4 Security — the part that matters

**Use `execFile` with an argument array. Never `exec` with a template string.**

```javascript
// CATASTROPHIC — a password of  x; rm -rf /  runs as a second command
exec(`sitectl add ${name} --dvr-pass ${pass}`);

// CORRECT — arguments go straight to the program, no shell involved
execFile('sitectl', ['add', name, '--dvr-pass', pass]);
```

With `execFile`, special characters in a password are inert data. With `exec`, they are code running as root.

**Also:**

- **Bind to `127.0.0.1`.** This service creates VPN peers as root. It must never be directly reachable from the internet. Put your dashboard in front of it, or reverse-proxy with authentication.
- **Validate the site name with a regex.** It becomes a filename and a MediaMTX path.
- **Require an admin session on every endpoint.** A leaked endpoint here lets someone enrol themselves into your private network.
- **Never log `dvr_pass` or the returned config.** The response contains a private key.
- **Set a timeout.** MediaMTX restarts take a couple of seconds; 30s is generous but bounded.

## 4.5 Running the API as a service

```ini
# /etc/systemd/system/relay-api.service
[Unit]
Description=Relay provisioning API
After=network.target

[Service]
ExecStart=/usr/bin/node /opt/relay-api/server.js
Restart=always
User=root
WorkingDirectory=/opt/relay-api

[Install]
WantedBy=multi-user.target
```

Running as root is required for `wg set` and writing to `/etc/relay`. That is precisely why it must be bound to localhost and authenticated.

## 4.6 Dashboard flow

1. Operator fills a form: site name, DVR credentials, channel count
2. `POST /api/sites`
3. Response contains `config` and `urls`
4. Dashboard renders the config as text **and** as a QR code, using any client-side QR library
5. Installer scans it on site
6. Dashboard polls `GET /api/sites` and shows `last_handshake` — under 120 seconds means the site is live

That last point is worth building early: it turns "did the install work?" into something visible from the office.

---

# Part 5 — Backup

The registry and keys are the only things that cannot be regenerated.

```bash
tar czf relay-backup-$(date +%F).tar.gz /etc/relay
gpg -c relay-backup-*.tar.gz          # encrypt — it contains private keys
```

Store it off the server. Losing `/etc/relay` means visiting every site in person to re-provision.

Everything else — `wg0.conf`, `mediamtx.yml` — regenerates from the registry with `sitectl sync`.

---

# Part 6 — Troubleshooting

| Symptom | Cause |
|---|---|
| `set RELAY_ENDPOINT first` | Edit `/etc/relay/relay.env` |
| `wg0 is not up` | `wg-quick up wg0` |
| `server keys not found` | Copy them into `/etc/relay/keys/` |
| `site already exists` | Use a different name, or `sitectl remove` first |
| MediaMTX times out after adding | Check the path has `rtspTransport: tcp` — `sitectl` sets it in `pathDefaults`, so run `sitectl sync` if the config was edited by hand |
| Site shows "never" for handshake | Config not applied to the box, or the box has no internet |
| Adding a site dropped other tunnels | Should not happen — `sitectl` uses `wg set`, not a restart. If it did, check nothing else restarted `wg-quick` |

---

## Related

- Quick start: [06 — sitectl](06-sitectl.md)
- **Day-to-day VPS ops:** [08 — Server operations](08-server-operations.md)
- Holistic architecture: [SYSTEM-ARCHITECTURE.md](SYSTEM-ARCHITECTURE.md)
- Technical reference: [technical/SITECTL.md](technical/SITECTL.md)
- Script: [../scripts/sitectl](../scripts/sitectl)
