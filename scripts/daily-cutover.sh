#!/usr/bin/env bash
set -euo pipefail
umask 077
stage=$1 previous=$2 candidate=$3 digest=$4
base=/opt/qingfeng-music
exec 9>/run/lock/qingfeng-music-release.lock
flock -n 9
old=$(readlink -e "$base/current")
test -L "$base/current"
test "$(unzip -p "$old/app.jar" BOOT-INF/classes/release-commit.txt)" = "$previous"
next=$(mktemp -d "$base/releases/${candidate:0:12}.XXXXXXXX")
install -m 644 "$stage/app.jar" "$next/app.jar"
chmod 755 "$next"
echo "$digest  $next/app.jar" | sha256sum -c -
test "$(unzip -p "$next/app.jar" BOOT-INF/classes/release-commit.txt)" = "$candidate"

backups=/var/backups/qingfeng-music
latest() {
    find "$backups" -maxdepth 1 -type d -name '????????T??????Z' -printf '%f\n' | sort | tail -1
}
before=$(latest)
systemctl start qingfeng-music-backup.service
after=$(latest)
[[ -n "$after" && "$before" != "$after" ]]
backup=$backups/$after
gzip -t "$backup/database.sql.gz"
tar -tzf "$backup/audio-config.tar.gz" >/dev/null
tar -czf "$backup/release-config.tar.gz" -C / etc/qingfeng-music
tar -tzf "$backup/release-config.tar.gz" >/dev/null
cp "$old/app.jar" "$backup/previous.jar"
cmp "$old/app.jar" "$backup/previous.jar"
echo "backup=$backup previous=$old candidate=$next"

switch_to() {
    ln -s "$1" "$next.link" && mv -Tf "$next.link" "$base/current"
}
healthy() {
    for attempt in {1..45}; do
        if systemctl is-active --quiet qingfeng-music &&
           curl -fsS --max-time 5 http://127.0.0.1:8080/actuator/health |
               python3 -c 'import json,sys; sys.exit(json.load(sys.stdin).get("status") != "UP")' &&
           curl -fsS --max-time 10 https://cheerfulmusic.com/ >/dev/null; then
            if [[ -e /usr/local/sbin/qingfeng-release-smoke ]]; then
                /usr/local/sbin/qingfeng-release-smoke "$1" || return 1
            fi
            return 0
        fi
        sleep 2
    done
    return 1
}
switch_to "$next"
if systemctl restart qingfeng-music && healthy "$candidate"; then
    echo "Published $candidate"
else
    switch_to "$old"
    systemctl restart qingfeng-music
    healthy "$previous"
    echo "Rolled back to $previous" >&2
    exit 1
fi
