import { test, expect } from '@playwright/test';
import {
  actor, cancelOwned, confirm, drawer, enterPassword, finishRepair, login,
  modal, noHorizontalOverflow, realApi, requireEnvironment, screenshot, selectFirst, unique, watchRuntime,
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

test('M1 availability, real stale 409 and mobile history reuse create a new reservation', async ({ page, context }, info) => {
  let id, repeatedId;
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
    const cancelled = await confirm(second, `/reservations/${id}/cancel`);
    await second.close();
    await card.getByRole('button', { name: '取消预约', exact: true }).click();
    await confirm(page, `/reservations/${id}/cancel`, [409]);
    const staleConfirm = modal(page).getByRole('button', { name: '确认', exact: true });
    await expect(staleConfirm).toHaveText(/^确\s*认$/);
    await expect(staleConfirm).toBeDisabled();
    await expect(staleConfirm).toHaveAttribute('aria-busy', 'false');
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

    await drawer(page).getByRole('button', { name: '再次预约', exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`/seats\\?roomId=${record.roomId}&seatId=${record.seatId}`));
    await expect(page.getByText('已带入上次使用的教室与座位', { exact: true })).toBeVisible();
    await expect(page.getByLabel('教室', { exact: true }).locator('..')).toContainText(record.roomName);
    await expect(page.getByRole('button', { name: /^预约 .*号座位/ })).toHaveCount(0);
    // History carries only the location. A new time must be searched and adjudicated again.
    const startTime = page.locator('.time-filter > label').filter({ hasText: '开始时间' });
    await expect(startTime.getByTitle('09:00', { exact: true })).toHaveText('09:00');
    const timeInput = startTime.getByRole('combobox');
    await timeInput.click();
    // The mobile list is virtualized and opens upward; keyboard navigation scrolls it normally.
    for (let hour = 9; hour < 13; hour++) await timeInput.press('ArrowDown');
    await timeInput.press('Enter');
    await expect(startTime.getByTitle('13:00', { exact: true })).toHaveText('13:00');
    await timeInput.press('Escape');
    const availability = page.waitForResponse((response) => new URL(response.url()).pathname === '/api/v1/availability/seats');
    await page.getByRole('button', { name: '查找空间' }).click();
    const availableResponse = await availability;
    expect(availableResponse.status()).toBe(200);
    const searched = new URL(availableResponse.url()).searchParams;
    expect(searched.get('roomId')).toBe(record.roomId);
    expect(searched.get('startAt')).toContain('T13:00:00+08:00');
    const priorSeat = page.locator('button.seat.selected');
    await expect(priorSeat).toHaveCount(1);
    await expect(priorSeat).toContainText(record.seatCode);
    await noHorizontalOverflow(page);
    await screenshot(page, info, 'mobile-repeat-reservation-selection');
    await priorSeat.click();
    const repeated = await confirm(page, '/reservations', [201]);
    repeatedId = repeated.id;
    expect(repeatedId).toBeTruthy();
    expect(repeatedId).not.toBe(id);
    expect(repeated.seatId).toBe(record.seatId);
    expect(repeated.status).toBe('RESERVED');
    expect(repeated.startAt).not.toBe(record.startAt);
    const original = await realApi(context, 'GET', `/reservations/${id}`);
    for (const key of ['id', 'version', 'status', 'startAt', 'endAt', 'seatId', 'cancelReason'])
      expect(original[key], `History reuse must preserve original ${key}`).toEqual(cancelled[key]);
    const newCard = page.locator('.reservation-card').filter({ hasText: repeatedId.slice(0, 8).toUpperCase() });
    await expect(newCard.getByText('待签到', { exact: true })).toBeVisible();
    await screenshot(page, info, 'mobile-repeat-reservation-created');
  } finally {
    await cancelOwned(context, 'reservations', repeatedId);
    await cancelOwned(context, 'reservations', id);
  }
});

