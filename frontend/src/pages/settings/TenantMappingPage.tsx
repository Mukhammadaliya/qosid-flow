import { useEffect, useState } from 'react';
import { Card, Form, Input, Switch, Button, Alert, Descriptions, message } from 'antd';
import { useTranslation } from 'react-i18next';
import { getSettings, putSetting, testTenantMapping, TenantTestResult } from '../../api/settings';

const KEYS = {
  field: 'gateway.tenant.field-name',
  extra: 'gateway.tenant.extra-field-name',
  def: 'gateway.tenant.default-rest-tenant',
  trust: 'gateway.tenant.trust-extra-tenant-header',
};

export function TenantMappingPage() {
  const { t } = useTranslation();
  const [form] = Form.useForm();
  const [loaded, setLoaded] = useState(false);
  const [sample, setSample] = useState('{\n  "company_id": 100,\n  "filial_id": 5\n}');
  const [result, setResult] = useState<TenantTestResult | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);

  useEffect(() => {
    getSettings()
      .then((all) => {
        const byKey = Object.fromEntries(all.map((r) => [r.key, r.value]));
        form.setFieldsValue({
          field: byKey[KEYS.field] ?? 'company_id',
          extra: byKey[KEYS.extra] ?? 'filial_id',
          def: byKey[KEYS.def] ?? '0',
          trust: (byKey[KEYS.trust] ?? 'false') === 'true',
        });
        setLoaded(true);
      })
      .catch((e: any) => setLoadError(e?.response?.data?.message ?? t('common.loadError')));
  }, [form]);

  const onSave = async () => {
    const v = form.getFieldsValue();
    setSaving(true);
    try {
      await putSetting(KEYS.field, v.field, false);
      await putSetting(KEYS.extra, v.extra, false);
      await putSetting(KEYS.def, v.def, false);
      await putSetting(KEYS.trust, String(v.trust), false);
      message.success(t('settings.saved'));
    } catch (e: any) {
      message.error(e?.response?.data?.message ?? 'Error');
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
      <Form form={form} layout="vertical" style={{ maxWidth: 480 }}>
        <Form.Item name="field" label="field-name"><Input /></Form.Item>
        <Form.Item name="extra" label="extra-field-name"><Input /></Form.Item>
        <Form.Item name="def" label="default-rest-tenant"><Input /></Form.Item>
        <Form.Item name="trust" label="trust-extra-tenant-header" valuePropName="checked"><Switch /></Form.Item>
        <Button type="primary" onClick={onSave} loading={saving}>{t('settings.save')}</Button>
      </Form>

      <div style={{ marginTop: 24, maxWidth: 480 }}>
        <Form.Item label={t('tenant.sampleJson')}>
          <Input.TextArea rows={5} value={sample} onChange={(e) => setSample(e.target.value)} />
        </Form.Item>
        <Button onClick={onTest}>{t('settings.test')}</Button>
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
