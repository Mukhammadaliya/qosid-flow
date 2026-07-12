import { useEffect, useState } from 'react';
import { Card, Form, Input, Button, Space, Alert, message } from 'antd';
import { useTranslation } from 'react-i18next';
import { getAiConfig, putAiConfig, analyzeError, AiConfig } from '../../api/ai';

const MASK = '***';

export function AiConfigPage() {
  const { t } = useTranslation();
  const [form] = Form.useForm();
  const [loaded, setLoaded] = useState(false);
  const [testResult, setTestResult] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    getAiConfig()
      .then((c) => { form.setFieldsValue(c); setLoaded(true); })
      .catch(() => setLoaded(true));
  }, [form]);

  const onSave = async () => {
    const v = form.getFieldsValue() as AiConfig;
    setSaving(true);
    try {
      const body: Partial<AiConfig> = { baseUrl: v.baseUrl, model: v.model };
      if (v.apiKey && v.apiKey !== MASK) body.apiKey = v.apiKey; // never resend the mask
      await putAiConfig(body);
      message.success(t('settings.saved'));
    } catch (e: any) {
      message.error(e?.response?.data?.message ?? t('ai.error'));
    } finally {
      setSaving(false);
    }
  };

  const onTest = async () => {
    setTestResult(null);
    const r = await analyzeError({
      timestamp: '', level: 'ERROR', line: 'test: connection timeout to upstream', labels: {}, parsed: null,
    });
    setTestResult(r.error ? `❌ ${r.error}` : `✅ ${r.analysis?.slice(0, 200)}`);
  };

  return (
    <Card title={t('ai.settingsTitle')} loading={!loaded}>
      <Form form={form} layout="vertical" style={{ maxWidth: 520 }}>
        <Form.Item name="baseUrl" label={t('ai.baseUrl')}>
          <Input placeholder="https://openrouter.ai/api/v1" />
        </Form.Item>
        <Form.Item name="apiKey" label={t('ai.apiKey')}>
          <Input.Password placeholder={t('common.secretPlaceholder')} />
        </Form.Item>
        <Form.Item name="model" label={t('ai.model')}>
          <Input placeholder="openai/gpt-4o-mini" />
        </Form.Item>
        <Space>
          <Button type="primary" onClick={onSave} loading={saving}>{t('settings.save')}</Button>
          <Button onClick={onTest}>{t('ai.test')}</Button>
        </Space>
      </Form>
      {testResult && <Alert style={{ marginTop: 16 }} type={testResult.startsWith('✅') ? 'success' : 'error'} message={testResult} />}
    </Card>
  );
}
