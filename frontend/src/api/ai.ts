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

export interface AiConfig { baseUrl: string; apiKey: string; model: string }

// Config writes go through opsSettings while the service is down (gateway PUT is 409 now).
export const getAiConfig = () => api.get<AiConfig>(`${BASE}/config`).then(r => r.data);
