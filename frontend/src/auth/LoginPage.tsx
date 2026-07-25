import { useState } from 'react';
import { Card, Form, Input, Button, Alert, Typography } from 'antd';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuth } from './AuthContext';

export function LoginPage() {
  const { login } = useAuth();
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [error, setError] = useState<'badcreds' | 'down' | null>(null);
  const [busy, setBusy] = useState(false);

  const onFinish = async (v: { username: string; password: string }) => {
    setBusy(true); setError(null);
    try {
      await login(v.username, v.password);
      navigate('/', { replace: true });
    } catch (e) {
      setError((e as Error).message === 'down' ? 'down' : 'badcreds');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div style={{ display: 'flex', justifyContent: 'center', marginTop: '15vh' }}>
      <Card style={{ width: 360 }}>
        <Typography.Title level={3} style={{ textAlign: 'center' }}>{t('app.title')}</Typography.Title>
        {error && <Alert type="error" message={t(error === 'down' ? 'auth.allDown' : 'auth.loginFailed')} style={{ marginBottom: 16 }} />}
        <Form layout="vertical" onFinish={onFinish}>
          <Form.Item name="username" label={t('auth.username')} rules={[{ required: true }]}>
            <Input autoFocus />
          </Form.Item>
          <Form.Item name="password" label={t('auth.password')} rules={[{ required: true }]}>
            <Input.Password />
          </Form.Item>
          <Button type="primary" htmlType="submit" block loading={busy}>{t('auth.login')}</Button>
        </Form>
      </Card>
    </div>
  );
}
