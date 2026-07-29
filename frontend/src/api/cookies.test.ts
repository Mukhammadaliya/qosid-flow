import { describe, it, expect, afterEach } from 'vitest';
import { getCookie } from './cookies';

describe('getCookie', () => {
  afterEach(() => { document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT'; });

  it('returns the value of a named cookie', () => {
    document.cookie = 'XSRF-TOKEN=abc123';
    expect(getCookie('XSRF-TOKEN')).toBe('abc123');
  });

  it('returns null when absent', () => {
    expect(getCookie('MISSING')).toBeNull();
  });
});
