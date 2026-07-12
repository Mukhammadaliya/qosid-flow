import { api } from './client';
import type { LogEntry } from './logs';

const BASE = '/qosid/admin/api/ai';

export interface AiStatus { enabled: boolean }
export interface AiAnalysisResult { analysis: string | null; error: string | null }

export const getAiStatus = () => api.get<AiStatus>(`${BASE}/status`).then(r => r.data);

export const analyzeError = (entry: LogEntry) =>
  api.post<AiAnalysisResult>(`${BASE}/analyze`, {
    line: entry.line, level: entry.level, labels: entry.labels, parsed: entry.parsed ?? null,
  }).then(r => r.data);
