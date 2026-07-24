#!/bin/bash
#
# ============================================================
#  Camera Relay Server — one-time setup
# ============================================================
#
#  Installs and configures:
#    - WireGuard  (the private tunnel)
#    - MediaMTX   (converts camera video for browsers)
#    - ffmpeg     (diagnostic tools)
#    - firewall rules
#
#  Run once on a fresh Ubuntu 22.04 or 24.04 VPS, as root:
#
#      chmod +x setup-server.sh
#      ./setup-server.sh
#
#  Afterwards, add each site with:  ./add-site.sh <name> <dvr-ip>
#
#  Docs (novice + technical):
#      docs/04-vps-server-setup.md
#      docs/technical/SERVER.md
#
# ============================================================

set -e  # stop immediately if any command fails

# ---- Settings you may want to change ------------------------

WG_PORT=51820                # UDP port WireGuard listens on
WG_SUBNET="10.8.0"           # tunnel network. Server becomes 10.8.0.1
MEDIAMTX_VERSION="v1.19.3"   # check github.com/bluenviron/mediamtx/releases

# -------------------------------------------------------------

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
say()  { echo -e "${GREEN}==>${NC} $1"; }
warn() { echo -e "${YELLOW}!!${NC} $1"; }
die()  { echo -e "${RED}ERROR:${NC} $1"; exit 1; }

[ "$EUID" -eq 0 ] || die "Run this as root (use: sudo ./setup-server.sh)"


# =============================================================
# 1. System packages
# =============================================================
say "Updating system and installing packages"

apt-get update -qq
apt-get install -y -qq \
    wireguard wireguard-tools \
    ufw curl wget tar nano \
    ffmpeg \
    qrencode          # for showing phone configs as QR codes

# Allow the server to pass traffic between network interfaces.
# Needed so the tunnel can reach beyond the server itself.
echo "net.ipv4.ip_forward=1" > /etc/sysctl.d/99-wireguard.conf
sysctl -p /etc/sysctl.d/99-wireguard.conf >/dev/null


# =============================================================
# 2. WireGuard server
# =============================================================
say "Setting up WireGuard"

mkdir -p /etc/wireguard/keys
chmod 700 /etc/wireguard/keys
cd /etc/wireguard

if [ -f keys/server_private.key ]; then
    warn "Server keys already exist — keeping them"
else
    wg genkey | tee keys/server_private.key | wg pubkey > keys/server_public.key
    chmod 600 keys/server_private.key
fi

SERVER_PRIVATE=$(cat keys/server_private.key)
SERVER_PUBLIC=$(cat keys/server_public.key)

