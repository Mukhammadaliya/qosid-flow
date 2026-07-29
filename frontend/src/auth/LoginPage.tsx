import { useState } from 'react';
import { Form, Input, Button, Alert } from 'antd';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuth } from './AuthContext';
import { Logo } from '../layout/Logo';
import './LoginPage.css';

/** Composite ids in the same shape the pipeline actually carries: tenant:request. */
const INBOUND = ['100:15073141', '100:15073206', '101:4471902', '100:15073388'];
const OUTBOUND = ['100:15072884', '101:4471755', '100:15073019'];

/** Staggered so the lane never looks like a marching column. */
function Lane({ dir, ids, label }: { dir: 'in' | 'out'; ids: string[]; label: string }) {
  return (
    <div className={`qf-lane qf-lane--${dir}`} aria-hidden="true">
      <span className="qf-lane__label">{label}</span>
      {ids.map((id, i) => (
        <span
          key={id}
          className="qf-pill"
          style={{ animationDelay: `${-(i * 13) / ids.length - dir.length}s` }}
        >
          {id}
        </span>
      ))}
    </div>
  );
}

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
    <div className="qf-login">
      <Lane dir="in" ids={INBOUND} label={t('flow.inbound')} />
      <Lane dir="out" ids={OUTBOUND} label={t('flow.outbound')} />

      <div className="qf-card">
        <div className="qf-card__head">
          <Logo size={30} />
        </div>
        <p className="qf-card__sub">{t('auth.console')}</p>

        {error && (
          <Alert
            type="error"
            showIcon
            message={t(error === 'down' ? 'auth.allDown' : 'auth.loginFailed')}
            style={{ marginBottom: 18 }}
          />
        )}

        <Form layout="vertical" onFinish={onFinish} requiredMark={false}>
          <Form.Item name="username" label={t('auth.username')} rules={[{ required: true }]}>
            <Input autoFocus size="large" autoComplete="username" />
          </Form.Item>
          <Form.Item name="password" label={t('auth.password')} rules={[{ required: true }]}>
            <Input.Password size="large" autoComplete="current-password" />
          </Form.Item>
          <Button type="primary" htmlType="submit" block size="large" loading={busy}>
            {t('auth.login')}
          </Button>
        </Form>
      </div>

      <div className="qf-foot">{t('auth.footNote')}</div>
    </div>
  );
}
