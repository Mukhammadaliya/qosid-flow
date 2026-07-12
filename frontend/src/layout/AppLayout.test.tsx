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

  it('shows future nav items (Errors) as disabled', () => {
    renderLayout();
    const errors = screen.getByText('Xatolar').closest('.ant-menu-item');
    expect(errors?.className).toContain('ant-menu-item-disabled');
  });

  it('shows the logged-in username', () => {
    renderLayout();
    expect(screen.getByText('admin')).toBeInTheDocument();
  });
});
