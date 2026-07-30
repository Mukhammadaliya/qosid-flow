import { describe, it, expect } from 'vitest';
import { qosidTheme } from './theme';

describe('qosidTheme', () => {
  it('uses the brand primary and success colors', () => {
    expect(qosidTheme.token?.colorPrimary).toBe('#0095D9');
    expect(qosidTheme.token?.colorSuccess).toBe('#2EC27E');
  });
});
