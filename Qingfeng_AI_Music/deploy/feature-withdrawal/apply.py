"""Reversible edge-only withdrawal. No JAR, database, or application restart."""
import datetime,fcntl,json,shutil,subprocess
from pathlib import Path
stage=Path(__file__).resolve().parent
lock=open('/run/lock/qingfeng-release.lock','a');fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
config=Path('/etc/nginx/conf.d/cheerfulmusic.conf');original=config.read_text()
assert 'feature-withdrawal' not in original
backup=Path('/srv/qingfeng-data/qingfeng-release-backups/feature-withdrawal-'+datetime.datetime.utcnow().strftime('%Y%m%dT%H%M%SZ'));backup.mkdir(mode=0o700)
shutil.copy2(config,backup/'cheerfulmusic.conf')
(backup/'current-release.txt').write_text(str(Path('/opt/qingfeng-music/current').resolve()))
assets=Path('/var/lib/nginx/feature-withdrawal');assert not assets.exists();assets.mkdir(mode=0o755)
for name in ['withdraw.js','withdraw.css']:shutil.copyfile(stage/name,assets/name);(assets/name).chmod(0o644)
proxy="""        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header X-Forwarded-Host $host;
        proxy_set_header X-Forwarded-Port $server_port;
        proxy_set_header Forwarded "";
        proxy_set_header X-Forwarded-Proto $scheme;
"""
block="""
    # Temporary feature-withdrawal: preserve local implementation and existing files.
    location = /agent { return 302 /create; }
    location ^~ /agent/ { return 302 /create; }
    location = /api/app/agent { default_type application/json; return 403 '{"code":"FEATURE_WITHDRAWN","message":"Agent is temporarily unavailable."}'; }
    location ^~ /api/app/agent/ { default_type application/json; return 403 '{"code":"FEATURE_WITHDRAWN","message":"Agent is temporarily unavailable."}'; }
    location = /feature-withdrawal.js { alias /var/lib/nginx/feature-withdrawal/withdraw.js; default_type application/javascript; add_header Cache-Control "no-store" always; }
    location = /feature-withdrawal.css { alias /var/lib/nginx/feature-withdrawal/withdraw.css; default_type text/css; add_header Cache-Control "no-store" always; }
"""
for route in ['= /api/app/music-videos','^~ /api/app/music-videos/']:
 block+='    location '+route+""" {
        default_type application/json;
        if ($request_method !~ ^(GET|HEAD)$) { return 403 '{"code":"FEATURE_WITHDRAWN","message":"MV generation is temporarily unavailable."}'; }
"""+proxy+'    }\n'

modified=original.replace('    ssl_protocols TLSv1.2 TLSv1.3;','    ssl_protocols TLSv1.2 TLSv1.3;\n'+block)
needle='    location / {\n        proxy_pass http://127.0.0.1:8080;'
assert modified.count(needle)==1
modified=modified.replace(needle,"""    location / {
        proxy_set_header Accept-Encoding "";
        sub_filter_once on;
        sub_filter '</head>' '<link rel="stylesheet" href="/feature-withdrawal.css"><script src="/feature-withdrawal.js"></script></head>';
        proxy_pass http://127.0.0.1:8080;""")
try:
 assert config.read_text()==original
 config.write_text(modified)
 subprocess.run(['nginx','-t'],check=True)
 subprocess.run(['systemctl','reload','nginx'],check=True)
except BaseException:
 shutil.copy2(backup/'cheerfulmusic.conf',config)
 subprocess.run(['nginx','-t'],check=True);subprocess.run(['systemctl','reload','nginx'],check=True)
 raise
print(json.dumps({'backup':str(backup),'currentReleaseUnchanged':str(Path('/opt/qingfeng-music/current').resolve())}),flush=True)
