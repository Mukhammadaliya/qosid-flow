import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import '../i18n';
import { AppLayout } from './AppLayout';
import { AuthContext } from '../auth/AuthContext';

function renderLayout(overrides: Partial<{ gatewayUp: boolean; opsUp: boolean; gatewayAuthExpired: boolean }> = {}) {
  return render(
    <AuthContext.Provider value={{ user: 'admin', loading: false, gatewayUp: true, opsUp: true, gatewayAuthExpired: false, passwordChangeRequired: false, ...overrides, login: async () => {}, logout: async () => {}, refresh: async () => {}, attemptHandoff: async () => {} }}>
      <MemoryRouter initialEntries={['/settings']}>
        <AppLayout><div>content-here</div></AppLayout>
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

describe('AppLayout', () => {
  it('renders the Settings nav item as enabled', () => {
    renderLayout();
    expect(screen.getByText('Sozlamalar')).toBeInTheDocument();
  });

  it('renders the Errors nav as an enabled submenu', () => {
    renderLayout();
    const errors = screen.getByText('Xatolar').closest('.ant-menu-submenu');
    expect(errors).not.toBeNull();
    expect(errors?.className).not.toContain('ant-menu-submenu-disabled');
  });

  it('renders the Statistics nav item as enabled (D3)', () => {
    renderLayout();
    const statistics = screen.getByText('Statistika').closest('.ant-menu-item');
    expect(statistics).not.toBeNull();
    expect(statistics?.className).not.toContain('ant-menu-item-disabled');
  });

  it('shows the logged-in username', () => {
    renderLayout();
    expect(screen.getByText('admin')).toBeInTheDocument();
  });

  it('keeps Settings, Errors and Statistics usable through ops while the gateway is down', () => {
    renderLayout({ gatewayUp: false, opsUp: true });
    // Settings edits go through the ops API (strict stop-to-edit). Errors and Statistics read
    // Loki and Prometheus, which outlive the gateway and are proxied by the sidecar too — a
    // stopped gateway is when an operator most needs to read them, so they must NOT go dark.
    for (const label of ['Sozlamalar', 'Xatolar', 'Statistika']) {
      const item = screen.getByText(label).closest('[role="menuitem"]');
      expect(item?.getAttribute('aria-disabled'), label).not.toBe('true');
    }
    expect(screen.getByText(/Gateway to'xtatilgan/)).toBeInTheDocument();
  });

  it('disables Settings, Errors and Statistics only when both gateway and ops are down', () => {
    renderLayout({ gatewayUp: false, opsUp: false });
    for (const label of ['Sozlamalar', 'Xatolar', 'Statistika']) {
      const item = screen.getByText(label).closest('[role="menuitem"]');
      expect(item?.getAttribute('aria-disabled'), label).toBe('true');
    }
  });

  it('FIX3 regression: shows the expired-session banner + re-login link when gateway session expired', () => {
    renderLayout({ gatewayUp: false, opsUp: true, gatewayAuthExpired: true });
    // The expired-session variant replaces the generic gatewayDown banner.
    expect(screen.queryByText('Gateway o\'chiq — faqat Ops sahifasi ishlaydi')).not.toBeInTheDocument();
    expect(screen.getByText(/Gateway sessiyasi tugagan/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Kirish' })).toBeInTheDocument();
  });

  it('shows enabled Ops nav item when ops is up', () => {
    renderLayout({ gatewayUp: true, opsUp: true });
    const ops = screen.getByText('Ops').closest('[role="menuitem"]');
    expect(ops).not.toBeNull();
    expect(ops?.getAttribute('aria-disabled')).not.toBe('true');
  });
});
