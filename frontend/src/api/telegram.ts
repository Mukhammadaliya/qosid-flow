import { api } from './client';

const BASE = '/qosid/admin/api/telegram-config';

export interface TelegramConfig {
  enabled: string;          // '' = DB'da yo'q (env fallback amalda), 'true'/'false' = DB qiymati
  botToken: string;         // '' yoki '***'
  chatId: string;
  messageThreadId: string;
}
export interface TelegramTestResult { ok: boolean; error?: string }

export const getTelegramConfig = () => api.get<TelegramConfig>(BASE).then(r => r.data);
export const testTelegram = () => api.post<TelegramTestResult>(`${BASE}/test`).then(r => r.data);
