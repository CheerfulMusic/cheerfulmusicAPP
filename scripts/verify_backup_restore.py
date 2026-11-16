"""Verify an EXISTING backup in isolation. No production SQL, service or policy writes.

Run with python3.11. Success receipts attest only the specified backup, not deployment.
"""
import argparse
from contextlib import ExitStack
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import uuid

from restore_dump_verifier import require


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def controls():
    current = Path('/opt/qingfeng-music/current').resolve(strict=True)
    return {'current': str(current), 'source': (current/'source-commit.txt').read_text().strip(),
            'files': {str(p): digest(p) for p in [current/'app.jar',
                       Path('/etc/qingfeng-music/application.properties'),
                       Path('/etc/qingfeng-music/release-policy.json'),
                       Path('/opt/qingfeng-music/verify-release.py'),
                       Path('/opt/qingfeng-music/release_gate.py'),
                       Path('/etc/nginx/conf.d/cheerfulmusic.conf')]}}


def verify(backup, expected_sha, expected_source):
    require(sys.version_info[:2] == (3, 11) and os.geteuid() == 0, 'PYTHON311_LINUX_ROOT_REQUIRED')
    backup = Path(backup).resolve(strict=True)
    require(backup.parent == Path('/var/backups/qingfeng-music'), 'BACKUP_PATH_OUTSIDE_ROOT')
    for name in ('database.sql', 'history.tsv', 'rollback-files.tar.gz'):
        require((backup/name).is_file() and not (backup/name).is_symlink(), 'BACKUP_INPUT_INVALID')
    require(digest(backup/'database.sql') == expected_sha, 'BACKUP_HASH_MISMATCH')
    code = Path(__file__).resolve().parent
    with ExitStack() as stack:
        import fcntl
        for name in ('qingfeng-release.lock', 'qingfeng-music-backup.lock'):
            lock = stack.enter_context(open('/run/lock/'+name, 'r'))
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        before = controls()
        require(before['source'] == expected_source, 'PRODUCTION_BASELINE_CHANGED')
        run = backup.parent/('restore-check-'+uuid.uuid4().hex)
        run.mkdir(mode=0o700)
        sandbox = run/'sandbox'; sandbox.mkdir(mode=0o700); os.chown(sandbox, 65534, 65534)
        for name in ('database.sql', 'history.tsv'):
            shutil.copyfile(backup/name, run/name); os.chmod(run/name, 0o444)
        require(digest(run/'database.sql') == expected_sha, 'COPY_HASH_MISMATCH')
        cmd = ['bwrap', '--unshare-pid', '--unshare-net', '--unshare-ipc', '--unshare-uts',
               '--die-with-parent', '--cap-add', 'CAP_SETUID', '--cap-add', 'CAP_SETGID',
               '--ro-bind', '/usr', '/usr', '--symlink', 'usr/lib', '/lib',
               '--symlink', 'usr/lib64', '/lib64', '--symlink', 'usr/bin', '/bin',
               '--proc', '/proc', '--dev', '/dev', '--tmpfs', '/tmp', '--dir', '/etc',
               '--bind', str(sandbox), '/sandbox',
               '--ro-bind', str(run/'database.sql'), '/backup.sql',
               '--ro-bind', str(run/'history.tsv'), '/history.tsv']
        for name in ('restore_isolated_mysql.py', 'restore_dump_verifier.py'):
            cmd += ['--ro-bind', str(code/name), '/'+name]
        cmd += ['--', '/usr/bin/setpriv', '--reuid=65534', '--regid=65534',
                '--clear-groups', '--no-new-privs', '/usr/bin/python3.11', '-B', '/restore_isolated_mysql.py']
        try:
            result = subprocess.run(cmd, env={'PATH': '/usr/bin:/usr/sbin', 'HOME': '/tmp'},
                                    capture_output=True, timeout=900)
            (run/'verification.log').write_bytes(result.stdout + result.stderr)
            require(result.returncode == 0, 'ISOLATED_VERIFICATION_FAILED:'+str(run))
            evidence = json.loads(result.stdout)
            require(evidence['restore'] == 'PASS', 'RESTORE_NOT_VERIFIED')
            require(evidence['comparison']['sourceSha256'] == expected_sha, 'RESTORE_INPUT_MISMATCH')
            require(evidence['historySha256'] == digest(backup/'history.tsv'), 'RESTORE_HISTORY_MISMATCH')
            require(digest(backup/'database.sql') == expected_sha, 'ORIGINAL_BACKUP_CHANGED')
            require(controls() == before, 'PRODUCTION_CONTROLS_CHANGED')
            receipt = {'version': 1, 'scope': 'EXISTING_BACKUP_ISOLATED_RESTORE_ONLY',
                       'deploymentApproved': False, 'productionSqlExecuted': False,
                       'backup': str(backup), 'verificationDirectory': str(run),
                       'baseline': before, 'verifiedAtEpoch': int(time.time()),
                       'rollbackArchiveSha256': digest(backup/'rollback-files.tar.gz'),
                       'scriptSha256': {name: digest(code/name) for name in
                                        ('verify_backup_restore.py', 'restore_isolated_mysql.py', 'restore_dump_verifier.py')},
                       'logSha256': digest(run/'verification.log'), 'evidence': evidence}
            with (run/'receipt.json').open('x') as stream:
                json.dump(receipt, stream, sort_keys=True); stream.flush(); os.fsync(stream.fileno())
            print(json.dumps({'status': 'PASS', 'receipt': str(run/'receipt.json'),
                              'receiptSha256': digest(run/'receipt.json'),
                              'tables': len(evidence['comparison']['tables']),
                              'rows': sum(t['rows'] for t in evidence['comparison']['tables'].values())}))
        finally:
            for name in ('database.sql', 'history.tsv'):
                os.chmod(run/name, 0o600)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--backup', required=True)
    parser.add_argument('--dump-sha256', required=True)
    parser.add_argument('--baseline', required=True)
    args = parser.parse_args()
    verify(args.backup, args.dump_sha256, args.baseline)
