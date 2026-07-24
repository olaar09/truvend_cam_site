# Restore — Rebuild the Relay Server From Backup

**Rebuilds a working relay VPS from the snapshot tarball onto a blank Ubuntu server.**

Use this when the droplet is gone, corrupted, or you are migrating. If the droplet merely rebooted or MediaMTX stopped, you do not need this — see the operations reference.

---

## What you need

1. The snapshot tarball `relay-snapshot-YYYY-MM-DD.tar.gz` (decrypt first if gpg-encrypted).
2. A fresh Ubuntu 22.04 or 24.04 server with root.
3. The same domain, which you will re-point at the new server's IP. This is the one thing the backup cannot contain.

The tarball holds the irreplaceable state: sites.db, all keys, relay.env, and generated configs. Everything else is reinstalled or regenerated.

---

## Step 1 — Get the backup onto the new server

Decrypt on your Mac if needed:

    gpg relay-snapshot-2026-07-24.tar.gz.gpg

Upload via Cyberduck (SFTP), or:

    scp relay-snapshot-2026-07-24.tar.gz root@NEW_SERVER_IP:/root/

---

## Step 2 — Install software

    apt update
    apt install -y wireguard wireguard-tools ufw curl wget tar ffmpeg qrencode netcat-openbsd
    echo "net.ipv4.ip_forward=1" > /etc/sysctl.d/99-wireguard.conf
    sysctl -p /etc/sysctl.d/99-wireguard.conf

---

## Step 3 — Install MediaMTX

Check current version at github.com/bluenviron/mediamtx/releases:

    cd /tmp
    VER=v1.19.3
    wget "https://github.com/bluenviron/mediamtx/releases/download/${VER}/mediamtx_${VER}_linux_amd64.tar.gz"
    tar -xzf mediamtx_*.tar.gz
    mv mediamtx /root/mediamtx
    chmod +x /root/mediamtx

The config comes from the backup next, so ignore the yml in this download.

---

## Step 4 — Restore the backup

    cd /
    tar xzf /root/relay-snapshot-2026-07-24.tar.gz

Fix permissions (tar may not preserve them fully):

    chmod 700 /etc/relay /etc/relay/keys
    chmod 600 /etc/relay/keys/* /etc/relay/sites.db /etc/wireguard/wg0.conf
    chmod +x /usr/local/bin/sitectl /root/mediamtx

---

## Step 5 — Point the domain at the new server  (THE KEY STEP)

Every box dials relay.truvend.online. Point that A record at the new server's IP and every box reconnects on its own. Leave it pointing at the dead server and nothing connects.

Update the A record at your DNS provider, then confirm:

    dig +short relay.truvend.online
    curl -s https://api.ipify.org

Both must print the new server's IP before continuing. This is why the endpoint is a domain, not a raw IP — recovery is one DNS change instead of visiting every site.

---

## Step 6 — Start everything

    systemctl enable wg-quick@wg0
    systemctl start wg-quick@wg0
    wg show

    systemctl daemon-reload
    systemctl enable mediamtx
    systemctl start mediamtx
    systemctl status mediamtx

---

## Step 7 — Firewall

    ufw allow 22/tcp
    ufw allow 51820/udp
    ufw allow 8889/tcp
    ufw allow 8189/udp
    ufw --force enable

---

## Step 8 — Reconcile and verify

    sitectl sync
    sitectl list

Wait a minute or two for boxes to notice the new endpoint. Handshakes go recent as each keepalive fires. Then:

    sitectl verify site001

Green OK means the full path works through the rebuilt server.

---

## The boxes need no attention

You touch no box. Each keeps dialing relay.truvend.online, and once DNS points at the new server its next handshake succeeds — usually within a minute or two. A box powered off during the outage reconnects whenever it next comes on.

If one does not reconnect:

- sitectl list — is it "never" or an old timestamp (powered off vs never configured)
- dig +short relay.truvend.online from the box's network — did DNS propagate
- wg show — is its peer present; if missing but in sites.db, run sitectl sync again
- ufw status — 51820/udp must be open

---

## Keep the backup current

Only as good as the last backup. sites.db changes on every add/remove. Re-take after changes:

    tar czf /root/relay-snapshot-$(date +%F).tar.gz \
        /etc/relay \
        /usr/local/bin/sitectl \
        /etc/wireguard/wg0.conf \
        /root/mediamtx.yml \
        /etc/systemd/system/mediamtx.service
    gpg -c /root/relay-snapshot-$(date +%F).tar.gz

Pull the .gpg off the server and store it somewhere you control. A DigitalOcean snapshot complements this but cannot replace it — DO snapshots cannot be downloaded, so they guard against the droplet dying but are not an offline copy you hold.
