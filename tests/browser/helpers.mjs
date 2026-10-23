import { readFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { expect } from '@playwright/test';

export const baseURL = process.env.STUDYROOM_E2E_BASE_URL || 'http://127.0.0.1:19282';
export const unique = (label) => `E2E ${label} ${randomUUID().slice(0, 8)}`;
export const modal = (page) => page.locator('.ant-modal:visible');
export const drawer = (page) => page.locator('.ant-drawer-open .ant-drawer-body');

export function watchRuntime(page) {
  page.runtimeFailures = [];
  page.on('pageerror', (error) => page.runtimeFailures.push(error.message));
  page.on('console', (message) => {
    if (message.type() === 'error' && /Content Security Policy|Refused to|unsafe-eval/i.test(message.text()))
      page.runtimeFailures.push(message.text());
  });
}

export function requireEnvironment() {
  if (process.env.STUDYROOM_E2E_ALLOW_MUTATIONS !== '1')
    throw new Error('Set STUDYROOM_E2E_ALLOW_MUTATIONS=1 only for an isolated seeded test installation.');
  if (!process.env.STUDYROOM_TEST_PASSWORD_FILE && !process.env.STUDYROOM_TEST_PASSWORD)
    throw new Error('Provide STUDYROOM_TEST_PASSWORD_FILE or the masked STUDYROOM_TEST_PASSWORD environment variable.');
  const url = new URL(baseURL);
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password)
    throw new Error('E2E base URL must be an HTTP(S) origin without credentials.');
}

function password() {
  const value = process.env.STUDYROOM_TEST_PASSWORD_FILE
    ? readFileSync(process.env.STUDYROOM_TEST_PASSWORD_FILE, 'utf8').trim()
    : process.env.STUDYROOM_TEST_PASSWORD;
  if (!value) throw new Error('The test credential is empty.');
  return value;
}

export async function enterPassword(page, value) {
  // Keep a real credential out of Playwright fill-step titles and error reports.
  await page.getByLabel('密码', { exact: true }).evaluate((input, secret) => {
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(input, secret);
    input.dispatchEvent(new Event('input', { bubbles: true }));
    input.dispatchEvent(new Event('change', { bubbles: true }));
  }, value);
}

export async function login(page, username) {
  await page.goto('/login');
  await page.getByLabel('账号', { exact: true }).fill(username);
  await enterPassword(page, password());
  await page.getByRole('button', { name: '登录', exact: false }).click();
  await expect(page.locator('#main-content')).toBeVisible();
}

export async function actor(browser, username, mobile = false) {
  const context = await browser.newContext({
    baseURL, locale: 'zh-CN', timezoneId: 'Asia/Shanghai',
    viewport: mobile ? { width: 375, height: 812 } : { width: 1440, height: 1000 },
  });
  const page = await context.newPage();
  watchRuntime(page);
  await login(page, username);
  return { context, page };
}

export async function confirm(page, path, expectedStatus = [200, 201, 202, 204]) {
  const results = await Promise.allSettled([
    page.waitForResponse((response) => new URL(response.url()).pathname === `/api/v1${path}` && response.request().method() !== 'GET'),
    modal(page).getByRole('button', { name: /^确\s*认$/ }).click(),
  ]);
  if (results.some((result) => result.status === 'rejected')) {
    const diagnostic = await page.evaluate(() => {
      const dialog = [...document.querySelectorAll('.ant-modal')].find((node) => node.getBoundingClientRect().width > 0);
      const ancestry = [];
      for (let node = dialog; node; node = node.parentElement)
        ancestry.push({ tag: node.tagName, class: node.className, hidden: node.getAttribute('aria-hidden'), inert: node.hasAttribute('inert') });
      return { ancestry, fields: [...(dialog?.querySelectorAll('input,textarea') || [])].map((field) => ({ tag: field.tagName, length: field.value.length })),
        buttons: [...(dialog?.querySelectorAll('button') || [])].map((button) => ({ text: button.textContent, disabled: button.disabled, label: button.getAttribute('aria-label'), busy: button.getAttribute('aria-busy'), iconLabels: [...button.querySelectorAll('[role=img]')].map(icon => icon.getAttribute('aria-label')) })) };
    });
    // No request bodies, input values, credentials or cookies are included.
    console.error('Confirmation diagnostic:', JSON.stringify({ path, ...diagnostic }));
    // A response timeout must not mask an actual failed click from the same Promise group.
    throw (results[1].status === 'rejected' ? results[1].reason : results[0].reason);
  }
  const response = results[0].value;
  expect(expectedStatus, `Unexpected status from ${path}`).toContain(response.status());
  const payload = response.status() === 204 ? {} : await response.json();
  if (response.ok()) await expect(modal(page)).toHaveCount(0);
  return payload.data ?? payload;
}

