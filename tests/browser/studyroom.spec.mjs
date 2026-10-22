import { test, expect } from '@playwright/test';
import {
  actor, cancelOwned, confirm, drawer, enterPassword, finishRepair, login,
  modal, noHorizontalOverflow, requireEnvironment, screenshot, selectFirst, unique, watchRuntime,
} from './helpers.mjs';

test.beforeAll(requireEnvironment);
test.beforeEach(async ({ page }) => watchRuntime(page));
test.afterEach(async ({ page }) => expect(page.runtimeFailures || [], "No JavaScript or CSP failures").toEqual([]));

test('login rejects a wrong password, preserves a real session and guards administration', async ({ page }, info) => {
  await page.goto('/login');
  await page.getByLabel('账号', { exact: true }).fill('student');
  await enterPassword(page, 'deliberately-invalid-e2e-password');
  const denied = page.waitForResponse((r) => new URL(r.url()).pathname === '/api/v1/auth/login');
  await page.getByRole('button', { name: '登录' }).click();
  expect((await denied).status()).toBe(401);
  await expect(page.locator('.error-box')).toBeVisible();
  await login(page, 'student');
  await page.reload();
  await expect(page.getByRole('heading', { name: '找一个好位置' })).toBeVisible();
  await page.goto('/admin/users');
  await expect(page.getByText('暂无页面访问权限', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '新建账号' })).toHaveCount(0);
  await screenshot(page, info, 'student-administration-denied');
});

test('M1 availability creates a reservation; a second real page causes a visible 409 and history remains usable at 375px', async ({ page, context }, info) => {
  let id;
  try {
    await login(page, 'student');
    await page.goto('/seats');
    await page.getByRole('button', { name: '查找空间' }).click();
    const seat = page.getByRole('button', { name: /^预约 .*号座位/ }).first();
    await expect(seat).toBeVisible();
    await seat.click();
    const record = await confirm(page, '/reservations', [201]);
    id = record.id;
    expect(id).toBeTruthy();
    await expect(page).toHaveURL(/\/reservations/);
    const card = page.locator('.reservation-card').filter({ hasText: id.slice(0, 8).toUpperCase() });
    await expect(card.getByText('待签到', { exact: true })).toBeVisible();
    await screenshot(page, info, 'desktop-reservation-created');

    // The first page intentionally retains its old version while a real second page cancels.
    const second = await context.newPage();
    await second.goto(`/reservations?id=${id}`);
    await drawer(second).getByRole('button', { name: '取消预约', exact: true }).click();
    await confirm(second, `/reservations/${id}/cancel`);
    await second.close();
    await card.getByRole('button', { name: '取消预约', exact: true }).click();
    await confirm(page, `/reservations/${id}/cancel`, [409]);
    await expect(modal(page).getByRole('button', { name: /^确\s*认$/ })).toBeDisabled();
    await expect(modal(page).getByText('已刷新服务器数据，请返回后重新选择操作。')).toBeVisible();
    await screenshot(page, info, 'real-stale-version-conflict');
    await modal(page).getByRole('button', { name: '返回并重新选择' }).click();

    await page.setViewportSize({ width: 375, height: 812 });
    await expect(card.getByText('已取消', { exact: true })).toBeVisible();
    await noHorizontalOverflow(page);
    await card.getByRole('button', { name: '查看详情' }).click();
    await expect(drawer(page).getByText('已取消', { exact: true }).first()).toBeVisible();
    await expect(drawer(page).getByRole('button', { name: '立即签到' })).toHaveCount(0);
    await noHorizontalOverflow(page);
    await screenshot(page, info, 'mobile-reservation-history');
  } finally {
    await cancelOwned(context, 'reservations', id);
  }
});

