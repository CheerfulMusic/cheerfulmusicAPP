"""Build a review artifact from a clean commit in a brand-new Git export.

Uses the separately verified frontend dist, checks every byte, and never loads
runtime credentials or deploys. Test evidence is not replaced by packaging.
"""
from pathlib import Path
import datetime, hashlib, importlib.util, io, json, os, shutil, subprocess, sys, zipfile
sys.stdout.reconfigure(encoding='utf-8',errors='backslashreplace')
WORK=Path(__file__).resolve().parents[2]
def git(*args):return subprocess.check_output(['git','-C',str(WORK),*args]).decode('utf-8').strip()
ROOT=Path(git('rev-parse','--path-format=absolute','--git-common-dir')).parent
assert WORK!=ROOT and not git('status','--porcelain=v1','--untracked-files=all'),'A clean isolated source commit is required'
COMMIT=git('rev-parse','HEAD')
BASE=ROOT/'.cache/payment-main-integration-20261112'
TESTED=BASE/'maven-full-20261113T030731559249Z/tested-production-classes.json'
tested=json.loads(TESTED.read_text(encoding='utf-8'))
assert tested['maven_exit_code']==0 and tested['failures']==0 and tested['errors']==0
FRONT=BASE/'frontend/complete-acceptance-20261113T030939326320Z'
receipt=json.loads((FRONT/'evidence.json').read_text(encoding='utf-8'))
assert receipt['status']=='PASS' and receipt['manifest_version']==2 and receipt['complete_input_inventory']
assert receipt['inputs_unchanged'] and not receipt['sensitive_environment_loaded'] and not receipt['runtime_env_files_loaded']
assert [step['step'] for step in receipt['steps']]==['test','build_includes_typecheck']
assert all(step['exit_code']==0 for step in receipt['steps']) and receipt['package_and_lock_unchanged']
vitest=json.loads((FRONT/'vitest.json').read_text(encoding='utf-8'))
assert vitest.get('success') is True and receipt['test_runner_success']
counts=receipt['test_counts']
assert all(vitest[key]==value for key,value in counts.items())
assert counts['numTotalTests']>0 and counts['numPassedTests']==counts['numTotalTests']
assert counts['numFailedTests']==counts['numPendingTests']==counts['numFailedTestSuites']==0
expected=json.loads((FRONT/'dist-manifest.json').read_text(encoding='utf-8'))
def digest(data):return hashlib.sha256(data).hexdigest()
dist=WORK/'Qingfeng_AI_Music_VUE/dist'
actual=[{'path':p.relative_to(dist).as_posix(),'bytes':p.stat().st_size,'sha256':digest(p.read_bytes())} for p in sorted(dist.rglob('*')) if p.is_file()]
actual_by_path={row['path']:(row['bytes'],row['sha256']) for row in actual}
expected_by_path={row['path']:(row['bytes'],row['sha256']) for row in expected}
assert len(actual_by_path)==len(actual) and len(expected_by_path)==len(expected) and actual_by_path==expected_by_path,'Frontend artifact differs from its executed acceptance receipt'
stamp=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
OUT=BASE/('artifact-'+stamp);OUT.mkdir()
EXPORT=ROOT/'.cache/pbuild'/stamp;EXPORT.mkdir(parents=True,exist_ok=False)
raw=subprocess.check_output(['git','-C',str(WORK),'archive','--format=zip',COMMIT])
(OUT/'source.zip').write_bytes(raw)
with zipfile.ZipFile(io.BytesIO(raw)) as archive:
    assert archive.comment.decode().strip()==COMMIT
    for item in archive.infolist():
        target=(EXPORT/item.filename).resolve()
        assert target.is_relative_to(EXPORT.resolve()) and not item.filename.startswith(('/','\\'))
    archive.extractall(EXPORT)
inputs=json.loads((FRONT/'inputs.json').read_text(encoding='utf-8'))
assert receipt['input_count']==len(inputs) and 'build/preview-media.ts' in inputs and any(name.startswith('public/') for name in inputs)
frontend_export=EXPORT/'Qingfeng_AI_Music_VUE'
exported_inputs={}
generated={'node_modules','dist','.cache','coverage','.vite','.git'}
assert receipt['generated_directories_excluded']==sorted(generated)
for directory,dirs,names in os.walk(frontend_export,followlinks=False):
    dirs[:]=sorted(name for name in dirs if name not in generated)
    for name in sorted(names):
        path=Path(directory)/name
        assert not path.is_symlink() and path.resolve().is_relative_to(frontend_export.resolve())
        exported_inputs[path.relative_to(frontend_export).as_posix()]=path
