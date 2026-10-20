import { describe, expect, it } from 'vitest';
import { errorText, formatAmount } from '../src/api';

describe('what the player is told (review 5.7 #1)', () => {
  const text = (code: string, status = 400) => errorText({ status, code });

  it('every server answer has its own message', () => {
    const codes = ['INVALID_TOKEN', 'SESSION_EXPIRED', 'NO_SESSION', 'CURRENCY_NOT_SUPPORTED', 'PLAYER_BLOCKED', 'GAME_DISABLED',
      'INSUFFICIENT_FUNDS', 'ROUND_IN_PROGRESS', 'SESSION_LOST', 'REQUEST_MISMATCH', 'REQUEST_NOT_PLAYED', 'ROUND_UNDER_REVIEW',
      'BET_TOO_HIGH', 'LIMIT_REACHED', 'TOO_FAST'];
    const fallback = text('SOMETHING_ELSE');
    for (const code of codes) expect(text(code), code).not.toBe(fallback);
  });

  it('a session that ended says to relaunch from the casino', () => {
    expect(text('SESSION_EXPIRED', 401)).toMatch(/relaunch/);
    expect(text('INVALID_TOKEN', 401)).toMatch(/relaunch/);
  });

  it('a round under review reassures the player', () => {
    expect(text('ROUND_UNDER_REVIEW', 503)).toMatch(/safe/);
  });

  it('the casino\'s own limits are named as such', () => {
    expect(text('LIMIT_REACHED', 403)).toMatch(/limit/i);
  });

  it('unknown and network failures fall back to a connection message', () => {
    expect(text('NETWORK', 0)).toMatch(/connect/i);
  });
});

describe('amounts', () => {
  it('always show two decimals with the currency symbol', () => {
    expect(formatAmount('€', 1)).toBe('€1.00');
    expect(formatAmount('$', 1234.5)).toBe('$1,234.50');
    expect(formatAmount('RON ', 0.1)).toBe('RON 0.10');
  });
});