test('M2 applicant submits, two different reviewers approve, applicant cancels from mobile', async ({ browser }, info) => {
  const applicant = await actor(browser, 'applicant');
  let department, space, id;
  const title = unique('two-level approval');
  try {
    await applicant.page.goto('/applications');
    await applicant.page.getByRole('button', { name: '申请教室' }).click();
    await drawer(applicant.page).getByRole('button', { name: '查找空间' }).click();
    await drawer(applicant.page).getByRole('button', { name: '选择教室' }).first().click();
    await modal(applicant.page).getByLabel('用途标题', { exact: true }).fill(title);
    await modal(applicant.page).getByLabel('用途说明', { exact: true }).fill('真实浏览器验收：小组自习讨论，结束后撤销并释放资源。');
    id = (await confirm(applicant.page, '/room-applications', [201])).id;
    expect(id).toBeTruthy();

    department = await actor(browser, 'dept-approver');
    await department.page.goto(`/applications?id=${id}`);
    await expect(drawer(department.page).getByRole('heading', { name: title })).toBeVisible();
    await drawer(department.page).getByRole('button', { name: '通过审批' }).click();
    await modal(department.page).getByLabel('审批意见（选填）').fill('院系同意；真实浏览器验收');
    await confirm(department.page, `/room-applications/${id}/decisions`);
    await expect(drawer(department.page).getByRole('button', { name: '通过审批' })).toHaveCount(0);

    space = await actor(browser, 'space-approver');
    await space.page.goto(`/applications?id=${id}`);
    await drawer(space.page).getByRole('button', { name: '通过审批' }).click();
    await modal(space.page).getByLabel('审批意见（选填）').fill('空间同意；真实浏览器验收');
    await confirm(space.page, `/room-applications/${id}/decisions`);
    await expect(drawer(space.page).getByText('已批准', { exact: true }).first()).toBeVisible();
    await screenshot(space.page, info, 'desktop-two-level-approved');

    await applicant.page.setViewportSize({ width: 375, height: 812 });
    await applicant.page.goto(`/applications?id=${id}`);
    await expect(drawer(applicant.page).getByText('院系同意；真实浏览器验收')).toBeVisible();
    await expect(drawer(applicant.page).getByText('空间同意；真实浏览器验收')).toBeVisible();
    await noHorizontalOverflow(applicant.page);
    await drawer(applicant.page).getByRole('button', { name: '撤销申请', exact: true }).click();
    await modal(applicant.page).getByLabel('撤销原因').fill('浏览器验收完成，释放教室');
    await noHorizontalOverflow(applicant.page);
    await confirm(applicant.page, `/room-applications/${id}/cancel`);
    await expect(drawer(applicant.page).getByText('已取消', { exact: true }).first()).toBeVisible();
    await screenshot(applicant.page, info, 'mobile-application-cancelled');
  } finally {
    try { await cancelOwned(applicant.context, 'room-applications', id); }
    finally { await Promise.all([applicant.context.close(), department?.context.close(), space?.context.close()]); }
  }
});

test('M3 mobile reporter, dispatcher and repairer finish real assignment, resource synchronization and acceptance', async ({ browser }, info) => {
  const reporter = await actor(browser, 'student', true);
  let dispatcher, worker, id;
  const title = unique('repair workflow');
  try {
    await reporter.page.goto('/repairs');
    await reporter.page.getByRole('button', { name: '上报故障' }).click();
    await selectFirst(reporter.page, '故障教室');
    await modal(reporter.page).getByLabel('故障标题').fill(title);
    await modal(reporter.page).getByLabel('问题描述').fill('隔离测试环境内的模拟报修；验证派单、维修、验收与资源恢复。');
    await noHorizontalOverflow(reporter.page);
    id = (await confirm(reporter.page, '/repairs', [201])).id;
    expect(id).toBeTruthy();
    await expect(drawer(reporter.page).getByRole('heading', { name: title })).toBeVisible();
    await expect(drawer(reporter.page).getByRole('button', { name: '开始维修', exact: true })).toHaveCount(0);
    await screenshot(reporter.page, info, 'mobile-repair-created');

    dispatcher = await actor(browser, 'dispatcher');
    await dispatcher.page.goto(`/repairs?id=${id}`);
    await drawer(dispatcher.page).getByRole('button', { name: '派单 / 重新派单' }).click();
    await selectFirst(dispatcher.page, '维修人员');
    await confirm(dispatcher.page, `/repairs/${id}/assign`);
    await expect(drawer(dispatcher.page).getByText('待维修', { exact: true }).first()).toBeVisible();

    worker = await actor(browser, 'repairer');
    await worker.page.goto(`/repairs?id=${id}`);
    await drawer(worker.page).getByRole('button', { name: '开始维修', exact: true }).click();
    await confirm(worker.page, `/repairs/${id}/start`);
    await expect(drawer(worker.page).getByRole('button', { name: '提交完工', exact: true })).toBeVisible({ timeout: 45_000 });
    await expect(drawer(worker.page).getByRole('button', { name: '验收通过', exact: true })).toHaveCount(0);
    await drawer(worker.page).getByRole('button', { name: '提交完工', exact: true }).click();
    await modal(worker.page).getByLabel('维修处理情况').fill('已检查模拟问题，测试完成后恢复教室资源。');
    await confirm(worker.page, `/repairs/${id}/complete`);
    await expect(drawer(worker.page).getByText('等待验收，座位仍保持下架')).toBeVisible();
    await screenshot(worker.page, info, 'desktop-repair-awaiting-acceptance');

    await reporter.page.reload();
    // Creation opened a drawer without changing the URL; reopen using the real created ID.
    await reporter.page.goto(`/repairs?id=${id}`);
    await drawer(reporter.page).getByRole('button', { name: '验收通过', exact: true }).click();
    await modal(reporter.page).getByLabel('验收意见（选填）').fill('验收通过，结束浏览器测试');
    await confirm(reporter.page, `/repairs/${id}/acceptance`);
    await expect(drawer(reporter.page).getByRole('button', { name: '评价维修' })).toBeVisible({ timeout: 45_000 });
    await noHorizontalOverflow(reporter.page);
    await screenshot(reporter.page, info, 'mobile-repair-closed');
  } finally {
    try { await finishRepair(reporter.context, worker?.context, dispatcher?.context, id); }
    finally { await Promise.all([reporter.context.close(), worker?.context.close(), dispatcher?.context.close()]); }
  }
});