export async function selectFirst(page, label) {
  await modal(page).getByLabel(label, { exact: true }).click();
  const option = page.locator('.ant-select-dropdown:visible .ant-select-item-option').first();
  await expect(option).toBeVisible();
  const labelText = (await option.locator('.ant-select-item-option-content').innerText()).trim();
  await option.click();
  return labelText;
}

export async function noHorizontalOverflow(page) {
  // Observe the settled layout, including Ant Design opening/closing transitions.
  await expect.poll(async () => {
    const dimensions = await page.evaluate(() => ({
      viewport: window.innerWidth,
      document: document.documentElement.scrollWidth,
      body: document.body.scrollWidth,
    }));
    const overflow = [];
    if (dimensions.document > dimensions.viewport + 1 || dimensions.body > dimensions.viewport + 1)
      overflow.push(dimensions);
    for (const element of await page.locator('.ant-modal:visible, .ant-drawer-open .ant-drawer-content-wrapper').all()) {
      const box = await element.boundingBox();
      if (box && (box.x < -1 || box.x + box.width > dimensions.viewport + 1))
        overflow.push({ ...box, viewport: dimensions.viewport });
    }
    return overflow;
  }, { timeout: 5_000, message: 'Page and visible dialogs must fit the viewport after transitions' }).toEqual([]);
}

export async function screenshot(page, info, label) {
  expect(page.runtimeFailures || [], 'No JavaScript or CSP failures').toEqual([]);
  const path = info.outputPath(`${label}.png`);
  await page.screenshot({ path, fullPage: true });
  await info.attach(label, { path, contentType: 'image/png' });
}

export async function realApi(context, method, path, body) {
  const headers = {};
  if (method !== 'GET') {
    const tokenResponse = await context.request.get(`${baseURL}/api/v1/auth/csrf`);
    const token = await tokenResponse.json();
    headers['X-CSRF-TOKEN'] = token.data.csrfToken;
    headers['Idempotency-Key'] = randomUUID();
  }
  const response = await context.request.fetch(`${baseURL}/api/v1${path}`, { method, headers, data: body });
  if (!response.ok()) throw new Error(`Fixture cleanup ${method} ${path} failed: HTTP ${response.status()}`);
  return response.status() === 204 ? undefined : (await response.json()).data;
}

export async function cancelOwned(context, collection, id) {
  if (!id) return;
  const record = await realApi(context, 'GET', `/${collection}/${id}`);
  if (record.allowedActions?.includes('cancel'))
    await realApi(context, 'POST', `/${collection}/${id}/cancel`, { expectedVersion: record.version, reason: 'E2E fixture cleanup' });
}

export async function finishRepair(reporter, worker, dispatcher, id) {
  if (!id) return;
  let record = await realApi(reporter, 'GET', `/repairs/${id}`);
  if (['CLOSED', 'CANCELLED'].includes(record.status)) return;
  if (['SUBMITTED', 'ASSIGNED'].includes(record.status)) {
    await cancelOwned(dispatcher || reporter, 'repairs', id);
    return;
  }
  // Cleanup only this test's own order through the same real public workflow.
  for (let attempt = 0; attempt < 40; attempt++) {
    record = await realApi(reporter, 'GET', `/repairs/${id}`);
    if (['CLOSED', 'CANCELLED'].includes(record.status)) return;
    if (record.status === 'IN_REPAIR' && worker)
      await realApi(worker, 'POST', `/repairs/${id}/complete`, { expectedVersion: record.version, workNote: 'E2E fixture cleanup: simulated report checked and closed' });
    if (record.status === 'AWAITING_ACCEPTANCE')
      await realApi(reporter, 'POST', `/repairs/${id}/acceptance`, { expectedVersion: record.version, decision: 'ACCEPT', comment: 'E2E fixture cleanup' });
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error(`Repair fixture ${id} still needs cleanup; inspect the isolated test environment.`);
}
