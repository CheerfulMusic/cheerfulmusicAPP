"""Prepare private beta admission for loopback UI requests to the owned main backend."""
from pathlib import Path
import csv,http.cookiejar,json,os,re,subprocess,time,urllib.request
ROOT=Path(__file__).resolve().parent.parent
DEST=ROOT/'.secrets/production-main.gateway.json'
SSH=['ssh','-i','D:/it_ai/青风/.runtime/qingfeng-deploy-key.pem','-o','BatchMode=yes','-o','StrictHostKeyChecking=yes','-o','ConnectTimeout=15','root@47.84.5.172','python3','-']
cookies=http.cookiejar.CookieJar()
client=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))
def data(path,body=None,csrf=None):
    headers={'Content-Type':'application/json'}
    if csrf:headers['X-XSRF-TOKEN']=csrf
    request=urllib.request.Request('https://cheerfulmusic.com'+path,data=json.dumps(body).encode() if body is not None else None,headers=headers)
    with client.open(request,timeout=20) as response:
        value=json.load(response);return value.get('data',value)
status=data('/api/public/invite/status')
admission='';expires=int(time.time())+86400
if status['enabled']:
    source=r'''
from pathlib import Path
import json
props={}
for line in Path('/etc/qingfeng-music/invite-gate.properties').read_text().splitlines():
    if '=' in line and not line.lstrip().startswith(('#','!')):
        key,value=line.split('=',1);props[key.strip()]=value.strip()
print(json.dumps({'code':props['qingfeng.invite-gate.codes'].split(',')[0].strip()}))
'''
    result=subprocess.run(SSH,input=source.encode(),stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=30)
    if result.returncode:raise RuntimeError('Could not read owned project admission configuration')
    code=json.loads(result.stdout)['code']
    assert data('/api/public/invite/enter',{'code':code},status['csrfToken'])['admitted']
    matches=[c for c in cookies if c.name=='QF_BETA_ACCESS']
    assert len(matches)==1
    cookie=matches[0]
    assert re.fullmatch(r'[A-Za-z0-9_.-]+',cookie.value)
    admission=cookie.name+'='+cookie.value;expires=int(cookie.value.split('.')[2])
DEST.parent.mkdir(exist_ok=True)
if not DEST.exists():
    fd=os.open(str(DEST),os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600);os.close(fd)
if os.name=='nt':
    identity=subprocess.run(['whoami','/user','/fo','csv','/nh'],stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=True)
    sid=next(csv.reader(identity.stdout.decode(errors='replace').splitlines()))[-1].strip()
    assert re.fullmatch(r'S-1-[0-9-]+',sid)
    subprocess.run(['icacls',str(DEST),'/inheritance:r','/grant:r','*'+sid+':(F)','*S-1-5-18:(F)'],stdout=subprocess.DEVNULL,stderr=subprocess.PIPE,check=True)
else:os.chmod(DEST,0o600)
DEST.write_text(json.dumps({'upstream':'https://cheerfulmusic.com','inviteCookie':admission,'expiresAt':expires}),encoding='utf-8')
print(json.dumps({'mainGatewayPrepared':True,'privateAdmissionStored':bool(admission),'upstream':'https://cheerfulmusic.com'}))
