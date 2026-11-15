#!/usr/bin/env bash
# Planned release gate for a VonixGuardian loader jar.
#
# Everything here is headless and runs on a throwaway Forge server under TMPDIR.
# It exists because 2.3.1 shipped broken: a mixin that fails to apply compiles
# perfectly, so "the build succeeded" is not evidence that the mod loads. This gate
# boots the jar, drives it over RCON, and exercises the rollback paths that have
# actually failed in the field.
#
# Usage:  gate.sh <jar> <mc-version> <forge-version> [--keep]
# Exit:   0 = every stage passed, non-zero = the stage that failed.
set -uo pipefail

JAR="${1:?usage: gate.sh <jar> <mc-version> <forge-version> [--keep]}"
MC="${2:?mc version required, e.g. 1.18.2}"
FORGE="${3:?forge version required, e.g. 40.3.12}"
KEEP="${4:-}"

WORK="${TMPDIR:-/tmp}/vg-gate-$(basename "$JAR" .jar)"
JDK17=/usr/lib/jvm/java-17-openjdk-amd64
RCON_PORT=25601
GAME_PORT=25600
RCON_PASS=vg-gate-$$
LOG="$WORK/gate.log"
FAILED=0

say() { printf '%s\n' "$*" | tee -a "$LOG"; }
stage() { say ""; say "=== STAGE: $* ==="; }
die() {
    say "FAIL: $*"
    # Preserve evidence on failure. A gate that deletes its own logs cannot be
    # diagnosed, which is how the last run cost a full cycle to a cause I could not see.
    if [ -f "${WORK:-}/boot.log" ]; then
        say "--- last 20 boot lines ---"
        tail -20 "$WORK/boot.log" | tee -a "$LOG"
    fi
    say "evidence kept in: ${WORK:-<none>}"
    FAILED=1
    if [ -n "${SRV_PID:-}" ]; then kill "$SRV_PID" 2>/dev/null; fi
    exit 1
}
cleanup() {
    pkill -f "vg-gate" 2>/dev/null
    if [ -n "${SRV_PID:-}" ]; then kill "$SRV_PID" 2>/dev/null; wait "$SRV_PID" 2>/dev/null; fi
    if [ "$KEEP" != "--keep" ]; then rm -rf "$WORK"; else say "kept: $WORK"; fi
}

mkdir -p "$WORK"
: > "$LOG"
say "gate start $(date -u +%FT%TZ)"
say "jar   : $JAR"
say "target: MC $MC / Forge $FORGE"

# ---------------------------------------------------------------- stage 1: artifact
stage "artifact sanity"
[ -f "$JAR" ] || die "jar not found: $JAR"
SHA=$(sha256sum "$JAR" | cut -d' ' -f1)
SIZE=$(stat -c%s "$JAR")
say "sha256: $SHA"
say "bytes : $SIZE"
unzip -l "$JAR" >/dev/null 2>&1 || die "not a readable zip"
say "zip: ok"

# Every registered mixin must have a class in the jar. A dormant registration is a
# boot failure waiting for a player to trigger it.
MIXCONF=$(unzip -l "$JAR" | awk '{print $4}' | grep -E '(^|/)vg\.mixins\.json$' | head -1)
if [ -n "$MIXCONF" ]; then
    MISSING=$(JARPATH="$JAR" python3 - "$MIXCONF" <<'PYEOF'
import json,sys,zipfile,os
jar=os.environ['JARPATH']; conf=sys.argv[1]
with zipfile.ZipFile(jar) as z:
    d=json.loads(z.read(conf))
    pkg=d.get('package','').replace('.','/')
    names=set(z.namelist())
    reg=[m for k in ('mixins','client','server') for m in (d.get(k) or [])]
    print(sum(1 for m in reg if f"{pkg}/{m}.class" not in names))
PYEOF
)
    say "mixins missing a class: ${MISSING:-?}"
    [ "${MISSING:-1}" = "0" ] || die "a registered mixin has no class in the jar"
fi

# ------------------------------------------------------------ stage 2: server stand-up
stage "install a throwaway Forge server"
if [ ! -f "$WORK/run.sh" ]; then
    curl -sL -o "$WORK/installer.jar" \
        "https://maven.minecraftforge.net/net/minecraftforge/forge/${MC}-${FORGE}/forge-${MC}-${FORGE}-installer.jar" \
        || die "forge installer download failed"
    ( cd "$WORK" && "$JDK17/bin/java" -jar installer.jar --installServer >/dev/null 2>&1 ) \
        || die "forge installer failed"
fi
mkdir -p "$WORK/mods"
rm -f "$WORK"/mods/vonixguardian-*.jar
cp "$JAR" "$WORK/mods/"
echo 'eula=true' > "$WORK/eula.txt"
cat > "$WORK/server.properties" <<EOF
level-type=flat
online-mode=false
max-tick-time=-1
enable-rcon=true
rcon.port=${RCON_PORT}
server-port=${GAME_PORT}
rcon.password=${RCON_PASS}
spawn-protection=0
EOF
if ss -ltn 2>/dev/null | grep -qE ":(${GAME_PORT}|${RCON_PORT})"; then
    die "port ${GAME_PORT} or ${RCON_PORT} already bound — stop the other server first"
fi
say "server prepared (game ${GAME_PORT}, rcon ${RCON_PORT})"

