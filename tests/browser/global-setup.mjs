import { mkdirSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { baseURL, requireEnvironment } from './helpers.mjs';

export default async function setup() {
  requireEnvironment();
  const versions = {};
  for (const [service, path] of [['web', '/version.json'], ['core', '/api/v1/version'], ['repair', '/api/v1/repairs/version']]) {
    const response = await fetch(new URL(path, baseURL), { signal: AbortSignal.timeout(20_000) });
    if (!response.ok) throw new Error(`${service} version probe failed: HTTP ${response.status}`);
    const body = await response.json();
    versions[service] = body.data ?? body;
  }
  const { commit, releaseId } = versions.web;
  if (!/^[a-f0-9]{40}$/.test(commit || '') || !releaseId)
    throw new Error('Browser acceptance requires a packaged release with an exact commit.');
  if (Object.values(versions).some((value) => value.commit !== commit || value.releaseId !== releaseId))
    throw new Error('The web/core/repair release combination is inconsistent.');
  if (process.env.STUDYROOM_E2E_EXPECT_COMMIT && process.env.STUDYROOM_E2E_EXPECT_COMMIT !== commit)
    throw new Error('The running release differs from STUDYROOM_E2E_EXPECT_COMMIT.');
  const directory = fileURLToPath(new URL('../../output/browser/', import.meta.url));
  mkdirSync(directory, { recursive: true });
  writeFileSync(`${directory}/run-metadata.json`, JSON.stringify({
    startedAt: new Date().toISOString(), baseURL, commit, releaseId, versions,
    playwright: '1.64.1', viewports: ['1440x1000', '375x812'], mockedApi: false,
  }, null, 2) + '\n');
}
