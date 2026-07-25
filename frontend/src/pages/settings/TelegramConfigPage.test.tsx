import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import i18n from '../../i18n';
import { TelegramConfigPage } from './TelegramConfigPage';
import * as telegramApi from '../../api/telegram';
import { opsSettings } from '../../api/opsSettings';
import { AuthContext } from '../../auth/AuthContext';

beforeEach(() => vi.restoreAllMocks());

const opsRows = [
  { key: 'gateway.telegram.enabled', value: 'true', secret: false, group: 'Boshqa', label: 'gateway.telegram.enabled', type: 'text', editable: true },
  { key: 'gateway.telegram.bot-token', value: '***', secret: true, group: 'Boshqa', label: 'gateway.telegram.bot-token', type: 'text', editable: true },
  { key: 'gateway.telegram.chat-id', value: '-100200', secret: false, group: 'Boshqa', label: 'gateway.telegram.chat-id', type: 'text', editable: true },
];

function renderPage(overrides: Partial<{ gatewayUp: boolean; opsUp: boolean }> = {}) {
  const value = {
    user: 'admin', loading: false, gatewayUp: true, opsUp: true, gatewayAuthExpired: false,
    login: async () => {}, logout: async () => {}, refresh: async () => {}, ...overrides,
  };
  return render(
    <AuthContext.Provider value={value as never}>
      <MemoryRouter>
        <TelegramConfigPage />
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

describe('TelegramConfigPage — service UP (read-only mode)', () => {
  it('loads via the gateway, disables inputs, test button enabled', async () => {
    const get = vi.spyOn(telegramApi, 'getTelegramConfig').mockResolvedValue(
      { enabled: 'true', botToken: '***', chatId: '-100200', messageThreadId: '' });
    const opsGet = vi.spyOn(opsSettings, 'getAll').mockResolvedValue([] as never);
    renderPage({ gatewayUp: true, opsUp: true });
    await waitFor(() => expect(screen.getByDisplayValue('-100200')).toBeInTheDocument());
    expect(get).toHaveBeenCalled();
    expect(opsGet).not.toHaveBeenCalled();
    expect(screen.getByDisplayValue('-100200')).toBeDisabled();
    expect(screen.queryByRole('button', { name: 'Saqlash' })).toBeNull();
    expect(screen.getByRole('button', { name: i18n.t('telegram.test') })).toBeEnabled();
  });

  it('shows the test result', async () => {
    vi.spyOn(telegramApi, 'getTelegramConfig').mockResolvedValue(
      { enabled: '', botToken: '', chatId: '', messageThreadId: '' });
    vi.spyOn(telegramApi, 'testTelegram').mockResolvedValue({ ok: false, error: 'telegram-disabled' });
    renderPage({ gatewayUp: true, opsUp: true });
    await waitFor(() => expect(screen.getByRole('button', { name: i18n.t('telegram.test') })).toBeEnabled());
    await userEvent.click(screen.getByRole('button', { name: i18n.t('telegram.test') }));
    // The page renders multiple antd Alerts (ModeBanner + hint + result), each role="alert",
    // so assert the result text lives inside one of them (deviation from brief's getByRole).
    await waitFor(() => expect(
      screen.getAllByRole('alert').some((a) => a.textContent?.includes('telegram-disabled')),
    ).toBe(true));
  });
});

describe('TelegramConfigPage — service DOWN + ops session (edit mode)', () => {
  it('loads from ops rows and saves without the untouched token', async () => {
    vi.spyOn(opsSettings, 'getAll').mockResolvedValue(opsRows as never);
    const put = vi.spyOn(opsSettings, 'put').mockResolvedValue(undefined as never);
    renderPage({ gatewayUp: false, opsUp: true });
    await waitFor(() => expect(screen.getByDisplayValue('-100200')).toBeInTheDocument());
    await userEvent.click(screen.getByRole('button', { name: 'Saqlash' }));
    await waitFor(() => expect(put).toHaveBeenCalledWith('gateway.telegram.enabled', 'true'));
    expect(put).toHaveBeenCalledWith('gateway.telegram.chat-id', '-100200');
    expect(put).toHaveBeenCalledWith('gateway.telegram.message-thread-id', '');
    expect(put).toHaveBeenCalledTimes(3); // token bo'sh -> keep-existing, PUT yo'q
  });

  it('sends the token too when a new one is typed', async () => {
    vi.spyOn(opsSettings, 'getAll').mockResolvedValue(opsRows as never);
    const put = vi.spyOn(opsSettings, 'put').mockResolvedValue(undefined as never);
    renderPage({ gatewayUp: false, opsUp: true });
    await waitFor(() => expect(screen.getByDisplayValue('-100200')).toBeInTheDocument());
    await userEvent.type(screen.getByLabelText(i18n.t('telegram.botToken')), '123:abc');
    await userEvent.click(screen.getByRole('button', { name: 'Saqlash' }));
    await waitFor(() => expect(put).toHaveBeenCalledWith('gateway.telegram.bot-token', '123:abc'));
    expect(put).toHaveBeenCalledTimes(4);
  });

  it('disables the Test button (test is gateway-side)', async () => {
    vi.spyOn(opsSettings, 'getAll').mockResolvedValue(opsRows as never);
    renderPage({ gatewayUp: false, opsUp: true });
    await waitFor(() => expect(screen.getByDisplayValue('-100200')).toBeInTheDocument());
    expect(screen.getByRole('button', { name: i18n.t('telegram.test') })).toBeDisabled();
  });
});
