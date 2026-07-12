import { useEffect, useState } from 'react';
import { Form, Select, Input, Button, Alert, Space, Card, message } from 'antd';
import { useTranslation } from 'react-i18next';
import { getSourceAuth, putSourceAuth, testSourceAuth, SourceAuth, TestResult } from '../../api/settings';

const MASK = '***';

export function SourceAuthPage() {
  const { t } = useTranslation();
  const [form] = Form.useForm();
  const [type, setType] = useState('basic');
  const [loaded, setLoaded] = useState<SourceAuth | null>(null);
  const [testResult, setTestResult] = useState<TestResult | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    getSourceAuth().then((d) => {
      setLoaded(d); setType(d.type);
      form.setFieldsValue(d);
    });
  }, [form]);

  const onSave = async () => {
    const v = form.getFieldsValue();
    setSaving(true);
    try {
      const body: Partial<SourceAuth> = { type };
      if (type === 'basic') {
        body.basicUsername = v.basicUsername;
        // Only send the password if the admin typed a new one (masked value means unchanged).
        if (v.basicPassword && v.basicPassword !== MASK) body.basicPassword = v.basicPassword;
      } else if (type === 'apikey') {
        body.apikeyHeader = v.apikeyHeader;
        if (v.apikeyValue && v.apikeyValue !== MASK) body.apikeyValue = v.apikeyValue;
      } else {
        body.oauth2TokenUrl = v.oauth2TokenUrl;
        body.oauth2ClientId = v.oauth2ClientId;
        if (v.oauth2ClientSecret && v.oauth2ClientSecret !== MASK) body.oauth2ClientSecret = v.oauth2ClientSecret;
        body.oauth2Scope = v.oauth2Scope;
        body.oauth2CacheTtlSeconds = v.oauth2CacheTtlSeconds;
      }
      await putSourceAuth(body);
      message.success(t('settings.saved'));
    } catch (e: any) {
      message.error(e?.response?.data?.message ?? 'Error');
    } finally {
      setSaving(false);
    }
  };

  const onTest = async () => setTestResult(await testSourceAuth());

  return (
    <Card title={t('settings.sourceAuth')} loading={!loaded}>
      <Form form={form} layout="vertical" style={{ maxWidth: 480 }}>
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

        <Space>
          <Button type="primary" onClick={onSave} loading={saving}>{t('settings.save')}</Button>
          <Button onClick={onTest}>{t('settings.test')}</Button>
        </Space>
      </Form>

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
