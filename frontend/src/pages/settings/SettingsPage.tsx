import { Tabs } from 'antd';
import { useTranslation } from 'react-i18next';
import { SourceAuthPage } from './SourceAuthPage';
import { TenantMappingPage } from './TenantMappingPage';
import { GeneralSettingsPage } from './GeneralSettingsPage';
import { AiConfigPage } from './AiConfigPage';
import { TelegramConfigPage } from './TelegramConfigPage';

export function SettingsPage() {
  const { t } = useTranslation();
  return (
    <Tabs
      defaultActiveKey="source-auth"
      items={[
        { key: 'source-auth', label: t('settings.sourceAuth'), children: <SourceAuthPage /> },
        { key: 'tenant', label: t('settings.tenantMapping'), children: <TenantMappingPage /> },
        { key: 'advanced', label: t('settings.general'), children: <GeneralSettingsPage /> },
        { key: 'ai', label: t('ai.settingsTitle'), children: <AiConfigPage /> },
        { key: 'telegram', label: t('telegram.settingsTitle'), children: <TelegramConfigPage /> },
      ]}
    />
  );
}
