import { useEffect, useState } from 'react';
import { Table, Drawer, Tag, Alert, Button, Spin, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useTranslation } from 'react-i18next';
import type { LogEntry } from '../../api/logs';
import { getAiStatus, analyzeError, AiAnalysisResult } from '../../api/ai';

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
  const [aiEnabled, setAiEnabled] = useState(false);
  const [aiLoading, setAiLoading] = useState(false);
  const [aiResult, setAiResult] = useState<AiAnalysisResult | null>(null);

  useEffect(() => { getAiStatus().then((s) => setAiEnabled(s.enabled)).catch(() => setAiEnabled(false)); }, []);

  const openDetail = (r: LogEntry) => { setDetail(r); setAiResult(null); };

  const runAi = () => {
    if (!detail) return;
    setAiLoading(true); setAiResult(null);
    analyzeError(detail)
      .then(setAiResult)
      .catch(() => setAiResult({ analysis: null, error: t('ai.error') }))
      .finally(() => setAiLoading(false));
  };

  return (
    <>
      {error && <Alert type="error" style={{ marginBottom: 12 }} message={error} />}
      <Table<LogEntry>
        size="small"
        loading={loading}
        rowKey={(_r, i) => String(i)}
        dataSource={entries}
        columns={columns}
        onRow={(r) => ({ onClick: () => openDetail(r), style: { cursor: 'pointer' } })}
        pagination={{ pageSize: 50 }}
      />
      <Drawer open={!!detail} title={t('errors.detail')} width={640} onClose={() => setDetail(null)}>
        {detail && (
          <>
            <p><Tag color={levelColor[detail.level] ?? 'default'}>{detail.level || '-'}</Tag> {detail.timestamp}</p>
            <pre style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
              {detail.parsed ? JSON.stringify(detail.parsed, null, 2) : detail.line}
            </pre>
            {aiEnabled && (
              <div style={{ marginTop: 16 }}>
                <Button onClick={runAi} loading={aiLoading}>{t('ai.analyze')}</Button>
                {aiResult?.error && <Alert style={{ marginTop: 12 }} type="error" message={aiResult.error} />}
                {aiResult?.analysis && (
                  <div style={{ marginTop: 12, background: '#F4F6F9', padding: 12, borderRadius: 6 }}>
                    <Typography.Text strong>{t('ai.title')}</Typography.Text>
                    <pre style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word', marginTop: 8 }}>{aiResult.analysis}</pre>
                  </div>
                )}
                {aiLoading && <Spin style={{ marginLeft: 12 }} />}
              </div>
            )}
          </>
        )}
      </Drawer>
    </>
  );
}
