"""Execute frontend acceptance with a complete, immutable input inventory.

Only generated dist and ignored evidence files are written. No runtime .env,
provider credentials, database, install, Git mutation, or deployment is used.
"""
from pathlib import Path
import datetime, hashlib, json, os, shutil, subprocess, sys, time

sys.stdout.reconfigure(encoding='utf-8', errors='backslashreplace')
WORK = Path(__file__).resolve().parents[2]
ROOT = Path(subprocess.check_output(['git', '-C', str(WORK), 'rev-parse', '--path-format=absolute', '--git-common-dir']).decode().strip()).parent
FRONT = WORK / 'Qingfeng_AI_Music_VUE'
assert WORK != ROOT and FRONT.resolve().is_relative_to(WORK.resolve())
STAMP = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
OUT = ROOT / '.cache/payment-main-integration-20261112/frontend' / ('complete-acceptance-' + STAMP)
OUT.mkdir(parents=True, exist_ok=False)
GENERATED = {'node_modules', 'dist', '.cache', 'coverage', '.vite', '.git'}
TEXT_SUFFIXES = {'.ts', '.js', '.vue', '.css', '.scss', '.html', '.json', '.md', '.yml', '.yaml', '.xml', '.svg', '.properties', '.txt'}

def digest(data):
    return hashlib.sha256(data).hexdigest()

def inventory():
    files = {}
    for directory, dirs, names in os.walk(FRONT, followlinks=False):
        dirs[:] = sorted(name for name in dirs if name not in GENERATED)
        for name in sorted(names):
            path = Path(directory) / name
            assert not path.is_symlink() and path.resolve().is_relative_to(FRONT.resolve()), 'Input symlinks are not accepted'
            relative = path.relative_to(FRONT).as_posix()
            # Vite loadEnv(..., '') reads every variable in a real .env file.
            assert not name.startswith('.env') or name in ('.env.example', '.env.native.example'), 'Runtime .env input is forbidden'
            data = path.read_bytes()
            files[relative] = {'bytes': len(data), 'sha256': digest(data)}
    known = set()
    for options in (['ls-files', '-z'], ['ls-files', '--others', '--exclude-standard', '-z']):
        raw = subprocess.check_output(['git', '-C', str(WORK), *options, '--', 'Qingfeng_AI_Music_VUE'])
        known.update(name.decode('utf-8').split('/', 1)[1] for name in raw.split(b'\0') if name)
    assert set(files) == known, 'Missing source or unregistered ignored frontend input'
    return dict(sorted(files.items()))

before = inventory()
assert 'build/preview-media.ts' in before and any(name.startswith('public/') for name in before)
(OUT / 'inputs.json').write_text(json.dumps(before, indent=2), encoding='utf-8')
dist = FRONT / 'dist'
if dist.is_dir():
    shutil.copytree(dist, OUT / 'previous-dist')
    previous = {path.relative_to(dist).as_posix(): digest(path.read_bytes()) for path in dist.rglob('*') if path.is_file()}
else:
    previous = {}

blocked = ('SECRET', 'TOKEN', 'PASSWORD', 'KEY', 'CREDENTIAL', 'CREDIT_', 'CONFIG', 'DATABASE', 'CONNECTION', 'DSN', 'INVITE_GATE',
           'PAYPAL', 'STRIPE', 'MYSQL', 'SPRING_', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'MAVEN_OPTS',
           'OPENAI', 'ANTHROPIC', 'KIE_', 'OSS_', 'AWS_', 'VITE_', 'QF_', 'P0_', 'NODE_OPTIONS')
env = {key: value for key, value in os.environ.items() if not any(part in key.upper() for part in blocked)}
node_dir = ROOT / '.cache/toolchains/node-v24.22.1-win-x64'
node = node_dir / 'node.exe'
npm = node_dir / 'node_modules/npm/bin/npm-cli.js'
assert node.is_file() and npm.is_file()
env['PATH'] = str(node_dir) + os.pathsep + env.get('PATH', '')
for label in ('user', 'global'):
    config = OUT / ('npm-' + label + '.config')
    config.write_text('', encoding='ascii')
    env['NPM_CONFIG_' + label.upper() + 'CONFIG'] = str(config)
env['NPM_CONFIG_UPDATE_NOTIFIER'] = 'false'

record = {'status': 'FAIL', 'manifest_version': 2, 'complete_input_inventory': True,
          'generated_directories_excluded': sorted(GENERATED), 'sensitive_environment_loaded': False,
          'runtime_env_files_loaded': False, 'input_count': len(before), 'steps': [],
          'scope': 'LOCAL_FRONTEND_TEST_AND_BUILD_ONLY', 'database_access': False, 'provider_calls': False,
          'checkpoint': subprocess.check_output(['git', '-C', str(WORK), 'rev-parse', 'HEAD']).decode().strip()}
print(json.dumps({'evidence': str(OUT / 'evidence.json'), 'input_count': len(before)}), flush=True)
for step, args in (('test', ['test', '--', '--reporter=default', '--reporter=json', '--outputFile=' + str(OUT / 'vitest.json')]),
                   ('build_includes_typecheck', ['run', 'build'])):
    command = [str(node), str(npm), *args]
    started = time.monotonic()
    with (OUT / (step + '.log')).open('w', encoding='utf-8') as log:
        result = subprocess.run(command, cwd=FRONT, env=env, stdout=log, stderr=subprocess.STDOUT)
    record['steps'].append({'step': step, 'command': command, 'exit_code': result.returncode, 'seconds': round(time.monotonic() - started, 2)})
    if result.returncode:
        break
after = inventory()
record['inputs_unchanged'] = before == after
record['package_and_lock_unchanged'] = all(before[name] == after[name] for name in ('package.json', 'package-lock.json'))
if (OUT / 'vitest.json').is_file():
    tests = json.loads((OUT / 'vitest.json').read_text(encoding='utf-8'))
    record['test_counts'] = {key: tests[key] for key in ('numTotalTests', 'numPassedTests', 'numFailedTests', 'numPendingTests', 'numTotalTestSuites', 'numFailedTestSuites')}
    record['test_runner_success'] = tests.get('success') is True
if len(record['steps']) == 2 and all(step['exit_code'] == 0 for step in record['steps']) and record['inputs_unchanged']:
    counts = record['test_counts']
    assert record['test_runner_success'] and counts['numTotalTests'] == counts['numPassedTests'] == 717
    assert counts['numFailedTests'] == counts['numPendingTests'] == counts['numFailedTestSuites'] == 0
    manifest = [{'path': path.relative_to(dist).as_posix(), 'bytes': path.stat().st_size, 'sha256': digest(path.read_bytes())}
                for path in sorted(dist.rglob('*')) if path.is_file()]
    assert manifest and any(row['path'] == 'index.html' for row in manifest)
    (OUT / 'dist-manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    record.update({'status': 'PASS', 'dist': {'files': len(manifest), 'bytes': sum(row['bytes'] for row in manifest),
                                           'index_sha256': next(row['sha256'] for row in manifest if row['path'] == 'index.html')},
                   'dist_matches_previous_build': {row['path']: row['sha256'] for row in manifest} == previous})
(OUT / 'evidence.json').write_text(json.dumps(record, indent=2), encoding='utf-8')
print(json.dumps({'status': record['status'], 'evidence': str(OUT / 'evidence.json'), 'input_count': len(before),
                  'test_counts': record.get('test_counts'), 'dist_matches_previous_build': record.get('dist_matches_previous_build')}), flush=True)
raise SystemExit(0 if record['status'] == 'PASS' else 1)
