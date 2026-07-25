import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import i18n from '../i18n';
import { ChangePasswordPage } from './ChangePasswordPage';
import * as adminAuth from '../api/adminAuth';
import { AuthContext } from '../auth/AuthContext';

beforeEach(() => vi.restoreAllMocks());

const NEW_PASSWORD = 'Str0ng-Passw0rd';

function renderPage(overrides: Partial<{ passwordChangeRequired: boolean }> = {}) {
  const logout = vi.fn().mockResolvedValue(undefined);
  const value = {
    user: 'admin', loading: false, gatewayUp: true, opsUp: true, gatewayAuthExpired: false,
    passwordChangeRequired: false,
    login: async () => {}, logout, refresh: async () => {}, attemptHandoff: async () => {},
    ...overrides,
  };
  render(
    <AuthContext.Provider value={value as never}>
      <MemoryRouter>
        <ChangePasswordPage />
      </MemoryRouter>
    </AuthContext.Provider>,
  );
  return { logout };
}

describe('ChangePasswordPage', () => {
  it('renders the old/new/confirm fields and the submit button', () => {
    renderPage();
    expect(screen.getByLabelText(i18n.t('auth.oldPassword'))).toBeInTheDocument();
    expect(screen.getByLabelText(i18n.t('auth.newPassword'))).toBeInTheDocument();
    expect(screen.getByLabelText(i18n.t('auth.confirmPassword'))).toBeInTheDocument();
    expect(screen.getByRole('button', { name: i18n.t('auth.changePassword') })).toBeInTheDocument();
  });

  it('refuses to submit when the confirmation does not match', async () => {
    const change = vi.spyOn(adminAuth, 'changePassword').mockResolvedValue(undefined);
    renderPage();
    await userEvent.type(screen.getByLabelText(i18n.t('auth.oldPassword')), 'admin');
    await userEvent.type(screen.getByLabelText(i18n.t('auth.newPassword')), NEW_PASSWORD);
    await userEvent.type(screen.getByLabelText(i18n.t('auth.confirmPassword')), 'something-else');
    await userEvent.click(screen.getByRole('button', { name: i18n.t('auth.changePassword') }));
    await waitFor(() => expect(screen.getByText(i18n.t('auth.passwordMismatch'))).toBeInTheDocument());
    expect(change).not.toHaveBeenCalled();
  });

  it('rejects a new password shorter than 8 characters without calling the API', async () => {
    const change = vi.spyOn(adminAuth, 'changePassword').mockResolvedValue(undefined);
    renderPage();
    await userEvent.type(screen.getByLabelText(i18n.t('auth.oldPassword')), 'admin');
    await userEvent.type(screen.getByLabelText(i18n.t('auth.newPassword')), 'short');
    await userEvent.type(screen.getByLabelText(i18n.t('auth.confirmPassword')), 'short');
    await userEvent.click(screen.getByRole('button', { name: i18n.t('auth.changePassword') }));
    await waitFor(() => expect(screen.getByText(i18n.t('auth.passwordTooShort'))).toBeInTheDocument());
    expect(change).not.toHaveBeenCalled();
  });

  it('calls the API and logs out on success (the backend kills the session on purpose)', async () => {
    const change = vi.spyOn(adminAuth, 'changePassword').mockResolvedValue(undefined);
    const { logout } = renderPage();
    await userEvent.type(screen.getByLabelText(i18n.t('auth.oldPassword')), 'admin');
    await userEvent.type(screen.getByLabelText(i18n.t('auth.newPassword')), NEW_PASSWORD);
    await userEvent.type(screen.getByLabelText(i18n.t('auth.confirmPassword')), NEW_PASSWORD);
    await userEvent.click(screen.getByRole('button', { name: i18n.t('auth.changePassword') }));
    await waitFor(() => expect(change).toHaveBeenCalledWith('admin', NEW_PASSWORD));
    await waitFor(() => expect(logout).toHaveBeenCalled());
  });

  it('shows the mandatory-change notice while the default password is still in use', () => {
    renderPage({ passwordChangeRequired: true });
    expect(screen.getByRole('alert')).toHaveTextContent(i18n.t('auth.passwordChangeRequired'));
  });

  it('shows no mandatory notice for a voluntary change', () => {
    renderPage();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('surfaces a backend rejection instead of pretending it worked', async () => {
    vi.spyOn(adminAuth, 'changePassword').mockRejectedValue({
      response: { status: 400, data: { message: 'Current password is incorrect' } },
    });
    const { logout } = renderPage();
    await userEvent.type(screen.getByLabelText(i18n.t('auth.oldPassword')), 'wrong');
    await userEvent.type(screen.getByLabelText(i18n.t('auth.newPassword')), NEW_PASSWORD);
    await userEvent.type(screen.getByLabelText(i18n.t('auth.confirmPassword')), NEW_PASSWORD);
    await userEvent.click(screen.getByRole('button', { name: i18n.t('auth.changePassword') }));
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Current password is incorrect'));
    expect(logout).not.toHaveBeenCalled();
  });
});
