import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec=importlib.util.spec_from_file_location('livingtv',Path(__file__).resolve().parents[1]/'mac/livingtv.py')
mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)

class FakeADB:
    def __init__(self):
        self.states={'com.vendor.recommend':0,'com.vendor.optional':1,'com.vendor.launcher':0,'com.android.settings':0,'com.vendor.shared':0}
        self.changed=[]
        self.fail_after_mutation=False
        self.disconnect=False
    def identity(self):
        if self.disconnect: raise mod.ToolError('device offline')
        return dict(serial='fixture',manufacturer='Fixture',model='TV',fingerprint='fixture/build/1')
    def homes(self): return {'com.vendor.launcher'}
    def packages(self): return sorted(self.states)
    def package_state(self,p):
        if self.disconnect: raise mod.ToolError('device offline')
        return self.states[p], 'sharedUserId=android.uid.system' if p.endswith('.shared') else ''
    def change(self,p,action):
        self.changed.append((p,action))
        self.states[p]={'disable-user':3,'default-state':0,'enable':1}[action]
        if self.fail_after_mutation:
            self.disconnect=True
            raise mod.ToolError('device offline after command')

class ManagerTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.state=Path(self.tmp.name);self.adb=FakeADB()
        self.profile=dict(manufacturer='Fixture',model='TV',fingerprint='fixture/build/1',verified=True,evidence='isolated test fixture, not a real device rule',packages=[dict(name=p,optional=True,purpose='测试') for p in self.adb.states])
        self.rules=dict(schema_version=1,profiles=[self.profile])
    def tearDown(self): self.tmp.cleanup()
    def test_unknown_firmware_scan_never_changes(self):
        self.profile['fingerprint']='other'
        result=mod.scan(self.adb,self.rules)
        self.assertFalse(any(p['eligible'] for p in result['packages']));self.assertEqual([],self.adb.changed)
    def test_unverified_profile_not_accepted(self):
        self.profile['verified']=False
        with self.assertRaises(mod.ToolError):mod.disable(self.adb,self.rules,['com.vendor.recommend'],self.state)
        self.assertEqual([],self.adb.changed)
    def test_protected_home_settings_and_shared_uid(self):
        for p in ['com.vendor.launcher','com.android.settings','com.vendor.shared']:
            with self.assertRaises(mod.ToolError):mod.disable(self.adb,self.rules,[p],self.state)
        self.assertEqual([],self.adb.changed)
    def test_disable_restore_preserves_exact_initial_state(self):
        result=mod.disable(self.adb,self.rules,['com.vendor.recommend','com.vendor.optional'],self.state)
        self.assertTrue(all(p['success'] for p in result['results']))
        self.assertEqual(3,self.adb.states['com.vendor.recommend'])
        result=mod.restore(self.adb,self.state)
        self.assertTrue(all(p['success'] for p in result['results']))
        self.assertEqual(0,self.adb.states['com.vendor.recommend']);self.assertEqual(1,self.adb.states['com.vendor.optional'])
        self.assertEqual(0,self.adb.states['com.android.settings'])
    def test_already_disabled_external_component_not_recorded(self):
        self.adb.states['com.vendor.recommend']=3
        with self.assertRaises(mod.ToolError):mod.disable(self.adb,self.rules,['com.vendor.recommend'],self.state)
        self.assertEqual([],list(self.state.glob('*.json')))
    def test_disconnect_keeps_recoverable_pending_record(self):
        self.adb.fail_after_mutation=True
        result=mod.disable(self.adb,self.rules,['com.vendor.recommend','com.vendor.optional'],self.state)
        self.assertFalse(any(r['success'] for r in result['results']))
        journal=json.loads(Path(result['journal']).read_text());self.assertEqual('pending',journal['operations'][0]['status'])
        self.adb.disconnect=False;self.adb.fail_after_mutation=False
        result=mod.restore(self.adb,self.state)
        self.assertTrue(result['results'][0]['success']);self.assertEqual(0,self.adb.states['com.vendor.recommend'])
    def test_restore_without_own_journal_rejected(self):
        with self.assertRaises(mod.ToolError):mod.restore(self.adb,self.state)
        self.assertEqual([],self.adb.changed)
    def test_external_state_change_not_overwritten(self):
        mod.disable(self.adb,self.rules,['com.vendor.recommend'],self.state)
        self.adb.states['com.vendor.recommend']=2
        result=mod.restore(self.adb,self.state)
        self.assertFalse(result['results'][0]['success']);self.assertEqual(2,self.adb.states['com.vendor.recommend'])
    def test_no_shell_interpolation(self):
        seen=[]
        a=mod.ADB('fixture');a.run=lambda *args:seen.append(args) or ''
        a.shell('am','start','--es','source_url','https://example.org/a?x=1&b=$(bad)')
        import shlex
        self.assertEqual(['am','start','--es','source_url','https://example.org/a?x=1&b=$(bad)'],shlex.split(seen[0][1]))

if __name__=='__main__':unittest.main()
