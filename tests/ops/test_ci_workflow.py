"""Evaluate the repository's actual workflow rules and preserve complete release gates.

No YAML dependency is needed in the Maven CI image. The reader deliberately accepts
only this workflow's single-quoted if/when form, and rejects unsupported syntax.
The small expression parser handles GitLab variables, equality, &&, || and grouping;
it does not use Python eval or repeat the production branch-selection function.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def workflow_rules(source):
    block = re.search(r'^workflow:\n(.*?)(?=^\S)', source, re.M | re.S).group(1)
    rules = []
    for line in block.splitlines():
        if not line.strip() or line.lstrip().startswith('#') or line == '  rules:':
            continue
        condition = re.fullmatch(r"    - if: '(.*)'", line)
        if condition:
            rules.append({'if': condition.group(1), 'when': 'always'})
        elif line == '      when: never' and rules:
            rules[-1]['when'] = 'never'
        else:
            raise AssertionError('Unsupported workflow rule syntax: ' + line)
    return rules


def expression_matches(expression, variables):
    pattern = re.compile(r'\s*(\$[A-Z0-9_]+|"[^"\\]*"|==|&&|\|\||[()])')
    tokens, offset = [], 0
    while offset < len(expression):
        match = pattern.match(expression, offset)
        if not match:
            raise AssertionError('Unsupported GitLab expression: ' + expression[offset:])
        tokens.append(match.group(1))
        offset = match.end()
    position = 0

    def primary():
        nonlocal position
        token = tokens[position]
        position += 1
        if token == '(':
            value = disjunction()
            if tokens[position] != ')':
                raise AssertionError('Unbalanced GitLab expression')
            position += 1
            return value
        if token.startswith('$'):
            return variables.get(token[1:], '')
        if token.startswith('"'):
            return token[1:-1]
        raise AssertionError('Unexpected token: ' + token)

    def equality():
        nonlocal position
        value = primary()
        if position < len(tokens) and tokens[position] == '==':
            position += 1
            return value == primary()
        return value

    def conjunction():
        nonlocal position
        value = equality()
        while position < len(tokens) and tokens[position] == '&&':
            position += 1
            other = equality()
            value = bool(value) and bool(other)
        return value

    def disjunction():
        nonlocal position
        value = conjunction()
        while position < len(tokens) and tokens[position] == '||':
            position += 1
            other = conjunction()
            value = bool(value) or bool(other)
        return value

    result = disjunction()
    if position != len(tokens):
        raise AssertionError('Trailing GitLab expression tokens')
    return bool(result)


def decision(rules, variables):
    for index, rule in enumerate(rules):
        if expression_matches(rule['if'], variables):
            return index, rule['when']
    return None, 'never'


def section(source, name):
    match = re.search(r'^' + re.escape(name) + r':\n(.*?)(?=^\S|\Z)', source, re.M | re.S)
    if not match:
        raise AssertionError('Missing YAML section: ' + name)
    return match.group(1)


def inline_list(block, key):
    match = re.search(r'^  ' + re.escape(key) + r': \[([^]]*)\]$', block, re.M)
    if not match:
        raise AssertionError('Missing inline list: ' + key)
    return [value.strip() for value in match.group(1).split(',')]


class CiWorkflowTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = (ROOT / '.gitlab-ci.yml').read_text()
        cls.rules = workflow_rules(cls.source)

    def branch(self, name='ycy/campus-platform', source='push', **changes):
        variables = {'CI_PROJECT_ID': '11753', 'CI_COMMIT_REF_PROTECTED': 'true',
                     'CI_COMMIT_BRANCH': name, 'CI_PIPELINE_SOURCE': source,
                     'CI_OPEN_MERGE_REQUESTS': 'yangchunyu/campus-study-room!1'}
        variables.update(changes)
        return variables

    def mr(self, **changes):
        variables = {'CI_PROJECT_ID': '11753', 'CI_PIPELINE_SOURCE': 'merge_request_event',
                     'CI_COMMIT_REF_PROTECTED': 'false',
                     'CI_MERGE_REQUEST_SOURCE_PROJECT_ID': '11753',
                     'CI_MERGE_REQUEST_SOURCE_BRANCH_PROTECTED': 'true',
                     'CI_MERGE_REQUEST_SOURCE_BRANCH_NAME': 'ycy/campus-platform',
                     'CI_MERGE_REQUEST_TARGET_BRANCH_PROTECTED': 'true',
                     'CI_MERGE_REQUEST_TARGET_BRANCH_NAME': 'main'}
        variables.update(changes)
        return variables

    def test_exact_protected_branches_run_complete_push_and_web_even_with_open_mr(self):
        for name in ('main', 'ycy/campus-platform', 'ycy/ci-container-check'):
            for source in ('push', 'web'):
                with self.subTest(name=name, source=source):
                    self.assertEqual((0, 'always'), decision(self.rules, self.branch(name, source)))

    def test_untrusted_branches_cannot_use_trusted_branch_exception(self):
        cases = [self.branch(CI_COMMIT_REF_PROTECTED='false'),
                 self.branch(CI_COMMIT_REF_PROTECTED=''),
                 self.branch(CI_PROJECT_ID='99999'),
                 self.branch('ycy/other'), self.branch('ycy/campus-platform-extra'),
                 self.branch('main-other'), self.branch('ycy/ci-container-check/other')]
        for variables in cases:
            with self.subTest(variables=variables):
                self.assertNotEqual(0, decision(self.rules, variables)[0])
                self.assertEqual('never', decision(self.rules, variables)[1])

    def test_only_push_and_web_enter_the_new_branch_exception(self):
        for source in ('schedule', 'api', 'trigger', 'pipeline', 'parent_pipeline'):
            with self.subTest(source=source):
                self.assertEqual('never', decision(self.rules, self.branch(source=source))[1])

    def test_trusted_same_project_protected_mr_is_deduplicated_to_full_branch_pipeline(self):
        for name in ('ycy/campus-platform', 'ycy/ci-container-check'):
            variables = self.mr(CI_MERGE_REQUEST_SOURCE_BRANCH_NAME=name)
            self.assertEqual((1, 'never'), decision(self.rules, variables))
            self.assertEqual((0, 'always'), decision(self.rules, self.branch(name)))

    def test_ordinary_mr_and_forks_keep_original_full_pipeline_rule(self):
        cases = [self.mr(CI_MERGE_REQUEST_SOURCE_PROJECT_ID='99999'),
                 self.mr(CI_PROJECT_ID='99999', CI_MERGE_REQUEST_SOURCE_PROJECT_ID='99999'),
                 self.mr(CI_MERGE_REQUEST_SOURCE_BRANCH_PROTECTED='false'),
                 self.mr(CI_MERGE_REQUEST_TARGET_BRANCH_PROTECTED='false'),
                 self.mr(CI_MERGE_REQUEST_TARGET_BRANCH_NAME='other'),
                 self.mr(CI_MERGE_REQUEST_SOURCE_BRANCH_NAME='ycy/untrusted'),
                 self.mr(CI_MERGE_REQUEST_SOURCE_BRANCH_NAME='ycy/campus-platform-extra')]
        for variables in cases:
            with self.subTest(variables=variables):
                self.assertEqual((2, 'always'), decision(self.rules, variables))

    def test_removing_temporary_protection_restores_mr_rule(self):
        for name in ('ycy/campus-platform', 'ycy/ci-container-check'):
            self.assertEqual((2, 'always'), decision(self.rules, self.mr(
                CI_MERGE_REQUEST_SOURCE_BRANCH_NAME=name,
                CI_MERGE_REQUEST_SOURCE_BRANCH_PROTECTED='false')))
            self.assertEqual('never', decision(self.rules, self.branch(
                name, CI_COMMIT_REF_PROTECTED='false'))[1])

    def test_existing_tag_and_branch_without_mr_rules_are_unchanged(self):
        for protected in ('true', 'false'):
            self.assertEqual((3, 'always'), decision(self.rules, {
                'CI_COMMIT_TAG': 'v0.1.0', 'CI_PIPELINE_SOURCE': 'push',
                'CI_COMMIT_REF_PROTECTED': protected}))
        self.assertEqual((5, 'always'), decision(self.rules, self.branch(
            'feature/untrusted', CI_COMMIT_REF_PROTECTED='false', CI_OPEN_MERGE_REQUESTS='')))

    def test_all_five_jobs_and_required_dependency_chain_remain_blocking(self):
        jobs = {name: section(self.source, name) for name in
                ('backend', 'frontend', 'integration', 'container-release', 'package')}
        actual_jobs = {name for name in re.findall(r'^([\w-]+):\n', self.source, re.M)
                       if re.search(r'^  stage:', section(self.source, name), re.M)}
        self.assertEqual(set(jobs), actual_jobs)
        self.assertIn('stages: [verify, integration, release-test, package]', self.source)
        self.assertEqual(['backend', 'frontend'], inline_list(jobs['integration'], 'needs'))
        self.assertEqual(['backend', 'frontend', 'integration'], inline_list(jobs['container-release'], 'needs'))
        self.assertEqual(['backend', 'frontend', 'integration', 'container-release'], inline_list(jobs['package'], 'needs'))
        for name, block in jobs.items():
            with self.subTest(job=name):
                self.assertNotRegex(block, re.compile(r'^  (rules|only|except|allow_failure|when):', re.M))
                self.assertNotIn('optional: true', block)
        for job, command in [('backend', './mvnw -B -ntp verify'),
                             ('frontend', 'pnpm generate'), ('frontend', 'pnpm typecheck'),
                             ('frontend', 'pnpm test'), ('frontend', 'pnpm build'),
                             ('integration', 'python3 scripts/ci-integration.py'),
                             ('container-release', 'python3 scripts/ci-release.py')]:
            self.assertIn('    - ' + command, jobs[job])

    def test_arm64_routing_tls_and_real_container_browser_gate_are_preserved(self):
        release = section(self.source, 'container-release')
        self.assertEqual(['studyroom-release-arm64'], inline_list(release, 'tags'))
        self.assertEqual(['amd64'], inline_list(section(self.source, 'default'), 'tags'))
        for value in ["DOCKER_TLS_VERIFY: '1'", 'DOCKER_HOST: tcp://docker:2376',
                      'DOCKER_TLS_CERTDIR: $CI_BUILDS_DIR/.studyroom-dind-$CI_JOB_ID',
                      'DOCKER_CERT_PATH: $CI_BUILDS_DIR/.studyroom-dind-$CI_JOB_ID/client',
                      'docker:28.5.2-cli@sha256:', 'docker:28.5.2-dind@sha256:',
                      'junit: output/browser/junit.xml']:
            self.assertIn(value, release)
        self.assertNotIn('HEALTHCHECK_TCP_PORT', release)
        script = (ROOT / 'scripts/ci-release.py').read_text()
        for gate in ["ops.build_release(release)", "ops.deploy('test', manifest_path)",
                     "require_junit_report(ROOT / 'output/browser/junit.xml')",
                     "ops.verify_running_release('test', manifest)", "npx playwright install --with-deps chromium"]:
            self.assertIn(gate, script)

    def test_expression_reader_rejects_unsupported_syntax_instead_of_silently_passing(self):
        with self.assertRaises(AssertionError):
            expression_matches('$CI_PROJECT_ID =~ /11753/', {'CI_PROJECT_ID': '11753'})
        with self.assertRaises(AssertionError):
            workflow_rules('workflow:\n  rules:\n    - when: always\nstages: [verify]\n')


if __name__ == '__main__':
    unittest.main()
