import { test, expect } from '@playwright/test';

// Mock the admin API at the network layer so the E2E needs no live backend.
test.beforeEach(async ({ page }) => {
  await page.route('**/qosid/admin/api/health', (r) => r.fulfill({ status: 200, body: '{}', headers: { 'content-type': 'application/json' } }));
  let loggedIn = false;
  await page.route('**/qosid/admin/api/me', (r) =>
    r.fulfill({ status: loggedIn ? 200 : 401, body: JSON.stringify({ username: 'admin' }), headers: { 'content-type': 'application/json' } }));
  await page.route('**/qosid/admin/api/login', (r) => { loggedIn = true; return r.fulfill({ status: 200, body: JSON.stringify({ username: 'admin', status: 'AUTHENTICATED' }), headers: { 'content-type': 'application/json' } }); });
  await page.route('**/qosid/admin/api/source-auth', (r) =>
    r.fulfill({ status: 200, headers: { 'content-type': 'application/json' }, body: JSON.stringify({
      type: 'basic', basicUsername: 'svc', basicPassword: '***', apikeyHeader: 'X-Api-Key', apikeyValue: '',
      oauth2TokenUrl: '', oauth2ClientId: '', oauth2ClientSecret: '', oauth2Scope: '', oauth2CacheTtlSeconds: '' }) }));
  await page.route('**/qosid/admin/api/source-auth/test', (r) =>
    r.fulfill({ status: 200, headers: { 'content-type': 'application/json' }, body: JSON.stringify({ ok: true, type: 'basic', message: 'Auth configured' }) }));
  await page.route('**/qosid/admin/api/settings', (r) => r.fulfill({ status: 200, body: '[]', headers: { 'content-type': 'application/json' } }));
  await page.route('**/qosid/admin/api/settings/audit', (r) => r.fulfill({ status: 200, body: '[]', headers: { 'content-type': 'application/json' } }));
});

test('login then run Source Auth test', async ({ page }) => {
  await page.goto('/login');
  await page.getByLabel('Foydalanuvchi').fill('admin');
  await page.getByLabel('Parol').fill('admin');
  await page.getByRole('button', { name: 'Kirish' }).click();
  await expect(page.getByText('Sozlamalar')).toBeVisible();
  await page.getByRole('button', { name: 'Sinash' }).first().click();
  await expect(page.getByText('Auth configured')).toBeVisible();
});
