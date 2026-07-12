import { test, expect } from '@playwright/test';

test.beforeEach(async ({ page }) => {
  await page.route('**/qosid/admin/api/health', (r) => r.fulfill({ status: 200, body: '{}', headers: { 'content-type': 'application/json' } }));
  let loggedIn = false;
  await page.route('**/qosid/admin/api/me', (r) =>
    r.fulfill({ status: loggedIn ? 200 : 401, body: JSON.stringify({ username: 'admin' }), headers: { 'content-type': 'application/json' } }));
  await page.route('**/qosid/admin/api/login', (r) => { loggedIn = true; return r.fulfill({ status: 200, body: JSON.stringify({ username: 'admin', status: 'AUTHENTICATED' }), headers: { 'content-type': 'application/json' } }); });
  await page.route('**/qosid/admin/api/logs?*', (r) =>
    r.fulfill({ status: 200, headers: { 'content-type': 'application/json' }, body: JSON.stringify({
      entries: [{ timestamp: '2026-07-12T10:00:00Z', level: 'ERROR',
        line: '{"failureReason":"timeout","tenantId":100,"httpStatus":504}',
        labels: { stream: 'dlq' },
        parsed: { failureReason: 'timeout', tenantId: 100, httpStatus: 504 } }] }) }));
});

test('login then view a DLQ error', async ({ page }) => {
  await page.goto('/login');
  await page.getByLabel('Foydalanuvchi').fill('admin');
  await page.getByLabel('Parol').fill('admin');
  await page.getByRole('button', { name: 'Kirish' }).click();
  await page.goto('/errors/dlq');
  await expect(page.getByText('timeout')).toBeVisible();
});
