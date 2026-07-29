import { test, expect, Page } from '@playwright/test';

// Mock both the gateway admin API and the ops sidecar API at the network layer
// so the E2E needs no live backend (mirrors settings.spec.ts's route-mock pattern).
async function mockBackends(page: Page, { gatewayRunning = true } = {}) {
  await page.route('**/qosid/admin/api/health', (r) => r.fulfill({ status: 200, body: '{}', headers: { 'content-type': 'application/json' } }));
  let loggedIn = false;
  await page.route('**/qosid/admin/api/me', (r) =>
    r.fulfill({ status: loggedIn ? 200 : 401, body: JSON.stringify({ username: 'admin' }), headers: { 'content-type': 'application/json' } }));
  await page.route('**/qosid/admin/api/login', (r) => { loggedIn = true; return r.fulfill({ status: 200, body: JSON.stringify({ username: 'admin', status: 'AUTHENTICATED' }), headers: { 'content-type': 'application/json' } }); });

  await page.route('**/ops/api/login', (r) => r.fulfill({ status: 200, body: '{}', headers: { 'content-type': 'application/json' } }));
  await page.route('**/ops/api/me', (r) => r.fulfill({ status: 200, body: JSON.stringify({ user: 'admin' }), headers: { 'content-type': 'application/json' } }));
  await page.route('**/ops/api/status', (r) => r.fulfill({ status: 200, body: JSON.stringify({ up: gatewayRunning }), headers: { 'content-type': 'application/json' } }));
  await page.route('**/ops/api/stop', (r) => r.fulfill({ status: 200, body: JSON.stringify({ ok: true, exitCode: 0, output: '' }), headers: { 'content-type': 'application/json' } }));
  await page.route('**/ops/api/start', (r) => r.fulfill({ status: 200, body: JSON.stringify({ ok: true, exitCode: 0, output: '' }), headers: { 'content-type': 'application/json' } }));
  await page.route('**/ops/api/logs/stream', (r) => r.fulfill({
    status: 200,
    headers: { 'content-type': 'text/event-stream' },
    body: 'data: e2e-log-line\n\n',
  }));
}

async function login(page: Page) {
  await page.goto('/login');
  await page.getByLabel('Foydalanuvchi').fill('admin');
  await page.getByLabel('Parol').fill('admin');
  await page.getByRole('button', { name: 'Kirish' }).click();
}

test('ops page: status, log stream, stop with confirmation', async ({ page }) => {
  await mockBackends(page);
  await login(page);
  await page.getByText('Ops', { exact: true }).click();

  await expect(page.getByText('Ishlayapti')).toBeVisible();
  await expect(page.getByText('e2e-log-line')).toBeVisible();

  await page.getByRole('button', { name: "To'xtatish" }).click();
  await expect(page.locator('.ant-modal-confirm-title', { hasText: "Gateway to'xtatilsinmi?" })).toBeVisible();
  await page.getByRole('button', { name: "To'xtatish" }).last().click();
});

test('degraded mode: gateway down -> lands on /ops with banner', async ({ page }) => {
  await mockBackends(page, { gatewayRunning: false });
  // Gateway is unreachable: override its routes to abort.
  await page.route('**/qosid/admin/api/**', (r) => r.abort());
  await login(page);

  await expect(page).toHaveURL(/\/ops$/);
  await expect(page.getByText(/Gateway o'chiq/)).toBeVisible();
});
