import { useCallback, useEffect, useState } from 'react';
import { Card } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useTranslation } from 'react-i18next';
import { queryLogs, LogEntry } from '../../api/logs';
import { LogTable } from './LogTable';
import { LogFilters, FilterState } from './LogFilters';

export function JarPage() {
  const { t } = useTranslation();
  const [entries, setEntries] = useState<LogEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | undefined>();
  const [filter, setFilter] = useState<FilterState>({ q: '', level: 'ERROR', range: null });

  const load = useCallback(() => {
    setLoading(true);
    queryLogs({
      source: 'jar',
      level: filter.level || undefined,
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
    { title: 'level', dataIndex: 'level', width: 80 },
    { title: t('errors.reason'), dataIndex: 'line', ellipsis: true },
  ];

  return (
    <Card title={t('errors.jarTitle')}>
      <LogFilters value={filter} onChange={setFilter} onRefresh={load} />
      <LogTable entries={entries} loading={loading} error={error} columns={columns} />
    </Card>
  );
}
