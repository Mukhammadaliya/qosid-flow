import { useEffect, useState } from 'react';
import { Card, Form, Input, Button, Space, Alert, Tooltip, message } from 'antd';
import { useTranslation } from 'react-i18next';
import { getAiConfig, analyzeError, AiConfig } from '../../api/ai';
import { opsSettings } from '../../api/opsSettings';
import { aiConfig as aiConfigEntries, aiConfigFromRows } from '../../api/settingsKeys';
import { useAuth } from '../../auth/AuthContext';
import { ModeBanner } from './ModeBanner';

export function AiConfigPage() {
  const { t } = useTranslation();
  const { gatewayUp, opsUp } = useAuth();
  // Stop-to-edit: writable ONLY while the gateway is down and the ops session is
  // alive (gateway writes all answer 409 stop-required). Same as GeneralSettingsPage.
  const editMode = !gatewayUp && opsUp;

  const [form] = Form.useForm();
  const [loaded, setLoaded] = useState(false);
  const [testResult, setTestResult] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    setLoaded(false);
    const load: Promise<Partial<AiConfig>> = editMode
      ? opsSettings.getAll().then(aiConfigFromRows)
      : getAiConfig();
    load
      .then((c) => form.setFieldsValue(c))
      .catch(() => {})
      .finally(() => setLoaded(true));
  }, [form, editMode]);

  const onSave = async () => {
    setSaving(true);
    try {
      // Blank/masked api key is omitted by the mapping (keep-existing semantics).
      for (const entry of aiConfigEntries(form.getFieldsValue())) {
        await opsSettings.put(entry.key, entry.value);
      }
      message.success(t('settings.saved'));
    } catch (e: any) {
      const code = e?.response?.data?.error;
      message.error(code === 'service-running'
        ? t('settings.serviceRunning')
        : (e?.response?.data?.message ?? code ?? t('ai.error')));
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
      <ModeBanner editMode={editMode} />
      <Form form={form} layout="vertical" style={{ maxWidth: 520 }} disabled={!editMode}>
        <Form.Item name="baseUrl" label={t('ai.baseUrl')}>
          <Input placeholder="https://openrouter.ai/api/v1" />
        </Form.Item>
        <Form.Item name="apiKey" label={t('ai.apiKey')}>
          <Input.Password placeholder={t('common.secretPlaceholder')} />
        </Form.Item>
        <Form.Item name="model" label={t('ai.model')}>
          <Input placeholder="openai/gpt-4o-mini" />
        </Form.Item>
      </Form>
      <Space>
        {editMode && (
          <Button type="primary" onClick={onSave} loading={saving}>{t('settings.save')}</Button>
        )}
        {/* Analyze goes through the gateway's admin AI proxy — needs it running. */}
        <Tooltip title={editMode ? t('settings.testNeedsService') : undefined}>
          <Button onClick={onTest} disabled={editMode}>{t('ai.test')}</Button>
        </Tooltip>
      </Space>
      {testResult && <Alert style={{ marginTop: 16 }} type={testResult.startsWith('✅') ? 'success' : 'error'} message={testResult} />}
    </Card>
  );
}
