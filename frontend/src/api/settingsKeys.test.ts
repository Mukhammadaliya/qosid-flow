import { describe, it, expect } from 'vitest';
import {
  sourceAuth,
  tenant,
  aiConfig,
  sourceAuthFromRows,
  tenantFromRows,
  aiConfigFromRows,
  TYPED_FORM_KEYS,
} from './settingsKeys';

describe('settingsKeys.TYPED_FORM_KEYS', () => {
  it('covers keys emitted/read by the typed tabs', () => {
    expect(TYPED_FORM_KEYS.has('gateway.source.auth.oauth2.client-id')).toBe(true);
    expect(TYPED_FORM_KEYS.has('gateway.source.auth.oauth2.client-type')).toBe(true);
    expect(TYPED_FORM_KEYS.has('gateway.tenant.field-name')).toBe(true);
    expect(TYPED_FORM_KEYS.has('gateway.ai.api-key')).toBe(true);
  });

  it('does not hide generic registry keys', () => {
    expect(TYPED_FORM_KEYS.has('gateway.api.base-url')).toBe(false);
  });
});

describe('settingsKeys.sourceAuth', () => {
  it('maps a filled basic form to type + username + password entries', () => {
    expect(sourceAuth({ type: 'basic', basicUsername: 'svc', basicPassword: 'pw1' })).toEqual([
      { key: 'gateway.source.auth.type', value: 'basic', secret: false },
      { key: 'gateway.source.auth.basic.username', value: 'svc', secret: false },
      { key: 'gateway.source.auth.basic.password', value: 'pw1', secret: true },
    ]);
  });

  it('omits the password entry when left blank (keep-existing semantics)', () => {
    const entries = sourceAuth({ type: 'basic', basicUsername: 'svc', basicPassword: '' });
    expect(entries.map((e) => e.key)).toEqual([
      'gateway.source.auth.type',
      'gateway.source.auth.basic.username',
    ]);
  });

  it('omits the password entry when it still holds the mask', () => {
    const entries = sourceAuth({ type: 'basic', basicUsername: 'svc', basicPassword: '***' });
    expect(entries.some((e) => e.key === 'gateway.source.auth.basic.password')).toBe(false);
  });

  it('maps an apikey form to type + header + secret value', () => {
    expect(sourceAuth({ type: 'apikey', apikeyHeader: 'X-Api-Key', apikeyValue: 'k1' })).toEqual([
      { key: 'gateway.source.auth.type', value: 'apikey', secret: false },
      { key: 'gateway.source.auth.apikey.header', value: 'X-Api-Key', secret: false },
      { key: 'gateway.source.auth.apikey.value', value: 'k1', secret: true },
    ]);
  });

  it('omits the apikey value when blank or masked', () => {
    for (const v of ['', '***', undefined]) {
      const entries = sourceAuth({ type: 'apikey', apikeyHeader: 'X-Api-Key', apikeyValue: v });
      expect(entries.some((e) => e.key === 'gateway.source.auth.apikey.value')).toBe(false);
    }
  });

  it('maps a full oauth2 form including cache-ttl and client-type', () => {
    expect(
      sourceAuth({
        type: 'oauth2',
        oauth2TokenUrl: 'https://idp/token',
        oauth2ClientId: 'cid',
        oauth2ClientSecret: 'sec',
        oauth2Scope: 'read',
        oauth2CacheTtlSeconds: '3600',
        oauth2ClientType: 'biruni',
      }),
    ).toEqual([
      { key: 'gateway.source.auth.type', value: 'oauth2', secret: false },
      { key: 'gateway.source.auth.oauth2.token-url', value: 'https://idp/token', secret: false },
      { key: 'gateway.source.auth.oauth2.client-id', value: 'cid', secret: false },
      { key: 'gateway.source.auth.oauth2.client-secret', value: 'sec', secret: true },
      { key: 'gateway.source.auth.oauth2.scope', value: 'read', secret: false },
      { key: 'gateway.source.auth.oauth2.cache-ttl-seconds', value: '3600', secret: false },
      { key: 'gateway.source.auth.oauth2.client-type', value: 'biruni', secret: false },
    ]);
  });

  it('emits client-type "standard" by default when the field is blank', () => {
    const entries = sourceAuth({
      type: 'oauth2',
      oauth2TokenUrl: 'https://idp/token',
      oauth2ClientId: 'cid',
    });
    expect(entries).toContainEqual({
      key: 'gateway.source.auth.oauth2.client-type',
      value: 'standard',
      secret: false,
    });
  });

  it('omits a blank oauth2 client-secret but keeps the other oauth2 fields', () => {
    const entries = sourceAuth({
      type: 'oauth2',
      oauth2TokenUrl: 'https://idp/token',
      oauth2ClientId: 'cid',
      oauth2ClientSecret: '',
      oauth2Scope: '',
      oauth2CacheTtlSeconds: '',
    });
    expect(entries.some((e) => e.key === 'gateway.source.auth.oauth2.client-secret')).toBe(false);
    expect(entries.map((e) => e.key)).toContain('gateway.source.auth.oauth2.token-url');
  });

  it('only emits fields for the selected type (residual values from other tabs ignored)', () => {
    const entries = sourceAuth({
      type: 'basic',
      basicUsername: 'svc',
      basicPassword: 'pw',
      oauth2TokenUrl: 'https://stale/token',
      apikeyHeader: 'X-Stale',
    });
    expect(entries.map((e) => e.key)).toEqual([
      'gateway.source.auth.type',
      'gateway.source.auth.basic.username',
      'gateway.source.auth.basic.password',
    ]);
  });

  it('coerces undefined plain fields to empty strings', () => {
    expect(sourceAuth({ type: 'basic' })).toEqual([
      { key: 'gateway.source.auth.type', value: 'basic', secret: false },
      { key: 'gateway.source.auth.basic.username', value: '', secret: false },
    ]);
  });
});

