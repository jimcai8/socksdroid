#!/usr/bin/env bash
# control_socksdroid_ldplayer.sh - daily control of SocksDroid inside an LDPlayer
# instance (install it first with setup_socksdroid_ldplayer.sh)
# ----------------------------------------------------------------------------
# Usage:
#   bash control_socksdroid_ldplayer.sh <instance> on                     # connect with the saved profile
#   bash control_socksdroid_ldplayer.sh <instance> set "socks5://[u:p@]ip:port"   # set proxy and connect
#   bash control_socksdroid_ldplayer.sh <instance> off                    # disconnect (also clears always-on)
#   bash control_socksdroid_ldplayer.sh <instance> status                 # show status
#
# Notes:
#   - The instance must be running; this script does not launch instances.
#   - off clears always_on_vpn_app/lockdown BEFORE disconnecting: under
#     lockdown a disconnected VPN means total network loss, and the system
#     would immediately bring the VPN back up.
#   - set re-writes the always-on settings on success, keeping the
#     boot-autostart path alive.
#   - URL-encode special characters in the password (%xx); spaces and
#     quotes are not supported.
# ============================================================================

set -uo pipefail
export MSYS_NO_PATHCONV=1

PKG="net.typeblog.socks"
LDCONSOLE="E:/LDPlayer9/ldconsole.exe"
ADB="E:/LDPlayer9/adb.exe"
TUN_WAIT=15   # 15 x 2s = 30s

usage() {
  sed -n '2,19p' "$0" | sed 's/^#\{1\} \{0,1\}//'
  exit 1
}

tun0_up() {  # $1 = serial
  local out
  out="$("$ADB" -s "$1" shell ip addr show tun0 2>/dev/null | tr -d '\r')"
  [ -n "$out" ] && return 0
  out="$("$ADB" -s "$1" shell ls /sys/class/net/tun0 2>/dev/null | tr -d '\r')"
  [ -n "$out" ] && ! printf '%s' "$out" | grep -qi 'no such file'
}

wait_tun() {  # $1 = serial, $2 = want up(0)/down(1)
  local serial="$1" want="$2" try=0
  while [ "$try" -lt "$TUN_WAIT" ]; do
    sleep 2
    if [ "$want" = "0" ]; then
      tun0_up "$serial" && return 0
    else
      tun0_up "$serial" || return 0
    fi
    try=$((try + 1))
  done
  return 1
}

set_always_on() {  # $1 = serial (re-write always-on + lockdown)
  "$ADB" -s "$1" shell settings put secure always_on_vpn_app "$PKG" >/dev/null
  "$ADB" -s "$1" shell settings put secure always_on_vpn_lockdown 1 >/dev/null
}

clear_always_on() {  # $1 = serial
  "$ADB" -s "$1" shell settings delete secure always_on_vpn_lockdown >/dev/null 2>&1
  "$ADB" -s "$1" shell settings delete secure always_on_vpn_app >/dev/null 2>&1
}

# ---- entry ----
[ $# -ge 2 ] || usage
NAME="$1"; OP="$2"; URL="${3:-}"

# resolve the instance (ldconsole list2 is GBK-encoded)
idx=""; started=""
while IFS=',' read -r i nm top bind st rest; do
  [ "$nm" = "$NAME" ] && { idx="$i"; started="$st"; }
done < <("$LDCONSOLE" list2 2>/dev/null | iconv -f GBK -t UTF-8 2>/dev/null | tr -d '\r')

[ -z "$idx" ] && { echo "ERROR: instance [$NAME] not found (ldconsole list2)"; exit 1; }
[ "$started" = "1" ] || { echo "ERROR: instance [$NAME] is not running, launch it first"; exit 1; }

serial="127.0.0.1:$((5555 + idx * 2))"
"$ADB" connect "$serial" >/dev/null 2>&1

case "$OP" in
  on)
    "$ADB" -s "$serial" shell am broadcast -n "${PKG}/.ControlReceiver" -a "${PKG}.ACTION_CONNECT" --es auto true >/dev/null
    if wait_tun "$serial" 0; then
      set_always_on "$serial"
      echo "[$NAME] connected (tun0 up)"
    else
      echo "[$NAME] ERROR: tun0 did not come up within 30s (proxy unreachable? profile not initialized?)"; exit 1
    fi
    ;;
  set)
    [ -n "$URL" ] || { echo "ERROR: set requires a proxy URL argument"; usage; }
    "$ADB" -s "$serial" shell am broadcast -n "${PKG}/.ControlReceiver" -a "${PKG}.ACTION_CONNECT" \
        --es proxy "$URL" --es auto true >/dev/null
    if wait_tun "$serial" 0; then
      set_always_on "$serial"
      echo "[$NAME] proxy set and connected: $URL"
    else
      echo "[$NAME] ERROR: tun0 did not come up within 30s (check proxy address/port)"; exit 1
    fi
    ;;
  off)
    # clear always-on/lockdown first: otherwise disconnecting means total
    # network loss and the system would bring the VPN right back
    clear_always_on "$serial"
    "$ADB" -s "$serial" shell am broadcast -n "${PKG}/.ControlReceiver" -a "${PKG}.ACTION_DISCONNECT" >/dev/null
    if wait_tun "$serial" 1; then
      echo "[$NAME] disconnected (always-on cleared)"
    else
      echo "[$NAME] WARN: tun0 still present, please check"; exit 1
    fi
    ;;
  status)
    "$ADB" -s "$serial" shell am broadcast -n "${PKG}/.ControlReceiver" -a "${PKG}.ACTION_STATUS" 2>/dev/null | tr -d '\r' | grep -E 'result|Broadcast' | head -2
    if tun0_up "$serial"; then echo "tun0: up"; else echo "tun0: down"; fi
    echo "always_on_vpn_app: $("$ADB" -s "$serial" shell settings get secure always_on_vpn_app 2>/dev/null | tr -d '\r')"
    echo "always_on_vpn_lockdown: $("$ADB" -s "$serial" shell settings get secure always_on_vpn_lockdown 2>/dev/null | tr -d '\r')"
    ;;
  *)
    echo "ERROR: unknown operation: $OP"; usage
    ;;
esac
