import { ReactNode } from 'react';
import { Layout, Menu, Dropdown, Space, Tooltip } from 'antd';
import { UserOutlined, SettingOutlined, WarningOutlined, BarChartOutlined, FileTextOutlined, DownOutlined } from '@ant-design/icons';
import { useLocation, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/AuthContext';
import { Logo } from './Logo';

const { Header, Sider, Content } = Layout;

export function AppLayout({ children }: { children: ReactNode }) {
  const { t } = useTranslation();
  const { user, logout } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();

  const selectedKey = location.pathname.startsWith('/settings') ? '/settings' : location.pathname;

  const navItems = [
    { key: '/settings', icon: <SettingOutlined />, label: t('nav.settings') },
    { key: '/errors', icon: <WarningOutlined />, label: t('nav.errors'), children: [
        { key: '/errors/dlq', label: t('errors.dlqNav') },
        { key: '/errors/jar', label: t('errors.jarNav') },
        { key: '/errors/docker', label: t('errors.dockerNav') },
    ] },
    { key: 'statistics', icon: <BarChartOutlined />, disabled: true, label: <Tooltip title={t('nav.comingSoon')}>{t('nav.statistics')}</Tooltip> },
    { key: 'logs', icon: <FileTextOutlined />, disabled: true, label: <Tooltip title={t('nav.comingSoon')}>{t('nav.logs')}</Tooltip> },
  ];

  const userMenu = {
    items: [
      { key: 'change-password', label: t('auth.changePassword'), onClick: () => navigate('/settings') },
      { key: 'logout', label: t('auth.logout'), onClick: () => { logout().then(() => navigate('/login')); } },
    ],
  };

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Header style={{ background: '#fff', display: 'flex', alignItems: 'center', justifyContent: 'space-between', paddingInline: 16, borderBottom: '1px solid #E6EDF3' }}>
        <Logo />
        <Dropdown menu={userMenu}>
          <Space style={{ cursor: 'pointer' }}><UserOutlined /> {user} <DownOutlined /></Space>
        </Dropdown>
      </Header>
      <Layout>
        <Sider theme="light" width={220} breakpoint="lg" collapsedWidth={0}>
          <Menu
            mode="inline"
            selectedKeys={[selectedKey]}
            items={navItems}
            onClick={({ key }) => { if (key === '/settings' || key.startsWith('/errors/')) navigate(key); }}
            style={{ height: '100%', borderRight: 0 }}
          />
        </Sider>
        <Content style={{ margin: 24 }}>{children}</Content>
      </Layout>
    </Layout>
  );
}
