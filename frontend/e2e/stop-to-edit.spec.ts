import { test, expect, Page } from '@playwright/test';

// D-loyiha stop-to-edit full loop over stateful network mocks (mirrors ops.spec.ts's
// pattern — no live backend). The mock keeps one shared state machine: gateway process
// up/down (ops perspective), the gateway admin session, and the edited setting value.
// While "stopped", every gateway admin API call aborts (unreachable), exactly like a
// dead JVM behind nginx.
async function mockStack(page: Page) {
  const state = {
    gwUp: true,
    gwSession: false,
    baseUrl: 'http://old-value',
    loginTokenCalls: 0,
    handoffCalls: 0,
  };
  const json = (status: number, body: unknown) =>
    ({ status, body: JSON.stringify(body), headers: { 'content-type': 'application/json' } });

  // --- gateway admin API (unreachable while stopped) ---
  await page.route('**/qosid/admin/api/health', (r) =>
    state.gwUp ? r.fulfill(json(200, {})) : r.abort());
  await page.route('**/qosid/admin/api/me', (r) =>
    state.gwUp ? r.fulfill(json(state.gwSession ? 200 : 401, { username: 'admin' })) : r.abort());
  await page.route('**/qosid/admin/api/login', (r) => {
    if (!state.gwUp) return r.abort();
    state.gwSession = true;
    return r.fulfill(json(200, { username: 'admin', status: 'AUTHENTICATED' }));
  });
  await page.route('**/qosid/admin/api/login-token', (r) => {
    if (!state.gwUp) return r.abort();
    state.loginTokenCalls++;
    state.gwSession = true;
    return r.fulfill(json(200, { username: 'admin', status: 'AUTHENTICATED' }));
  });
  await page.route('**/qosid/admin/api/source-auth', (r) =>
    state.gwUp
      ? r.fulfill(json(200, {
          type: 'basic', basicUsername: 'svc', basicPassword: '***', apikeyHeader: 'X-Api-Key', apikeyValue: '',
          oauth2TokenUrl: '', oauth2ClientId: '', oauth2ClientSecret: '', oauth2Scope: '', oauth2CacheTtlSeconds: '' }))
      : r.abort());
  await page.route('**/qosid/admin/api/settings/registry', (r) =>
    state.gwUp
      ? r.fulfill(json(200, { settings: [{
          key: 'gateway.api.base-url', group: 'API', label: 'Base URL', type: 'string',
          effectClass: 'REQUIRES_RESTART', value: state.baseUrl, secret: false }] }))
      : r.abort());
  await page.route('**/qosid/admin/api/settings/audit', (r) =>
    state.gwUp ? r.fulfill(json(200, [])) : r.abort());

  // --- ops sidecar (always alive) ---
  await page.route('**/ops/api/login', (r) => r.fulfill(json(200, {})));
  await page.route('**/ops/api/me', (r) => r.fulfill(json(200, { user: 'admin' })));
  await page.route('**/ops/api/status', (r) => r.fulfill(json(200, { up: state.gwUp })));
  await page.route('**/ops/api/stop', (r) => {
    state.gwUp = false;
    state.gwSession = false; // in-memory gateway sessions die with the JVM
    return r.fulfill(json(200, { ok: true, exitCode: 0, output: '' }));
  });
  await page.route('**/ops/api/start', (r) => {
    state.gwUp = true;
    return r.fulfill(json(200, { ok: true, exitCode: 0, output: '' }));
  });
  await page.route('**/ops/api/handoff', (r) => {
    state.handoffCalls++;
    return r.fulfill(json(200, { token: 'e2e-one-time-token' }));
  });
  await page.route('**/ops/api/settings', (r) =>
    r.fulfill(json(200, [{
      key: 'gateway.api.base-url', value: state.baseUrl, secret: false,
      group: 'API', label: 'Base URL', type: 'string', editable: true }])));
  await page.route('**/ops/api/settings/gateway.api.base-url', async (r) => {
    state.baseUrl = r.request().postDataJSON().value;
    return r.fulfill(json(200, { key: 'gateway.api.base-url', status: 'SAVED' }));
  });
  await page.route('**/ops/api/settings/audit', (r) => r.fulfill(json(200, [])));
  await page.route('**/ops/api/logs/stream', (r) => r.fulfill({
    status: 200, headers: { 'content-type': 'text/event-stream' }, body: 'data: e2e-log-line\n\n',
  }));

  return state;
}

