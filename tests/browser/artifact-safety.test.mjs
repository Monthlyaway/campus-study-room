import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { inflateRawSync } from 'node:zlib';
import { randomUUID } from 'node:crypto';

function zipEntries(bytes) {
  const result = [];
  // HTML reports embed a ZIP. Inspect the central directory so compressed payloads
  // cannot conceal the credential from a plain-text artifact scan.
  for (let cursor = 0; cursor + 46 <= bytes.length; cursor++) {
    if (bytes.readUInt32LE(cursor) !== 0x02014b50) continue;
    const method = bytes.readUInt16LE(cursor + 10);
    const size = bytes.readUInt32LE(cursor + 20);
    const nameLength = bytes.readUInt16LE(cursor + 28);
    const extraLength = bytes.readUInt16LE(cursor + 30);
    const commentLength = bytes.readUInt16LE(cursor + 32);
    const local = bytes.readUInt32LE(cursor + 42);
    assert.equal(bytes.readUInt32LE(local), 0x04034b50);
    const start = local + 30 + bytes.readUInt16LE(local + 26) + bytes.readUInt16LE(local + 28);
    const compressed = bytes.subarray(start, start + size);
    assert.ok([0, 8].includes(method), 'Unsupported HTML report ZIP compression');
    result.push(method === 8 ? inflateRawSync(compressed) : compressed);
    cursor += 45 + nameLength + extraLength + commentLength;
  }
  assert.ok(result.length, 'HTML report ZIP must contain inspectable entries');
  return result;
}

test('failed browser reports do not expose a filled password input', { timeout: 45_000 }, () => {
  const directory = mkdtempSync(join(tmpdir(), 'studyroom-report-safety-'));
  // This random sentinel is not an application credential. The fixture intentionally
  // fails before login so that the password remains present when reports are captured.
  const sentinel = `report-probe-${randomUUID()}`;
  const configURL = new URL('./playwright.config.mjs', import.meta.url).href;
  const playwrightURL = new URL('./node_modules/@playwright/test/index.mjs', import.meta.url).href;
  const helpersURL = new URL('./helpers.mjs', import.meta.url).href;
  const output = join(directory, 'output');
  try {
    const sentinelFile = join(directory, 'probe-password');
    writeFileSync(sentinelFile, sentinel, { mode: 0o600 });
    writeFileSync(join(directory, 'probe.spec.mjs'), `
import { test, expect } from ${JSON.stringify(playwrightURL)};
import { enterPassword } from ${JSON.stringify(helpersURL)};
test('intentional privacy probe failure', async ({ page }) => {
  await page.setContent('<label>密码<input type="password"></label>');
  await enterPassword(page, process.env.STUDYROOM_REPORT_PROBE);
  await expect(page.locator('#missing-after-login')).toBeVisible({ timeout: 100 });
});
`);
    writeFileSync(join(directory, 'probe.config.mjs'), `
import config from ${JSON.stringify(configURL)};
export default { ...config, globalSetup: undefined, testDir: ${JSON.stringify(directory)},
  outputDir: ${JSON.stringify(join(output, 'results'))},
  reporter: [config.reporter[0], ['line'], ['junit', { outputFile: ${JSON.stringify(join(output, 'junit.xml'))} }],
    ['json', { outputFile: ${JSON.stringify(join(output, 'results.json'))} }],
    ['html', { outputFolder: ${JSON.stringify(join(output, 'html'))}, open: 'never' }]] };
`);
    const result = spawnSync(process.execPath, [fileURLToPath(new URL('./node_modules/playwright/cli.js', import.meta.url)),
      'test', '--config', join(directory, 'probe.config.mjs')], {
      env: { ...process.env, STUDYROOM_REPORT_PROBE: sentinel, STUDYROOM_TEST_PASSWORD_FILE: sentinelFile,
        STUDYROOM_TEST_PASSWORD: '' }, encoding: 'utf8', timeout: 35_000,
    });
    assert.equal(result.status, 1, 'Privacy fixture must reach its intentional assertion failure');
    assert.ok(!(result.stdout + result.stderr).includes(sentinel), 'Console output leaked the sentinel');
    const report = JSON.parse(readFileSync(join(output, 'results.json'), 'utf8'));
    assert.equal(report.stats.unexpected, 1);
    assert.equal(report.stats.skipped, 0);
    assert.match(JSON.stringify(report.suites), /missing-after-login/, 'Fixture failed before exercising report capture');
    let checked = 0;
    function scan(path) {
      for (const entry of readdirSync(path, { withFileTypes: true })) {
        const file = join(path, entry.name);
        if (entry.isDirectory()) { scan(file); continue; }
        const bytes = readFileSync(file);
        assert.ok(!bytes.includes(Buffer.from(sentinel)), `Report leaked the sentinel: ${entry.name}`);
        checked++;
        for (const match of bytes.toString('utf8').matchAll(/data:application\/zip;base64,([A-Za-z0-9+/=]+)/g))
          for (const payload of zipEntries(Buffer.from(match[1], 'base64')))
            assert.ok(!payload.includes(Buffer.from(sentinel)), 'Embedded HTML report data leaked the sentinel');
      }
    }
    scan(output);
    assert.ok(checked >= 4, 'Expected JUnit, JSON, HTML and screenshot/context artifacts');
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});
