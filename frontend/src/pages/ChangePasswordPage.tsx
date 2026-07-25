import { useState } from 'react';
import { Card, Form, Input, Button, Alert, message } from 'antd';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { changePassword } from '../api/adminAuth';
import { useAuth } from '../auth/AuthContext';

interface FormValues {
  oldPassword: string;
  newPassword: string;
  confirmPassword: string;
}

/**
 * Admin password change. Also the forced landing page while the seeded admin/admin password
 * is still in use: the gateway then answers every other admin route with
 * 403 password-change-required, so nothing else in the SPA would work anyway.
 */
export function ChangePasswordPage() {
  const { t } = useTranslation();
  const { passwordChangeRequired, logout } = useAuth();
  const navigate = useNavigate();
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const onFinish = async (v: FormValues) => {
    setBusy(true);
    setError(null);
    try {
      await changePassword(v.oldPassword, v.newPassword);
      message.success(t('auth.passwordChanged'));
      // The backend invalidates the session on success — send the user back to login
      // instead of leaving the SPA holding a dead session.
      await logout();
      navigate('/login', { replace: true });
    } catch (e: any) {
      setError(e?.response?.data?.message ?? e?.response?.data?.error ?? t('auth.changePasswordFailed'));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card title={t('auth.changePassword')} style={{ maxWidth: 520 }}>
      {passwordChangeRequired && !error && (
        <Alert type="warning" showIcon style={{ marginBottom: 16 }} message={t('auth.passwordChangeRequired')} />
      )}
      {error && <Alert type="error" showIcon style={{ marginBottom: 16 }} message={error} />}
      <Form layout="vertical" onFinish={onFinish}>
        <Form.Item name="oldPassword" label={t('auth.oldPassword')} rules={[{ required: true }]}>
          <Input.Password autoFocus autoComplete="current-password" />
        </Form.Item>
        <Form.Item
          name="newPassword"
          label={t('auth.newPassword')}
          rules={[{ required: true }, { min: 8, message: t('auth.passwordTooShort') }]}
        >
          <Input.Password autoComplete="new-password" />
        </Form.Item>
        <Form.Item
          name="confirmPassword"
          label={t('auth.confirmPassword')}
          dependencies={['newPassword']}
          rules={[
            { required: true },
            ({ getFieldValue }) => ({
              validator: (_, value) =>
                !value || value === getFieldValue('newPassword')
                  ? Promise.resolve()
                  : Promise.reject(new Error(t('auth.passwordMismatch'))),
            }),
          ]}
        >
          <Input.Password autoComplete="new-password" />
        </Form.Item>
        <Button type="primary" htmlType="submit" loading={busy}>{t('auth.changePassword')}</Button>
      </Form>
    </Card>
  );
}