describe('settingsKeys.tenant', () => {
  it('maps all four tenant fields, boolean switch to "true"/"false"', () => {
    expect(tenant({ field: 'company_id', extra: 'filial_id', def: '0', trust: true })).toEqual([
      { key: 'gateway.tenant.field-name', value: 'company_id', secret: false },
      { key: 'gateway.tenant.extra-field-name', value: 'filial_id', secret: false },
      { key: 'gateway.tenant.default-rest-tenant', value: '0', secret: false },
      { key: 'gateway.tenant.trust-extra-tenant-header', value: 'true', secret: false },
    ]);
  });

  it('serializes trust=false and defaults undefined values to empty strings', () => {
    const entries = tenant({ trust: false });
    expect(entries).toEqual([
      { key: 'gateway.tenant.field-name', value: '', secret: false },
      { key: 'gateway.tenant.extra-field-name', value: '', secret: false },
      { key: 'gateway.tenant.default-rest-tenant', value: '', secret: false },
      { key: 'gateway.tenant.trust-extra-tenant-header', value: 'false', secret: false },
    ]);
  });
});

describe('settingsKeys.aiConfig', () => {
  it('maps base-url + model + typed api-key', () => {
    expect(aiConfig({ baseUrl: 'https://llm/v1', apiKey: 'sk-new', model: 'm1' })).toEqual([
      { key: 'gateway.ai.base-url', value: 'https://llm/v1', secret: false },
      { key: 'gateway.ai.model', value: 'm1', secret: false },
      { key: 'gateway.ai.api-key', value: 'sk-new', secret: true },
    ]);
  });

  it('omits the api-key when blank or masked', () => {
    for (const v of ['', '***', undefined]) {
      const entries = aiConfig({ baseUrl: 'https://llm/v1', apiKey: v, model: 'm1' });
      expect(entries.some((e) => e.key === 'gateway.ai.api-key')).toBe(false);
      expect(entries.map((e) => e.key)).toEqual(['gateway.ai.base-url', 'gateway.ai.model']);
    }
  });
});

describe('settingsKeys fromRows helpers (ops GET -> form values)', () => {
  it('sourceAuthFromRows maps rows and blanks the secret fields', () => {
    const f = sourceAuthFromRows([
      { key: 'gateway.source.auth.type', value: 'oauth2' },
      { key: 'gateway.source.auth.oauth2.token-url', value: 'https://idp/token' },
      { key: 'gateway.source.auth.oauth2.client-id', value: 'cid' },
      { key: 'gateway.source.auth.oauth2.client-secret', value: '***' },
      { key: 'gateway.source.auth.basic.username', value: 'svc' },
      { key: 'gateway.source.auth.basic.password', value: '***' },
    ]);
    expect(f.type).toBe('oauth2');
    expect(f.oauth2TokenUrl).toBe('https://idp/token');
    expect(f.oauth2ClientId).toBe('cid');
    expect(f.basicUsername).toBe('svc');
    // secrets never round-trip: the form starts blank = keep existing
    expect(f.oauth2ClientSecret).toBe('');
    expect(f.basicPassword).toBe('');
    expect(f.apikeyValue).toBe('');
  });

  it('sourceAuthFromRows reads client-type and defaults it to standard', () => {
    expect(
      sourceAuthFromRows([{ key: 'gateway.source.auth.oauth2.client-type', value: 'biruni' }])
        .oauth2ClientType,
    ).toBe('biruni');
    expect(sourceAuthFromRows([]).oauth2ClientType).toBe('standard');
  });

  it('sourceAuthFromRows defaults type to basic when no row exists', () => {
    expect(sourceAuthFromRows([]).type).toBe('basic');
  });

  it('tenantFromRows maps rows with C-faza defaults for missing keys', () => {
    expect(tenantFromRows([{ key: 'gateway.tenant.field-name', value: 'org_id' }])).toEqual({
      field: 'org_id',
      extra: 'filial_id',
      def: '0',
      trust: false,
    });
    expect(
      tenantFromRows([{ key: 'gateway.tenant.trust-extra-tenant-header', value: 'true' }]).trust,
    ).toBe(true);
  });

  it('aiConfigFromRows maps rows and blanks the api key', () => {
    expect(
      aiConfigFromRows([
        { key: 'gateway.ai.base-url', value: 'https://llm/v1' },
        { key: 'gateway.ai.model', value: 'm1' },
        { key: 'gateway.ai.api-key', value: '***' },
      ]),
    ).toEqual({ baseUrl: 'https://llm/v1', apiKey: '', model: 'm1' });
  });
});
