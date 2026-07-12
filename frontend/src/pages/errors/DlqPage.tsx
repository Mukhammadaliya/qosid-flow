import { useCallback, useEffect, useState } from 'react';
import { Card } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useTranslation } from 'react-i18next';
import { queryLogs, LogEntry } from '../../api/logs';
import { LogTable } from './LogTable';
import { LogFilters, FilterState } from './LogFilters';

export function DlqPage() {
  const { t } = useTranslation();
  const [entries, setEntries] = useState<LogEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | undefined>();
  const [filter, setFilter] = useState<FilterState>({ q: '', level: '', range: null });

  const load = useCallback(() => {
    setLoading(true);
    queryLogs({
      source: 'dlq',
      q: filter.q || undefined,
      from: filter.range?.[0]?.valueOf(),
      to: filter.range?.[1]?.valueOf(),
    })
      .then((r) => { setEntries(r.entries); setError(r.error); })
      .catch(() => setError(t('errors.loadFailed')))
      .finally(() => setLoading(false));
  }, [filter, t]);

  useEffect(load, [load]);

  const columns: ColumnsType<LogEntry> = [
    { title: t('errors.time'), dataIndex: 'timestamp', width: 190 },
    { title: 'tenant', render: (_v, r) => r.parsed?.tenantId ?? '-' , width: 90 },
    { title: 'reqId', render: (_v, r) => r.parsed?.requestId ?? '-', width: 110 },
    { title: 'status', render: (_v, r) => r.parsed?.httpStatus ?? '-', width: 80 },
    { title: 'source', render: (_v, r) => r.parsed?.errorSource ?? '-', width: 90 },
    { title: t('errors.reason'), render: (_v, r) => r.parsed?.failureReason ?? r.line },
  ];

  return (
    <Card title={t('errors.dlqTitle')}>
      <LogFilters value={filter} onChange={setFilter} onRefresh={load} showLevel={false} />
      <LogTable entries={entries} loading={loading} error={error} columns={columns} />
    </Card>
  );
}
