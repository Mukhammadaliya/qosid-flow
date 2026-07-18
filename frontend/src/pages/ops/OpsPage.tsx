import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Badge, Button, Card, Modal, Space, Switch, Typography, message } from 'antd';
import { PoweroffOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { getOpsStatus, startGateway, stopGateway, ControlResult } from '../../api/ops';
import { useAuth } from '../../auth/AuthContext';

const MAX_LINES = 500;
const POLL_MS = 5000;

function is401(err: unknown): boolean {
  return (err as { response?: { status?: number } })?.response?.status === 401;
}

export function OpsPage() {
  const { t } = useTranslation();
  const { refresh, attemptHandoff, opsUp, logout } = useAuth();
  const navigate = useNavigate();
  const [up, setUp] = useState<boolean | null>(null); // null = hali noma'lum
  const [busy, setBusy] = useState(false);
  const [paused, setPaused] = useState(false);
  const [lines, setLines] = useState<string[]>([]);
  const boxRef = useRef<HTMLDivElement>(null);
  const pausedRef = useRef(false);
  pausedRef.current = paused;
  const prevUpRef = useRef<boolean | null>(null);

  // Gateway holati: 5s poll
  useEffect(() => {
    if (!opsUp) return; // Ops o'chiq / sessiya tugagan: guard sahifani himoya qiladi, poll ishga tushmaydi.
    let alive = true;
    const tick = async () => {
      try {
        const r = await getOpsStatus();
        if (!alive) return;
        const next = r.data.up;
        setUp(next);
        // Only react on an observed up/down TRANSITION, never on every tick — otherwise
        // gatewayUp/the banner/nav gating never catch up with a gateway that restarted mid-session.
        // down→up: avto-ulanish (handoff token -> gateway session, own silent re-probe inside);
        // up→down: plain silent re-probe so nav gating flips to degraded mode.
        const prev = prevUpRef.current;
        if (prev !== null && prev !== next) {
          if (next) attemptHandoff();
          else refresh({ silent: true });
        }
        prevUpRef.current = next;
      } catch (err) {
        if (!alive) return;
        setUp(null);
        if (is401(err)) message.warning(t('ops.sessionExpired'));
      }
    };
    tick();
    const id = setInterval(tick, POLL_MS);
    return () => { alive = false; clearInterval(id); };
  }, [refresh, attemptHandoff, t, opsUp]);

  // Jonli log (SSE). EventSource uzilganda o'zi qayta ulanadi.
  useEffect(() => {
    if (!opsUp) return; // guard faol bo'lsa SSE ochilmasin.
    const es = new EventSource('/ops/api/logs/stream');
    es.onmessage = (e) => {
      if (pausedRef.current) return;
      setLines((prev) => {
        const next = prev.length >= MAX_LINES ? prev.slice(prev.length - MAX_LINES + 1) : prev.slice();
        next.push(e.data);
        return next;
      });
    };
    return () => es.close();
  }, [opsUp]);

  // Smart auto-scroll: faqat foydalanuvchi pastda turganda ergashamiz.
  useEffect(() => {
    const el = boxRef.current;
    if (!el) return;
    const nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 60;
    if (nearBottom) el.scrollTop = el.scrollHeight;
  }, [lines]);

  const runAction = useCallback(async (fn: () => Promise<{ data: ControlResult }>) => {
    setBusy(true);
    try {
      const r = await fn();
      if (!r.data.ok) message.error(r.data.output || t('ops.actionFailed'));
    } catch (err) {
      if (is401(err)) message.warning(t('ops.sessionExpired'));
      else message.error(t('ops.actionFailed'));
    } finally {
      setBusy(false);
    }
  }, [t]);

  const onToggle = () => {
    if (up) {
      Modal.confirm({
        title: t('ops.confirmStopTitle'),
        content: t('ops.confirmStopBody'),
        okText: t('ops.stop'),
        okButtonProps: { danger: true },
        cancelText: t('common.cancel'),
        onOk: () => runAction(stopGateway),
      });
    } else {
      runAction(startGateway);
    }
  };

  // Sahifa o'zini himoya qiladi: Ops backend o'chiq YOKI ops sessiyasi tugagan bo'lsa
  // (to'g'ridan-to'g'ri URL bilan ochilsa ham) o'lik sahifa o'rniga qayta-kirish taklifi.
  // Barcha hook'lar yuqorida shartsiz chaqirildi — faqat RETURN shartli.
  if (!opsUp) {
    return (
      <Alert
        type="warning"
        showIcon
        message={t('ops.unavailable')}
        description={t('ops.reloginHint')}
        action={
          <Button type="primary" size="small" onClick={() => logout().then(() => navigate('/login'))}>
            {t('auth.login')}
          </Button>
        }
      />
    );
  }

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card>
        <Space size={24} wrap>
          <Badge
            status={up === true ? 'success' : up === false ? 'error' : 'default'}
            text={up === true ? t('ops.statusUp') : up === false ? t('ops.statusDown') : t('ops.statusUnknown')}
          />
          <Button
            type="primary"
            danger={!!up}
            icon={<PoweroffOutlined />}
            loading={busy}
            onClick={onToggle}
          >
            {up ? t('ops.stop') : t('ops.start')}
          </Button>
        </Space>
      </Card>
      <Card
        title={t('ops.logTitle')}
        extra={<Space>{t('ops.pause')}<Switch size="small" checked={paused} onChange={setPaused} /></Space>}
      >
        <div
          ref={boxRef}
          data-testid="ops-log"
          style={{
            height: '55vh', overflow: 'auto', background: '#0b1220', color: '#d7e3f4',
            fontFamily: 'monospace', fontSize: 12, padding: 12, borderRadius: 6, whiteSpace: 'pre-wrap',
          }}
        >
          {lines.map((l, i) => <div key={i}>{l}</div>)}
          {lines.length === 0 && <Typography.Text type="secondary">{t('ops.logEmpty')}</Typography.Text>}
        </div>
      </Card>
    </Space>
  );
}
