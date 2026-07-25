import { ReactNode } from 'react';
import { ConfigProvider } from 'antd';
import { BrowserRouter, Routes, Route, Navigate, useLocation } from 'react-router-dom';
import { smartupTheme } from './theme';
import { AuthProvider, useAuth } from './auth/AuthContext';
import { RequireAuth } from './auth/RequireAuth';
import { LoginPage } from './auth/LoginPage';
import { AppLayout } from './layout/AppLayout';
import { SettingsPage } from './pages/settings/SettingsPage';
import { DlqPage } from './pages/errors/DlqPage';
import { JarPage } from './pages/errors/JarPage';
import { DockerPage } from './pages/errors/DockerPage';
import { StatisticsPage } from './pages/stats/StatisticsPage';
import { OpsPage } from './pages/ops/OpsPage';
import { ChangePasswordPage } from './pages/ChangePasswordPage';

function HomeRedirect() {
  const { gatewayUp } = useAuth();
  return <Navigate to={gatewayUp ? '/settings' : '/ops'} replace />;
}

/**
 * While the seeded admin/admin password is in use the gateway 403s every admin route but
 * change-password, so keep the user on that form. Cosmetic only — the real gate is server-side.
 */
function ForcePasswordChange({ children }: { children: ReactNode }) {
  const { passwordChangeRequired } = useAuth();
  const { pathname } = useLocation();
  if (passwordChangeRequired && pathname !== '/change-password') {
    return <Navigate to="/change-password" replace />;
  }
  return <>{children}</>;
}

export default function App() {
  return (
    <ConfigProvider theme={smartupTheme}>
      <BrowserRouter>
        <AuthProvider>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/*" element={
              <RequireAuth>
                <AppLayout>
                  <ForcePasswordChange>
                    <Routes>
                      <Route index element={<HomeRedirect />} />
                      <Route path="settings/*" element={<SettingsPage />} />
                      <Route path="errors/dlq" element={<DlqPage />} />
                      <Route path="errors/jar" element={<JarPage />} />
                      <Route path="errors/docker" element={<DockerPage />} />
                      <Route path="statistics" element={<StatisticsPage />} />
                      <Route path="ops" element={<OpsPage />} />
                      <Route path="change-password" element={<ChangePasswordPage />} />
                      <Route path="*" element={<HomeRedirect />} />
                    </Routes>
                  </ForcePasswordChange>
                </AppLayout>
              </RequireAuth>
            } />
          </Routes>
        </AuthProvider>
      </BrowserRouter>
    </ConfigProvider>
  );
}
