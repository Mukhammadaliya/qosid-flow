import { useEffect, useState } from 'react';
import { Card, Form, Input, Switch, Button, Alert, Descriptions, Tooltip, message } from 'antd';
import { useTranslation } from 'react-i18next';
import { getSettings, testTenantMapping, TenantTestResult } from '../../api/settings';
import { opsSettings } from '../../api/opsSettings';
import { tenant as tenantEntries, tenantFromRows } from '../../api/settingsKeys';
import { useAuth } from '../../auth/AuthContext';
import { ModeBanner } from './ModeBanner';

export function TenantMappingPage() {
  const { t } = useTranslation();
  const { gatewayUp, opsUp } = useAuth();
  // Stop-to-edit: writable ONLY while the gateway is down and the ops session is
  // alive (gateway writes all answer 409 stop-required). Same as GeneralSettingsPage.
  const editMode = !gatewayUp && opsUp;

  const [form] = Form.useForm();
  const [loaded, setLoaded] = useState(false);
  const [sample, setSample] = useState('{\n  "company_id": 100,\n  "filial_id": 5\n}');
  const [result, setResult] = useState<TenantTestResult | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);

  useEffect(() => {
    setLoaded(false);
    setLoadError(null);
    (editMode ? opsSettings.getAll() : getSettings())
      .then((rows) => {
        form.setFieldsValue(tenantFromRows(rows));
        setLoaded(true);
      })
      .catch((e: any) => setLoadError(e?.response?.data?.message ?? t('common.loadError')));
  }, [form, editMode]);

  const onSave = async () => {
    setSaving(true);
    try {
      for (const entry of tenantEntries(form.getFieldsValue())) {
        await opsSettings.put(entry.key, entry.value);
      }
      message.success(t('settings.saved'));
    } catch (e: any) {
      const code = e?.response?.data?.error;
      message.error(code === 'service-running'
        ? t('settings.serviceRunning')
        : (e?.response?.data?.message ?? code ?? 'Error'));
    } finally {
      setSaving(false);
    }
  };

  const onTest = async () => {
    setError(null); setResult(null);
    try {
      setResult(await testTenantMapping(JSON.parse(sample)));
    } catch (e: any) {
      setError(e instanceof SyntaxError ? 'Invalid JSON' : (e?.response?.data?.message ?? 'Error'));
    }
  };

  return (
    <Card title={t('settings.tenantMapping')} loading={!loaded && !loadError}>
      {loadError && <Alert style={{ marginBottom: 16 }} type="error" showIcon message={loadError} />}
      <ModeBanner editMode={editMode} />
      <Form form={form} layout="vertical" style={{ maxWidth: 480 }} disabled={!editMode}>
        <Form.Item name="field" label="field-name"><Input /></Form.Item>
        <Form.Item name="extra" label="extra-field-name"><Input /></Form.Item>
        <Form.Item name="def" label="default-rest-tenant"><Input /></Form.Item>
        <Form.Item name="trust" label="trust-extra-tenant-header" valuePropName="checked"><Switch /></Form.Item>
      </Form>
      {editMode && (
        <Button type="primary" onClick={onSave} loading={saving}>{t('settings.save')}</Button>
      )}

      <div style={{ marginTop: 24, maxWidth: 480 }}>
        <Form.Item label={t('tenant.sampleJson')}>
          <Input.TextArea rows={5} value={sample} onChange={(e) => setSample(e.target.value)} />
        </Form.Item>
        {/* Test resolves the sample THROUGH the gateway's admin API — needs it running. */}
        <Tooltip title={editMode ? t('settings.testNeedsService') : undefined}>
          <Button onClick={onTest} disabled={editMode}>{t('settings.test')}</Button>
        </Tooltip>
        {error && <Alert style={{ marginTop: 12 }} type="error" message={error} />}
        {result && (
          <Descriptions style={{ marginTop: 12 }} column={1} bordered size="small">
            <Descriptions.Item label={t('tenant.resolvedTenant')}>{String(result.tenantId)}</Descriptions.Item>
            <Descriptions.Item label={t('tenant.resolvedExtra')}>{String(result.extraTenantId)}</Descriptions.Item>
          </Descriptions>
        )}
      </div>
    </Card>
  );
}
