import { describe, it, expect } from 'vitest';
import { smartupTheme } from './theme';

describe('smartupTheme', () => {
  it('uses the Smartup brand primary and success colors', () => {
    expect(smartupTheme.token?.colorPrimary).toBe('#0095D9');
    expect(smartupTheme.token?.colorSuccess).toBe('#2EC27E');
  });
});
