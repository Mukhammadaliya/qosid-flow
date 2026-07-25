import { ReactNode, useState } from 'react';
import { Layout, Menu, Dropdown, Space, Tooltip, Button, Alert } from 'antd';
import { UserOutlined, SettingOutlined, WarningOutlined, BarChartOutlined, ControlOutlined, DownOutlined, MenuFoldOutlined, MenuUnfoldOutlined } from '@ant-design/icons';
import { useLocation, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/AuthContext';
import { Logo } from './Logo';

const { Header, Sider, Content } = Layout;

export function AppLayout({ children }: { children: ReactNode }) {
  const { t } = useTranslation();
  const { user, logout, gatewayUp, opsUp, gatewayAuthExpired } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  const [collapsed, setCollapsed] = useState(false);

  const selectedKey = location.pathname.startsWith('/settings') ? '/settings' : location.pathname;

  const gwDisabled = !gatewayUp;
  const gwLabel = (label: string) =>
    gwDisabled ? <Tooltip title={t('auth.gatewayDown')}>{label}</Tooltip> : label;

  // Settings stays usable through the ops sidecar while the gateway is stopped
  // (strict stop-to-edit model); it is disabled only when BOTH backends are down.
  const settingsDisabled = !gatewayUp && !opsUp;

  const navItems = [
    { key: '/settings', icon: <SettingOutlined />, disabled: settingsDisabled,
      label: settingsDisabled ? <Tooltip title={t('auth.gatewayDown')}>{t('nav.settings')}</Tooltip> : t('nav.settings') },
    { key: '/errors', icon: <WarningOutlined />, disabled: gwDisabled, label: gwLabel(t('nav.errors')), children: [
        { key: '/errors/dlq', label: t('errors.dlqNav') },
        { key: '/errors/jar', label: t('errors.jarNav') },
        { key: '/errors/docker', label: t('errors.dockerNav') },
    ] },
    { key: '/statistics', icon: <BarChartOutlined />, disabled: gwDisabled, label: gwLabel(t('nav.statistics')) },
    { key: '/ops', icon: <ControlOutlined />, disabled: !opsUp, label: t('nav.ops') },
  ];

  const userMenu = {
    items: [
      { key: 'change-password', label: t('auth.changePassword'), onClick: () => navigate('/change-password') },
      { key: 'logout', label: t('auth.logout'), onClick: () => { logout().then(() => navigate('/login')); } },
    ],
  };

  return (
    <Layout style={{ height: '100vh' }}>
      <Header style={{ background: '#fff', display: 'flex', alignItems: 'center', justifyContent: 'space-between', paddingInline: 16, borderBottom: '1px solid #E6EDF3' }}>
        <Space>
          <Button
            type="text"
            aria-label={t('nav.toggleMenu')}
            icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
            onClick={() => setCollapsed((c) => !c)}
          />
          <Logo />
        </Space>
        <Dropdown menu={userMenu}>
          <Space style={{ cursor: 'pointer' }}><UserOutlined /> {user} <DownOutlined /></Space>
        </Dropdown>
      </Header>
      {!gatewayUp && gatewayAuthExpired && (
        <Alert
          banner
          type="warning"
          showIcon
          message={
            <Space>
              {t('auth.gatewaySessionExpired')}
              <Button type="link" size="small" onClick={() => navigate('/login')}>{t('auth.login')}</Button>
            </Space>
          }
        />
      )}
      {!gatewayUp && !gatewayAuthExpired && <Alert banner type="warning" showIcon message={t('auth.gatewayDown')} />}
      <Layout>
        <Sider
          theme="light"
          width={220}
          breakpoint="lg"
          collapsedWidth={0}
          collapsible
          trigger={null}
          collapsed={collapsed}
          onCollapse={setCollapsed}
        >
          <Menu
            mode="inline"
            selectedKeys={[selectedKey]}
            items={navItems}
            onClick={({ key }) => { if (key === '/settings' || key === '/statistics' || key === '/ops' || key.startsWith('/errors/')) navigate(key); }}
            style={{ height: '100%', borderRight: 0 }}
          />
        </Sider>
        <Content style={{ padding: 24, overflow: 'auto' }}>{children}</Content>
      </Layout>
    </Layout>
  );
}
