import { useEffect, useState } from 'react';
import { Form, Select, Input, Button, Alert, Space, Card, Tooltip, message } from 'antd';
import { useTranslation } from 'react-i18next';
import { getSourceAuth, testSourceAuth, SourceAuth, TestResult } from '../../api/settings';
import { opsSettings } from '../../api/opsSettings';
import { sourceAuth as sourceAuthEntries, sourceAuthFromRows } from '../../api/settingsKeys';
import { useAuth } from '../../auth/AuthContext';
import { ModeBanner } from './ModeBanner';

export function SourceAuthPage() {
  const { t } = useTranslation();
  const { gatewayUp, opsUp } = useAuth();
  // Stop-to-edit: writable ONLY while the gateway is down and the ops session is
  // alive (gateway writes all answer 409 stop-required). Same as GeneralSettingsPage.
  const editMode = !gatewayUp && opsUp;

  const [form] = Form.useForm();
  const [type, setType] = useState('basic');
  const [loaded, setLoaded] = useState(false);
  const [testResult, setTestResult] = useState<TestResult | null>(null);
  const [saving, setSaving] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);

  useEffect(() => {
    setLoaded(false);
    setLoadError(null);
    const load: Promise<SourceAuth | ReturnType<typeof sourceAuthFromRows>> = editMode
      ? opsSettings.getAll().then(sourceAuthFromRows)
      : getSourceAuth();
    load
      .then((d) => {
        setType(d.type);
        form.setFieldsValue(d);
        setLoaded(true);
      })
      .catch((e: any) => setLoadError(e?.response?.data?.message ?? t('common.loadError')));
  }, [form, editMode]);

  const onSave = async () => {
    setSaving(true);
    try {
      // Only the selected type's fields go out; blank/masked secrets are omitted
      // (keep-existing) by the mapping itself.
      for (const entry of sourceAuthEntries({ ...form.getFieldsValue(), type })) {
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
    try {
      setTestResult(await testSourceAuth());
    } catch (e: any) {
      // Network drop / non-2xx must not reject unhandled — surface it in the result Alert.
      setTestResult({ ok: false, message: e?.response?.data?.message ?? t('common.loadError') });
    }
  };

  return (
    <Card title={t('settings.sourceAuth')} loading={!loaded && !loadError}>
      {loadError && <Alert style={{ marginBottom: 16 }} type="error" showIcon message={loadError} />}
      <ModeBanner editMode={editMode} />
      <Form form={form} layout="vertical" style={{ maxWidth: 480 }} disabled={!editMode}>
        <Form.Item label={t('settings.type')}>
          <Select
            value={type}
            onChange={setType}
            options={[{ value: 'basic' }, { value: 'apikey' }, { value: 'oauth2' }].map(o => ({ value: o.value, label: o.value, title: o.value }))}
          />
        </Form.Item>

        {type === 'basic' && <>
          <Form.Item name="basicUsername" label={t('sourceAuth.basicUsername')}><Input /></Form.Item>
          <Form.Item name="basicPassword" label={t('sourceAuth.basicPassword')}>
            <Input.Password placeholder={t('common.secretPlaceholder')} />
          </Form.Item>
        </>}

        {type === 'apikey' && <>
          <Form.Item name="apikeyHeader" label={t('sourceAuth.apikeyHeader')}><Input /></Form.Item>
          <Form.Item name="apikeyValue" label={t('sourceAuth.apikeyValue')}>
            <Input.Password placeholder={t('common.secretPlaceholder')} />
          </Form.Item>
        </>}

        {type === 'oauth2' && <>
          <Form.Item name="oauth2TokenUrl" label={t('sourceAuth.oauth2TokenUrl')}><Input /></Form.Item>
          <Form.Item name="oauth2ClientId" label={t('sourceAuth.oauth2ClientId')}><Input /></Form.Item>
          <Form.Item name="oauth2ClientSecret" label={t('sourceAuth.oauth2ClientSecret')}>
            <Input.Password placeholder={t('common.secretPlaceholder')} />
          </Form.Item>
          <Form.Item name="oauth2Scope" label={t('sourceAuth.oauth2Scope')}><Input /></Form.Item>
          <Form.Item name="oauth2CacheTtlSeconds" label={t('sourceAuth.oauth2CacheTtl')}><Input /></Form.Item>
        </>}
      </Form>
      <Space>
        {editMode && (
          <Button type="primary" onClick={onSave} loading={saving}>{t('settings.save')}</Button>
        )}
        {/* Test fires a real outbound call THROUGH the gateway — needs it running. */}
        <Tooltip title={editMode ? t('settings.testNeedsService') : undefined}>
          <Button onClick={onTest} disabled={editMode}>{t('settings.test')}</Button>
        </Tooltip>
      </Space>

      {testResult && (
        <Alert
          style={{ marginTop: 16 }}
          type={testResult.ok ? 'success' : 'error'}
          message={testResult.message}
        />
      )}
    </Card>
  );
}
