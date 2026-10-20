import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { betLadder, nextBet, prizeIndexOfSlot, round2, slotTier } from '../src/rules';

// Review N15.5: the layout both sides are tested against (the server's OddsTest reads the same file), for the boards
// the server offers (8 to 14 rows): the game must put every prize where the server pays it. The file lives with the
// server's tests, because the server's image build (which runs them) only has backend/.
const FIXTURE = new URL('../../backend/src/test/resources/fixtures/slot-layout.json', import.meta.url);
const layout = JSON.parse(readFileSync(FIXTURE, 'utf8')) as { rows: Record<string, number[]> };
const boards = Object.keys(layout.rows).map(Number);

describe('slot layout (review 5.7 #2)', () => {
  it('covers every board the server offers', () => {
    expect(boards).toEqual([8, 9, 10, 11, 12, 13, 14]);
  });

  it('matches the shared layout for every board', () => {
    for (const rows of boards) {
      const slots = Array.from({ length: rows + 1 }, (_, slot) => prizeIndexOfSlot(rows, slot));
      expect(slots, `${rows} rows`).toEqual(layout.rows[rows]);
    }
  });

  it('colours run from the centre tier 0 to the edge tier 4', () => {
    for (const rows of boards) {
      expect(slotTier(rows, 0)).toBe(0);
      expect(slotTier(rows, Math.floor(rows / 2))).toBe(4);
    }
  });
});

describe('bets (review 5.7 #3)', () => {
  it('a ladder holds the limits and 1-2-5 steps between them', () => {
    expect(betLadder(0.1, 100, 1)).toEqual([0.1, 0.2, 0.5, 1, 2, 5, 10, 20, 50, 100]);
    expect(betLadder(0.25, 3, 0.75)).toEqual([0.25, 0.5, 0.75, 1, 2, 3]);
  });

  it('stepping stops at the limits', () => {
    const ladder = betLadder(0.1, 10);
    expect(nextBet(ladder, 10, true)).toBe(10);
    expect(nextBet(ladder, 0.1, false)).toBe(0.1);
    expect(nextBet(ladder, 1, true)).toBe(2);
    expect(nextBet(ladder, 1, false)).toBe(0.5);
  });

  it('amounts are rounded to the cent', () => {
    expect(round2(0.1 + 0.2)).toBe(0.3);
    expect(round2(0.2 * 3)).toBe(0.6);
    expect(round2(12.3456)).toBe(12.35);
  });
});
