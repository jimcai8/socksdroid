#!/usr/bin/env bash
# setup_socksdroid_ldplayer.sh - install and configure SocksDroid on LDPlayer
# ----------------------------------------------------------------------------
# Usage:
#   Put socksdroid-*.apk in the current directory, then:
#       bash setup_socksdroid_ldplayer.sh
#   Emulator name -> socks proxy mapping is defined in EMULATOR_PROFILES below.
#   Proxy URL format: socks5://[username:password@]ip:port
#   (username:password optional; special chars in password must be URL-encoded)
#
# Requires our patched SocksDroid >= 1.1.0 (ControlReceiver). Get it by building
# socksdroid/ (fork of github.com/bndeff/socksdroid, branch local/1.1.0).
#
# Flow per instance:
#   resolve name -> index via ldconsole list2 (GBK aware)
#   -> install/update SocksDroid apk (uninstall first on signature change)
#   -> wake app once (launch + HOME; NEVER force-stop: stopped-state apps
#      do not receive manifest broadcasts)
#   -> grant VPN consent via appops ACTIVATE_VPN (no root needed)
#   -> configure + connect via `am broadcast ACTION_CONNECT` (explicit
#      component: manifest receivers get no implicit broadcasts on O+)
#      -> poll tun0; on failure fall back to UI automation (toggle the
#         connect switch + auto-tap the VPN permission dialog)
#   -> after tun0 is up: set system always-on VPN + lockdown (block traffic
#      without VPN)
#   -> verify: always_on setting, tun0 interface, tun2socks process
#   -> boot-autostart check: quit instance -> relaunch -> tun0 must come up
#      by itself (BootReceiver + always-on, with root mode untouched)
#   -> quit instance
#
# ============================================================================

set -uo pipefail
export MSYS_NO_PATHCONV=1

# ===================== constants (edit as needed) =====================
declare -A EMULATOR_PROFILES=(
  ["test"]="socks5://1.2.3.4:1080"
  # ["instance-1"]="socks5://user:pass@ip:port"
  # ["instance-2"]="socks5://user:pass@ip:port"
)
PKG="net.typeblog.socks"
APK_GLOB='socksdroid*.apk'   # apk file in current dir
# =====================================================================

LDCONSOLE="E:/LDPlayer9/ldconsole.exe"
ADB="E:/LDPlayer9/adb.exe"
BOOT_TRIES=60            # 60 x 3s = 180s boot wait
TUN_WAIT=15              # 15 x 2s = 30s wait for tun0 after broadcast
BOOT_TUN_TRIES=30        # 30 x 3s = 90s wait for tun0 after reboot

fail=0

# ---- helpers --------------------------------------------------------------

die() { echo "ERROR: $*"; exit 1; }

wait_boot() {  # $1 = serial
  local serial="$1" try=0 boot
  while [ "$try" -lt "$BOOT_TRIES" ]; do
    "$ADB" connect "$serial" >/dev/null 2>&1
    boot="$("$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
    [ "$boot" = "1" ] && return 0
    try=$((try + 1)); sleep 3
  done
  return 1
}

close_instance() {  # $1 = name, $2 = index
  local name="$1" idx="$2" started try=0
  echo "[$name] closing instance (index=$idx) ..."
  "$LDCONSOLE" quit --index "$idx"
  while [ "$try" -lt 15 ]; do
    sleep 2
    started="$("$LDCONSOLE" list2 2>/dev/null | iconv -f GBK -t UTF-8 2>/dev/null | tr -d '\r' \
               | awk -F',' -v i="$idx" '$1==i {print $5; exit}')"
    [ "$started" = "0" ] && { echo "[$name] closed."; return 0; }
    try=$((try + 1))
  done
  echo "[$name] WARN: close not confirmed in 30s, check dnplayer/VBoxHeadless."
}

# run a shell command on device via root (plain adb shell or su -c wrapper)
RC_PREFIX=""   # "su -c" when adbd is not root but su exists
run_root() {  # $1 = serial, $2 = command
  if [ -z "$RC_PREFIX" ]; then
    "$ADB" -s "$1" shell "$2"
  else
    "$ADB" -s "$1" shell "su -c '$2'"
  fi
}

# pick the newest socksdroid apk (plain glob order would hit 1.0.4 before 1.1.0)
pick_apk() {
  ls -v $APK_GLOB 2>/dev/null | tail -1
}

# tun0 present? (ip may be unavailable to the shell uid on some images,
# fall back to sysfs which is world-readable)
tun0_up() {  # $1 = serial -> rc 0 when tun0 exists
  local serial="$1" out
  out="$("$ADB" -s "$serial" shell ip addr show tun0 2>/dev/null | tr -d '\r')"
  [ -n "$out" ] && return 0
  out="$("$ADB" -s "$serial" shell ls /sys/class/net/tun0 2>/dev/null | tr -d '\r')"
  [ -n "$out" ] && ! printf '%s' "$out" | grep -qi 'no such file'
}

