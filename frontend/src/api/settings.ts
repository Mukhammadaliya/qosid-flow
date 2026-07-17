import { api } from './client';

const BASE = '/qosid/admin/api';

export interface SourceAuth {
  type: string; basicUsername: string; basicPassword: string;
  apikeyHeader: string; apikeyValue: string;
  oauth2TokenUrl: string; oauth2ClientId: string; oauth2ClientSecret: string;
  oauth2Scope: string; oauth2CacheTtlSeconds: string;
}
export interface TestResult { ok: boolean; type?: string; message: string; }
export interface SettingRow { key: string; value: string; secret: boolean; updatedAt: string; updatedBy: string; }
export interface TenantTestResult { tenantId: number | null; extraTenantId: number | null; tenantField: string; extraField: string; }

export const getSourceAuth = () => api.get<SourceAuth>(`${BASE}/source-auth`).then(r => r.data);
export const putSourceAuth = (body: Partial<SourceAuth>) => api.put(`${BASE}/source-auth`, body);
export const testSourceAuth = () => api.post<TestResult>(`${BASE}/source-auth/test`).then(r => r.data);

export const getSettings = () => api.get<SettingRow[]>(`${BASE}/settings`).then(r => r.data);
export const putSetting = (key: string, value: string, secret: boolean) =>
  api.put(`${BASE}/settings/${encodeURIComponent(key)}`, { value, secret });
export const getAudit = () => api.get<any[]>(`${BASE}/settings/audit`).then(r => r.data);

export interface RegistryItem {
  key: string; group: string; label: string; type: string;
  effectClass: 'LIVE' | 'REQUIRES_RESTART' | 'READ_ONLY';
  value: string; secret: boolean;
}
export interface SettingsRegistry { settings: RegistryItem[]; }

export const getRegistry = () =>
  api.get<SettingsRegistry>(`${BASE}/settings/registry`).then(r => r.data);

export const testTenantMapping = (sample: unknown) =>
  api.post<TenantTestResult>(`${BASE}/tenant-mapping/test`, sample).then(r => r.data);
