import { api } from './client';

const BASE = '/qosid/admin/api/logs';

export interface LogEntry {
  timestamp: string;
  level: string;
  line: string;
  labels: Record<string, string>;
  parsed?: any;
}
export interface LogQueryResult { entries: LogEntry[]; error?: string }

export interface LogQueryParams {
  source: 'dlq' | 'jar' | 'docker';
  from?: number;
  to?: number;
  q?: string;
  container?: string;
  level?: string;
  limit?: number;
}

export const queryLogs = (params: LogQueryParams) =>
  api.get<LogQueryResult>(BASE, { params }).then(r => r.data);

export const getLabelValues = (name: string) =>
  api.get<{ values: string[] }>(`${BASE}/labels`, { params: { name } }).then(r => r.data.values);
