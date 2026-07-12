import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { RequireAuth } from './RequireAuth';
import { AuthContext } from './AuthContext';

function renderWithUser(user: string | null) {
  return render(
    <AuthContext.Provider value={{ user, loading: false, login: async () => {}, logout: async () => {}, refresh: async () => {} }}>
      <MemoryRouter initialEntries={['/secret']}>
        <Routes>
          <Route path="/login" element={<div>login-page</div>} />
          <Route path="/secret" element={<RequireAuth><div>secret-page</div></RequireAuth>} />
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

describe('RequireAuth', () => {
  it('renders children when authenticated', () => {
    renderWithUser('admin');
    expect(screen.getByText('secret-page')).toBeInTheDocument();
  });
  it('redirects to /login when not authenticated', () => {
    renderWithUser(null);
    expect(screen.getByText('login-page')).toBeInTheDocument();
  });
});
