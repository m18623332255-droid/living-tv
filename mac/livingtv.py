#!/usr/bin/env python3
"""LivingTV's local ADB companion. No network service and no implicit mutations."""
import argparse
import contextlib
import fcntl
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile
import time

APP = 'cn.living.tv'
COMPONENT = APP + '/com.gathin.tvhome.MainActivity'
SOURCE_COMPONENT = APP + '/cn.living.tv.PlayerPortalActivity'
ROOT = Path(__file__).resolve().parent
DEFAULT_STATE = Path.home() / 'Library/Application Support/LivingTV'
PKG = re.compile(r'^[a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z0-9_]+)+$')
PROTECTED = re.compile(r'(^android$|^android\.|^com\.android\.|^com\.google\.android\.|launcher|settings|systemui|keyguard|permission|packageinstaller|input|remote|bluetooth|network|wifi|security|framework|television|tvservice|tvinput|updater|update|provision|setupwizard|accessibility|voice|power|cec)', re.I)

class ToolError(Exception):
    pass

class ADB:
    def __init__(self, serial, executable=None):
        self.serial = serial
        self.executable = executable or os.environ.get('LIVINGTV_ADB') or str(ROOT / 'bin/adb')
        if not Path(self.executable).is_file():
            import shutil
            self.executable = shutil.which('adb') or self.executable
    def run(self, *args, timeout=30):
        result = subprocess.run([self.executable] + (['-s', self.serial] if self.serial else []) + list(args), capture_output=True, text=True, timeout=timeout)
        if result.returncode:
            raise ToolError((result.stderr or result.stdout).strip()[:1200])
        return result.stdout.strip()
    def shell(self, *args):
        # adb shell is parsed by a device shell: quote each argument separately.
        return self.run('shell', ' '.join(shlex.quote(str(a)) for a in args))
    def ready(self):
        if self.run('get-state') != 'device':
            raise ToolError('电视未授权或未连接。请在电视确认 ADB 授权，再重新扫描。')
        uid = self.shell('id', '-u').strip()
        if uid not in ('2000', '0'):
            raise ToolError('当前连接没有可用的 ADB shell 权限')
    def identity(self):
        self.ready()
        return dict(serial=self.serial or self.run('get-serialno'), manufacturer=self.shell('getprop', 'ro.product.manufacturer'), model=self.shell('getprop', 'ro.product.model'), fingerprint=self.shell('getprop', 'ro.build.fingerprint'))
    def homes(self):
        out = self.shell('cmd', 'package', 'query-activities', '--brief', '--user', '0', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')
        result = {line.strip().split('/')[0] for line in out.splitlines() if '/' in line and PKG.fullmatch(line.strip().split('/')[0])}
        if not result:
            raise ToolError('系统无法枚举原桌面；为保护恢复入口，本次禁止组件变更')
        return result
    def packages(self):
        return sorted(line[8:] for line in self.shell('pm', 'list', 'packages', '--user', '0').splitlines() if line.startswith('package:') and PKG.fullmatch(line[8:]))
    def package_state(self, package):
        if not PKG.fullmatch(package):
            raise ToolError('包名格式无效')
        dump = self.shell('dumpsys', 'package', package)
        user = re.search(r'User 0:([^\n]+)', dump)
        enabled = re.search(r'\benabled=(\d+)\b', user.group(1)) if user else None
        if not enabled:
            raise ToolError('无法读取用户0的组件状态：' + package)
        return int(enabled.group(1)), dump
    def change(self, package, action):
        output = self.shell('pm', action, '--user', '0', package)
        if re.search(r'(^|\n)(Error|Exception|Failure)|SecurityException', output):
            raise ToolError(output)
        return output

def read_rules(path):
    data = json.loads(Path(path).read_text())
    if data.get('schema_version') != 1 or not isinstance(data.get('profiles'), list):
        raise ToolError('规则格式无效')
    return data

def matched_rules(identity, rules):
    found = {}
    for profile in rules['profiles']:
        if profile.get('verified') is not True or not profile.get('evidence'):
            continue
        if any(profile.get(k) != identity.get(k) for k in ('manufacturer', 'model', 'fingerprint')):
            continue
        for item in profile.get('packages', []):
            if item.get('optional') is True and PKG.fullmatch(item.get('name', '')):
                found[item['name']] = item
    return found

def protect(package, homes, dump=''):
    return package == APP or package in homes or bool(PROTECTED.search(package)) or bool(re.search(r'(sharedUserId|sharedUser)=.*android\.uid\.(system|phone|bluetooth)', dump)) or bool(re.search(r'\bPERSISTENT\b', dump))

def scan(adb, rules):
    identity = adb.identity()
    try:
        homes = adb.homes()
        guard_error = None
    except ToolError as e:
        homes = set()
        guard_error = str(e)
    known = matched_rules(identity, rules)
    packages = adb.packages()
    items = []
    for package in packages:
        item = dict(package=package, eligible=False, protected=protect(package, homes), purpose='未知组件，仅扫描')
        if package in known and not guard_error:
            try:
                state, dump = adb.package_state(package)
                item.update(protected=protect(package, homes, dump), state=state, purpose=known[package].get('purpose', '可选组件'))
                item['eligible'] = not item['protected'] and state in (0, 1)
            except ToolError as e:
                item['error'] = str(e)
        items.append(item)
    return dict(identity=identity, matched_profile=bool(known), guard_error=guard_error, packages=items, note='未知固件仅扫描。无实机验证规则不自动禁用。')

@contextlib.contextmanager
def journal_lock(state_dir):
    state_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    with (state_dir / 'operations.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        yield

def atomic_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(dir=path.parent, prefix='.journal-')
    try:
        with os.fdopen(fd, 'w') as stream:
            json.dump(data, stream, ensure_ascii=False, indent=2)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)

def journal_for(state_dir, identity):
    import hashlib
    key = hashlib.sha256((identity['serial']+'\n'+identity['fingerprint']).encode()).hexdigest()[:24]
    return state_dir / (key + '.json')

def disable(adb, rules, packages, state_dir):
    if not packages:
        raise ToolError('必须显式选择组件')
    report = scan(adb, rules)
    identity = report['identity']
    allowed = {p['package'] for p in report['packages'] if p['eligible']}
    rejected = set(packages) - allowed
    if rejected:
        raise ToolError('组件没有精确适配规则、已禁用或受到保护：' + ', '.join(sorted(rejected)))
    results = []
    with journal_lock(state_dir):
        path = journal_for(state_dir, identity)
        journal = json.loads(path.read_text()) if path.exists() else dict(identity=identity, operations=[])
        for package in dict.fromkeys(packages):
            op = None
            try:
                # Recheck immediately before each mutation, including protected identities.
                if adb.identity() != identity:
                    raise ToolError('连接设备发生变化')
                state, dump = adb.package_state(package)
                if state not in (0, 1) or protect(package, adb.homes(), dump):
                    raise ToolError('执行前状态变化或组件受到保护')
                op = dict(package=package, before=state, status='pending', timestamp=time.time())
                journal['operations'].append(op)
                atomic_json(path, journal)
                adb.change(package, 'disable-user')
                current, _ = adb.package_state(package)
                if current != 3:
                    raise ToolError('禁用后状态核对失败')
                op['status'] = 'disabled'
                results.append(dict(package=package, success=True))
            except Exception as e:
                if op is not None:
                    op['error'] = str(e)
                results.append(dict(package=package, success=False, error=str(e)))
            finally:
                atomic_json(path, journal)
    return dict(identity=identity, results=results, journal=str(path))

def restore(adb, state_dir):
    identity = adb.identity()
    results = []
    with journal_lock(state_dir):
        path = journal_for(state_dir, identity)
        if not path.exists():
            raise ToolError('这台设备和固件没有本工具的操作记录；不会恢复其他程序的变更')
        journal = json.loads(path.read_text())
        if journal['identity'] != identity:
            raise ToolError('设备或固件与原始记录不符')
        for op in reversed(journal['operations']):
            if op['status'] not in ('disabled', 'pending'):
                continue
            package = op['package']
            try:
                if adb.identity() != identity:
                    raise ToolError('连接设备发生变化')
                state, _ = adb.package_state(package)
                if state != 3:
                    # A disconnected operation might not have changed anything.
                    if state == op['before']:
                        op['status'] = 'restored'
                    else:
                        raise ToolError('当前状态与本工具禁用结果不符，停止覆盖外部变更')
                else:
                    adb.change(package, 'default-state' if op['before'] == 0 else 'enable')
                    state, _ = adb.package_state(package)
                    if state != op['before']:
                        raise ToolError('恢复后状态核对失败')
                    op['status'] = 'restored'
                results.append(dict(package=package, success=True))
            except Exception as e:
                results.append(dict(package=package, success=False, error=str(e)))
            finally:
                atomic_json(path, journal)
    return dict(results=results)

def set_home(adb):
    adb.ready()
    before = adb.shell('cmd', 'package', 'resolve-activity', '--brief', '--user', '0', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')
    result = adb.shell('cmd', 'package', 'set-home-activity', '--user', '0', COMPONENT)
    after = adb.shell('cmd', 'package', 'resolve-activity', '--brief', '--user', '0', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')
    if COMPONENT not in after:
        raise ToolError('系统未接受默认桌面设置，请在电视系统设置中手动选择。' + result)
    return dict(previous=before, current=after, note='Home键行为仍需实机验证；没有禁用原厂桌面')

def import_source(adb, name, url, kind):
    from urllib.parse import urlparse
    u = urlparse(url)
    if u.scheme not in ('http', 'https') or not u.hostname or u.username or '\n' in url or '\r' in url:
        raise ToolError('播放源必须是有效的 HTTP/HTTPS 地址')
    if kind not in ('direct', 'm3u', 'cms', 'tvbox'):
        raise ToolError('不支持的播放源类型')
    adb.ready()
    adb.shell('am', 'start', '-n', SOURCE_COMPONENT, '--es', 'source_name', name, '--es', 'source_url', url, '--es', 'source_kind', kind)
    return dict(note='已发送，请在电视上确认保存；没有自动访问该源地址')

def main(argv=None):
    p = argparse.ArgumentParser(description='客厅管家：本地 ADB 管理工具')
    p.add_argument('--serial', default='')
    p.add_argument('--adb')
    p.add_argument('--rules', default=str(ROOT/'rules.json'))
    p.add_argument('--state-dir', type=Path, default=DEFAULT_STATE)
    sub=p.add_subparsers(dest='command', required=True)
    sub.add_parser('devices')
    c=sub.add_parser('connect');c.add_argument('address')
    sub.add_parser('scan')
    d=sub.add_parser('disable');d.add_argument('--packages', nargs='+', required=True);d.add_argument('--apply', action='store_true', required=True)
    sub.add_parser('restore')
    sub.add_parser('set-home')
    s=sub.add_parser('import-source');s.add_argument('--name',required=True);s.add_argument('--url',required=True);s.add_argument('--kind',choices=['direct','m3u','cms','tvbox'],required=True)
    i=sub.add_parser('install');i.add_argument('apk')
    args=p.parse_args(argv)
    adb=ADB(args.serial,args.adb)
    if args.command not in ('devices','connect') and not args.serial:
        raise ToolError('请指定 --serial，避免误操作其他设备')
    if args.command=='devices':
        return dict(devices=adb.run('devices','-l'))
    if args.command=='connect':
        if not re.fullmatch(r'[a-zA-Z0-9.-]+:[0-9]{1,5}',args.address):
            raise ToolError('请输入电视IP:调试端口，例如 192.168.1.8:5555')
        out=ADB('',args.adb).run('connect',args.address)
        if 'connected to' not in out:
            raise ToolError(out)
        return dict(result=out,note='若设备显示 unauthorized，请在电视确认授权')
    if args.command=='scan':
        return scan(adb,read_rules(args.rules))
    if args.command=='disable':
        return disable(adb,read_rules(args.rules),args.packages,args.state_dir)
    if args.command=='restore':
        return restore(adb,args.state_dir)
    if args.command=='set-home':
        return set_home(adb)
    if args.command=='import-source':
        return import_source(adb,args.name,args.url,args.kind)
    if args.command=='install':
        adb.ready();return dict(result=adb.run('install','-r',str(Path(args.apk).resolve()),timeout=120))

if __name__=='__main__':
    try:
        print(json.dumps(dict(ok=True,data=main()),ensure_ascii=False,indent=2))
    except Exception as exc:
        print(json.dumps(dict(ok=False,error=str(exc)),ensure_ascii=False,indent=2))
        sys.exit(1)
