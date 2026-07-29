import { useEffect, useState } from 'react';
import { Card, Form, Input, Switch, Button, Space, Alert, Tooltip, message } from 'antd';
import { useTranslation } from 'react-i18next';
import { getTelegramConfig, testTelegram } from '../../api/telegram';
import { opsSettings } from '../../api/opsSettings';
import { telegramConfig as telegramEntries, telegramConfigFromRows, TelegramConfigFormValues } from '../../api/settingsKeys';
import { useAuth } from '../../auth/AuthContext';
import { ModeBanner } from './ModeBanner';

export function TelegramConfigPage() {
  const { t } = useTranslation();
  const { gatewayUp, opsUp } = useAuth();
  // Stop-to-edit: faqat gateway DOWN + ops sessiya tirik bo'lganda yoziladi (AiConfigPage bilan bir xil).
  const editMode = !gatewayUp && opsUp;

  const [form] = Form.useForm<TelegramConfigFormValues>();
  const [loaded, setLoaded] = useState(false);
  const [testResult, setTestResult] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    setLoaded(false);
    const load: Promise<TelegramConfigFormValues> = editMode
      ? opsSettings.getAll().then(telegramConfigFromRows)
      : getTelegramConfig().then((c) => ({
          enabled: c.enabled === 'true',
          botToken: '', // maskalangan token inputga qo'yilmaydi (bo'sh = keep-existing)
          chatId: c.chatId,
          messageThreadId: c.messageThreadId,
        }));
    load
      .then((c) => form.setFieldsValue(c))
      .catch(() => {})
      .finally(() => setLoaded(true));
  }, [form, editMode]);

  const onSave = async () => {
    setSaving(true);
    try {
      // Bo'sh/maskalangan token mapping tomonidan tashlab ketiladi (keep-existing semantikasi).
      for (const entry of telegramEntries(form.getFieldsValue())) {
        await opsSettings.put(entry.key, entry.value);
      }
      message.success(t('settings.saved'));
    } catch (e: any) {
      const code = e?.response?.data?.error;
      message.error(code === 'service-running'
        ? t('settings.serviceRunning')
        : (e?.response?.data?.message ?? code ?? t('settings.saveFailed', 'Saqlashda xato')));
    } finally {
      setSaving(false);
    }
  };

  const onTest = async () => {
    setTestResult(null);
    const r = await testTelegram();
    setTestResult(r.ok ? `✅ ${t('telegram.testOk')}` : `❌ ${r.error}`);
  };

  return (
    <Card title={t('telegram.settingsTitle')} loading={!loaded}>
      <ModeBanner editMode={editMode} />
      <Alert type="info" showIcon style={{ marginBottom: 16 }} message={t('telegram.hint')} />
      <Form form={form} layout="vertical" style={{ maxWidth: 520 }} disabled={!editMode}>
        <Form.Item name="enabled" label={t('telegram.enabled')} valuePropName="checked">
          <Switch />
        </Form.Item>
        <Form.Item name="botToken" label={t('telegram.botToken')}>
          <Input.Password placeholder={t('common.secretPlaceholder')} />
        </Form.Item>
        <Form.Item name="chatId" label={t('telegram.chatId')}>
          <Input placeholder="-1001234567890" />
        </Form.Item>
        <Form.Item name="messageThreadId" label={t('telegram.messageThreadId')}>
          <Input placeholder="8520" />
        </Form.Item>
      </Form>
      <Space>
        {editMode && (
          <Button type="primary" onClick={onSave} loading={saving}>{t('settings.save')}</Button>
        )}
        {/* Test gateway'ning admin API'si orqali — servis ishlab turishi kerak. */}
        <Tooltip title={editMode ? t('settings.testNeedsService') : undefined}>
          <Button onClick={onTest} disabled={editMode}>{t('telegram.test')}</Button>
        </Tooltip>
      </Space>
      {testResult && <Alert style={{ marginTop: 16 }} type={testResult.startsWith('✅') ? 'success' : 'error'} message={testResult} />}
    </Card>
  );
}
