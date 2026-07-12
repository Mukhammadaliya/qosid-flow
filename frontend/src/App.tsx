import { ConfigProvider } from 'antd';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { smartupTheme } from './theme';
import { AuthProvider } from './auth/AuthContext';
import { RequireAuth } from './auth/RequireAuth';
import { LoginPage } from './auth/LoginPage';
import { AppLayout } from './layout/AppLayout';
import { SettingsPage } from './pages/settings/SettingsPage';
import { DlqPage } from './pages/errors/DlqPage';
import { JarPage } from './pages/errors/JarPage';
import { DockerPage } from './pages/errors/DockerPage';
import { StatisticsPage } from './pages/stats/StatisticsPage';

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
                  <Routes>
                    <Route index element={<Navigate to="/settings" replace />} />
                    <Route path="settings/*" element={<SettingsPage />} />
                    <Route path="errors/dlq" element={<DlqPage />} />
                    <Route path="errors/jar" element={<JarPage />} />
                    <Route path="errors/docker" element={<DockerPage />} />
                    <Route path="statistics" element={<StatisticsPage />} />
                    <Route path="*" element={<Navigate to="/settings" replace />} />
                  </Routes>
                </AppLayout>
              </RequireAuth>
            } />
          </Routes>
        </AuthProvider>
      </BrowserRouter>
    </ConfigProvider>
  );
}
