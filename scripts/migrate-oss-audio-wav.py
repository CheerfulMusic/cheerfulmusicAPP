import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import wave

import imageio_ffmpeg
import oss2
import pymysql


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def convert(ffmpeg, source, target):
    subprocess.run(
        [ffmpeg, "-nostdin", "-v", "error", "-xerror", "-y", "-f", "mp3",
         "-i", str(source), "-map", "0:a:0", "-c:a", "pcm_s16le", str(target)],
        check=True, timeout=180, stdout=subprocess.DEVNULL,
    )
    with wave.open(str(target), "rb") as audio:
        frames = audio.getnframes()
        if not frames or audio.getsampwidth() != 2:
            raise ValueError("invalid PCM WAV")
        return frames / audio.getframerate()


def plan_asset(bucket, prefix, ffmpeg, old, upload):
    asset, storage, _, size, digest = old
    if not re.fullmatch(r"oss:[0-9a-f-]{36}\.mp3", storage):
        raise ValueError("unexpected source storage key")
    src_key = prefix + storage[4:]
    new_storage = storage[:-4] + ".wav"
    dst_key = prefix + new_storage[4:]
    with tempfile.TemporaryDirectory(prefix="qf-wav-") as folder:
        source, target = Path(folder) / "source.mp3", Path(folder) / "result.wav"
        bucket.get_object_to_file(src_key, str(source))
        if source.stat().st_size != size or sha256(source) != digest.lower():
            raise ValueError("source does not match database")
        seconds = convert(ffmpeg, source, target)
        output_size, output_hash = target.stat().st_size, sha256(target)
        if upload:
            if not bucket.object_exists(dst_key):
                bucket.put_object_from_file(dst_key, str(target), headers={
                    "Content-Type": "audio/wav",
                    "x-oss-object-acl": "private",
                    "x-oss-forbid-overwrite": "true",
                    "x-oss-meta-qf-source-sha256": digest,
                    "x-oss-meta-qf-wav-sha256": output_hash,
                })
            head = bucket.head_object(dst_key)
            remote = head.headers
            if (head.content_length != output_size or head.content_type != "audio/wav"
                    or remote.get("x-oss-meta-qf-source-sha256") != digest
                    or remote.get("x-oss-meta-qf-wav-sha256") != output_hash):
                raise ValueError("destination metadata does not match conversion")
            with bucket.get_object(dst_key) as obj:
                verified = hashlib.sha256()
                for block in iter(lambda: obj.read(1024 * 1024), b""):
                    verified.update(block)
                if verified.hexdigest() != output_hash:
                    raise ValueError("destination bytes do not match conversion")
            if bucket.get_object_acl(dst_key).acl != "private":
                raise ValueError("destination is not private")
        return {"old": old, "new": [asset, new_storage, "audio/wav", output_size, output_hash],
                "seconds": round(seconds, 3)}