assert set(exported_inputs)==set(inputs),'Committed frontend input set differs from the complete executed acceptance inventory'
text_suffixes={'.ts','.js','.vue','.css','.scss','.html','.json','.md','.yml','.yaml','.xml','.svg','.properties','.txt'}
for relative,entry in inputs.items():
    path=(EXPORT/'Qingfeng_AI_Music_VUE'/relative).resolve()
    assert path.is_relative_to((EXPORT/'Qingfeng_AI_Music_VUE').resolve())
    value=path.read_bytes();approved=entry['sha256'] if isinstance(entry,dict) else entry
    candidates=[value]
    if path.suffix.lower() in text_suffixes or relative in ('.env.example','.env.native.example'):
        lf=value.replace(b'\r\n',b'\n')
        candidates.extend((lf,lf.replace(b'\n',b'\r\n')))
    assert any(digest(candidate)==approved for candidate in candidates),'Committed frontend input differs beyond Git EOL normalization: '+relative
shutil.copytree(dist,EXPORT/'Qingfeng_AI_Music_VUE/dist')
# This is generated release metadata in the export, never a change to source.
(EXPORT/'Qingfeng_AI_Music/deploy/release-metadata/release-commit.txt').write_text(COMMIT+'\n',encoding='ascii')
blocked=('SECRET','TOKEN','PASSWORD','KEY','CREDENTIAL','CONFIG','DATABASE','CONNECTION','DSN','INVITE_GATE','PAYPAL','STRIPE','MYSQL','SPRING_','JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS','MAVEN_OPTS','OPENAI','ANTHROPIC','KIE_','OSS_','AWS_','VITE_','QF_','P0_')
env={k:v for k,v in os.environ.items() if not any(x in k.upper() for x in blocked)}
cmd=['mvn.cmd','-o','-Dmaven.repo.local='+str(ROOT/'.cache/maven'),'-DskipTests','package']
record={'status':'FAIL','source_commit':COMMIT,'frontend_source_tree':git('rev-parse',COMMIT+':Qingfeng_AI_Music_VUE'),
    'source_archive_sha256':digest(raw),'fresh_export':True,'runtime_credentials_loaded':False,'test_execution':'NOT_RUN_IN_PACKAGING_ALREADY_RECORDED_SEPARATELY',
    'frontend_receipt_sha256':digest((FRONT/'evidence.json').read_bytes()),'frontend_complete_input_manifest_sha256':digest((FRONT/'inputs.json').read_bytes()),
    'frontend_input_count':len(inputs),'frontend_test_counts':counts,'committed_frontend_inputs_match_acceptance':True,
    'command':cmd,'allow_release':False,'allow_live':False}
print(json.dumps({'log':str(OUT/'build.log'),'source_commit':COMMIT}),flush=True)
with (OUT/'build.log').open('w',encoding='utf-8') as log:
    result=subprocess.run(cmd,cwd=EXPORT/'Qingfeng_AI_Music',env=env,stdout=log,stderr=subprocess.STDOUT)
record['exit_code']=result.returncode
if result.returncode==0:
    compiled={}
    for module in ('qf-common','qf-business','qf-infrastructure','qf-server'):
        classes=EXPORT/'Qingfeng_AI_Music'/module/'target/classes'
        for path in classes.rglob('*.class'):
            compiled[module+'/'+path.relative_to(classes).as_posix()]=digest(path.read_bytes())
    assert compiled==tested['classes'],'Fresh artifact code differs from the exact production classes used by the passing test run'
    record['tested_class_manifest_sha256']=digest(TESTED.read_bytes())
    record['all_production_class_bytes_match_tested']=True
    record['production_class_count']=len(compiled)
    original=EXPORT/'Qingfeng_AI_Music/qf-server/target/qf-server-0.1.0-SNAPSHOT.jar'
    artifact=OUT/'app.jar';shutil.copyfile(original,artifact)
    spec=importlib.util.spec_from_file_location('artifact_gate',WORK/'scripts/release_gate.py')
    gate=importlib.util.module_from_spec(spec);spec.loader.exec_module(gate)
    verified=gate.inspect_artifact(artifact)
    assert verified.get('sourceCommit')==COMMIT
    with zipfile.ZipFile(artifact) as jar:
        bundled={name.removeprefix('BOOT-INF/classes/static/'):jar.read(name) for name in jar.namelist() if name.startswith('BOOT-INF/classes/static/') and not name.endswith('/')}
    assert set(bundled)=={row['path'] for row in expected},'No stale or missing frontend assets allowed'
    for row in expected:assert digest(bundled[row['path']])==row['sha256']
    record.update({'status':'PASS','jar':str(artifact),'jar_sha256':digest(artifact.read_bytes()),'artifact_gate':verified,
        'frontend_files':len(expected),'all_frontend_bytes_match_acceptance':True})
(OUT/'results.json').write_text(json.dumps(record,indent=2),encoding='utf-8')
print(json.dumps({'status':record['status'],'evidence':str(OUT/'results.json'),'source_commit':COMMIT}),flush=True)
raise SystemExit(0 if record['status']=='PASS' else 1)
