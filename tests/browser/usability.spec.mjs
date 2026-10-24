import { test, expect } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { actor, drawer, modal, noHorizontalOverflow, realApi, requireEnvironment, screenshot, unique } from './helpers.mjs';

// Keep automatic failure screenshots off for this file: the check-in popup contains a secret.
// Non-secret journeys attach explicit screenshots through screenshot() below.
test.use({ screenshot: 'off' });
test.beforeAll(requireEnvironment);

const journeys = [
  { user: 'student', title: '查找座位', routes: [['/seats', '查找座位'], ['/reservations', '我的座位预约'], ['/applications', '教室申请'], ['/repairs', '设施报修'], ['/notifications', '通知中心']] },
  { user: 'student2', title: '查找座位', routes: [['/seats', '查找座位'], ['/reservations', '我的座位预约']] },
  { user: 'applicant', title: '查找座位', routes: [['/applications', '教室申请']] },
  { user: 'dept-approver', title: '教室申请审批', tab: '待我审批', routes: [['/applications', '教室申请审批']] },
  { user: 'space-approver', title: '教室申请审批', tab: '待我审批', routes: [['/applications', '教室申请审批']] },
  { user: 'dispatcher', title: '维修工作台', tab: '工单管理', routes: [['/repairs', '维修工作台']] },
  { user: 'repairer', title: '维修工作台', tab: '我的维修任务', routes: [['/repairs', '维修工作台']] },
  { user: 'property-admin', title: '维修工作台', tab: '工单管理', routes: [['/repairs', '维修工作台']] },
  { user: 'room-admin', title: '教室与座位', routes: [['/admin/spaces', '教室与座位']] },
  { user: 'operator', title: '使用统计', routes: [['/admin/statistics', '使用统计'], ['/reservations', '我的座位预约']] },
  { user: 'admin', title: '账号与授权', routes: [['/admin/users', '账号与授权'], ['/admin/roles', '角色与职责'], ['/admin/spaces', '教室与座位'], ['/admin/config', '系统参数'], ['/admin/audit', '操作审计'], ['/notifications', '通知中心']] },
];

for (const journey of journeys) test(`clear task entry and responsive pages for ${journey.user}`, async ({ browser }, info) => {
  const session = await actor(browser, journey.user);
  const { page, context } = session;
  try {
    await expect(page.getByRole('heading', { name: journey.title, exact: true })).toBeVisible();
    if (journey.tab) await expect(page.getByRole('tab', { name: journey.tab, exact: true })).toHaveAttribute('aria-selected', 'true');
    const navigation = page.getByRole('navigation', { name: '主导航', exact: true });
    if (journey.user === 'admin') {
      await expect(navigation.getByRole('link', { name: '账号与授权', exact: true })).toBeVisible();
      await expect(navigation.getByRole('link', { name: '查找座位', exact: true })).toHaveCount(0);
    } else {
      await expect(navigation.getByRole('link', { name: '账号与授权', exact: true })).toHaveCount(0);
    }
    for (const width of [1440, 375]) {
      await page.setViewportSize({ width, height: 900 });
      for (const [path, title] of journey.routes) {
        await page.goto(path);
        await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible();
        await expect(page.locator('.ant-spin-spinning')).toHaveCount(0);
        await expect(page.locator('.error-box')).toHaveCount(0);
        if (path === '/admin/spaces') {
          const roomsTab = page.getByRole('tab', { name: '教室与座位', exact: true });
          const departmentsTab = page.getByRole('tab', { name: '院系', exact: true });
          if (journey.user === 'admin') {
            await expect(departmentsTab).toBeVisible();
            await expect(roomsTab).toHaveCount(0);
          } else if (journey.user === 'room-admin') {
            await expect(roomsTab).toBeVisible();
            await expect(departmentsTab).toHaveCount(0);
          }
        }
        await noHorizontalOverflow(page);
        await screenshot(page, info, `${journey.user}-${path.replaceAll('/', '-')}-${width}`);
      }
      if (width === 375) {
        await page.getByRole('button', { name: '打开导航' }).click();
        await expect(page.locator('.ant-drawer-open .mobile-menu')).toHaveCount(1);
        await expect(page.locator('.mobile-menu').getByRole('link', { name: '通知中心', exact: true })).toBeVisible();
        await page.locator('.mobile-menu').getByRole('link', { name: '通知中心', exact: true }).click();
        await expect(page.locator('.ant-drawer-open .mobile-menu')).toHaveCount(0);
        await expect(page.getByRole('heading', { name: '通知中心', exact: true })).toBeVisible();
      }
    }
    if (journey.user !== 'admin') {
      await page.goto('/admin/users');
      await expect(page.locator('.ant-result-title')).toHaveText('暂无页面访问权限');
      await expect(page.getByRole('button', { name: '创建账号', exact: true })).toHaveCount(0);
    }
  } finally { await context.close(); }
});