# install with retries; on signature conflict (INSTALL_FAILED_UPDATE_INCOMPATIBLE)
# uninstall first (wipes prefs/appops/always-on - that is fine here, everything
# is reconfigured afterwards) and retry
install_apk() {  # $1 = serial, $2 = apk file -> rc 0 on success
  local serial="$1" apk="$2" ins_try=0 out
  while [ "$ins_try" -lt 3 ]; do
    out="$("$ADB" -s "$serial" install -r "$apk" 2>&1)"
    if [ $? -eq 0 ] && ! printf '%s' "$out" | grep -qi 'Failure'; then
      return 0
    fi
    if printf '%s' "$out" | grep -qi 'UPDATE_INCOMPATIBLE\|signatures inconsistent'; then
      echo "  signature changed vs installed version -> uninstall + reinstall"
      "$ADB" -s "$serial" uninstall "$PKG" >/dev/null 2>&1
    else
      echo "  install attempt $((ins_try + 1)) failed: $(printf '%s' "$out" | tail -1)"
      "$ADB" connect "$serial" >/dev/null 2>&1
      sleep 5
    fi
    ins_try=$((ins_try + 1))
  done
  return 1
}

# wake the app out of stopped state; MUST NOT force-stop afterwards
# (force-stopped apps receive no manifest broadcasts -> am broadcast dead)
wake_app() {  # $1 = serial
  "$ADB" -s "$1" shell am start -n "${PKG}/.MainActivity" >/dev/null 2>&1
  sleep 2
  "$ADB" -s "$1" shell input keyevent 3 >/dev/null 2>&1   # HOME
}

# grant the VPN consent without the system dialog: appops ACTIVATE_VPN.
# Works from the plain shell uid on userdebug images (LDPlayer qualifies);
# tries su as well when available. Failure is not fatal - the UI fallback
# still taps the consent dialog.
grant_vpn() {  # $1 = serial -> rc 0 when appops reports allow
  local serial="$1" out
  out="$("$ADB" -s "$serial" shell appops set "$PKG" ACTIVATE_VPN allow 2>&1 | tr -d '\r')"
  [ -n "$out" ] && echo "  appops set: $out"
  out="$("$ADB" -s "$serial" shell appops get "$PKG" ACTIVATE_VPN 2>/dev/null | tr -d '\r')"
  if printf '%s' "$out" | grep -qi 'allow'; then return 0; fi
  # retry via su if available
  if "$ADB" -s "$serial" shell su -c id 2>/dev/null | grep -q 'uid=0'; then
    RC_PREFIX="su -c"
    run_root "$serial" "appops set $PKG ACTIVATE_VPN allow" >/dev/null 2>&1
    out="$("$ADB" -s "$serial" shell appops get "$PKG" ACTIVATE_VPN 2>/dev/null | tr -d '\r')"
    printf '%s' "$out" | grep -qi 'allow'
    return $?
  fi
  return 1
}

# configure + connect through the sh command interface, then poll tun0
connect_via_broadcast() {  # $1 = serial, $2 = proxy url -> rc 0 when tun0 up
  local serial="$1" url="$2" try=0
  "$ADB" -s "$serial" shell am broadcast -n "${PKG}/.ControlReceiver" -a "${PKG}.ACTION_CONNECT" \
      --es proxy "$url" --es auto true >/dev/null 2>&1
  while [ "$try" -lt "$TUN_WAIT" ]; do
    sleep 2
    tun0_up "$serial" && return 0
    try=$((try + 1))
  done
  return 1
}

# ---- UI automation fallback (first-run VPN consent dialog) ----------------
# Used only when the broadcast connect failed: open the app, toggle the
# connect SWITCH in the action bar (the app has no Connect button), and
# auto-tap the VPN permission dialog (OK/确定) when it appears.

ui_tap_text() {  # $1 = serial, $2 = grep pattern for text attr; echoes "cx cy" if found
  local serial="$1" pat="$2" line bounds x1 y1 x2 y2
  "$ADB" -s "$serial" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  "$ADB" -s "$serial" pull /sdcard/ui.xml "$TMP/ui.xml" >/dev/null 2>&1
  line="$(grep -oE "text=\"${pat}\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" "$TMP/ui.xml" 2>/dev/null | head -1)"
  [ -z "$line" ] && return 1
  bounds="$(printf '%s' "$line" | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]')"
  x1="$(echo "$bounds" | grep -oE '[0-9]+' | sed -n 1p)"
  y1="$(echo "$bounds" | grep -oE '[0-9]+' | sed -n 2p)"
  x2="$(echo "$bounds" | grep -oE '[0-9]+' | sed -n 3p)"
  y2="$(echo "$bounds" | grep -oE '[0-9]+' | sed -n 4p)"
  echo "$(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))"
  return 0
}

