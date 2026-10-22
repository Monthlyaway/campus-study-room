import contextlib
import datetime as dt
import json
from pathlib import Path
import plistlib
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
import backup_schedule as schedule


class BackupScheduleTests(unittest.TestCase):
    def checked_pair(self, state, completed=None):
        completed = completed or dt.datetime.now(dt.timezone.utc)
        directory = state / 'backups'
        directory.mkdir()
        path = directory / (completed.strftime('%Y%m%dT%H%M%S%fZ') + '.dump')
        path.write_bytes(b'checked fixture archive')
        metadata = {'file': path.name, 'sha256': schedule.ops.digest(path),
                    'completedAt': completed.isoformat(),
                    'validation': {'archiveTocReadable': True, 'fullArchiveDecoded': True}}
        path.with_suffix('.json').write_text(json.dumps(metadata))
        marker = {key: metadata[key] for key in ('completedAt', 'sha256')}
        marker['backup'] = path.name
        (state / 'scheduled-backup.json').write_text(json.dumps(marker))
        return path, metadata, marker

    def test_failed_backup_keeps_old_files_and_does_not_mark_success(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory)
            with patch.object(schedule.ops, 'environment_lock', return_value=contextlib.nullcontext(state)), patch.object(schedule.ops, 'backup', side_effect=RuntimeError('simulated failure')), patch.object(schedule, 'prune') as prune:
                with self.assertRaisesRegex(RuntimeError, 'simulated'):
                    schedule.run_due('test')
                self.assertFalse((state / 'scheduled-backup.json').exists())
                prune.assert_not_called()

    def test_today_success_skips_and_never_prunes_before_new_backup(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory)
            self.checked_pair(state)
            with patch.object(schedule.ops, 'environment_lock', return_value=contextlib.nullcontext(state)), patch.object(schedule.ops, 'backup') as backup, patch.object(schedule, 'prune') as prune:
                self.assertIsNone(schedule.run_due('test'))
                backup.assert_not_called(); prune.assert_not_called()

    def test_invalid_daily_archive_is_recreated_instead_of_skipped(self):
        cases = ('missing_dump', 'changed_dump', 'missing_metadata', 'broken_metadata',
                 'marker_hash', 'metadata_hash', 'metadata_filename', 'metadata_time',
                 'broken_marker', 'missing_marker_time', 'naive_marker_time',
                 'future_marker_time', 'old_toc_only_validation', 'traversal', 'absolute_path')
        for case in cases:
            with self.subTest(case=case), tempfile.TemporaryDirectory() as directory:
                state = Path(directory)
                path, metadata, marker = self.checked_pair(state)
                meta = path.with_suffix('.json')
                if case == 'missing_dump': path.unlink()
                elif case == 'changed_dump': path.write_bytes(b'truncated or damaged')
                elif case == 'missing_metadata': meta.unlink()
                elif case == 'marker_hash': marker['sha256'] = '0' * 64
                elif case == 'metadata_hash': metadata['sha256'] = '0' * 64
                elif case == 'metadata_filename': metadata['file'] = 'other.dump'
                elif case == 'metadata_time': metadata['completedAt'] = '2026-01-01T00:00:00+00:00'
                elif case == 'missing_marker_time': marker.pop('completedAt')
                elif case == 'naive_marker_time': marker['completedAt'] = dt.datetime.now().isoformat()
                elif case == 'future_marker_time': marker['completedAt'] = (dt.datetime.now(dt.timezone.utc) + dt.timedelta(hours=1)).isoformat()
                elif case == 'old_toc_only_validation': metadata.pop('validation')
                elif case == 'traversal': marker['backup'] = '../' + path.name
                elif case == 'absolute_path': marker['backup'] = str(path)
                if case != 'missing_metadata':
                    meta.write_text('not JSON' if case == 'broken_metadata' else json.dumps(metadata))
                (state / 'scheduled-backup.json').write_text('[]' if case == 'broken_marker' else json.dumps(marker))
                with patch.object(schedule.ops, 'environment_lock', return_value=contextlib.nullcontext(state)), patch.object(schedule.ops, 'backup', side_effect=RuntimeError('replacement requested')) as backup:
                    with self.assertRaisesRegex(RuntimeError, 'replacement requested'):
                        schedule.run_due('test')
                    backup.assert_called_once_with('test')

    def test_symlink_marker_archive_metadata_or_directory_cannot_satisfy_skip(self):
        for component in ('marker', 'archive', 'metadata', 'directory'):
            with self.subTest(component=component), tempfile.TemporaryDirectory() as directory:
                state = Path(directory)
                path, _, _ = self.checked_pair(state)
                link = {'marker': state / 'scheduled-backup.json', 'archive': path,
                        'metadata': path.with_suffix('.json'), 'directory': state / 'backups'}[component]
                original = state / ('retained-' + component)
                link.rename(original)
                link.symlink_to(original, target_is_directory=component == 'directory')
                self.assertFalse(schedule.verified_today(state, dt.datetime.now(dt.timezone.utc)))
                self.assertTrue(original.exists(), 'Checking must not alter the symlink target')

    def test_force_creates_new_checked_backup_then_replaces_marker_and_prunes(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory)
            old, _, _ = self.checked_pair(state)
            new = state / 'backups/20261022T235959999999Z.dump'
            new.write_bytes(b'new checked fixture')
            metadata = {'completedAt': dt.datetime.now(dt.timezone.utc).isoformat(), 'sha256': schedule.ops.digest(new)}
            new.with_suffix('.json').write_text(json.dumps(metadata))
            with patch.object(schedule.ops, 'environment_lock', return_value=contextlib.nullcontext(state)), patch.object(schedule.ops, 'backup', return_value=new) as backup, patch.object(schedule, 'prune') as prune:
                self.assertEqual(new, schedule.run_due('test', force=True))
                backup.assert_called_once_with('test')
                prune.assert_called_once()
            self.assertTrue(old.exists())
            marker = json.loads((state / 'scheduled-backup.json').read_text())
            self.assertEqual(new.name, marker['backup'])
            self.assertEqual(metadata['sha256'], marker['sha256'])
            self.assertEqual(0o600, (state / 'scheduled-backup.json').stat().st_mode & 0o777)

    def test_backup_validates_complete_data_before_publishing_either_file(self):
        for decode_fails in (False, True):
            with self.subTest(decode_fails=decode_fails), tempfile.TemporaryDirectory() as directory:
                state = Path(directory)
                calls = []
                def dump(_command, **kwargs):
                    kwargs['stdout'].write(b'fixture compressed archive')
                def validate(_environment, *args, **kwargs):
                    self.assertEqual([], list((state / 'backups').glob('*.dump')))
                    self.assertEqual([], list((state / 'backups').glob('*.json')))
                    self.assertEqual(b'fixture compressed archive', kwargs['stdin'].read())
                    calls.append(args[-1])
                    if decode_fails and args[-1] == '--file=/dev/null':
                        raise subprocess.CalledProcessError(1, ['pg_restore', '--file=/dev/null'])
                with patch.object(schedule.ops, 'config', return_value=(state, {})), patch.object(schedule.ops, 'database_inventory', return_value={}), patch.object(schedule.ops.subprocess, 'run', side_effect=dump), patch.object(schedule.ops, 'compose', side_effect=validate):
                    if decode_fails:
                        with self.assertRaises(subprocess.CalledProcessError):
                            schedule.ops.backup('test')
                        self.assertEqual([], list((state / 'backups').glob('*.dump')))
                        self.assertEqual([], list((state / 'backups').glob('*.json')))
                        self.assertEqual(1, len(list((state / 'backups').glob('*.partial'))))
                    else:
                        path = schedule.ops.backup('test')
                        metadata = json.loads(path.with_suffix('.json').read_text())
                        self.assertEqual({'archiveTocReadable': True, 'fullArchiveDecoded': True}, metadata['validation'])
                        self.assertEqual(schedule.ops.digest(path), metadata['sha256'])
                        self.assertEqual(0o600, path.stat().st_mode & 0o777)
                        self.assertEqual(0o600, path.with_suffix('.json').stat().st_mode & 0o777)
                self.assertEqual(['--list', '--file=/dev/null'], calls)

    def test_backup_refuses_symlink_directory_before_dump(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory)
            target = state / 'outside'; target.mkdir()
            (state / 'backups').symlink_to(target, target_is_directory=True)
            with patch.object(schedule.ops, 'config', return_value=(state, {})), patch.object(schedule.ops.subprocess, 'run') as dump:
                with self.assertRaisesRegex(RuntimeError, 'directory symlink'):
                    schedule.ops.backup('test')
                dump.assert_not_called()
            self.assertEqual([], list(target.iterdir()))

    def test_retention_removes_only_checked_old_pair(self):
        now = dt.datetime(2026, 10, 22, tzinfo=dt.timezone.utc)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for days, corrupt in ((8, False), (9, True), (6, False)):
                stamp = (now - dt.timedelta(days=days)).strftime('%Y%m%dT%H%M%S%fZ')
                data = root / (stamp + '.dump'); data.write_bytes(b'fixture')
                (root / (stamp + '.json')).write_text(json.dumps({'file': data.name, 'sha256': 'mismatch' if corrupt else schedule.ops.digest(data)}))
            partial = root / '20261001000000.dump.partial'; partial.write_bytes(b'failed')
            (root / '20261001T000000000000Z.json').write_text('broken metadata')
            schedule.prune(root, now)
            self.assertFalse((root / '20261014T000000000000Z.dump').exists())
            self.assertTrue((root / '20261013T000000000000Z.dump').exists())
            self.assertTrue((root / '20261016T000000000000Z.dump').exists())
            self.assertTrue(partial.exists())

    def test_scheduler_definitions_bind_exact_state_without_secret_values(self):
        with patch.object(schedule.shutil, 'which', return_value='/usr/local/bin/docker'):
            state = Path('/tmp/studyroom state')
            label, files = schedule.scheduler_files('test', state, 'darwin')
            value = plistlib.loads(files[label + '.plist'])
            self.assertEqual(str(state), value['ProgramArguments'][-1])
            self.assertEqual(3600, value['StartInterval'])
            label2, linux = schedule.scheduler_files('test', state, 'linux')
            self.assertEqual(label, label2)
            self.assertIn(b'"/tmp/studyroom state"', linux[label + '.service'])
            self.assertIn(b'OnUnitActiveSec=1h', linux[label + '.timer'])
            self.assertNotIn('PASSWORD', repr(files) + repr(linux))


if __name__ == '__main__':
    unittest.main()
