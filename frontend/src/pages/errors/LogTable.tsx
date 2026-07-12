import { useState } from 'react';
import { Table, Drawer, Tag, Alert } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useTranslation } from 'react-i18next';
import type { LogEntry } from '../../api/logs';

const levelColor: Record<string, string> = { ERROR: 'red', WARN: 'gold', INFO: 'blue' };

export function LogTable({
  entries, loading, error, columns,
}: {
  entries: LogEntry[];
  loading: boolean;
  error?: string;
  columns: ColumnsType<LogEntry>;
}) {
  const { t } = useTranslation();
  const [detail, setDetail] = useState<LogEntry | null>(null);

  return (
    <>
      {error && <Alert type="error" style={{ marginBottom: 12 }} message={error} />}
      <Table<LogEntry>
        size="small"
        loading={loading}
        rowKey={(_r, i) => String(i)}
        dataSource={entries}
        columns={columns}
        onRow={(r) => ({ onClick: () => setDetail(r), style: { cursor: 'pointer' } })}
        pagination={{ pageSize: 50 }}
      />
      <Drawer open={!!detail} title={t('errors.detail')} width={640} onClose={() => setDetail(null)}>
        {detail && (
          <>
            <p><Tag color={levelColor[detail.level] ?? 'default'}>{detail.level || '-'}</Tag> {detail.timestamp}</p>
            <pre style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
              {detail.parsed ? JSON.stringify(detail.parsed, null, 2) : detail.line}
            </pre>
          </>
        )}
      </Drawer>
    </>
  );
}