# tap the connect switch (action-bar Switch, resource-id switch_action_button)
# only when it is currently OFF; echoes nothing, rc 0 when a tap was made
ui_tap_switch() {  # $1 = serial
  local serial="$1" line bounds x1 y1 x2 y2
  "$ADB" -s "$serial" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  "$ADB" -s "$serial" pull /sdcard/ui.xml "$TMP/ui.xml" >/dev/null 2>&1
  line="$(grep -oE "<node[^>]*switch_action_button[^>]*>" "$TMP/ui.xml" 2>/dev/null | head -1)"
  [ -z "$line" ] && return 1
  printf '%s' "$line" | grep -q 'checked="false"' || return 1   # already on
  bounds="$(printf '%s' "$line" | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]')"
  x1="$(echo "$bounds" | grep -oE '[0-9]+' | sed -n 1p)"
  y1="$(echo "$bounds" | grep -oE '[0-9]+' | sed -n 2p)"
  x2="$(echo "$bounds" | grep -oE '[0-9]+' | sed -n 3p)"
  y2="$(echo "$bounds" | grep -oE '[0-9]+' | sed -n 4p)"
  local cx=$(( (x1 + x2) / 2 )) cy=$(( (y1 + y2) / 2 ))
  echo "  connect switch (off) -> tap ($cx $cy)"
  "$ADB" -s "$serial" shell input tap "$cx" "$cy"
  return 0
}

connect_via_ui() {  # $1 = serial -> 0 when tun0 is up
  local serial="$1" try=0 xy
  "$ADB" -s "$serial" shell am start -n "${PKG}/.MainActivity" >/dev/null 2>&1
  while [ "$try" -lt 15 ]; do
    sleep 2
    # 1) tun0 up already? done
    if tun0_up "$serial"; then
      return 0
    fi
    # 2) consent dialog first (blocks everything else)
    xy="$(ui_tap_text "$serial" '(OK|确定)')" && {
      echo "  vpn permission dialog -> tap ($xy)"
      "$ADB" -s "$serial" shell input tap $xy
      sleep 3
      continue
    }
    # 3) toggle the connect switch when it is off
    ui_tap_switch "$serial" && sleep 3
    try=$((try + 1))
  done
  return 1
}

# ---- boot autostart verification -------------------------------------------
# Restart the instance and require tun0 to come up by itself. This exercises
# both start channels: BootReceiver (app-level) and always-on VPN (system).
verify_boot_autostart() {  # $1 = name, $2 = index, $3 = serial -> rc 0 on success
  local name="$1" idx="$2" serial="$3" try=0
  echo "[$name] rebooting instance to verify boot autostart ..."
  close_instance "$name" "$idx"
  "$LDCONSOLE" launch --index "$idx"
  wait_boot "$serial" || { echo "[$name] boot timeout"; return 1; }
  # give BootReceiver + always-on a moment, then poll
  sleep 5
  while [ "$try" -lt "$BOOT_TUN_TRIES" ]; do
    tun0_up "$serial" && { echo "[$name] boot autostart OK: tun0 is up after reboot"; return 0; }
    sleep 3
    try=$((try + 1))
  done
  return 1
}

# ---- main per-instance flow ------------------------------------------------

