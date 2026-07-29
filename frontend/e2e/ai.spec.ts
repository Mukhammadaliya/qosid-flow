import { test, expect } from '@playwright/test';

test.beforeEach(async ({ page }) => {
  await page.route('**/qosid/admin/api/health', (r) => r.fulfill({ status: 200, body: '{}', headers: { 'content-type': 'application/json' } }));
  let loggedIn = false;
  await page.route('**/qosid/admin/api/me', (r) =>
    r.fulfill({ status: loggedIn ? 200 : 401, body: JSON.stringify({ username: 'admin' }), headers: { 'content-type': 'application/json' } }));
  await page.route('**/qosid/admin/api/login', (r) => { loggedIn = true; return r.fulfill({ status: 200, body: JSON.stringify({ username: 'admin', status: 'AUTHENTICATED' }), headers: { 'content-type': 'application/json' } }); });
  await page.route('**/qosid/admin/api/ai/status', (r) => r.fulfill({ status: 200, body: JSON.stringify({ enabled: true }), headers: { 'content-type': 'application/json' } }));
  await page.route('**/qosid/admin/api/ai/analyze', (r) => r.fulfill({ status: 200, headers: { 'content-type': 'application/json' }, body: JSON.stringify({ analysis: 'Root cause: upstream timeout. Fix: raise the client timeout.', error: null }) }));
  await page.route('**/qosid/admin/api/logs?*', (r) =>
    r.fulfill({ status: 200, headers: { 'content-type': 'application/json' }, body: JSON.stringify({
      entries: [{ timestamp: '2026-07-12T10:00:00Z', level: 'ERROR', line: '{"failureReason":"timeout"}', labels: { stream: 'dlq' }, parsed: { failureReason: 'timeout' } }] }) }));
});

test('login, open a DLQ error, run AI analysis', async ({ page }) => {
  await page.goto('/login');
  await page.getByLabel('Foydalanuvchi').fill('admin');
  await page.getByLabel('Parol').fill('admin');
  await page.getByRole('button', { name: 'Kirish' }).click();
  await expect(page.getByText('Sozlamalar')).toBeVisible();
  await page.goto('/errors/dlq');
  await page.getByText('timeout').first().click();      // open the row detail drawer
  await page.getByRole('button', { name: 'AI tahlil' }).click();
  await expect(page.getByText(/Root cause: upstream timeout/)).toBeVisible();
});