test('authorization names rooms outside administrator booking visibility and preserves selected objects', async ({ browser }, info) => {
  const admin = await actor(browser, 'admin');
  const { page, context } = admin;
  let target;
  const username = 'ux-' + randomUUID().slice(0, 12);
  const readTarget = async () => {
    const users = await realApi(context, 'GET', '/users?q=' + encodeURIComponent(username) + '&size=100');
    const user = users.items.find(user => user.id === target.id);
    if (!user) throw new Error('The isolated authorization fixture was not found in the user directory.');
    return user;
  };
  try {
    const me = await realApi(context, 'GET', '/auth/me');
    const available = await realApi(context, 'GET', '/rooms?size=100');
    const directory = await realApi(context, 'GET', '/role-scope-rooms?size=100');
    const hiddenRoom = directory.items.find(room => !available.items.some(visible => visible.id === room.id));
    expect(hiddenRoom, 'Seed includes an other-department room that admin cannot access for business').toBeTruthy();
    const roles = await realApi(context, 'GET', '/roles?size=100');
    const role = roles.items.find(role => role.code === 'ROOM_ADMIN');
    expect(role, 'Seed includes the room administrator role').toBeTruthy();
    target = await realApi(context, 'POST', '/users', {
      username, password: randomUUID(), displayName: unique('授权姓名显示'),
      email: `${username}@example.invalid`, departmentId: me.departmentId,
    });
    target = await realApi(context, 'PUT', `/users/${target.id}/role-bindings`, {
      expectedVersion: target.version, bindings: [{ roleId: role.id, scopes: [{ scopeType: 'ROOM', roomId: hiddenRoom.id }] }],
    });
    await page.goto('/admin/users');
    const search = page.getByLabel('搜索账号或姓名', { exact: true });
    await search.fill(username);
    await search.press('Enter');
    const row = page.locator('.ant-table-row').filter({ hasText: username });
    await row.getByRole('button', { name: '设置职责', exact: true }).click();
    await expect(modal(page)).toContainText('能做什么');
    await expect(modal(page)).toContainText('负责哪些对象');
    await expect(modal(page)).toContainText(hiddenRoom.name);
    await expect(modal(page)).not.toContainText(hiddenRoom.id);
    await screenshot(page, info, 'authorization-readable-room-desktop');
    await page.setViewportSize({ width: 375, height: 812 });
    await noHorizontalOverflow(page);
    await expect(modal(page)).toContainText(hiddenRoom.name);
    await screenshot(page, info, 'authorization-readable-room-mobile');
    const [saved] = await Promise.all([
      page.waitForResponse(response => response.request().method() === 'PUT' && new URL(response.url()).pathname === `/api/v1/users/${target.id}/role-bindings`),
      modal(page).getByRole('button', { name: '保存授权', exact: true }).click(),
    ]);
    expect(saved.status()).toBe(200);
    await expect(modal(page)).toHaveCount(0);
    const readback = await readTarget();
    expect(readback.roleBindings.map(binding => ({
      roleId: binding.roleId,
      scopes: binding.scopes.map(scope => ({ scopeType: scope.scopeType, roomId: scope.roomId, roomName: scope.roomName })),
    }))).toEqual([{ roleId: role.id, scopes: [{ scopeType: 'ROOM', roomId: hiddenRoom.id, roomName: hiddenRoom.name }] }]);
  } finally {
    try {
      if (target) {
        const latest = await readTarget();
        await realApi(context, 'PATCH', `/users/${target.id}`, { expectedVersion: latest.version, enabled: false });
      }
    } finally {
      await context.close();
    }
  }
});

test.describe('check-in display privacy', () => {
  test('room administrator can open the real check-in display at desktop and phone widths', async ({ browser }) => {
    const admin = await actor(browser, 'room-admin');
    try {
      await admin.page.goto('/admin/spaces');
      await admin.page.getByRole('button', { name: '管理教室', exact: true }).first().click();
      const [display] = await Promise.all([
        admin.context.waitForEvent('page'),
        drawer(admin.page).getByRole('link', { name: '打开签到屏幕' }).click(),
      ]);
      for (const width of [1440, 375]) {
        await display.setViewportSize({ width, height: 900 });
        // Keep the code and its accessible label inside the browser. Locator
        // assertion diagnostics can otherwise expose them even with screenshots off.
        await expect.poll(() => display.evaluate(() => {
          const code = document.querySelector('.display-code');
          const refresh = [...document.querySelectorAll('button')].find(button => button.textContent.trim() === '刷新签到码');
          const isVisible = element => {
            if (!element) return false;
            const rect = element.getBoundingClientRect();
            const style = getComputedStyle(element);
            return rect.width > 0 && rect.height > 0 && style.visibility !== 'hidden' && style.display !== 'none';
          };
          return {
            codeVisible: isVisible(code) && Boolean(code.textContent.trim()),
            refreshVisible: isVisible(refresh),
          };
        }), { message: 'Check-in display and refresh control must be visible; no secret values are returned' })
          .toEqual({ codeVisible: true, refreshVisible: true });
        await noHorizontalOverflow(display);
        // Never attach screenshots, traces, or page text from the check-in display.
      }
    } finally {
      await admin.context.close();
    }
  });
});
