import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import '../i18n';
import { AppLayout } from './AppLayout';
import { AuthContext } from '../auth/AuthContext';

function renderLayout() {
  return render(
    <AuthContext.Provider value={{ user: 'admin', loading: false, login: async () => {}, logout: async () => {}, refresh: async () => {} }}>
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

  it('shows future nav items (Logs) as disabled', () => {
    renderLayout();
    const logs = screen.getByText('Loglar').closest('.ant-menu-item');
    expect(logs?.className).toContain('ant-menu-item-disabled');
  });

  it('shows the logged-in username', () => {
    renderLayout();
    expect(screen.getByText('admin')).toBeInTheDocument();
  });
});