def main():
    parser = argparse.ArgumentParser(description="Stage private WAV objects and switch or restore media references.")
    parser.add_argument("action", choices=["sample", "stage", "switch", "rollback"])
    parser.add_argument("--properties", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--port", type=int, default=13306)
    parser.add_argument("--limit", type=int)
    parser.add_argument("--workers", type=int, choices=range(1, 9), default=1)
    parser.add_argument("--bucket")
    parser.add_argument("--region")
    args = parser.parse_args()
    if args.limit is not None and (args.limit <= 0 or args.action not in {"sample", "stage"}):
        parser.error("--limit must be positive and applies only to sample or stage")
    settings = {}
    for line in args.properties.read_text(encoding="utf-8-sig").splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            settings[key.strip()] = value.strip()
    region = args.region or settings["qingfeng.oss.region"]
    endpoint = f"https://oss-{region}.aliyuncs.com" if args.region else settings["qingfeng.oss.endpoint"]
    if not re.fullmatch(r"https://oss-[a-z0-9-]+\.aliyuncs\.com", endpoint):
        raise ValueError("expected the HTTPS regional OSS endpoint")
    prefix = settings["qingfeng.oss.prefix"]
    if not re.fullmatch(r"[A-Za-z0-9/_-]+/", prefix):
        raise ValueError("invalid OSS audio prefix")
    auth = oss2.AuthV4(settings["qingfeng.oss.access-key-id"], settings["qingfeng.oss.access-key-secret"])
    bucket_name = args.bucket or settings["qingfeng.oss.bucket"]

    def open_bucket():
        return oss2.Bucket(auth, endpoint, bucket_name, region=region, connect_timeout=30)

    bucket = open_bucket()
    with pymysql.connect(host="127.0.0.1", port=args.port, database="qingfeng_music",
                         user=settings["spring.datasource.username"],
                         password=settings["spring.datasource.password"], connect_timeout=10) as db:
        with db.cursor() as cursor:
            cursor.execute("SELECT @@server_uuid")
            scope = [endpoint, bucket.bucket_name, prefix, cursor.fetchone()[0]]
            if args.action in {"sample", "stage"}:
                cursor.execute(
                    "SELECT id,storage_key,content_type,byte_size,sha256 FROM media_asset "
                    "WHERE storage_key LIKE 'oss:%.mp3' AND content_type='audio/mpeg' ORDER BY byte_size,id"
                )
                rows = cursor.fetchall()
                db.rollback()
                objects = {obj.key: obj.size for obj in oss2.ObjectIterator(bucket, prefix=prefix)}
                matched = [row for row in rows if objects.get(prefix + row[1][4:]) == row[3]]
                print(f"Matched {len(matched)} of {len(rows)} production references", flush=True)
                if args.action == "stage" and len(matched) != len(rows):
                    raise ValueError("configured bucket is missing source files; staging aborted")
                if not matched:
                    raise ValueError("no matching files in configured bucket")
                rows = matched
                ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
                if args.action == "sample":
                    for row in rows[:args.limit or 1]:
                        entry = plan_asset(bucket, prefix, ffmpeg, row, False)
                        print(f"Sample: MP3 {row[3]} B -> WAV {entry['new'][3]} B, {entry['seconds']} s", flush=True)
                    return
                completed = {}
                if args.manifest.exists():
                    with args.manifest.open(encoding="utf-8") as saved:
                        if json.loads(next(saved)) != scope:
                            raise ValueError("manifest does not match current database and bucket")
                        for line in saved:
                            entry = json.loads(line)
                            completed[entry["old"][0]] = entry["old"]
                with args.manifest.open("a", encoding="utf-8") as out:
                    if out.tell() == 0:
                        out.write(json.dumps(scope) + "\n")
                        out.flush()
                        os.fsync(out.fileno())
                    pending = []
                    for row in rows[:args.limit]:
                        if row[0] in completed:
                            if list(row) != completed[row[0]]:
                                raise ValueError("a previously staged source row changed")
                        else:
                            pending.append(row)
                    with ThreadPoolExecutor(max_workers=args.workers) as executor:
                        for offset in range(0, len(pending), args.workers):
                            tasks = [
                                executor.submit(plan_asset, open_bucket(), prefix, ffmpeg, row, True)
                                for row in pending[offset:offset + args.workers]
                            ]
                            for task in tasks:
                                entry = task.result()
                                out.write(json.dumps(entry) + "\n")
                                out.flush()
                                os.fsync(out.fileno())
                            print(f"Staged {offset + len(tasks)} of {len(pending)} pending WAV files", flush=True)
                    print(f"Verified manifest for {len(rows[:args.limit])} WAV files", flush=True)
                return
            with args.manifest.open(encoding="utf-8") as manifest:
                if json.loads(next(manifest)) != scope:
                    raise ValueError("manifest does not match current database and bucket")
                entries = [json.loads(line) for line in manifest]
            if not entries:
                raise ValueError("empty manifest")
            for entry in entries:
                source, target = entry["old"], entry["new"]
                if args.action == "rollback":
                    source, target = target, source
                head = bucket.head_object(prefix + target[1][4:])
                if head.content_length != target[3] or head.content_type != target[2]:
                    raise ValueError("referenced object failed validation")
            for entry in entries:
                source, target = entry["old"], entry["new"]
                if args.action == "rollback":
                    source, target = target, source
                cursor.execute(
                    "UPDATE media_asset SET storage_key=%s,content_type=%s,byte_size=%s,sha256=%s "
                    "WHERE id=%s AND storage_key=%s AND content_type=%s AND byte_size=%s AND sha256=%s",
                    (*target[1:], *source),
                )
                if cursor.rowcount != 1:
                    raise ValueError("a source row changed after staging; transaction rolled back")
            db.commit()
            print(f"{args.action}: {len(entries)} committed", flush=True)


if __name__ == "__main__":
    main()
