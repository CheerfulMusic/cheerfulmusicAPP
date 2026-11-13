from pathlib import Path
from unittest import TestCase,mock
import importlib.util
import subprocess
import sys
import tempfile

path=Path(__file__).resolve().parents[1]/'payment-main-integration-20261112/java_runtime.py'
spec=importlib.util.spec_from_file_location('java_runtime',path)
runtime=importlib.util.module_from_spec(spec);spec.loader.exec_module(runtime)


class JavaRuntimeTests(TestCase):
    def test_resolves_actual_binary_instead_of_reusing_launcher(self):
        with tempfile.TemporaryDirectory() as folder:
            home=Path(folder);binary=home/'bin'/('java.exe' if runtime.os.name=='nt' else 'java')
            binary.parent.mkdir();binary.touch()
            probe=subprocess.CompletedProcess([],0,b'',('    java.home = '+str(home)+'\n').encode())
            with mock.patch.object(runtime.subprocess,'run',return_value=probe):
                self.assertEqual(binary.resolve(),runtime.resolve_java_binary())
    def test_unknown_or_missing_java_home_is_rejected(self):
        for probe in [subprocess.CompletedProcess([],1,b'',b''),subprocess.CompletedProcess([],0,b'',b'no home'),
                      subprocess.CompletedProcess([],0,b'',b'java.home = /missing/runtime')]:
            with mock.patch.object(runtime.subprocess,'run',return_value=probe):
                with self.assertRaises(RuntimeError):runtime.resolve_java_binary()
    def test_owned_direct_process_and_its_port_are_both_closed(self):
        with runtime.socket.socket() as listener:
            listener.bind(('127.0.0.1',0));port=listener.getsockname()[1]
        process=subprocess.Popen([sys.executable,'-c','import time; time.sleep(60)'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        self.assertTrue(runtime.stop_owned_process(process,port))
        self.assertIsNotNone(process.poll())
