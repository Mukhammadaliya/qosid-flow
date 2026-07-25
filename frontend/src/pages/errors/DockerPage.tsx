import { useCallback, useEffect, useState } from 'react';
import { Card, Select } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useTranslation } from 'react-i18next';
import { queryLogs, getLabelValues, LogEntry } from '../../api/logs';
import { LogTable } from './LogTable';
import { LogFilters, FilterState } from './LogFilters';

export function DockerPage() {
  const { t } = useTranslation();
  const [entries, setEntries] = useState<LogEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | undefined>();
  const [containers, setContainers] = useState<string[]>([]);
  const [container, setContainer] = useState<string | undefined>();
  const [filter, setFilter] = useState<FilterState>({ q: '', level: 'ERROR', range: null });

  useEffect(() => { getLabelValues('container').then(setContainers).catch(() => setContainers([])); }, []);

  const load = useCallback(() => {
    setLoading(true);
    queryLogs({
      source: 'docker',
      container: container || undefined,
      level: filter.level || undefined,
      q: filter.q || undefined,
      from: filter.range?.[0]?.valueOf(),
      to: filter.range?.[1]?.valueOf(),
    })
      .then((r) => { setEntries(r.entries); setError(r.error); })
      .catch(() => setError(t('errors.loadFailed')))
      .finally(() => setLoading(false));
  }, [filter, container, t]);

  useEffect(load, [load]);

  const columns: ColumnsType<LogEntry> = [
    { title: t('errors.time'), dataIndex: 'timestamp', width: 190 },
    { title: t('errors.container'), render: (_v, r) => r.labels.container ?? '-', width: 140 },
    { title: t('errors.reason'), dataIndex: 'line', ellipsis: true },
  ];

  const containerSelect = (
    <Select
      allowClear
      style={{ width: 180 }}
      placeholder={t('errors.container')}
      value={container}
      onChange={setContainer}
      options={containers.map((c) => ({ value: c, label: c }))}
    />
  );

  return (
    <Card title={t('errors.dockerTitle')}>
      <LogFilters value={filter} onChange={setFilter} onRefresh={load} extra={containerSelect} />
      <LogTable entries={entries} loading={loading} error={error} columns={columns} />
    </Card>
  );
}
