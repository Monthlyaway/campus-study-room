"""Daily checked backups with seven-day retention and explicit host scheduler setup."""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import plistlib
import re
import shutil
import subprocess
import sys
import studyroom as ops


def verified_today(state, now):
    """Trust a daily marker only while its local, fully checked archive still matches."""
    marker = state / 'scheduled-backup.json'
    directory = state / 'backups'
    if marker.is_symlink() or not marker.is_file() or directory.is_symlink() or not directory.is_dir():
        return False
    try:
        prior = json.loads(marker.read_text())
        if not isinstance(prior, dict):
            return False
        name, checksum, completed = prior.get('backup'), prior.get('sha256'), prior.get('completedAt')
        if (not isinstance(name, str) or not re.fullmatch(r'[0-9]{8}T[0-9]{12}Z\.dump', name)
                or not isinstance(checksum, str) or not re.fullmatch(r'[0-9a-f]{64}', checksum)
                or not isinstance(completed, str)):
            return False
        previous = dt.datetime.fromisoformat(completed)
        if previous.tzinfo is None or previous > now or previous.astimezone(dt.timezone.utc).date() != now.date():
            return False
        path = directory / name
        meta = path.with_suffix('.json')
        if path.is_symlink() or meta.is_symlink() or not path.is_file() or not meta.is_file():
            return False
        data = json.loads(meta.read_text())
        return (isinstance(data, dict) and data.get('file') == name
                and data.get('sha256') == checksum and data.get('completedAt') == completed
                and data.get('validation') == {'archiveTocReadable': True, 'fullArchiveDecoded': True}
                and ops.digest(path) == checksum)
    except (ValueError, OSError):
        return False


def run_due(environment, force=False):
    with ops.environment_lock(environment) as state:
        marker = state / 'scheduled-backup.json'
        now = dt.datetime.now(dt.timezone.utc)
        if not force and verified_today(state, now):
            print('Daily backup already verified for this UTC date.')
            return None
        path = ops.backup(environment)
        metadata = json.loads(path.with_suffix('.json').read_text())
        record = {'completedAt': metadata['completedAt'], 'backup': path.name, 'sha256': metadata['sha256']}
        ops.write_json(marker, record)
        marker.chmod(0o600)
        prune(state / 'backups', now)
        return path


def prune(directory, now):
    """Only matched, checked backup pairs expire; unknown/partial files are retained."""
    eligible = []
    for meta in directory.glob('*.json'):
        if not re.fullmatch(r'\d{8}T\d{12}Z\.json', meta.name) or meta.is_symlink():
            continue
        try:
            data = json.loads(meta.read_text())
        except (ValueError, OSError):
            continue
        if not isinstance(data, dict):
            continue
        path = meta.with_suffix('.dump')
        if data.get('file') != path.name or not path.is_file() or path.is_symlink():
            continue
        try:
            created = dt.datetime.strptime(meta.stem, '%Y%m%dT%H%M%S%fZ').replace(tzinfo=dt.timezone.utc)
        except ValueError:
            continue
        if created < now - dt.timedelta(days=7) and ops.digest(path) == data.get('sha256'):
            eligible.append((meta, path))
    # The successful new backup exists before retention is considered.
    for meta, path in eligible:
        path.unlink()
        meta.unlink()
    print(f'Expired {len(eligible)} verified backup pairs older than seven days.')


def scheduler_files(environment, state, platform=None):
    platform = platform or sys.platform
    label = 'com.campus-study-room.backup.' + hashlib.sha256(str(state).encode()).hexdigest()[:12]
    docker = shutil.which('docker')
    if not docker:
        raise RuntimeError('Docker CLI must be installed before scheduling backups')
    command = [sys.executable, str(Path(__file__).resolve()), 'run', environment, '--state-dir', str(state)]
    search_path = ':'.join(dict.fromkeys([str(Path(docker).parent), '/usr/local/bin', '/opt/homebrew/bin', '/usr/bin', '/bin', '/usr/sbin', '/sbin']))
    if any('\n' in item or '\r' in item for item in command):
        raise RuntimeError('Scheduler paths cannot contain line breaks')
    log = state / 'backup-scheduler.log'
    if platform == 'darwin':
        value = {'Label': label, 'ProgramArguments': command, 'WorkingDirectory': str(ops.ROOT),
                 'EnvironmentVariables': {'PATH': search_path}, 'RunAtLoad': True, 'StartInterval': 3600,
                 'StandardOutPath': str(log), 'StandardErrorPath': str(log)}
        return label, {label + '.plist': plistlib.dumps(value)}
    if platform.startswith('linux'):
        def quoted(value):
            return '"' + value.replace('\\', '\\\\').replace('"', '\\"').replace('%', '%%') + '"'
        service = '[Unit]\nDescription=Campus Study Room checked daily backup\n[Service]\nType=oneshot\nUMask=0077\n'
        service += 'WorkingDirectory=' + quoted(str(ops.ROOT)) + '\nEnvironment=' + quoted('PATH=' + search_path) + '\n'
        service += 'ExecStart=' + ' '.join(map(quoted, command)) + '\n'
        timer = '[Unit]\nDescription=Campus Study Room daily backup check\n[Timer]\nOnBootSec=2min\nOnUnitActiveSec=1h\n[Install]\nWantedBy=timers.target\n'
        return label, {label + '.service': service.encode(), label + '.timer': timer.encode()}
    raise RuntimeError('Automatic scheduling supports macOS launchd and Linux systemd')


