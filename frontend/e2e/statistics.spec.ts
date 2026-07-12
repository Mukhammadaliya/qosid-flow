import { test, expect } from '@playwright/test';

test.beforeEach(async ({ page }) => {
  await page.route('**/qosid/admin/api/health', (r) => r.fulfill({ status: 200, body: '{}', headers: { 'content-type': 'application/json' } }));
  let loggedIn = false;
  await page.route('**/qosid/admin/api/me', (r) =>
    r.fulfill({ status: loggedIn ? 200 : 401, body: JSON.stringify({ username: 'admin' }), headers: { 'content-type': 'application/json' } }));
  await page.route('**/qosid/admin/api/login', (r) => { loggedIn = true; return r.fulfill({ status: 200, body: JSON.stringify({ username: 'admin', status: 'AUTHENTICATED' }), headers: { 'content-type': 'application/json' } }); });
  await page.route('**/qosid/admin/api/stats?*', (r) => {
    const preset = new URL(r.request().url()).searchParams.get('preset');
    const body = preset === 'kpi'
      ? { series: [], kpis: { throughput: 7, successRate: 99, errorRate: 1, dlq: 3, e2eP95: 250 } }
      : { series: [], kpis: {} };
    return r.fulfill({ status: 200, headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) });
  });
});

test('login then view statistics KPIs', async ({ page }) => {
  await page.goto('/login');
  await page.getByLabel('Foydalanuvchi').fill('admin');
  await page.getByLabel('Parol').fill('admin');
  await page.getByRole('button', { name: 'Kirish' }).click();
  await expect(page.getByText('Sozlamalar')).toBeVisible();
  await page.goto('/statistics');
  await expect(page.locator('.ant-statistic', { hasText: 'req/s' })).toContainText('7');
});
