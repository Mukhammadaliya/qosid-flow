import { useEffect, useMemo, useState } from 'react';
import { Alert, Card, Collapse, Input, Button, Table, Tabs, Tag, Space, message } from 'antd';
import { LockOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { getRegistry, putSetting, getAudit, RegistryItem } from '../../api/settings';

/** Audit log tab — render code moved verbatim from the retired AdvancedSettingsPage.
 *  Mounted lazily by antd Tabs, so getAudit fires only when the tab is first opened. */
function AuditTab() {
  const { t } = useTranslation();
  const [audit, setAudit] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);

  useEffect(() => {
    getAudit()
      .then(setAudit)
      .catch((e: any) => setLoadError(e?.response?.data?.message ?? t('common.loadError')))
      .finally(() => setLoading(false));
  }, []);

  const auditColumns = [
    { title: t('settings.auditKey'), dataIndex: 'skey', key: 'skey' },
    { title: t('settings.auditOld'), dataIndex: 'old_value', key: 'old_value' },
    { title: t('settings.auditNew'), dataIndex: 'new_value', key: 'new_value' },
    { title: t('settings.auditBy'), dataIndex: 'changed_by', key: 'changed_by' },
    { title: t('settings.auditAt'), dataIndex: 'changed_at', key: 'changed_at' },
  ];

  return (
    <Card loading={loading}>
      {loadError && <Alert style={{ marginBottom: 16 }} type="error" showIcon message={loadError} />}
      <Table rowKey={(_r, i) => String(i)} size="small" columns={auditColumns} dataSource={audit} />
    </Card>
  );
}

const BADGE: Record<RegistryItem['effectClass'], { color: string; key: string }> = {
  LIVE: { color: 'green', key: 'settings.legend.live' },
  REQUIRES_RESTART: { color: 'gold', key: 'settings.legend.restart' },
  READ_ONLY: { color: 'default', key: 'settings.legend.readonly' },
};

export function GeneralSettingsPage() {
  const { t } = useTranslation();
  const [items, setItems] = useState<RegistryItem[]>([]);
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [restartPending, setRestartPending] = useState(false);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);

  const load = () => {
    setLoading(true);
    setLoadError(null);
    getRegistry()
      .then((r) => {
        setItems(r.settings);
        setRestartPending(r.restartPending);
        setDrafts(Object.fromEntries(r.settings.map((i) => [i.key, i.value])));
      })
      .catch((e: any) => setLoadError(e?.response?.data?.message ?? t('common.loadError')))
      .finally(() => setLoading(false));
  };
  useEffect(load, []);

  const groups = useMemo(() => {
    const map = new Map<string, RegistryItem[]>();
    for (const it of items) (map.get(it.group) ?? map.set(it.group, []).get(it.group)!).push(it);
    return [...map.entries()];
  }, [items]);

  const save = async (it: RegistryItem) => {
    try {
      await putSetting(it.key, drafts[it.key] ?? '', it.secret);
      if (it.effectClass === 'REQUIRES_RESTART') {
        setRestartPending(true);
        message.warning(t('settings.savedRestart'));
      } else {
        message.success(t('settings.savedLive'));
      }
    } catch (e: any) {
      message.error(e?.response?.data?.message ?? 'Error');
    }
  };

  const settingsTab = (
    <Card loading={loading}>
      {loadError && <Alert style={{ marginBottom: 16 }} type="error" showIcon message={loadError} />}
      <Space style={{ marginBottom: 12 }} size="middle">
        <Tag color="green">{t('settings.legend.live')}</Tag>
        <Tag color="gold">{t('settings.legend.restart')}</Tag>
        <Tag>{t('settings.legend.readonly')}</Tag>
      </Space>
      {restartPending && (
        <Alert style={{ marginBottom: 16 }} type="warning" showIcon
               message={t('settings.restartPending')} />
      )}
      <Collapse defaultActiveKey={groups.map(([g]) => g)} items={groups.map(([group, rows]) => ({
        key: group,
        label: group,
        children: (
          <div>
            {rows.map((it) => {
              const readOnly = it.effectClass === 'READ_ONLY';
              return (
                <div key={it.key} style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
                  <Tag color={BADGE[it.effectClass].color} style={{ minWidth: 90, textAlign: 'center' }}>
                    {t(BADGE[it.effectClass].key)}
                  </Tag>
                  <span style={{ minWidth: 200 }}>{it.label}</span>
                  <Input
                    style={{ maxWidth: 360 }}
                    aria-label={it.label}
                    value={drafts[it.key] ?? ''}
                    disabled={readOnly}
                    onChange={(e) => setDrafts({ ...drafts, [it.key]: e.target.value })}
                    suffix={readOnly ? <LockOutlined /> : undefined}
                  />
                  {!readOnly && (
                    <Button size="small" type="primary" onClick={() => save(it)}>
                      {t('settings.save')}
                    </Button>
                  )}
                </div>
              );
            })}
          </div>
        ),
      }))} />
    </Card>
  );

  return (
    <Tabs items={[
      { key: 'settings', label: t('settings.general'), children: settingsTab },
      { key: 'audit', label: t('settings.audit'), children: <AuditTab /> },
    ]} />
  );
}