def install(environment):
    state, _ = ops.config(environment)
    label, files = scheduler_files(environment, state)
    log = state / 'backup-scheduler.log'
    log.touch(mode=0o600, exist_ok=True)
    log.chmod(0o600)
    if sys.platform == 'darwin':
        folder = Path.home() / 'Library/LaunchAgents'
    else:
        folder = Path('/etc/systemd/system') if os.geteuid() == 0 else Path.home() / '.config/systemd/user'
    folder.mkdir(parents=True, exist_ok=True)
    for name, content in files.items():
        target = folder / name
        if target.is_symlink():
            raise RuntimeError('Refusing to replace a scheduler symlink')
        target.write_bytes(content)
        target.chmod(0o600)
    if sys.platform == 'darwin':
        domain = f'gui/{os.getuid()}'
        subprocess.run(['launchctl', 'bootout', domain + '/' + label], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
        subprocess.run(['launchctl', 'bootstrap', domain, str(folder / (label + '.plist'))], check=True)
        subprocess.run(['launchctl', 'print', domain + '/' + label], check=True, stdout=subprocess.DEVNULL)
    else:
        command = ['systemctl'] + ([] if os.geteuid() == 0 else ['--user'])
        subprocess.run(command + ['daemon-reload'], check=True)
        subprocess.run(command + ['enable', '--now', label + '.timer'], check=True)
        subprocess.run(command + ['is-active', label + '.timer'], check=True)
    print(f'Installed scheduler {label}. Checks hourly; one verified backup per UTC date, retains seven days.')
    print(f'On-demand check: {sys.executable} {Path(__file__).resolve()} run {environment} --state-dir {state}')


def manage(environment, action):
    state, _ = ops.config(environment)
    label, files = scheduler_files(environment, state)
    if sys.platform == 'darwin':
        domain = f'gui/{os.getuid()}'
        folder = Path.home() / 'Library/LaunchAgents'
        command = ['launchctl', 'print' if action == 'status' else 'bootout', domain + '/' + label]
    else:
        folder = Path('/etc/systemd/system') if os.geteuid() == 0 else Path.home() / '.config/systemd/user'
        command = ['systemctl'] + ([] if os.geteuid() == 0 else ['--user'])
        command += ['status', label + '.timer'] if action == 'status' else ['disable', '--now', label + '.timer']
    subprocess.run(command, check=True)
    if action == 'remove':
        for name in files:
            path = folder / name
            if path.is_symlink():
                raise RuntimeError('Refusing to remove scheduler symlink')
            path.unlink(missing_ok=True)
        print('Removed this environment scheduler. Backups, state and logs were retained.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['install', 'run', 'status', 'remove'])
    parser.add_argument('environment', choices=['local', 'dev', 'test', 'prod'])
    parser.add_argument('--state-dir', type=Path)
    parser.add_argument('--force', action='store_true', help='Run a new backup even if today already succeeded')
    args = parser.parse_args()
    if args.state_dir:
        os.environ['STUDYROOM_STATE_DIR'] = str(args.state_dir.resolve())
    state = Path(os.environ.get('STUDYROOM_STATE_DIR', ops.ROOT / '.local' / args.environment)).resolve()
    if not (state / 'compose.env').is_file():
        raise RuntimeError('Install/configure this deployment before scheduling backups')
    if args.action == 'install':
        install(args.environment)
    elif args.action == 'run':
        run_due(args.environment, args.force)
    else:
        manage(args.environment, args.action)


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, ValueError, OSError, subprocess.CalledProcessError) as error:
        print(f'STUDYROOM BACKUP: {error}', file=sys.stderr)
        sys.exit(1)
