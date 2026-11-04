#!/bin/bash
set -euo pipefail
umask 077
exec 9>/run/lock/qingfeng-music-backup.lock
flock -n 9 || exit 0
install -d -m 700 /var/backups/qingfeng-music
stamp=$(date -u +%Y%m%dT%H%M%SZ)
target=/var/backups/qingfeng-music/$stamp
mkdir "$target.partial"
mysqldump --single-transaction --no-tablespaces qingfeng_music | gzip > "$target.partial/database.sql.gz"
backup_paths=(var/lib/qingfeng-music etc/qingfeng-music/application.properties)
if [[ -f /etc/qingfeng-music/usage-limits.properties ]]; then
    backup_paths+=(etc/qingfeng-music/usage-limits.properties)
fi
tar -czf "$target.partial/audio-config.tar.gz" -C / "${backup_paths[@]}"
mv "$target.partial" "$target"
# 保留现有备份；不自动删除。管理员按磁盘容量另行确定保留策略。