test('M2 independent approvals, mobile cancellation and rejection with a mandatory visible reason', async ({ browser }, info) => {
  const applicant = await actor(browser, 'applicant');
  let department, space, id, rejectedId;
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

    await applicant.page.goto('/applications');
    await applicant.page.getByRole('button', { name: '申请教室' }).click();
    await drawer(applicant.page).getByRole('button', { name: '查找空间' }).click();
    await drawer(applicant.page).getByRole('button', { name: '选择教室' }).first().click();
    const rejectedTitle = unique('rejection history');
    await modal(applicant.page).getByLabel('用途标题', { exact: true }).fill(rejectedTitle);
    await modal(applicant.page).getByLabel('用途说明', { exact: true }).fill('真实浏览器验证拒绝理由必填、申请人历史可见及终态不可再次审批。');
    rejectedId = (await confirm(applicant.page, '/room-applications', [201])).id;
    expect(rejectedId).toBeTruthy();
    await department.page.goto(`/applications?id=${rejectedId}`);
    await drawer(department.page).getByRole('button', { name: /^驳\s*回$/ }).click();
    const decisions = [];
    const decisionListener = (request) => {
      if (request.method() === 'POST' && new URL(request.url()).pathname === `/api/v1/room-applications/${rejectedId}/decisions`)
        decisions.push(request);
    };
    department.page.on('request', decisionListener);
    await modal(department.page).getByRole('button', { name: /^确\s*认$/ }).click();
    await expect(modal(department.page).getByText('请填写此项', { exact: true })).toBeVisible();
    expect(decisions, 'An empty rejection reason must not submit a decision').toHaveLength(0);
    const reason = '活动用途尚不明确，请补充后重新提交；真实浏览器拒绝验收。';
    await modal(department.page).getByLabel('驳回理由', { exact: true }).fill(reason);
    const rejected = await confirm(department.page, `/room-applications/${rejectedId}/decisions`);
    department.page.off('request', decisionListener);
    expect(decisions).toHaveLength(1);
    expect(rejected.status).toBe('REJECTED');
    expect(rejected.allowedActions).not.toContain('approve');
    expect(rejected.allowedActions).not.toContain('reject');
    expect(rejected.actions).toEqual(expect.arrayContaining([expect.objectContaining({ decision: 'REJECT', comment: reason })]));
    await department.page.reload();
    await expect(drawer(department.page).getByText('未通过', { exact: true }).first()).toBeVisible();
    await expect(drawer(department.page).getByRole('button', { name: '通过审批', exact: true })).toHaveCount(0);
    await expect(drawer(department.page).getByRole('button', { name: /^驳\s*回$/ })).toHaveCount(0);
    await screenshot(department.page, info, 'desktop-application-rejected');
    await applicant.page.goto(`/applications?id=${rejectedId}`);
    await expect(drawer(applicant.page).getByRole('heading', { name: rejectedTitle })).toBeVisible();
    await expect(drawer(applicant.page).getByText('未通过', { exact: true }).first()).toBeVisible();
    await expect(drawer(applicant.page).getByText(reason)).toBeVisible();
    await expect(drawer(applicant.page).getByRole('button', { name: '撤销申请', exact: true })).toHaveCount(0);
    await noHorizontalOverflow(applicant.page);
    await screenshot(applicant.page, info, 'mobile-rejection-reason');
  } finally {
    try {
      await cancelOwned(applicant.context, 'room-applications', rejectedId);
      await cancelOwned(applicant.context, 'room-applications', id);
    }
    finally { await Promise.all([applicant.context.close(), department?.context.close(), space?.context.close()]); }
  }
});

