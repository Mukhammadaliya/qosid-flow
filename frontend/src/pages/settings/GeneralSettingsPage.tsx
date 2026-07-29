import { useEffect, useMemo, useState } from 'react';
import { Alert, Card, Collapse, Input, Button, Table, Tabs, Space, Tooltip, message } from 'antd';
import { LockOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { getRegistry, getAudit } from '../../api/settings';
import { opsSettings } from '../../api/opsSettings';
import { TYPED_FORM_KEYS } from '../../api/settingsKeys';
import { useAuth } from '../../auth/AuthContext';

/** Keys shown only via their typed tabs (Source Auth / Tenant / AI) or that are internal
 *  bookkeeping markers must never appear in the General list — every setting is configurable
 *  in exactly one place. Filtered in the frontend so it applies to BOTH data sources. */
const isHiddenFromGeneral = (key: string): boolean =>
  TYPED_FORM_KEYS.has(key)
  || key.startsWith('admin.')
  || key.startsWith('gateway._meta.')
  || key === 'gateway.settings.migration-completed';

/** Unified row shape for both data sources (gateway registry / ops sidecar). */
interface SettingRow {
  key: string;
  group: string;
  label: string;
  type: string;
  value: string;
  secret: boolean;
  editable: boolean;
  /** Whether a settings-DB row exists (ops edit mode only). When false the yaml/env
   *  value is live but not persisted — the empty input shows an env-default placeholder. */
  stored: boolean;
}

/** Audit log tab. Mode-aware: while the gateway is up it reads the gateway audit
 *  endpoint; in stopped-service edit mode it reads the same H2 table via the ops
 *  sidecar. Both return identical row keys (skey/old_value/new_value/changed_by/changed_at).
 *  Mounted lazily by antd Tabs, so the fetch fires only when the tab is first opened. */
function AuditTab({ editMode }: { editMode: boolean }) {
  const { t } = useTranslation();
  const [audit, setAudit] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);

  useEffect(() => {
    (editMode ? opsSettings.getAudit() : getAudit())
      .then(setAudit)
      .catch((e: any) => setLoadError(e?.response?.data?.message ?? t('common.loadError')))
      .finally(() => setLoading(false));
  }, [editMode]);

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

const MASKED = '***';

export function GeneralSettingsPage() {
  const { t } = useTranslation();
  const { gatewayUp, opsUp } = useAuth();
  const navigate = useNavigate();
  // Strict stop-to-edit model: editing is possible ONLY while the gateway is down
  // and the ops sidecar session is alive. When the gateway is up, the page is
  // read-only — all gateway settings writes answer 409 {"error":"stop-required"}.
  const editMode = !gatewayUp && opsUp;

  const [items, setItems] = useState<SettingRow[]>([]);
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);

  const load = () => {
    setLoading(true);
    setLoadError(null);
    const fetchRows: Promise<SettingRow[]> = editMode
      ? opsSettings.getAll().then((rows) =>
          rows
            .filter((r) => !isHiddenFromGeneral(r.key))
            .map((r) => ({ key: r.key, group: r.group, label: r.label, type: r.type, value: r.value, secret: r.secret, editable: r.editable, stored: r.stored })))
      : getRegistry().then((r) =>
          r.settings
            .filter((it) => !isHiddenFromGeneral(it.key))
            .map((it) => ({ key: it.key, group: it.group, label: it.label, type: it.type, value: it.value, secret: it.secret, editable: false, stored: true })));
    fetchRows
      .then((rows) => {
        setItems(rows);
        setDrafts(Object.fromEntries(rows.map((i) => [i.key, i.value])));
      })
      .catch((e: any) => setLoadError(e?.response?.data?.message ?? t('common.loadError')))
      .finally(() => setLoading(false));
  };
  useEffect(load, [editMode]);

  const groups = useMemo(() => {
    const map = new Map<string, SettingRow[]>();
    for (const it of items) (map.get(it.group) ?? map.set(it.group, []).get(it.group)!).push(it);
    return [...map.entries()];
  }, [items]);

  // Stop-to-edit model: all edits are applied together on the next start, so a single
  // top-of-tab Saqlash replaces per-row saves. A row is dirty when its draft differs from
  // the stored value — EXCEPT an untouched masked secret ("***"), which must never be sent.
  const dirtyRows = useMemo(
    () =>
      items.filter((it) => {
        if (!it.editable) return false;
        const draft = drafts[it.key] ?? '';
        const untouchedSecret = it.secret && draft === MASKED && it.value === MASKED;
        return !untouchedSecret && draft !== it.value;
      }),
    [items, drafts],
  );

  const saveAll = async () => {
    setSaving(true);
    const failures: { key: string; code: string }[] = [];
    for (const it of dirtyRows) {
      try {
        await opsSettings.put(it.key, drafts[it.key] ?? '');
      } catch (e: any) {
        const code = e?.response?.data?.error;
        failures.push({
          key: it.key,
          code: code === 'service-running'
            ? t('settings.serviceRunning')
            : (e?.response?.data?.message ?? code ?? 'Error'),
        });
      }
    }
    setSaving(false);
    if (failures.length === 0) {
      message.success(t('settings.savedCount', { count: dirtyRows.length }));
    } else {
      message.error(failures.map((f) => `${f.key}: ${f.code}`).join('; '));
    }
    load();
  };

  const settingsTab = (
    <Card loading={loading}>
      {loadError && <Alert style={{ marginBottom: 16 }} type="error" showIcon message={loadError} />}
      {editMode ? (
        <>
          <Alert style={{ marginBottom: 16 }} type="info" showIcon message={t('settings.appliesOnStart')} />
          <Button
            type="primary"
            style={{ marginBottom: 16 }}
            loading={saving}
            disabled={dirtyRows.length === 0}
            onClick={saveAll}
          >
            {t('settings.save')}{dirtyRows.length > 0 ? ` (${dirtyRows.length})` : ''}
          </Button>
        </>
      ) : (
        <Alert
          style={{ marginBottom: 16 }}
          type="warning"
          showIcon
          message={
            <Space>
              {t('settings.stopToEdit')}
              <Tooltip title={!opsUp ? t('ops.unavailable') : undefined}>
                <Button type="link" size="small" disabled={!opsUp} onClick={() => navigate('/ops')}>{t('nav.ops')}</Button>
              </Tooltip>
            </Space>
          }
        />
      )}
      <Collapse defaultActiveKey={groups.map(([g]) => g)} items={groups.map(([group, rows]) => ({
        key: group,
        label: group,
        children: (
          <div>
            {rows.map((it) => {
              const canEdit = editMode && it.editable;
              return (
                <div key={it.key} style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
                  <span style={{ minWidth: 200 }}>{it.label}</span>
                  <Input
                    style={{ maxWidth: 360 }}
                    aria-label={it.label}
                    value={drafts[it.key] ?? ''}
                    disabled={!canEdit}
                    placeholder={it.stored ? undefined : t('settings.envDefault')}
                    onChange={(e) => setDrafts({ ...drafts, [it.key]: e.target.value })}
                    suffix={!canEdit ? <LockOutlined /> : undefined}
                  />
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
      { key: 'audit', label: t('settings.audit'), children: <AuditTab editMode={editMode} /> },
    ]} />
  );
}