// OpsPage polls /ops/api/status every 5s — transitions land within one tick.
test.setTimeout(60000);

test('stop-to-edit loop: read-only -> stop -> edit -> start -> auto-reconnect -> new value read-only', async ({ page }) => {
  const state = await mockStack(page);

  // 1. Login: full mode, /settings is read-only (stop-to-edit banner + lock).
  await page.goto('/login');
  await page.getByLabel('Foydalanuvchi').fill('admin');
  await page.getByLabel('Parol').fill('admin');
  await page.getByRole('button', { name: 'Kirish' }).click();
  await expect(page.getByText("Tahrirlash uchun servisni Ops sahifasidan to'xtating")).toBeVisible();
  // The settings module lands on the Source Auth tab — switch to the general list.
  await page.getByRole('tab', { name: 'Sozlamalar' }).first().click();
  const baseUrlInput = page.getByRole('textbox', { name: 'Base URL' });
  await expect(baseUrlInput).toHaveValue('http://old-value');
  await expect(baseUrlInput).toBeDisabled();

  // 2. Ops page: stop with confirmation -> badge flips to O'chiq on the next poll tick.
  await page.getByRole('menu').getByText('Ops', { exact: true }).click();
  await expect(page.getByText('Ishlayapti')).toBeVisible();
  await page.getByRole('button', { name: "To'xtatish" }).click();
  await expect(page.locator('.ant-modal-confirm-title', { hasText: "Gateway to'xtatilsinmi?" })).toBeVisible();
  await page.getByRole('button', { name: "To'xtatish" }).last().click();
  await expect(page.getByText("O'chiq", { exact: true })).toBeVisible({ timeout: 15000 });

  // 3. Settings in edit mode (via ops sidecar): change the value, save.
  await page.getByRole('menu').getByText('Sozlamalar', { exact: true }).click();
  await page.getByRole('tab', { name: 'Sozlamalar' }).first().click();
  // The edit-mode banner renders in every settings tab panel — assert the visible one.
  await expect(page.getByText(/keyingi ishga tushirishda qo'llanadi/).last()).toBeVisible();
  const editInput = page.getByRole('textbox', { name: 'Base URL' });
  await expect(editInput).toBeEnabled();
  await editInput.fill('http://new-value');
  await page.getByRole('button', { name: 'Saqlash' }).click();
  await expect(page.getByText('Saqlandi')).toBeVisible();

  // 4. Ops page: start -> badge Ishlayapti -> avto-ulanish (handoff) restores the gateway
  //    session WITHOUT the login page: the down-banner disappears, nav opens up.
  await page.getByRole('menu').getByText('Ops', { exact: true }).click();
  await page.getByRole('button', { name: 'Ishga tushirish' }).click();
  await expect(page.getByText('Ishlayapti')).toBeVisible({ timeout: 15000 });
  await expect(page.getByRole('alert').filter({ hasText: 'faqat Ops sahifasi ishlaydi' })).toHaveCount(0, { timeout: 15000 });
  expect(page.url()).not.toContain('/login');

  // 5. Settings again: the new value is visible, back in read-only mode.
  await page.getByRole('menu').getByText('Sozlamalar', { exact: true }).click();
  await expect(page.getByText("Tahrirlash uchun servisni Ops sahifasidan to'xtating")).toBeVisible();
  await page.getByRole('tab', { name: 'Sozlamalar' }).first().click();
  const finalInput = page.getByRole('textbox', { name: 'Base URL' });
  await expect(finalInput).toHaveValue('http://new-value');
  await expect(finalInput).toBeDisabled();

  // The reconnect really went through the one-time-token bridge.
  expect(state.handoffCalls).toBeGreaterThanOrEqual(1);
  expect(state.loginTokenCalls).toBeGreaterThanOrEqual(1);
});
