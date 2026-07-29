import { ReactNode, Suspense, lazy } from 'react';
import { ConfigProvider, Spin } from 'antd';
import { BrowserRouter, Routes, Route, Navigate, useLocation } from 'react-router-dom';
import { qosidTheme } from './theme';
import { AuthProvider, useAuth } from './auth/AuthContext';
import { RequireAuth } from './auth/RequireAuth';
import { LoginPage } from './auth/LoginPage';
import { AppLayout } from './layout/AppLayout';
import { ChangePasswordPage } from './pages/ChangePasswordPage';

// Route-level code splitting. Everything above is on the critical path (login, chrome, and the
// forced password-change form) and stays in the entry chunk; everything below is fetched when
// its route is first opened. Statistics is the reason this exists: @ant-design/plots and its
// AntV runtime are by far the heaviest dependency in the app and only that one page uses them —
// before the split every operator downloaded the whole charting stack just to open Settings.
const SettingsPage = lazy(() => import('./pages/settings/SettingsPage').then(m => ({ default: m.SettingsPage })));
const DlqPage = lazy(() => import('./pages/errors/DlqPage').then(m => ({ default: m.DlqPage })));
const JarPage = lazy(() => import('./pages/errors/JarPage').then(m => ({ default: m.JarPage })));
const DockerPage = lazy(() => import('./pages/errors/DockerPage').then(m => ({ default: m.DockerPage })));
const StatisticsPage = lazy(() => import('./pages/stats/StatisticsPage').then(m => ({ default: m.StatisticsPage })));
const OpsPage = lazy(() => import('./pages/ops/OpsPage').then(m => ({ default: m.OpsPage })));

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
    <ConfigProvider theme={qosidTheme}>
      <BrowserRouter>
        <AuthProvider>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/*" element={
              <RequireAuth>
                <AppLayout>
                  <ForcePasswordChange>
                    {/* Same spinner RequireAuth uses while it probes the session, so a chunk
                        fetch and an auth probe look identical to the operator. */}
                    <Suspense fallback={<Spin style={{ display: 'block', marginTop: '20vh' }} />}>
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
                    </Suspense>
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