# Find the server's public IP so we can print it later
PUBLIC_IP=$(curl -s https://api.ipify.org || echo "UNKNOWN")

if [ -f wg0.conf ]; then
    warn "wg0.conf already exists — not overwriting it"
else
cat > wg0.conf <<EOF
# WireGuard server configuration
# Peers (sites) are appended below by add-site.sh

[Interface]
Address    = ${WG_SUBNET}.1/24
ListenPort = ${WG_PORT}
PrivateKey = ${SERVER_PRIVATE}

EOF
    chmod 600 wg0.conf
fi

systemctl enable wg-quick@wg0 >/dev/null 2>&1
systemctl restart wg-quick@wg0

# Keep a note of which tunnel addresses are used
touch /etc/wireguard/allocated-ips.txt


# =============================================================
# 3. MediaMTX
# =============================================================
say "Installing MediaMTX ${MEDIAMTX_VERSION}"

cd /tmp
TARBALL="mediamtx_${MEDIAMTX_VERSION}_linux_amd64.tar.gz"
URL="https://github.com/bluenviron/mediamtx/releases/download/${MEDIAMTX_VERSION}/${TARBALL}"

wget -q "$URL" || die "Download failed. Check the version number at github.com/bluenviron/mediamtx/releases"
tar -xzf "$TARBALL"

mv -f mediamtx /usr/local/bin/
chmod +x /usr/local/bin/mediamtx
mkdir -p /usr/local/etc

if [ -f /usr/local/etc/mediamtx.yml ]; then
    warn "Config already exists — keeping it"
    rm -f mediamtx.yml
else
    mv -f mediamtx.yml /usr/local/etc/mediamtx.yml
fi

rm -f "$TARBALL"

# --- Base configuration --------------------------------------
# Written fresh so we know exactly what is in it.
# Camera entries get appended by add-site.sh

if ! grep -q "# MANAGED BY SETUP SCRIPT" /usr/local/etc/mediamtx.yml 2>/dev/null; then
cat > /usr/local/etc/mediamtx.yml <<'EOF'
# MANAGED BY SETUP SCRIPT
# Camera paths are added at the bottom by add-site.sh

logLevel: info

# Force video over TCP.
# Required: our relay carries a single TCP connection and
# cannot pass the separate UDP streams RTSP normally uses.
rtspTransport: tcp

rtsp: yes
rtspAddress: :8554

rtmp: no
hls: no
srt: no

webrtc: yes
webrtcAddress: :8889
webrtcLocalUDPAddress: :8189

api: yes
apiAddress: 127.0.0.1:9997

paths:
EOF
fi

# --- Run it as a service so it survives reboots --------------
cat > /etc/systemd/system/mediamtx.service <<'EOF'
[Unit]
Description=MediaMTX camera relay
After=network.target

[Service]
ExecStart=/usr/local/bin/mediamtx /usr/local/etc/mediamtx.yml
Restart=always
RestartSec=5
User=root

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable mediamtx >/dev/null 2>&1
systemctl restart mediamtx


# =============================================================
# 4. Firewall
# =============================================================
say "Configuring firewall"

ufw allow 22/tcp            comment 'SSH'        >/dev/null
ufw allow ${WG_PORT}/udp    comment 'WireGuard'  >/dev/null
ufw allow 8889/tcp          comment 'WebRTC signalling' >/dev/null
ufw allow 8189/udp          comment 'WebRTC video'      >/dev/null
ufw --force enable >/dev/null

warn "Port 8554 (RTSP) is deliberately NOT open to the internet."
warn "Use it only from the server itself for testing."


# =============================================================
# 5. Helper script for adding sites
# =============================================================
say "Creating add-site.sh"

cat > /root/add-site.sh <<'SCRIPT'
#!/bin/bash
#
# Add a new site (one DVR + its Android box).
#
#   ./add-site.sh <site-name> <dvr-ip> [number-of-cameras]
#
# Example:
#   ./add-site.sh site001 192.168.0.60 4
#
set -e

SITE=$1
DVR_IP=$2
CAMERAS=${3:-4}
WG_SUBNET="10.8.0"

if [ -z "$SITE" ] || [ -z "$DVR_IP" ]; then
    echo "Usage: ./add-site.sh <site-name> <dvr-ip> [cameras]"
    echo "Example: ./add-site.sh site001 192.168.0.60 4"
    exit 1
fi

cd /etc/wireguard

# --- Pick the next free tunnel address -----------------------
LAST=$(grep -c . allocated-ips.txt 2>/dev/null || echo 0)
NEXT=$((LAST + 2))          # .1 is the server, so sites start at .2

if [ $NEXT -gt 254 ]; then
    echo "Tunnel subnet is full."
    exit 1
fi

CLIENT_IP="${WG_SUBNET}.${NEXT}"

# --- Generate this site's keys -------------------------------
wg genkey | tee keys/${SITE}_private.key | wg pubkey > keys/${SITE}_public.key
chmod 600 keys/${SITE}_private.key

CLIENT_PRIVATE=$(cat keys/${SITE}_private.key)
CLIENT_PUBLIC=$(cat keys/${SITE}_public.key)
SERVER_PUBLIC=$(cat keys/server_public.key)
PUBLIC_IP=$(curl -s https://api.ipify.org)
WG_PORT=$(grep ListenPort wg0.conf | awk '{print $3}')

# --- Add the site to the server ------------------------------
cat >> wg0.conf <<EOF

# Site: ${SITE}  (DVR at ${DVR_IP})
[Peer]
PublicKey  = ${CLIENT_PUBLIC}
AllowedIPs = ${CLIENT_IP}/32
EOF

echo "${SITE} ${CLIENT_IP}" >> allocated-ips.txt

# Apply without dropping existing tunnels
wg syncconf wg0 <(wg-quick strip wg0)

# --- Write the config for the Android box --------------------
mkdir -p /root/site-configs
cat > /root/site-configs/${SITE}.conf <<EOF
[Interface]
PrivateKey = ${CLIENT_PRIVATE}
Address    = ${CLIENT_IP}/32

[Peer]
PublicKey           = ${SERVER_PUBLIC}
Endpoint            = ${PUBLIC_IP}:${WG_PORT}
AllowedIPs          = ${WG_SUBNET}.0/24
PersistentKeepalive = 25
EOF

# --- Add camera paths to MediaMTX ----------------------------
# Reads DVR credentials from environment, or prompts.
if [ -z "$DVR_USER" ]; then read -p  "DVR username: " DVR_USER; fi
if [ -z "$DVR_PASS" ]; then read -sp "DVR password: " DVR_PASS; echo; fi

for ch in $(seq 1 $CAMERAS); do
cat >> /usr/local/etc/mediamtx.yml <<EOF
  ${SITE}_ch${ch}:
    source: rtsp://${DVR_USER}:${DVR_PASS}@${CLIENT_IP}:8554/Streaming/Channels/${ch}02
    sourceOnDemand: yes
    sourceOnDemandCloseAfter: 10s
EOF
done

systemctl restart mediamtx

# --- Report --------------------------------------------------
echo
echo "============================================"
echo " Site added: ${SITE}"
echo "============================================"
echo " Tunnel address : ${CLIENT_IP}"
echo " DVR address    : ${DVR_IP}"
echo " Config file    : /root/site-configs/${SITE}.conf"
echo
echo " Camera URLs:"
for ch in $(seq 1 $CAMERAS); do
echo "   http://${PUBLIC_IP}:8889/${SITE}_ch${ch}"
done
echo
echo " Scan this QR code with the WireGuard app on the box:"
echo
qrencode -t ansiutf8 < /root/site-configs/${SITE}.conf
echo
echo "============================================"
SCRIPT

chmod +x /root/add-site.sh


# =============================================================
# Done
# =============================================================
echo
echo "============================================================"
echo -e "${GREEN} Server setup complete${NC}"
echo "============================================================"
echo
echo " Public IP        : ${PUBLIC_IP}"
echo " WireGuard port   : ${WG_PORT}/udp"
echo " Server public key: ${SERVER_PUBLIC}"
echo
echo " Next step — add your first site:"
echo
echo "     ./add-site.sh site001 192.168.0.60 4"
echo
echo " Useful commands:"
echo "     wg show                      # who is connected"
echo "     systemctl status mediamtx    # is the relay running"
echo "     journalctl -u mediamtx -f    # watch the relay log"
echo
echo "============================================================"
