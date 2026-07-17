import { Alert, Button, Space } from 'antd';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';

/**
 * Stop-to-edit mode banner shared by the typed settings pages (Source Auth /
 * Tenant / AI) — same contract as GeneralSettingsPage: while the gateway is up
 * the form is read-only (all gateway writes answer 409 stop-required) and the
 * banner points the operator at the Ops page; in edit mode (gateway down + ops
 * session) it notes that changes apply on the next start.
 */
export function ModeBanner({ editMode }: { editMode: boolean }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  return editMode ? (
    <Alert style={{ marginBottom: 16 }} type="info" showIcon message={t('settings.appliesOnStart')} />
  ) : (
    <Alert
      style={{ marginBottom: 16 }}
      type="warning"
      showIcon
      message={
        <Space>
          {t('settings.stopToEdit')}
          <Button type="link" size="small" onClick={() => navigate('/ops')}>{t('nav.ops')}</Button>
        </Space>
      }
    />
  );
}
