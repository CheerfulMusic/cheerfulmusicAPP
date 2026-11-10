#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""媒体只读探测：签名 CDN + 源站匿名拒绝检查；不改配置、不重启应用。
生产执行须显式 --run；默认只报告入口要求，不读取生产配置或连接数据库。
"""
import os
import re
import sys
import json
import time
import uuid
import hashlib
import subprocess
import datetime
import urllib.request
import urllib.error

PROPS = "/etc/qingfeng-music/application.properties"
LOG = "/var/log/qingfeng-media-health.log"
STATUS = "/var/run/qingfeng-media-health.json"
HEAL_STATE = "/opt/qingfeng-music/monitor/media_health_heal.json"

# 告警邮件收件人（可改为你的邮箱；本机无外网中继时只能进 root 邮箱）
MAIL_TO = "1349842135@qq.com"
# 连续告警邮件最小间隔（秒），避免每 10 分钟一封刷屏
ALERT_INTERVAL = 3600
# 兼容常量：自动变更能力已移除，设置此值也不会重启或切换。
AUTO_HEAL = False

# 站点名（用于告警邮件标题前缀）
SITE_NAME = "人人是歌手"
# 生产服务器标识（用于告警邮件一眼定位）；若服务器 IP/名称变化请更新
SERVER_HOST = "47.84.5.172（青风音乐生产 ECS）"


def now_ts():
    return datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def log(msg):
    line = "[%s] %s" % (now_ts(), msg)
    try:
        with open(LOG, "a", encoding="utf-8") as f:
            f.write(line + "\n")
    except Exception:
        pass
    print(line)


def parse_props(path):
    d = {}
    try:
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#"):
                    continue
                if "=" in line:
                    k, v = line.split("=", 1)
                    d[k.strip()] = v.strip()
    except Exception as e:
        log("ERR parse props: %s" % e)
    return d


def db_query(sql, props):
    user = props.get("spring.datasource.username", "")
    pwd = props.get("spring.datasource.password", "")
    env = dict(os.environ)
    env["MYSQL_PWD"] = pwd
    try:
        out = subprocess.run(
            ["mysql", "-u", user, "-h", "127.0.0.1", "-N", "-e", sql, "qingfeng_music"],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            universal_newlines=True, env=env, timeout=20)
        if out.returncode != 0:
            log("ERR db: %s" % out.stderr.strip())
            return None
        return out.stdout.strip()
    except Exception as e:
        log("ERR db: %s" % e)
        return None


def sign_url(base, prefix, object_key, key, uid):
    """与后端 MediaCdnSigner.hash() 完全一致的算法（阿里云 CDN 鉴权 A 类型）。

    Java 源码（MediaCdnSigner.java）：
        raw = uri + "-" + timestamp + "-" + rand + "-" + uid + "-" + key
        auth_key = timestamp + "-" + rand + "-" + uid + "-" + md5(raw)
    注意顺序是 uri-ts-rand-uid-key，不能写成 ts-rand-uid-key-uri，
    否则 CDN 会返回 403 X-Tengine-Error: denied by req auth: invalid md5hash。
    """
    base = base.rstrip("/")
    d = "/" + prefix + object_key          # /qingfeng/audio/<key>.mp3
    ts = int(time.time())
    rnd = uuid.uuid4().hex
    s = "%s-%d-%s-%s-%s" % (d, ts, rnd, uid, key)
    md5 = hashlib.md5(s.encode("utf-8")).hexdigest()
    ak = "%d-%s-%s-%s" % (ts, rnd, uid, md5)
    return base + d + "?auth_key=" + ak


def oss_vhost_url(endpoint, bucket, prefix, object_key):
    host = endpoint.rstrip("/").replace("https://", "").replace("http://", "")
    return "https://%s.%s/%s%s" % (bucket, host, prefix, object_key)


def http_check(url, timeout=20):
    req = urllib.request.Request(
        url,
        headers={"Range": "bytes=0-1023",
                 "User-Agent": "qingfeng-media-health/1.0"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.headers.get("Content-Type", ""), r.headers.get("Content-Length", "")
    except urllib.error.HTTPError as e:
        return e.code, e.headers.get("Content-Type", ""), ""
    except Exception as e:
        return -1, "", str(e)


def send_mail(subject, body):
    try:
        p = subprocess.run(["mail", "-s", subject, MAIL_TO],
                           input=body, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                           universal_newlines=True, timeout=20)
        return p.returncode == 0
    except Exception:
        return False


def build_body(severity, summary, details):
    """组装中文告警邮件正文。
    severity: 严重程度文案，如 '🔴 紧急' / '🟠 重要' / '✅ 已恢复'
    summary : 一句话结论
    details : 列表，元素为 (key, value) 二元组，或纯字符串
    """
    lines = [
        "%s · 媒体可用性监控" % SITE_NAME,
        "=" * 32,
        "严重程度：%s" % severity,
        "检测时间：%s" % now_ts(),
        "服务器：%s" % SERVER_HOST,
        "-" * 32,
        summary,
    ]
    for d in details:
        lines.append("")
        if isinstance(d, (tuple, list)) and len(d) == 2:
            lines.append("%s：%s" % (d[0], d[1]))
        else:
            lines.append(str(d))
    lines.append("")
    lines.append("-" * 32)
    lines.append("本邮件由 media_health_check.py 每 10 分钟自动检测后发送")
    return "\n".join(lines)


def read_status():
    try:
        with open(STATUS, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def write_status(state, detail, effective_url="", oss_baseline="", last_alert_ts=0):
    st = read_status()
    st.update({
        "state": state,
        "detail": detail,
        "effective_url": effective_url,
        "oss_baseline_http": oss_baseline,
        "checked_at": now_ts(),
        "last_alert_ts": st.get("last_alert_ts", 0) if last_alert_ts == 0 else last_alert_ts,
    })
    try:
        with open(STATUS, "w", encoding="utf-8") as f:
            json.dump(st, f, ensure_ascii=False, indent=2)
    except Exception:
        pass


def maybe_alert(subject, body):
    st = read_status()
    last = st.get("last_alert_ts", 0) or 0
    if time.time() - last < ALERT_INTERVAL:
        log("（告警邮件限流中，跳过；距上次 %ds）" % int(time.time() - last))
        return
    ok = send_mail(subject, body)
    if ok:
        st = read_status()
        st["last_alert_ts"] = int(time.time())
        try:
            with open(STATUS, "w", encoding="utf-8") as f:
                json.dump(st, f, ensure_ascii=False, indent=2)
        except Exception:
            pass
        log("已发送告警邮件 -> %s" % MAIL_TO)
    else:
        log("（邮件发送失败，请检查本机 mail 配置；日志已记录）")


# ------------------------- 兜底状态读写 -------------------------
def read_heal_state():
    try:
        with open(HEAL_STATE, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def write_heal_state(d):
    try:
        with open(HEAL_STATE, "w", encoding="utf-8") as f:
            json.dump(d, f, ensure_ascii=False, indent=2)
    except Exception:
        pass


def clear_heal_state():
    try:
        os.remove(HEAL_STATE)
    except Exception:
        pass


def rewrite_cdn_domain(new_domain):
    """Compatibility stub: configuration changes require a separate approved release."""
    log("BLOCKED: monitor cannot rewrite application configuration")
    return False


def auto_heal(vhost_base):
    """Never promote a failed probe into a global configuration change/restart."""
    log("BLOCKED: automatic public-origin fallback and application restart disabled")
    return False


def restore_cdn(original_domain):
    log("BLOCKED: monitor cannot switch global CDN configuration or restart")
    return False


def probe(props):
    """Read-only, bounded probe; anonymous source success is a security failure."""
    cdn_domain = props.get("qingfeng.cdn.domain", "").rstrip("/")
    cdn_key = props.get("qingfeng.cdn.key-primary", "")
    prefix = props.get("qingfeng.oss.prefix", "qingfeng/audio/")
    endpoint = props.get("qingfeng.oss.endpoint", "")
    bucket = props.get("qingfeng.oss.bucket", "")
    if not (cdn_domain.startswith("https://") and cdn_key and endpoint and bucket):
        log("FAIL: missing signed CDN/source probe configuration")
        return 2
    source_host = endpoint.rstrip("/").replace("https://", "").replace("http://", "")
    if cdn_domain == "https://%s.%s" % (bucket, source_host):
        log("FAIL: legacy public-origin fallback configuration; manual review required")
        return 2
    storage = db_query("SELECT storage_key FROM media_asset WHERE origin='GENERATED' AND storage_key LIKE 'oss:%' ORDER BY created_at DESC LIMIT 1;", props)
    if not storage or not re.fullmatch(r"oss:[0-9a-fA-F-]{36}\.(mp3|wav)", storage):
        log("FAIL: no valid generated audio probe fixture")
        return 2
    key = storage[4:]
    signed = sign_url(cdn_domain, prefix, key, cdn_key, props.get("qingfeng.cdn.uid", "") or "0")
    status, content_type, length = http_check(signed)
    try:
        cdn_ok = status in (200, 206) and content_type.startswith("audio/") and int(length) > 0
    except (TypeError, ValueError):
        cdn_ok = False
    anonymous_status, _, _ = http_check(oss_vhost_url(endpoint, bucket, prefix, key))
    # 404 does not prove a private, existing object. Never use anonymous success as fallback.
    source_private = anonymous_status == 403
    if cdn_ok and source_private:
        log("OK: signed CDN probe succeeded; anonymous source rejected")
        write_status("OK", "signed CDN reachable; unsigned source rejected", oss_baseline=anonymous_status)
        return 0
    detail = "CDN HTTP %s; anonymous source HTTP %s; automatic actions disabled" % (status, anonymous_status)
    log("FAIL: " + detail)
    write_status("FAIL", detail, oss_baseline=anonymous_status)
    maybe_alert("【%s·监控】媒体或源站访问风险" % SITE_NAME,
                build_body("需人工核对", detail, [("处理边界", "不修改配置，不放开匿名访问，不重启应用")]))
    return 2


def main(argv=None):
    import argparse
    parser = argparse.ArgumentParser(description="Report-only media probe; no configuration/restart capability")
    parser.add_argument("--run", action="store_true", help="explicitly allow configured read-only DB/HTTP probes")
    parser.add_argument("--props")
    parser.add_argument("--state-dir")
    parser.add_argument("--log-dir")
    args = parser.parse_args(argv)
    if not args.run:
        print("BLOCKED: explicit --run required; no configuration or production connection opened")
        return 2
    if not (args.props and args.state_dir and args.log_dir):
        print("BLOCKED: explicit --props, --state-dir and --log-dir required; no legacy paths opened")
        return 2
    if not (os.path.isabs(args.props) and os.path.isabs(args.state_dir) and os.path.isabs(args.log_dir)
            and os.path.isdir(args.state_dir) and os.path.isdir(args.log_dir)):
        print("BLOCKED: explicit absolute probe paths and provisioned directories required")
        return 2
    global PROPS, STATUS, LOG
    PROPS = args.props
    STATUS = os.path.join(args.state_dir, "media-health.json")
    LOG = os.path.join(args.log_dir, "media-health.log")
    # One process at a time, advisory lock; never unlink a live lock inode.
    lock_path = STATUS + ".lock"
    try:
        lock = open(lock_path, "a+b")
        if os.name == "nt":
            import msvcrt
            if os.path.getsize(lock_path) == 0:
                lock.write(b"0"); lock.flush()
            lock.seek(0)
            msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        if "lock" in locals():
            lock.close()
        print("BLOCKED: probe already running or lock unavailable")
        return 2
    try:
        return probe(parse_props(PROPS))
    finally:
        lock.close()


if __name__ == "__main__":
    sys.exit(main())