test('administrator can search an empty result but cannot use student booking actions', async ({ page }, info) => {
  await login(page, 'admin');
  await page.goto('/admin/users');
  const search = page.getByPlaceholder('搜索账号或姓名');
  await search.fill(unique('missing-user'));
  await search.press('Enter');
  await expect(page.locator('.ant-table-placeholder')).toBeVisible();
  await expect(page.locator('.ant-table-tbody tr.ant-table-row')).toHaveCount(0);
  await screenshot(page, info, 'administrator-empty-search');
  await page.goto('/seats');
  await expect(page.getByText('暂无页面访问权限', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '查找空间' })).toHaveCount(0);
});

test('375px navigation, real delayed loading, missing-record error and offline retry stay usable', async ({ page, context }, info) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await login(page, 'student2');
  await page.getByRole('button', { name: '打开导航' }).click();
  await page.locator('.mobile-menu').getByRole('link', { name: '我的预约' }).click();
  await expect(page.getByRole('heading', { name: '我的预约', exact: true })).toBeVisible();
  await noHorizontalOverflow(page);

  await page.goto('/seats');
  const cdp = await context.newCDPSession(page);
  await cdp.send('Network.enable');
  await cdp.send('Network.emulateNetworkConditions', { offline: false, latency: 1000, downloadThroughput: 1_000_000, uploadThroughput: 1_000_000 });
  await page.getByRole('button', { name: '查找空间' }).click();
  await expect(page.getByRole('button', { name: '查找空间' })).toHaveClass(/ant-btn-loading/);
  await expect(page.locator('.ant-btn-loading-icon').first()).toBeVisible();
  await expect(page.getByRole('button', { name: /^预约 .*号座位/ }).first()).toBeVisible();
  await cdp.send('Network.emulateNetworkConditions', { offline: false, latency: 0, downloadThroughput: -1, uploadThroughput: -1 });
  await cdp.detach();
  await noHorizontalOverflow(page);
  await screenshot(page, info, 'mobile-available-seats');

  await page.goto('/reservations');
  await expect(page.getByRole('button', { name: /刷新/ })).toBeVisible();
  await context.setOffline(true);
  try {
    await page.getByRole('button', { name: /刷新/ }).click();
    await expect(page.locator('.error-box')).toBeVisible();
    await expect(page.locator('.error-box').getByRole('button', { name: /重\s*试/ })).toBeVisible();
    await screenshot(page, info, 'mobile-offline-error');
  } finally { await context.setOffline(false); }
  await page.locator('.error-box').getByRole('button', { name: /重\s*试/ }).click();
  await expect(page.locator('.error-box')).toHaveCount(0);

  await page.goto('/reservations?id=00000000-0000-0000-0000-000000000000');
  await expect(drawer(page).locator('.error-box')).toBeVisible();
  await expect(drawer(page).locator('.error-box')).toContainText('请求编号');
  await noHorizontalOverflow(page);
  await screenshot(page, info, 'mobile-real-missing-record');
});
