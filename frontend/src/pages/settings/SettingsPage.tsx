import { Tabs } from 'antd';
import { useTranslation } from 'react-i18next';
import { SourceAuthPage } from './SourceAuthPage';
import { TenantMappingPage } from './TenantMappingPage';
import { AdvancedSettingsPage } from './AdvancedSettingsPage';

export function SettingsPage() {
  const { t } = useTranslation();
  return (
    <Tabs
      defaultActiveKey="source-auth"
      items={[
        { key: 'source-auth', label: t('settings.sourceAuth'), children: <SourceAuthPage /> },
        { key: 'tenant', label: t('settings.tenantMapping'), children: <TenantMappingPage /> },
        { key: 'advanced', label: t('settings.advanced'), children: <AdvancedSettingsPage /> },
      ]}
    />
  );
}
