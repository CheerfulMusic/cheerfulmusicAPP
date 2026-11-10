#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
青风音乐 · 媒体(音频)可用性探测（安全自愈版）
==============================================
目的：在"用户手机端发现音频加载失败"之前，主动发现音频 URL 不可达。

探测逻辑（与线上后端 MediaCdnSigner 完全一致）：
  1. 读取 /etc/qingfeng-music/application.properties
  2. 取 media_asset 中最近一个 GENERATED 音频的 storage_key
  3. 用与后端相同的 MD5 签名算法，生成"用户实际请求的音频签名 URL"
     （即 https://<cdn.domain>/qingfeng/audio/<key>.mp3?auth_key=...）
  4. 探测该 URL 是否 HTTP 200/206 + Content-Type audio/* + 有内容
  5. 同时探测 OSS 直链基线（韧性参考）

安全自愈（AUTO_HEAL=True）：
  - 正常态（cdn.domain 指向 CDN）：CDN 不可达且 OSS 可用时，把 cdn.domain 切到
    OSS 虚拟主机端点并重启，先保证用户能播；同时把"原 CDN 域名"记入状态文件
    /var/run/qingfeng-media-heal.json。
  - 兜底态（cdn.domain 指向 OSS）：每轮额外用"原 CDN 域名"单独探测；一旦原 CDN
    恢复，自动把 cdn.domain 切回 CDN 并重启，然后清掉兜底状态。不会永久赖在 OSS。
  - 若 OSS 兜底也不可达，发紧急告警。

产物：
  - /var/log/qingfeng-media-health.log       （人类可读滚动日志）
  - /var/run/qingfeng-media-health.json      （探测状态，机器可读）
  - /opt/qingfeng-music/monitor/media_health_heal.json （兜底状态：原 CDN 域名 + 切换时间，持久化，重启不丢）
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
# 自愈开关（安全版）：CDN 不可达且 OSS 可用时切 OSS 兜底；原 CDN 恢复后自动切回。
AUTO_HEAL = True

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
    """把 application.properties 里的 qingfeng.cdn.domain 改成 new_domain。"""
    try:
        s = open(PROPS, encoding="utf-8").read()
        cur = None
        for line in s.splitlines():
            if line.startswith("qingfeng.cdn.domain="):
                cur = line.split("=", 1)[1].strip()
                break
        if cur is None:
            log("改写跳过：未找到 qingfeng.cdn.domain 行")
            return False
        s = s.replace("qingfeng.cdn.domain=" + cur,
                      "qingfeng.cdn.domain=" + new_domain, 1)
        open(PROPS, "w", encoding="utf-8").write(s)
        return True
    except Exception as e:
        log("改写 cdn.domain 失败: %s" % e)
        return False


def auto_heal(vhost_base):
    """把 qingfeng.cdn.domain 改为 OSS 虚拟主机端点并重启（先保命）。"""
    if not rewrite_cdn_domain(vhost_base):
        return False
    log("AUTO_HEAL 已将 cdn.domain -> %s，重启服务" % vhost_base)
    try:
        subprocess.run(["systemctl", "restart", "qingfeng-music"], timeout=60)
        return True
    except Exception as e:
        log("AUTO_HEAL 重启失败: %s" % e)
        return False


def restore_cdn(original_domain):
    """把 qingfeng.cdn.domain 切回原 CDN 域名并重启（恢复）。"""
    if not rewrite_cdn_domain(original_domain):
        return False
    log("AUTO_HEAL 恢复：cdn.domain -> %s，重启服务" % original_domain)
    try:
        subprocess.run(["systemctl", "restart", "qingfeng-music"], timeout=60)
        return True
    except Exception as e:
        log("AUTO_HEAL 恢复重启失败: %s" % e)
        return False


def main():
    props = parse_props(PROPS)
    cdn_domain = props.get("qingfeng.cdn.domain", "")
    cdn_key = props.get("qingfeng.cdn.key-primary", "")
    uid = props.get("qingfeng.cdn.uid", "") or "0"
    audio_prefix = props.get("qingfeng.oss.prefix", "qingfeng/audio/")
    oss_endpoint = props.get("qingfeng.oss.endpoint", "")
    oss_bucket = props.get("qingfeng.oss.bucket", "")

    if not (cdn_domain and cdn_key and audio_prefix):
        log("FAIL 配置缺失（cdn.domain / cdn.key-primary / oss.prefix）")
        write_status("FAIL", "config missing")
        maybe_alert(
            "【%s·监控】🔴 紧急：音频探测配置缺失" % SITE_NAME,
            build_body("🔴 紧急",
                       "监控脚本无法读取必要配置，音频故障将不再被感知。",
                       [("缺失项", "qingfeng.cdn.domain / cdn.key-primary / oss.prefix"),
                        ("请检查", "/etc/qingfeng-music/application.properties 是否完整、文件权限是否正常")]))
        return 2

    storage_key = db_query(
        "SELECT storage_key FROM media_asset WHERE origin='GENERATED' AND storage_key LIKE 'oss:%' ORDER BY created_at DESC LIMIT 1;",
        props)
    if not storage_key or not storage_key.startswith("oss:"):
        log("FAIL 数据库中未找到任何 GENERATED 音频 asset")
        write_status("FAIL", "no audio asset")
        maybe_alert(
            "【%s·监控】🟠 重要：未找到可探测的音频资产" % SITE_NAME,
            build_body("🟠 重要",
                       "media_asset 表中没有 origin=GENERATED 的音频，无法探测。",
                       [("可能原因", "生成流程未产出音频 / 资产被清理 / storage_key 格式变化"),
                        ("请检查", "音频生成链路与 media_asset 表")]))
        return 2
    object_key = storage_key[4:]

    host = oss_endpoint.rstrip("/").replace("https://", "").replace("http://", "")
    vhost_base = "https://%s.%s" % (oss_bucket, host)

    # ================= 兜底态检测：当前是否已切到 OSS =================
    heal = read_heal_state()
    original = (heal.get("original_cdn_domain") or "").rstrip("/")
    in_fallback = bool(heal.get("healed")) and cdn_domain.rstrip("/") == vhost_base.rstrip("/")

    # 配置已被人工改回 CDN：清理兜底状态，按正常态处理
    if heal.get("healed") and not in_fallback:
        log("检测到 cdn.domain 已不是 OSS 兜底端点，清理兜底状态")
        clear_heal_state()

    if in_fallback:
        # 先确认 OSS 兜底本身可用
        oss_url = sign_url(cdn_domain, audio_prefix, object_key, cdn_key, uid)
        fst, fct, fcl = http_check(oss_url)
        if fst not in (200, 206):
            log("FAIL 兜底模式：OSS 直连也不可达 (HTTP %s)" % fst)
            write_status("FAIL", "fallback OSS HTTP %s" % fst,
                         effective_url=oss_url, oss_baseline=fst)
            maybe_alert(
                "【%s·监控】🔴 紧急：OSS 兜底也不可达" % SITE_NAME,
                build_body("🔴 紧急",
                           "已切到 OSS 兜底，但 OSS 直连也不可达，音频完全不可用。",
                           [("OSS URL", oss_url),
                            ("探测结果", "HTTP %s，Content-Type=%s" % (fst, fct)),
                            ("请检查", "OSS 桶权限 / 对象是否被删 / 服务器出网")]))
            return 2

        # 单独探测"原 CDN"是否恢复
        if original:
            orig_url = sign_url(original, audio_prefix, object_key, cdn_key, uid)
            ost, oct_, ocl = http_check(orig_url)
            orig_ok = ost in (200, 206) and (oct_ or "").startswith("audio/") and (int(ocl or 0) > 0)
        else:
            ost, oct_, ocl, orig_ok = -1, "", "", False

        if orig_ok:
            if restore_cdn(original):
                clear_heal_state()
                log("兜底模式：原 CDN 已恢复，已自动切回 CDN")
                send_mail(
                    "【%s·监控】✅ 已切回 CDN：音频恢复走 CDN" % SITE_NAME,
                    build_body("✅ 已恢复",
                               "原 CDN 已恢复，系统已自动把 qingfeng.cdn.domain 切回 CDN 并重启服务。",
                               [("恢复域名", original),
                                ("CDN 探测", "HTTP %s, %s" % (ost, oct_)),
                                ("OSS 兜底", "HTTP %s" % fst)]))
                return 0
            log("兜底模式：原 CDN 已恢复但切回失败，继续 OSS")
            write_status("OK", "fallback, restore failed",
                         effective_url=oss_url, oss_baseline=fst)
            return 0

        log("兜底模式：原 CDN 仍不可达 (HTTP %s)，继续走 OSS 兜底" % ost)
        write_status("OK", "fallback(OSS), CDN HTTP %s" % ost,
                     effective_url=oss_url, oss_baseline=fst)
        return 0
    # ================= 正常 CDN 态 =================

    effective_url = sign_url(cdn_domain, audio_prefix, object_key, cdn_key, uid)
    st, ct, cl = http_check(effective_url)
    ok = st in (200, 206) and (ct or "").startswith("audio/") and (int(cl or 0) > 0)

    vhost_full = oss_vhost_url(oss_endpoint, oss_bucket, audio_prefix, object_key)
    st2, ct2, cl2 = http_check(vhost_full)
    oss_ok = st2 in (200, 206)

    if ok:
        log("OK 有效音频URL可达 (HTTP %s, %s, %sB) | OSS基线 HTTP %s" % (st, ct, cl, st2))
        write_status("OK", "effective HTTP %s" % st, effective_url=effective_url, oss_baseline=st2)
        return 0

    log("FAIL 有效音频URL不可达 (HTTP %s, %s, len=%s) | OSS基线 HTTP %s (可用=%s)"
        % (st, ct, cl, st2, oss_ok))
    write_status("FAIL", "HTTP %s" % st, effective_url=effective_url, oss_baseline=st2)

    body = build_body(
        "🔴 紧急",
        "有效音频 URL 不可达，用户已无法播放音频。",
        [("失败 URL（用户实际请求的音频地址）", effective_url),
         ("探测结果", "%s，Content-Type=%s，Content-Length=%s" % (st, ct, cl)),
         ("OSS 直链基线", "HTTP %s（可用=%s）" % (st2, "是" if oss_ok else "否")),
         ("可能原因", "cdn.domain 解析异常 / 未接入 CDN、OSS 权限变更、对象被删、服务器出网异常"),
         ("处置建议", "检查 application.properties 中 qingfeng.cdn.domain 是否仍指向 CDN 域名；"
                    "若已开启自愈且 OSS 可用，系统已自动切 OSS 兜底，CDN 恢复后会自动切回")])

    if AUTO_HEAL and oss_ok and cdn_domain.rstrip("/") != vhost_base.rstrip("/"):
        # 先记录原 CDN，再切 OSS；即使切换过程中出错也能据此恢复
        write_heal_state({
            "healed": True,
            "original_cdn_domain": cdn_domain,
            "healed_at": int(time.time()),
        })
        if auto_heal(vhost_base):
            info = build_body(
                "⚠️ 已兜底",
                "自动自愈已执行：已把 qingfeng.cdn.domain 切到 OSS 兜底端点并重启服务；原 CDN 恢复后将自动切回。",
                [("原(失败)URL", effective_url),
                 ("当前 cdn.domain", vhost_base),
                 ("原 CDN(将自动探测)", cdn_domain),
                 ("请关注", "下次探测（≤10 分钟）确认；原 CDN 恢复后会自动切回，无需人工")])
            log("自动兜底已执行（OSS），后续将自动探测原 CDN 恢复")
            send_mail("【%s·监控】⚠️ 已切 OSS 兜底：音频暂走 OSS，CDN 恢复后自动切回" % SITE_NAME, info)
        else:
            clear_heal_state()
            maybe_alert("【%s·监控】🔴 紧急：音频 CDN 不可达" % SITE_NAME, body)
    else:
        maybe_alert("【%s·监控】🔴 紧急：音频 CDN 不可达" % SITE_NAME, body)
    return 2


if __name__ == "__main__":
    sys.exit(main())
