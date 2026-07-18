/**
 * Pure form-values <-> settings-key mappings for the typed settings pages
 * (Source Auth / Tenant Mapping / AI config) in stop-to-edit mode.
 *
 * While the gateway is DOWN, those pages save through the ops sidecar's generic
 * `PUT /ops/api/settings/{key}` instead of their dedicated gateway endpoints.
 * The functions here translate a page's form values into the exact settings-DB
 * keys (see ai-context/CONFIGURATION.md) and encode the secret-field contract:
 * a secret left blank (or still holding the "***" mask) means "keep the stored
 * value" and is OMITTED from the PUT list — mirroring the dedicated gateway
 * endpoints' mask semantics.
 */

export const MASK = '***';

/** Har bir kalit faqat bitta joydan sozlanadi: typed tablar boshqaradigan kalitlar General ro'yxatidan yashiriladi. */
export const TYPED_FORM_KEYS: ReadonlySet<string> = new Set([
  'gateway.source.auth.type',
  'gateway.source.auth.basic.username',
  'gateway.source.auth.basic.password',
  'gateway.source.auth.apikey.header',
  'gateway.source.auth.apikey.value',
  'gateway.source.auth.oauth2.token-url',
  'gateway.source.auth.oauth2.client-id',
  'gateway.source.auth.oauth2.client-secret',
  'gateway.source.auth.oauth2.scope',
  'gateway.source.auth.oauth2.cache-ttl-seconds',
  'gateway.tenant.field-name',
  'gateway.tenant.extra-field-name',
  'gateway.tenant.default-rest-tenant',
  'gateway.tenant.trust-extra-tenant-header',
  'gateway.ai.base-url',
  'gateway.ai.api-key',
  'gateway.ai.model',
]);

export interface SettingEntry {
  key: string;
  value: string;
  secret: boolean;
}

const plain = (key: string, value: string | undefined | null): SettingEntry => ({
  key,
  value: value == null ? '' : String(value),
  secret: false,
});

/** Blank or masked secret -> keep existing -> no entry at all. */
const secretEntry = (key: string, value: string | undefined | null): SettingEntry[] =>
  value && value !== MASK ? [{ key, value: String(value), secret: true }] : [];

type Row = { key: string; value: string };
const byKey = (rows: Row[]): Record<string, string> =>
  Object.fromEntries(rows.map((r) => [r.key, r.value]));

// ---------------------------------------------------------------- source auth

export interface SourceAuthFormValues {
  type: string;
  basicUsername?: string;
  basicPassword?: string;
  apikeyHeader?: string;
  apikeyValue?: string;
  oauth2TokenUrl?: string;
  oauth2ClientId?: string;
  oauth2ClientSecret?: string;
  oauth2Scope?: string;
  oauth2CacheTtlSeconds?: string;
}

/** Only the selected type's fields are emitted — same as the gateway PUT body. */
export function sourceAuth(f: SourceAuthFormValues): SettingEntry[] {
  const out: SettingEntry[] = [plain('gateway.source.auth.type', f.type)];
  if (f.type === 'basic') {
    out.push(plain('gateway.source.auth.basic.username', f.basicUsername));
    out.push(...secretEntry('gateway.source.auth.basic.password', f.basicPassword));
  } else if (f.type === 'apikey') {
    out.push(plain('gateway.source.auth.apikey.header', f.apikeyHeader));
    out.push(...secretEntry('gateway.source.auth.apikey.value', f.apikeyValue));
  } else if (f.type === 'oauth2') {
    out.push(plain('gateway.source.auth.oauth2.token-url', f.oauth2TokenUrl));
    out.push(plain('gateway.source.auth.oauth2.client-id', f.oauth2ClientId));
    out.push(...secretEntry('gateway.source.auth.oauth2.client-secret', f.oauth2ClientSecret));
    out.push(plain('gateway.source.auth.oauth2.scope', f.oauth2Scope));
    out.push(plain('gateway.source.auth.oauth2.cache-ttl-seconds', f.oauth2CacheTtlSeconds));
  }
  return out;
}

/** Ops GET rows -> form values. Secrets arrive masked and start BLANK (= keep). */
export function sourceAuthFromRows(rows: Row[]): SourceAuthFormValues {
  const m = byKey(rows);
  return {
    type: m['gateway.source.auth.type'] || 'basic',
    basicUsername: m['gateway.source.auth.basic.username'] ?? '',
    basicPassword: '',
    apikeyHeader: m['gateway.source.auth.apikey.header'] ?? '',
    apikeyValue: '',
    oauth2TokenUrl: m['gateway.source.auth.oauth2.token-url'] ?? '',
    oauth2ClientId: m['gateway.source.auth.oauth2.client-id'] ?? '',
    oauth2ClientSecret: '',
    oauth2Scope: m['gateway.source.auth.oauth2.scope'] ?? '',
    oauth2CacheTtlSeconds: m['gateway.source.auth.oauth2.cache-ttl-seconds'] ?? '',
  };
}

// --------------------------------------------------------------------- tenant

export interface TenantFormValues {
  field?: string;
  extra?: string;
  def?: string;
  trust?: boolean;
}

export function tenant(f: TenantFormValues): SettingEntry[] {
  return [
    plain('gateway.tenant.field-name', f.field),
    plain('gateway.tenant.extra-field-name', f.extra),
    plain('gateway.tenant.default-rest-tenant', f.def),
    plain('gateway.tenant.trust-extra-tenant-header', String(!!f.trust)),
  ];
}

export function tenantFromRows(rows: Row[]): TenantFormValues {
  const m = byKey(rows);
  return {
    field: m['gateway.tenant.field-name'] ?? 'company_id',
    extra: m['gateway.tenant.extra-field-name'] ?? 'filial_id',
    def: m['gateway.tenant.default-rest-tenant'] ?? '0',
    trust: (m['gateway.tenant.trust-extra-tenant-header'] ?? 'false') === 'true',
  };
}

// ------------------------------------------------------------------ AI config

export interface AiConfigFormValues {
  baseUrl?: string;
  apiKey?: string;
  model?: string;
}

export function aiConfig(f: AiConfigFormValues): SettingEntry[] {
  return [
    plain('gateway.ai.base-url', f.baseUrl),
    plain('gateway.ai.model', f.model),
    ...secretEntry('gateway.ai.api-key', f.apiKey),
  ];
}

export function aiConfigFromRows(rows: Row[]): AiConfigFormValues {
  const m = byKey(rows);
  return {
    baseUrl: m['gateway.ai.base-url'] ?? '',
    apiKey: '',
    model: m['gateway.ai.model'] ?? '',
  };
}

export const settingsKeys = {
  sourceAuth,
  sourceAuthFromRows,
  tenant,
  tenantFromRows,
  aiConfig,
  aiConfigFromRows,
};
