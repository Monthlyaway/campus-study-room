#!/usr/bin/env python3
"""Prove three critical resource guards are detected when broken in a temp copy.

The real checkout is never edited. An isolated PostgreSQL database is provisioned
with the same migrations and roles as the normal persistence suite. A surviving
mutation, a compile failure, or an unrelated test error fails this runner.
"""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

from run_core_persistence import ROOT, persistence_environment

JAVA = Path('services/core-service/src/main/java/com/campus/studyroom/core')
REPORT = Path('services/core-service/target/surefire-reports/TEST-com.campus.studyroom.core.CorePersistenceTest.xml')
CASES = (
    ('seat-conflict', 'ResourceService.java',
     'if (conflict(room, seat, start, end))', 'if (false)',
     'sameSeatConflictCannotCommitSecondClaim'),
    ('department-scope', 'BookingService.java',
     'if (!access.admissible(user, room)) throw ApiException.notFound();',
     'if (false) throw ApiException.notFound();',
     'foreignDepartmentCannotCreateReservation'),
    ('release-source', 'ResourceService.java',
     'public void release(String source, UUID id, UUID actor, UUID room) {',
     '''public void release(String source, UUID id, UUID actor, UUID room) {
    claims.findAll().stream().filter(c -> room.equals(c.roomId) && c.releasedAt == null)
      .forEach(c -> { c.releasedAt = clock.instant(); claims.saveAndFlush(c); });''',
     'sourceTombstoneAndMultipleMaintenanceClaimsAreIndependent'),
)

def snapshot():
    destination = Path(tempfile.mkdtemp(prefix='studyroom-core-mutation-'))
    for name in ('pom.xml', 'mvnw', 'mvnw.cmd', '.mvn', 'modules', 'services', 'tools', 'db'):
        source = ROOT / name
        if source.is_dir():
            shutil.copytree(source, destination / name,
                            ignore=shutil.ignore_patterns('target', 'maven-wrapper.jar', '__pycache__'))
        else:
            shutil.copy2(source, destination / name)
    return destination

def run_tests(copy, env, selection, log):
    report = copy / REPORT
    report.unlink(missing_ok=True)
    with log.open('w') as output:
        result = subprocess.run([str(copy / 'mvnw'), '-B', '-ntp', '-pl', 'services/core-service', '-am',
                                 '-Dtest=CorePersistenceTest#' + selection,
                                 '-Dsurefire.failIfNoSpecifiedTests=false', 'test'],
                                cwd=copy, env=env, stdout=output, stderr=subprocess.STDOUT)
    if not report.exists():
        raise AssertionError(f'Test report missing; compile/setup failures do not count: {log}')
    return result.returncode, ET.parse(report).getroot()

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--snapshot', type=Path, help='Reuse a generated temporary snapshot')
    args = parser.parse_args()
    copy = args.snapshot.resolve() if args.snapshot else snapshot()
    if copy.parent != Path(tempfile.gettempdir()).resolve() or not copy.name.startswith('studyroom-core-mutation-'):
        raise ValueError('Mutation target must be a generated system temporary directory')
    env = persistence_environment('studyroom_core_mutation')
    output = ROOT / 'output/core-mutations'
    output.mkdir(parents=True, exist_ok=True)
    selection = '+'.join(c[4] for c in CASES)
    code, report = run_tests(copy, env, selection, output / 'baseline.log')
    assert code == 0 and report.get('tests') == '3', 'Unmodified baseline must pass first'
    evidence = []
    for name, file, old, new, test in CASES:
        source = copy / JAVA / file
        original = source.read_text()
        assert original.count(old) == 1, f'Mutation anchor changed: {name}'
        try:
            source.write_text(original.replace(old, new))
            code, report = run_tests(copy, env, test, output / (name + '.log'))
            cases = report.findall('testcase')
            assert code != 0 and len(cases) == 1 and cases[0].get('name') == test, name
            failures = cases[0].findall('failure')
            assert not cases[0].findall('error') and len(failures) == 1, f'Unrelated error: {name}'
            assert failures[0].get('type') == 'org.opentest4j.AssertionFailedError', name
            evidence.append({'mutation': name, 'test': test, 'result': 'DETECTED',
                             'assertion': failures[0].get('message')})
            print(f'{name}: detected by {test}', flush=True)
        finally:
            source.write_text(original)
    code, report = run_tests(copy, env, selection, output / 'restored.log')
    assert code == 0 and report.get('tests') == '3', 'Restored source must pass again'
    (output / 'report.json').write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + '\n')
    print(f'All three mutations detected; restored baseline passes. Evidence: {output}')
    shutil.rmtree(copy)

if __name__ == '__main__':
    main()
