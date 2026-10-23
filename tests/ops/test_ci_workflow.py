"""Exercise trusted-event boundaries and the actual GitHub promotion graph."""
import importlib.util
import os
import re
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('preflight', ROOT/'scripts/github-preflight.py')
preflight=importlib.util.module_from_spec(spec); spec.loader.exec_module(preflight)
SHA='a'*40

class TrustedEventTest(unittest.TestCase):
    def env(self, **updates):
        return dict(GITHUB_ACTIONS='true', GITHUB_REPOSITORY='Monthlyaway/campus-study-room',
                    GITHUB_ACTOR='Monthlyaway', GITHUB_TRIGGERING_ACTOR='Monthlyaway', RUNNER_NAME='codex-lb-campus-study-room',
                    GITHUB_EVENT_NAME='push', GITHUB_REF='refs/heads/ycy/ci', GITHUB_SHA=SHA, **updates)
    def test_owner_branch_main_and_release_are_accepted(self):
        for ref in ['refs/heads/main','refs/heads/ycy/fix','refs/tags/v0.2.0']:
            env=self.env(); env['GITHUB_REF']=ref
            self.assertEqual(SHA, preflight.validate(env, {}))
    def test_wrong_actor_repo_runner_or_execution_is_rejected(self):
        for key,value in [('GITHUB_ACTIONS','false'),('GITHUB_REPOSITORY','other/repo'),
                          ('GITHUB_ACTOR','contributor'),('GITHUB_TRIGGERING_ACTOR','contributor'),('RUNNER_NAME','other'),
                          ('GITHUB_SHA','main'),('GITHUB_EVENT_NAME','pull_request_target'),
                          ('GITHUB_REF','refs/heads/untrusted'),('GITHUB_REF','refs/tags/v0.2.0/injected')]:
            env=self.env(); env[key]=value
            with self.subTest(key=key,value=value), self.assertRaises(RuntimeError):
                preflight.validate(env,{})
    def test_pr_checks_exact_head_and_rejects_forks_and_wrong_base(self):
        env=self.env(); env['GITHUB_EVENT_NAME']='pull_request'
        event={'pull_request':{'head':{'sha':'b'*40,'ref':'ycy/fix','repo':{'full_name':env['GITHUB_REPOSITORY']}},'base':{'ref':'main'}}}
        self.assertEqual('b'*40, preflight.validate(env,event))
        event['pull_request']['head']['repo']['full_name']='fork/repo'
        with self.assertRaises(RuntimeError): preflight.validate(env,event)
        event['pull_request']['head']['repo']['full_name']=env['GITHUB_REPOSITORY']
        event['pull_request']['base']['ref']='other'
        with self.assertRaises(RuntimeError): preflight.validate(env,event)

class WorkflowPromotionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls): cls.source=(ROOT/'.github/workflows/ci.yml').read_text()
    def job_source(self, name):
        match = re.search(
            rf"(?ms)^  {re.escape(name)}:\n(.*?)(?=^  [A-Za-z0-9_-]+:\n|\Z)",
            self.source,
        )
        self.assertIsNotNone(match, f"Missing workflow job {name}")
        return match.group(1)

    def test_business_jobs_use_dedicated_runner_and_actions_are_pinned(self):
        for action in re.findall(r'uses: (\S+)',self.source):
            self.assertRegex(action,r'^[\w/-]+@[0-9a-f]{40}$')
        for name in ("verify", "publish", "deploy"):
            with self.subTest(job=name):
                self.assertIn(
                    "    runs-on: [self-hosted, Linux, X64, campus-study-room]\n",
                    self.job_source(name),
                )
        self.assertNotIn('pull_request_target:',self.source)
        self.assertNotIn('continue-on-error',self.source)
        self.assertNotIn('--unit-only',self.source)
    def test_required_gate_is_always_scheduled_without_repository_access(self):
        gate = self.job_source("ci-gate")
        for directive in (
            "name: ci-gate", "needs: verify", "if: always()",
            "runs-on: ubuntu-latest", "permissions: {}", "timeout-minutes: 2",
        ):
            self.assertIn(f"    {directive}\n", gate)
        self.assertNotIn("uses:", gate)
        self.assertNotIn("checkout", gate.lower())
        self.assertNotRegex(gate, r"\bsecrets(?:\.|\[)")
        self.assertEqual(["needs.verify.result"], [
            expression.strip()
            for expression in re.findall(r"\$\{\{(.*?)\}\}", gate)
        ])
        self.assertIn("          VERIFY_RESULT: ${{ needs.verify.result }}\n", gate)
        self.assertIn("        shell: bash\n", gate)
        self.assertEqual(1, len(re.findall(r"(?m)^      - ", gate)))

    def test_required_gate_executes_actual_script_and_rejects_non_success(self):
        gate = self.job_source("ci-gate")
        match = re.search(
            r"(?m)^        run: \|\n((?:^          .*\n|^\n)+)",
            gate,
        )
        self.assertIsNotNone(match, "Required gate must contain its inline shell")
        script = textwrap.dedent(match.group(1))
        with tempfile.TemporaryDirectory() as empty_checkout:
            for result in ("success", "failure", "cancelled", "skipped", "", "unknown"):
                with self.subTest(verify_result=result):
                    process = subprocess.run(
                        ["bash", "--noprofile", "--norc", "-e", "-o", "pipefail", "-c", script],
                        cwd=empty_checkout,
                        env={"PATH": os.defpath, "VERIFY_RESULT": result},
                        text=True, capture_output=True, timeout=5,
                    )
                    self.assertEqual(
                        0 if result == "success" else 1,
                        process.returncode,
                        process.stdout + process.stderr,
                    )

    def test_real_gates_block_publish_and_no_cancellation_of_deployment(self):
        for command in ['./scripts/check','python3 scripts/ci-release.py','python3 scripts/github-preflight.py']:
            self.assertIn('run: '+command,self.source)
        verify,publish=self.source.split('  publish:\n')
        self.assertIn('needs: verify',publish)
        self.assertIn("startsWith(github.ref, 'refs/tags/v') && github.event_name == 'push'",publish)
        self.assertIn('cancel-in-progress: false',verify)
        self.assertIn('group: campus-study-room-codex-lb',verify)
        self.assertNotIn('packages: write',verify)
        self.assertIn('packages: write',publish)
        self.assertIn('persist-credentials: false',verify)
    def test_only_sanitized_artifact_directories_are_published(self):
        self.assertIn('output/check/',self.source)
        self.assertIn('output/container-release/',self.source)
        self.assertIn('output/browser/',self.source)
        block=self.source.split('path: |')[1].split('if-no-files-found:')[0]
        self.assertNotIn('.local/',block)
        self.assertNotIn('secrets/',block)
        self.assertIn('workflow:\n  rules:\n    - when: never',(ROOT/'.gitlab-ci.yml').read_text())

if __name__=='__main__': unittest.main()