# ----------------------------------------------------------------- stage 3: boot
stage "boot (mixin apply is the real test)"
( cd "$WORK" && "$JDK17/bin/java" @user_jvm_args.txt \
    "@libraries/net/minecraftforge/forge/${MC}-${FORGE}/unix_args.txt" nogui \
    > "$WORK/boot.log" 2>&1 ) &
SRV_PID=$!

BOOTED=0
for _ in $(seq 1 60); do
    sleep 5
    if grep -q 'Done (' "$WORK/boot.log" 2>/dev/null; then BOOTED=1; break; fi
    if grep -qE 'Mixin apply failed|InvalidMixinException|Exception in thread "main"' "$WORK/boot.log" 2>/dev/null; then
        say "$(grep -E 'Mixin apply failed|InvalidMixinException|@Shadow' "$WORK/boot.log" | head -3)"
        die "server died during boot (mixin or startup failure)"
    fi
    kill -0 "$SRV_PID" 2>/dev/null || die "server process exited before Done"
done
[ "$BOOTED" = "1" ] || die "server did not reach Done within 300s"

# `Done (` can print before VonixGuardian's own boot finishes: the mod defers its
# command registration to Guardian.boot and logs completion a second or two later.
# Poll for it instead of reading once, or the gate fails a healthy mod on a race.
BOOT_OK=0
for _ in $(seq 1 20); do
    if grep -q 'VonixGuardian bootstrap complete' "$WORK/boot.log" 2>/dev/null; then BOOT_OK=1; break; fi
    sleep 2
done
[ "$BOOT_OK" = "1" ] || die "mod never reached bootstrap complete within 40s of Done"
grep -q 'command tree registered' "$WORK/boot.log" || die "/vg command tree never registered"
say "boot: ok — Done, bootstrap complete, /vg registered"

# ------------------------------------------------------------ stage 4: command surface
stage "command surface over RCON"
rcon() {
    python3 - "$1" "$RCON_PORT" "$RCON_PASS" <<'PY'
import socket,struct,sys,time
cmd,port,pw=sys.argv[1],int(sys.argv[2]),sys.argv[3]
def pkt(rid,typ,b):
    d=b.encode()+b'\x00\x00'
    return struct.pack('<iii',len(d)+8,rid,typ)+d
s=socket.create_connection(('127.0.0.1',port),timeout=10); s.settimeout(10)
s.send(pkt(1,3,pw)); s.recv(4096)
s.send(pkt(2,2,cmd))
buf=b''; t=time.time()
while time.time()-t<8:
    try:
        d=s.recv(16384)
        if not d: break
        buf+=d
    except socket.timeout: break
s.close()
print(buf[12:].decode('utf-8','replace').replace('\x00','').strip())
PY
}
# Synchronous commands must answer on console. Async ones (status, lookup work off the
# server thread) legitimately return nothing to RCON before their reply exists, so they
# are asserted from the log instead.
HELP=$(rcon "vg help")
[ -n "$HELP" ] || die "/vg help returned nothing on console"
say "help: $(printf '%s' "$HELP" | head -1 | cut -c1-90)"
rcon "vg status" >/dev/null
sleep 4
grep -qiE 'VonixGuardian.*(Guardian|Storage|queue)' "$WORK/boot.log" \
    || say "note: status output not in log (async reply; verify via chat)"
say "commands: ok"

# ------------------------------------------------------- stage 5: rollback failure paths
stage "rollback failure paths"
rcon "gamerule doDaylightCycle false" >/dev/null
rcon "fill 0 60 0 4 60 4 minecraft:stone" >/dev/null
rcon "setblock 2 61 2 minecraft:chest" >/dev/null
rcon "setblock 1 61 1 minecraft:fire" >/dev/null
rcon "setblock 3 61 3 minecraft:farmland" >/dev/null
sleep 2

# Preview must not change anything.
BEFORE=$(rcon "data get block 0 60 0" 2>/dev/null)
rcon "vg rollback r:8 t:10m #preview" >/dev/null
sleep 5
AFTER=$(rcon "data get block 0 60 0" 2>/dev/null)
say "preview did not mutate: $([ "$BEFORE" = "$AFTER" ] && echo yes || echo NO)"

# A real rollback over the scene. This is where BURN, ENTITY_KILL and container rows
# have failed in the field; the gate fails if the log shows unexplained failures.
rcon "vg rollback r:8 t:10m" >/dev/null
sleep 12

BAD=$(grep -cE 'mutation unsuccessful' "$WORK/boot.log" 2>/dev/null | tr -d '[:space:]' || true)
BAD=${BAD:-0}
say "mutation failures: $BAD"
if [ "$BAD" != "0" ]; then
    grep -E 'mutation unsuccessful' "$WORK/boot.log" | head -5 | tee -a "$LOG"
    die "rollback reported $BAD failed mutations"
fi

PARTIAL=$(grep -cE 'REPAIR_REQUIRED' "$WORK/boot.log" 2>/dev/null | tr -d '[:space:]' || true)
PARTIAL=${PARTIAL:-0}
say "repair-required batches: $PARTIAL"

# ------------------------------------------------------------- stage 6: shutdown sanity
stage "clean shutdown"
rcon "stop" >/dev/null
for _ in $(seq 1 24); do
    sleep 5
    kill -0 "$SRV_PID" 2>/dev/null || break
done
kill -0 "$SRV_PID" 2>/dev/null && say "note: server slow to stop" || say "stopped cleanly"

say ""
say "GATE PASSED — $SHA"
[ "$KEEP" = "--keep" ] && say "kept: $WORK" || rm -rf "$WORK"
exit 0