test('M3 mobile reporter, dispatcher and repairer finish real assignment, resource synchronization and acceptance', async ({ browser }, info) => {
  const reporter = await actor(browser, 'student', true);
  let dispatcher, worker, id, cancelledId;
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
    const workerName = await selectFirst(dispatcher.page, '维修人员');
    const assigned = await confirm(dispatcher.page, `/repairs/${id}/assign`);
    expect(assigned.assignedToName).toBe(workerName);
    await expect(drawer(dispatcher.page).getByText('待维修', { exact: true }).first()).toBeVisible();
    const workerDescription = (page) => drawer(page).locator('.ant-descriptions-item-container')
      .filter({ has: page.locator('.ant-descriptions-item-label', { hasText: /^维修人员$/ }) })
      .locator('.ant-descriptions-item-content');
    await expect(workerDescription(dispatcher.page)).toHaveText(workerName);

    worker = await actor(browser, 'repairer');
    await worker.page.goto(`/repairs?id=${id}`);
    await drawer(worker.page).getByRole('button', { name: '开始维修', exact: true }).click();
    await confirm(worker.page, `/repairs/${id}/start`);
    await expect(drawer(worker.page).getByRole('button', { name: '提交完工', exact: true })).toBeVisible({ timeout: 45_000 });
    await expect(drawer(worker.page).getByText('处理截止', { exact: true })).toBeVisible();
    await expect(drawer(worker.page).getByRole('button', { name: '验收通过', exact: true })).toHaveCount(0);
    await drawer(worker.page).getByRole('button', { name: '提交完工', exact: true }).click();
    await modal(worker.page).getByLabel('维修处理情况').fill('已检查模拟问题，测试完成后恢复教室资源。');
    await expect(modal(worker.page).getByRole('button', { name: '确认', exact: true })).toHaveAttribute('aria-busy', 'false');
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
    await expect(workerDescription(reporter.page)).toHaveText(workerName);
    await expect(drawer(reporter.page).getByText('处理截止', { exact: true })).toHaveCount(0);
    await noHorizontalOverflow(reporter.page);
    await screenshot(reporter.page, info, 'mobile-repair-closed');

    // A real slow room request must keep report creation unavailable until choices arrive.
    // Otherwise opening the action early permanently snapshots an empty options list.
    const delayedRooms = await reporter.context.newCDPSession(reporter.page);
    await delayedRooms.send('Network.enable');
    await delayedRooms.send('Network.emulateNetworkConditions', { offline: false, latency: 1800, downloadThroughput: -1, uploadThroughput: -1 });
    const roomResponse = reporter.page.waitForResponse(response => new URL(response.url()).pathname === '/api/v1/rooms');
    await reporter.page.goto('/repairs');
    const reportButton = reporter.page.getByRole('button', { name: '上报故障', exact: true });
    await expect(reportButton).toBeDisabled();
    await expect(reportButton).toHaveAttribute('aria-busy', 'true');
    const loadedRooms = await roomResponse;
    expect(loadedRooms.status()).toBe(200);
    expect((await loadedRooms.json()).data.items.length).toBeGreaterThan(0);
    await expect(reportButton).toBeEnabled();
    await expect(reportButton).toHaveAttribute('aria-busy', 'false');
    await delayedRooms.send('Network.emulateNetworkConditions', { offline: false, latency: 0, downloadThroughput: -1, uploadThroughput: -1 });
    await delayedRooms.detach();
    await reportButton.click();
    await selectFirst(reporter.page, '故障教室');
    await modal(reporter.page).getByLabel('故障标题').fill(unique('cancelled repair'));
    await modal(reporter.page).getByLabel('问题描述').fill('独立测试取消终态的截止字段；提交后由本人撤销。');
    cancelledId = (await confirm(reporter.page, '/repairs', [201])).id;
    await drawer(reporter.page).getByRole('button', { name: '取消工单', exact: true }).click();
    await modal(reporter.page).getByLabel('取消原因').fill('检查后确认无需维修，撤销模拟工单');
    await confirm(reporter.page, `/repairs/${cancelledId}/cancel`);
    await expect(drawer(reporter.page).getByText('已取消', { exact: true }).first()).toBeVisible();
    await expect(drawer(reporter.page).getByText('处理截止', { exact: true })).toHaveCount(0);
    await noHorizontalOverflow(reporter.page);
    await screenshot(reporter.page, info, 'mobile-repair-cancelled');
  } finally {
    try {
      await cancelOwned(reporter.context, 'repairs', cancelledId);
      await finishRepair(reporter.context, worker?.context, dispatcher?.context, id);
    }
    finally { await Promise.all([reporter.context.close(), worker?.context.close(), dispatcher?.context.close()]); }
  }
});

test('administrator can search an empty result but cannot use student booking actions', async ({ page }, info) => {
  await login(page, 'admin');
  await page.goto('/admin/users');
  // A dialog opened from a desktop click must not leave toward that stale screen
  // position after a resize. Observe every closing frame, not only its final state.
  await page.getByRole('button', { name: /创建账号/ }).click();
  await expect(modal(page)).toBeVisible();
  await expect.poll(() => modal(page).evaluate((element) =>
    element.getAnimations().filter((animation) => animation.playState === 'running').length)).toBe(0);
  await page.setViewportSize({ width: 375, height: 812 });
  await noHorizontalOverflow(page);
  const closing = modal(page).evaluate((element) => new Promise((resolve) => {
    const started = performance.now();
    const overflow = [];
    let frames = 0;
    function observe() {
      const box = element.getBoundingClientRect();
      if (!box?.width || !box.height) return resolve({ closed: true, frames, overflow });
      frames++;
      if (box.x < -1 || box.right > innerWidth + 1)
        overflow.push({ x: box.x, width: box.width, viewport: innerWidth });
      if (performance.now() - started > 2_000) return resolve({ closed: false, frames, overflow });
      requestAnimationFrame(observe);
    }
    observe();
  }));
  await modal(page).getByRole('button', { name: /^返\s*回$/ }).click();
  const motion = await closing;
  expect(motion.closed, 'Dismissed dialog must finish closing').toBe(true);
  expect(motion.frames, 'Observe the real dialog before it closes').toBeGreaterThan(0);
  expect(motion.overflow, 'Every mobile closing frame must stay inside the viewport').toEqual([]);
  await noHorizontalOverflow(page);
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