setup_one() {  # $1 = name, $2 = proxy url
  local name="$1" url="$2"
  local idx="" started=""

  while IFS=',' read -r i nm top bind st rest; do
    [ "$nm" = "$name" ] && { idx="$i"; started="$st"; }
  done < <("$LDCONSOLE" list2 2>/dev/null | iconv -f GBK -t UTF-8 2>/dev/null | tr -d '\r')

  if [ -z "$idx" ]; then
    echo "[$name] not found in ldconsole list2, SKIPPED"
    fail=$((fail + 1)); return
  fi
  local serial="127.0.0.1:$((5555 + idx * 2))"

  echo "=== [$name] index=$idx serial=$serial ==="

  if [ "$started" != "1" ]; then
    echo "[$name] not running, launching ..."
    "$LDCONSOLE" launch --index "$idx"
  fi
  wait_boot "$serial" || { echo "[$name] boot timeout, SKIPPED"; fail=$((fail + 1)); return; }
  sleep 5   # let the system fully settle (installd etc.) after boot

  # --- clear always-on settings FIRST (a manual disconnect under lockdown
  #     makes the system instantly restart the vpn and races our next start),
  #     then stop a running vpn cleanly BEFORE replacing the app (a hard kill
  #     by the installer would leave orphan tun2socks/pdnsd holding the socks
  #     socket and sabotage the next start) ---
  "$ADB" -s "$serial" shell settings delete secure always_on_vpn_lockdown >/dev/null 2>&1
  "$ADB" -s "$serial" shell settings delete secure always_on_vpn_app >/dev/null 2>&1
  "$ADB" -s "$serial" shell am broadcast -n "${PKG}/.ControlReceiver" \
      -a "${PKG}.ACTION_DISCONNECT" >/dev/null 2>&1
  sleep 2

  # --- install or update apk (pick newest socksdroid*.apk) ---
  local apk_file
  apk_file="$(pick_apk)"
  if [ -z "$apk_file" ] || [ ! -f "$apk_file" ]; then
    echo "[$name] ERROR: no ${APK_GLOB} found in $(pwd)"
    fail=$((fail + 1)); return
  fi
  echo "[$name] installing/updating $apk_file ..."
  if ! install_apk "$serial" "$apk_file"; then
    echo "[$name] install FAILED after retries"
    fail=$((fail + 1)); return
  fi

  # --- wake the app (leaves stopped state; never force-stop afterwards) ---
  wake_app "$serial"

  # --- clear stale always-on settings: secure settings SURVIVE an uninstall,
  #     and a lockdown pointing at a reinstalled app blocks establish() ---
  "$ADB" -s "$serial" shell settings delete secure always_on_vpn_app >/dev/null 2>&1
  "$ADB" -s "$serial" shell settings delete secure always_on_vpn_lockdown >/dev/null 2>&1

  # --- VPN consent: appops first, UI automation as fallback ---
  if grant_vpn "$serial"; then
    echo "[$name] VPN consent granted via appops"
  else
    echo "[$name] WARN: appops grant failed, will rely on UI automation for consent"
  fi

  # --- configure + connect via sh command ---
  if connect_via_broadcast "$serial" "$url"; then
    echo "[$name] connected via am broadcast, tun0 is up"
  else
    echo "[$name] broadcast connect did not bring tun0 up, trying UI automation ..."
    if connect_via_ui "$serial"; then
      echo "[$name] connected via UI automation, tun0 is up"
    else
      echo "[$name] ERROR: could not establish VPN"
      fail=$((fail + 1)); return
    fi
  fi

  # --- always-on vpn (system level), only AFTER tun0 is confirmed:
  #     with lockdown enabled, an unconnected VPN blackholes all traffic ---
  "$ADB" -s "$serial" shell settings put secure always_on_vpn_app "$PKG"
  "$ADB" -s "$serial" shell settings put secure always_on_vpn_lockdown 1

  # --- verify ---
  sleep 3
  local v_always v_tun v_ps ok=1
  v_always="$("$ADB" -s "$serial" shell settings get secure always_on_vpn_app 2>/dev/null | tr -d '\r')"
  tun0_up "$serial" || { echo "[$name] VERIFY FAIL: tun0 interface not present"; ok=0; }
  [ "$v_always" = "$PKG" ] || { echo "[$name] VERIFY FAIL: always_on_vpn_app=$v_always"; ok=0; }
  v_ps="$("$ADB" -s "$serial" shell ps -A 2>/dev/null | tr -d '\r' | grep -c tun2socks)"
  [ "${v_ps:-0}" -ge 1 ] || { echo "[$name] VERIFY WARN: tun2socks process not seen (continuing)"; }
  if [ "$ok" = "1" ]; then
    echo "[$name] VERIFY OK: always-on set, tun0 up ($url)"
    echo "[$name] NOTE: open the browser in this instance and visit ifconfig.me to confirm the exit IP."
  else
    echo "[$name] some checks failed, see messages above."
    fail=$((fail + 1))
  fi

  # --- boot autostart verification (the reason this fork exists) ---
  if ! verify_boot_autostart "$name" "$idx" "$serial"; then
    echo "[$name] VERIFY FAIL: tun0 did NOT come up automatically after reboot"
    fail=$((fail + 1))
  fi

  # --- quit instance ---
  close_instance "$name" "$idx"
}

# ---- entry ------------------------------------------------------------------
# NOTE: temp dir must be a Windows path: adb.exe cannot see MSYS /tmp paths
# (MSYS_NO_PATHCONV=1 disables the automatic path translation).

TMP="$(pwd -W)/.setup_socksdroid_tmp"
mkdir -p "$TMP"
trap 'rm -rf "$TMP" 2>/dev/null; exit' EXIT

if [ ${#EMULATOR_PROFILES[@]} -eq 0 ]; then
  die "EMULATOR_PROFILES is empty, edit the constants section first."
fi

for name in "${!EMULATOR_PROFILES[@]}"; do
  setup_one "$name" "${EMULATOR_PROFILES[$name]}"
done

echo "========================================"
echo "Done. failed instances: $fail"
exit "$fail"
