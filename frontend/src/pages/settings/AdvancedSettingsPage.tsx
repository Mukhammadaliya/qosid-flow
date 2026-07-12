import { useEffect, useMemo, useState } from 'react';
import { Alert, Card, Table, Button, Modal, Input, Tag, Tabs, message } from 'antd';
import { useTranslation } from 'react-i18next';
import { getSettings, putSetting, getAudit, SettingRow } from '../../api/settings';

function groupOf(key: string): string {
  const i = key.lastIndexOf('.');
  return i > 0 ? key.slice(0, i) : key;
}

export function AdvancedSettingsPage() {
  const { t } = useTranslation();
  const [rows, setRows] = useState<SettingRow[]>([]);
  const [audit, setAudit] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [editing, setEditing] = useState<SettingRow | null>(null);
  const [draft, setDraft] = useState('');
  const [loadError, setLoadError] = useState<string | null>(null);

  const load = () => {
    setLoading(true);
    setLoadError(null);
    Promise.all([getSettings(), getAudit()])
      .then(([s, a]) => { setRows(s); setAudit(a); })
      .catch((e: any) => setLoadError(e?.response?.data?.message ?? t('common.loadError')))
      .finally(() => setLoading(false));
  };
  useEffect(load, []);

  const grouped = useMemo(() => {
    const map = new Map<string, SettingRow[]>();
    for (const r of [...rows].sort((a, b) => a.key.localeCompare(b.key))) {
      const g = groupOf(r.key);
      (map.get(g) ?? map.set(g, []).get(g)!).push(r);
    }
    return map;
  }, [rows]);

  const openEdit = (row: SettingRow) => { setEditing(row); setDraft(row.secret ? '' : row.value); };

  const save = async () => {
    if (!editing) return;
    // Blank-means-keep for secrets: closing without a new value must NOT wipe the stored
    // secret. trim() matches the backend's isBlank so whitespace-only doesn't overwrite either.
    if (editing.secret && draft.trim() === '') { setEditing(null); return; }
    try {
      await putSetting(editing.key, draft, editing.secret);
      message.success(t('settings.saved'));
      setEditing(null);
      load();
    } catch (e: any) {
      message.error(e?.response?.data?.message ?? 'Error');
    }
  };

  const columns = [
    { title: t('settings.key'), dataIndex: 'key', key: 'key' },
    { title: t('settings.value'), dataIndex: 'value', key: 'value',
      render: (v: string, r: SettingRow) => (r.secret ? <Tag>***</Tag> : v) },
    { title: '', key: 'action',
      render: (_: unknown, r: SettingRow) => <Button size="small" onClick={() => openEdit(r)}>{t('settings.save')}</Button> },
  ];

  const settingsTab = (
    <Card loading={loading}>
      {[...grouped.entries()].map(([group, groupRows]) => (
        <div key={group} style={{ marginBottom: 20 }}>
          <h4 style={{ color: '#1A2333' }}>{group}</h4>
          <Table rowKey="key" size="small" pagination={false} columns={columns} dataSource={groupRows} />
        </div>
      ))}
    </Card>
  );

  const auditColumns = [
    { title: t('settings.auditKey'), dataIndex: 'skey', key: 'skey' },
    { title: t('settings.auditOld'), dataIndex: 'old_value', key: 'old_value' },
    { title: t('settings.auditNew'), dataIndex: 'new_value', key: 'new_value' },
    { title: t('settings.auditBy'), dataIndex: 'changed_by', key: 'changed_by' },
    { title: t('settings.auditAt'), dataIndex: 'changed_at', key: 'changed_at' },
  ];

  return (
    <>
      {loadError && <Alert style={{ marginBottom: 16 }} type="error" showIcon message={loadError} />}
      <Tabs items={[
        { key: 'settings', label: t('settings.advanced'), children: settingsTab },
        { key: 'audit', label: t('settings.audit'),
          children: <Table rowKey={(_r, i) => String(i)} size="small" columns={auditColumns} dataSource={audit} /> },
      ]} />

      <Modal
        open={!!editing}
        title={editing?.key}
        okText={t('common.ok')}
        onOk={save}
        onCancel={() => setEditing(null)}
      >
        <label htmlFor="adv-value">{t('settings.value')}</label>
        <Input
          id="adv-value"
          aria-label={t('settings.value')}
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          placeholder={editing?.secret ? t('common.secretPlaceholder') : ''}
        />
      </Modal>
    </>
  );
}
