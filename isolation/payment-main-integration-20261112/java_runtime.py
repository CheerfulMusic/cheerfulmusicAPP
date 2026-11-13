"""Resolve the installed JVM behind Windows java launchers; never kill by name."""
from pathlib import Path
import os
import re
import socket
import subprocess
import time


def resolve_java_binary():
    probe=subprocess.run(['java','-XshowSettings:properties','-version'],
                         stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=10)
    if probe.returncode!=0:
        raise RuntimeError('JAVA_RUNTIME_PROBE_FAILED')
    match=re.search(r'^\s*java\.home\s*=\s*(.+)$',probe.stderr.decode('utf-8',errors='replace'),re.M)
    if not match:
        raise RuntimeError('JAVA_RUNTIME_HOME_MISSING')
    binary=Path(match.group(1).strip())/'bin'/('java.exe' if os.name=='nt' else 'java')
    if not binary.is_file():
        raise RuntimeError('JAVA_RUNTIME_BINARY_MISSING')
    return binary.resolve()


def stop_owned_process(process, port):
    """The caller launches the resolved binary directly and retains its Popen handle."""
    if process.poll() is None:
        process.terminate()
        try:process.wait(timeout=20)
        except subprocess.TimeoutExpired:process.kill();process.wait(timeout=5)
    deadline=time.monotonic()+5
    while time.monotonic()<deadline:
        with socket.socket() as probe:
            probe.settimeout(.2)
            if probe.connect_ex(('127.0.0.1',port))!=0:
                return process.poll() is not None
        time.sleep(.1)
    return False
