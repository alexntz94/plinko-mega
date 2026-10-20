import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { one, reset } from './stubs';
import type { ApiError, MeterInfo, Round, Session } from '../src/api';

// Review 5.7 #1, #3, #4, #5: the game's own logic, run in Node. Everything it draws or plays is stood in for and
// records what it was told (test/stubs.ts); the server calls (playRound, resumeSession) are answered by each test.
vi.mock('pixi.js', async () => (await import('./stubs')).pixi());
vi.mock('gsap', async () => (await import('./stubs')).gsapStub());
vi.mock('../src/api', async importOriginal => ({
  ...(await importOriginal<typeof import('../src/api')>()),
  playRound: vi.fn(),
  resumeSession: vi.fn(),
}));
vi.mock('../src/board', async () => {
  const { Stub } = await import('./stubs');
  const { slotTier } = await import('../src/rules');
  class Board extends Stub {
    rows = 8;
    bumpButton = new Stub();
    setRows(rows: number) { this.rows = rows; this.calls.push(['setRows', rows]); }
    /** A ball lands a second after it is dropped, on the slot the server chose. */
    dropBall(slot: number, handlers: { onLand: (landing: object) => void }, boost = 1) {
      this.calls.push(['dropBall', slot, boost]);
      const prizeIndex = Math.floor(Math.abs(slot - this.rows / 2));
      return new Promise<void>(resolve => setTimeout(() => {
        handlers.onLand({ slot, prizeIndex, x: 0, y: 0, pan: 0 });
        resolve();
      }, 1000));
    }
  }
  return { Board, slotTier };
});
vi.mock('../src/fortuneWheel', async () => {
  const { Stub } = await import('./stubs');
  return { FortuneWheel: class FortuneWheel extends Stub { turn() { this.calls.push(['turn']); return Promise.resolve(); } } };
});
vi.mock('../src/ticker', async () => {
  const { Stub } = await import('./stubs');
  return { WinTicker: class WinTicker extends Stub {} };
});
vi.mock('../src/megaControls', async () => {
  const { Stub } = await import('./stubs');
  return { MegaControls: class MegaControls extends Stub {} };
});
vi.mock('../src/meter', async () => {
  const { Stub } = await import('./stubs');
  return { Meter: class Meter extends Stub {} };
});
vi.mock('../src/historyPanel', async () => {
  const { Stub } = await import('./stubs');
  return { HistoryPanel: class HistoryPanel extends Stub { open = false; } };
});
vi.mock('../src/effects', async () => {
  const { Stub } = await import('./stubs');
  return {
    BigWin: class BigWin extends Stub {},
    winLevel: (win: number, bet: number) => (bet > 0 && win / bet >= 10 ? 'BIG WIN' : null),
  };
});
vi.mock('../src/screens', async () => {
  const { Stub } = await import('./stubs');
  return { Menu: class Menu extends Stub {}, ExitDialog: class ExitDialog extends Stub {}, ResultScreen: class ResultScreen extends Stub {},
    Options: class Options extends Stub {} };
});
vi.mock('../src/responsible', async () => {
  const { Stub } = await import('./stubs');
  return { ResponsiblePlay: class ResponsiblePlay extends Stub { pausing = false; allowed() { return Promise.resolve(true); } } };
});
vi.mock('../src/sound', () => ({ play: () => undefined, isMuted: () => false, setMuted: () => undefined }));
vi.mock('../src/theme', () => ({ neonLayers: () => [] }));

import { errorText, playRound, resumeSession } from '../src/api';
import { Game } from '../src/game';
import type { Theme } from '../src/theme';

const play = vi.mocked(playRound);
const resume = vi.mocked(resumeSession);

const THEME = {
  accent: '#ff0', text: '#fff', background: '#000', playText: undefined, landscapeExitFromPortrait: false,
  layout: {}, board: { fx: 'standard', peg: null, slots: [] },
  wheel: { faces: ['#fff'], text: ['#000'], rim: '#000', ink: '#000', title: '#fff', gold: { faces: ['#ff0'], text: ['#000'], rim: '#000' },
    hub: null, pointer: null, gate: null }, ui: { style: 'flat', drop: null, controls: null, ink: '#000', panel: '#fff' },
} as unknown as Theme;

const METER: MeterInfo = { enabled: true, charges: 1, of: 5, positions: { '8:0': [4], '8:1': [3, 4], '10:0': [5] } };

function session(balance: number, extra: Partial<Session['config']> = {}): Session {
  const prizes = (rows: number) => Array.from({ length: rows / 2 + 1 }, (_, i) => [0.5 + i, 0.4 + 2 * i, 0.2 + 3 * i]);
  return {
    sessionId: 's1', player: { username: 'alice' }, currency: 'EUR', currencySymbol: '€', balance, theme: 'loteria-muerta',
    config: { minBet: 0.1, maxBet: 100, defaultBet: 1, maxBalls: 100, rows: [{ rows: 8, prizes: prizes(8) }, { rows: 10, prizes: prizes(10) }],
      holdToDrop: true, ...extra },
    meter: METER,
  };
}

/** A server answer: slots and wins per ball, the balance after the round. */
function round(start: number, bet: number, wins: number[], free: null = null, totalWin?: number, meter?: MeterInfo): Round {
  const sum = (values: number[]) => Math.round(values.reduce((a, b) => a + b, 0) * 100) / 100;
  const win = totalWin ?? sum(wins);
  const totalBet = Math.round(bet * wins.length * 100) / 100;
  return {
    roundId: 'r' + Math.random(), slots: wins.map((_, i) => i % 9), multipliers: wins.map(w => w / bet), totalBet, totalWin: win,
    balance: Math.round((start - totalBet + win) * 100) / 100, boosts: wins.map(() => 1), hotSlot: null, hotBoost: null,
    wins, meter,
  };
}

/** A server call the test answers when it wants to. */
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

let fatal: string[];

interface BarActions { play(): void; auto(): void; bet(up: boolean): void; risk(step: number): void; balls(step: number): void; rows(step: number): void }

/**
 * A game on its table (the start screen's button pressed), and its control bar's actions; balls(index) and rows(index)
 * choose a value by its place (index 0: 1 ball, the fewest rows), stepping the bar's − and + there.
 */
async function table(start: number, extra: Partial<Session['config']> = {}, fresh?: Partial<Session>) {
  fatal = [];
  resume.mockResolvedValueOnce({ ...session(start, extra), ...fresh });
  const game = new Game(session(start, extra), THEME, () => ({}) as never, text => fatal.push(text));
  (one('Menu').args[3] as () => void)();
  await vi.advanceTimersByTimeAsync(0);
  const bar = one('MegaControls').args[1] as BarActions;
  let balls = 0;
  let rows = 0;
  return { game, bar, actions: {
    bet: (up: boolean) => bar.bet(up), risk: (step: number) => bar.risk(step), play: () => bar.play(),
    balls: (index: number) => { bar.balls(index - balls); balls = index; },
    rows: (index: number) => { bar.rows(index - rows); rows = index; },
  } };
}

const controls = () => one('MegaControls');
const board = () => one('Board');
const credit = () => controls().last('setBalance');
const statuses = () => board().calledWith('setStatus').map(c => c[0] as string);
/** Every balance the panel showed, as numbers. */
const credits = () => controls().calledWith('setBalance').map(c => Number(String(c[0]).replace(/[€,]/g, '')));

beforeEach(() => {
  reset();
  play.mockReset();
  resume.mockReset();
  vi.useFakeTimers();
  vi.stubGlobal('window', { addEventListener: () => undefined, localStorage: { getItem: () => null, setItem: () => undefined } });
  vi.stubGlobal('document', { fullscreenEnabled: false, documentElement: {} });
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe('the balance shown is the server\'s (review 5.7 #5)', () => {
  it('starts from the casino\'s latest balance', async () => {
    await table(100, {}, { balance: 87.65 });
    expect(credit()).toBe('€87.65');
  });

  it('a 100-ball round ends on exactly the server\'s balance, never showing more on the way', async () => {
    const { actions } = await table(500);
    for (let i = 0; i < 99; i++) actions.balls(i + 1);    // the BALLS slider to 100
    const cents = [0.07, 0.13, 0.29, 0, 1.1, 0.01, 0.33];
    const answer = round(500, 1, Array.from({ length: 100 }, (_, i) => cents[i % cents.length]));
    play.mockResolvedValueOnce(answer);
    actions.play();
    await vi.advanceTimersByTimeAsync(0);
    expect(play).toHaveBeenCalledWith(8, 0, 1, 100);
    // the bet is gone, the win not yet: it shows as the balls land
    expect(credit()).toBe('€' + (500 - 100).toFixed(2));
    const shownBefore = credits().length;
    await vi.runAllTimersAsync();
    const during = credits().slice(shownBefore);
    expect(Math.max(...during)).toBeLessThanOrEqual(answer.balance);
    expect(during).toEqual([...during].sort((a, b) => a - b));
    expect(credit()).toBe('€' + answer.balance.toFixed(2));
    expect(board().calledWith('dropBall')).toHaveLength(100);
  });

  it('Plinko Mega: wheel spins and a MEGA SPIN are added as they stop, and the round ends on the server\'s balance', async () => {
    const mega = { gates: { '8': [[3, 0], [5, 5], [7, 2]] }, spinSlots: { '8': [0, 8] }, gateBoost: 2,
      wheel: [{ multiplier: 2 }, { multiplier: 5 }, { name: 'MINI', multiplier: 50 }, { multiplier: 1 }],
      megaWheel: [{ multiplier: 1 }, { multiplier: 3 }, { name: 'MEGA', multiplier: 100 }, { multiplier: 2 }] };
    const { actions, bar } = await table(100, { mega });
    bar.balls(2);
    const base = round(100, 1, [0.3, 0.6, 2.4]);
    const spins = [{ ball: 0, segment: 2, multiplier: 50, win: 50 }];
    const megaSpins = [{ stake: 1.5, segment: 1, multiplier: 3, win: 4.5 }];
    const totalWin = Math.round((base.totalWin + 50 + 4.5) * 100) / 100;
    const answer = { ...base, paths: [0, 15, 3], gates: [1, 0, 0], spins, megaSpins, totalWin,
      balance: Math.round((100 - base.totalBet + totalWin) * 100) / 100 };
    play.mockResolvedValueOnce(answer);
    actions.play();
    await vi.runAllTimersAsync();
    // each ball falls along the server's path
    expect(board().calledWith('dropBall')).toHaveLength(3);
    expect(Math.max(...credits())).toBeLessThanOrEqual(answer.balance);
    expect(credit()).toBe('€' + answer.balance.toFixed(2));
    expect(one('FortuneWheel').calledWith('turn')).toHaveLength(2);
  });

  it('a capped round (balls worth more than the round pays) never shows more than the server paid', async () => {
    const { actions } = await table(100);
    actions.balls(4);
    const answer = round(100, 1, [20, 20, 20, 20, 20], null, 40);
    play.mockResolvedValueOnce(answer);
    actions.play();
    await vi.runAllTimersAsync();
    expect(Math.max(...credits())).toBeLessThanOrEqual(answer.balance);
    expect(credit()).toBe('€' + answer.balance.toFixed(2));
  });

  it('rounds played back to back end on the last round\'s balance', async () => {
    const { actions } = await table(100);
    const first = round(100, 1, [0.5]);
    const second = round(first.balance, 1, [2.6]);
    play.mockResolvedValueOnce(first).mockResolvedValueOnce(second);
    actions.play();
    await vi.advanceTimersByTimeAsync(0);
    actions.play();
    await vi.runAllTimersAsync();
    expect(play).toHaveBeenCalledTimes(2);
    expect(credit()).toBe('€' + second.balance.toFixed(2));
  });

  it('a refusal that carries the balance shows it', async () => {
    const { actions } = await table(100);
    play.mockRejectedValueOnce({ status: 402, code: 'INSUFFICIENT_FUNDS', balance: 0.42 } as ApiError);
    actions.play();
    await vi.runAllTimersAsync();
    expect(credit()).toBe('€0.42');
  });

  it('after a lost or unclear round the casino\'s balance is read again, never guessed', async () => {
    for (const failure of [{ status: 0, code: 'NETWORK' }, { status: 409, code: 'ROUND_STILL_RUNNING' },
      { status: 503, code: 'WALLET_UNAVAILABLE' }] as ApiError[]) {
      reset();
      const { actions } = await table(100);
      resume.mockResolvedValueOnce(session(97.5));
      play.mockRejectedValueOnce(failure);
      actions.play();
      await vi.runAllTimersAsync();
      expect(credit(), failure.code).toBe('€97.50');
    }
  });

  it('a bet the shown balance can\'t pay is not sent', async () => {
    const { actions } = await table(0.5);
    actions.play();
    await vi.runAllTimersAsync();
    expect(play).not.toHaveBeenCalled();
    expect(statuses()).toContain('NOT ENOUGH CREDIT');
  });
});

describe('quick taps (review 5.7 #3)', () => {
  it('taps while a round is asked for are kept, at most 3, and played one after the other', async () => {
    const { actions } = await table(100);
    const answers = Array.from({ length: 6 }, () => deferred<Round>());
    answers.forEach(a => play.mockReturnValueOnce(a.promise));
    for (let tap = 0; tap < 6; tap++) actions.play();
    await vi.advanceTimersByTimeAsync(0);
    expect(play).toHaveBeenCalledTimes(1);      // only one request is ever on its way
    let balance = 100;
    for (let n = 0; n < 4; n++) {
      const answer = round(balance, 1, [0.5]);
      balance = answer.balance;
      answers[n].resolve(answer);
      await vi.advanceTimersByTimeAsync(0);
    }
    await vi.runAllTimersAsync();
    expect(play).toHaveBeenCalledTimes(4);      // the first tap and 3 kept ones
    expect(credit()).toBe('€' + balance.toFixed(2));
  });

  it('a failed round drops the taps kept after it', async () => {
    const { actions } = await table(100);
    const first = deferred<Round>();
    play.mockReturnValueOnce(first.promise);
    actions.play();
    actions.play();
    actions.play();
    await vi.advanceTimersByTimeAsync(0);
    first.reject({ status: 503, code: 'WALLET_UNAVAILABLE' });
    resume.mockResolvedValue(session(100));
    await vi.runAllTimersAsync();
    expect(play).toHaveBeenCalledTimes(1);
  });

  it('the settings are locked while a round is asked for or its balls fall, and free again after', async () => {
    const { actions } = await table(100);
    const answer = deferred<Round>();
    play.mockReturnValueOnce(answer.promise);
    actions.play();
    await vi.advanceTimersByTimeAsync(0);
    expect(controls().last('setPlaying')).toBe(true);
    const betBefore = controls().last('setTotalBet');
    actions.bet(true);
    actions.risk(1);
    actions.rows(1);
    actions.balls(5);
    answer.resolve(round(100, 1, [0.5, 1, 2]));
    await vi.advanceTimersByTimeAsync(0);
    actions.bet(true);    // balls still falling
    expect(controls().last('setTotalBet')).toBe(betBefore);
    expect(controls().last('setRows')).toBe(8);
    expect(controls().last('setRisk')).toBe('LOW');
    expect(controls().last('setBalls')).toBe(1);
    await vi.runAllTimersAsync();
    expect(controls().last('setPlaying')).toBe(false);
    actions.bet(true);
    expect(controls().last('setTotalBet')).toBe('€2.00');
  });
});

describe('bet steps, ½ and 2X (review 5.7 #3)', () => {
  it('½ and 2X step along the ladder and stop at the limits', async () => {
    const { actions } = await table(1000, { minBet: 0.2, maxBet: 50, defaultBet: 0.75 });
    expect(controls().last('setTotalBet')).toBe('€0.75');
    actions.bet(true);
    expect(controls().last('setTotalBet')).toBe('€1.00');
    for (let i = 0; i < 20; i++) actions.bet(true);
    expect(controls().last('setTotalBet')).toBe('€50.00');
    for (let i = 0; i < 20; i++) actions.bet(false);
    expect(controls().last('setTotalBet')).toBe('€0.20');
    actions.bet(false);
    expect(controls().last('setTotalBet')).toBe('€0.20');
    play.mockResolvedValueOnce(round(1000, 0.2, [0]));
    actions.play();
    await vi.advanceTimersByTimeAsync(0);
    expect(play).toHaveBeenCalledWith(8, 0, 0.2, 1);
  });

  it('the price of a play is the bet times the balls', async () => {
    const { actions } = await table(1000);
    actions.balls(9);
    actions.bet(true);
    expect(statuses().at(-1)).toBe('PLAY €20.00');
  });
});

describe('what a failed round shows (review 5.7 #1)', () => {
  async function fail(failure: ApiError) {
    const { actions } = await table(100);
    resume.mockResolvedValue(session(100));
    play.mockRejectedValueOnce(failure);
    actions.play();
    await vi.advanceTimersByTimeAsync(0);
  }

  for (const code of ['SESSION_EXPIRED', 'PLAYER_BLOCKED', 'GAME_DISABLED', 'CURRENCY_NOT_SUPPORTED']) {
    it(`${code} ends the game with its own message`, async () => {
      await fail({ status: code === 'GAME_DISABLED' ? 503 : 403, code });
      expect(fatal).toEqual([errorText({ status: 0, code })]);
    });
  }

  const flashed: [ApiError, string][] = [
    [{ status: 402, code: 'INSUFFICIENT_FUNDS' }, 'NOT ENOUGH CREDIT'],
    [{ status: 400, code: 'BET_TOO_HIGH' }, 'BET TOO HIGH FOR THIS BOARD'],
    [{ status: 409, code: 'ROUND_IN_PROGRESS' }, 'ROUND IN PROGRESS, WAIT'],
    [{ status: 409, code: 'ROUND_STILL_RUNNING' }, 'ROUND IN PROGRESS, WAIT'],
    [{ status: 429, code: 'TOO_FAST' }, 'TOO FAST, SLOW DOWN'],
    [{ status: 429, code: 'RATE_LIMITED' }, 'TOO FAST, SLOW DOWN'],
    [{ status: 503, code: 'WALLET_UNAVAILABLE' }, 'CONNECTION ERROR, TRY AGAIN'],
    [{ status: 0, code: 'NETWORK' }, 'CONNECTION ERROR, TRY AGAIN'],
  ];
  for (const [failure, text] of flashed) {
    it(`${failure.code} (${failure.status}) is shown on the board and play goes on`, async () => {
      await fail(failure);
      expect(fatal).toEqual([]);
      expect(statuses().at(-1)).toBe(text);
      await vi.runAllTimersAsync();
      expect(statuses().at(-1)).toBe('PLAY €1.00');
    });
  }

  // a round blocked for review (D3: 503 ROUND_UNDER_REVIEW) and the casino's own limit (403 LIMIT_REACHED) say so,
  // not 'CONNECTION ERROR'
  for (const failure of [{ status: 503, code: 'ROUND_UNDER_REVIEW' }, { status: 403, code: 'LIMIT_REACHED' }] as ApiError[]) {
    it(`${failure.code} is shown with its own message during play, not as a connection error`, async () => {
      await fail(failure);
      expect(statuses().at(-1)).not.toBe('CONNECTION ERROR, TRY AGAIN');
    });
  }

  // review 7.4: where the market requires proof, the server holds rounds until the reality check is shown and answered
  it('REALITY_CHECK_DUE shows the reality check at once, not an error', async () => {
    await fail({ status: 409, code: 'REALITY_CHECK_DUE' });
    expect(one('ResponsiblePlay').calledWith('checkNow')).toHaveLength(1);
    expect(fatal).toEqual([]);
    expect(statuses()).not.toContain('CONNECTION ERROR, TRY AGAIN');
  });
});

describe('the bonus meter on the table (review 5.7 #4)', () => {
  it('shows the server\'s meter, and marks the slots that charge it on this board and risk', async () => {
    const { actions } = await table(100, {}, { meter: { ...METER, charges: 2 } });
    const meter = one('Meter');
    expect(meter.calledWith('set')[0]).toEqual([true, 1, 5]);       // from the session
    expect(meter.calledWith('set').at(-1)).toEqual([true, 2, 5]);   // the casino's latest, read when the table opens
    expect(board().last('setMeterPositions')).toEqual([4]);
    actions.risk(1);
    expect(board().last('setMeterPositions')).toEqual([3, 4]);
    actions.rows(1);
    expect(board().last('setMeterPositions')).toEqual([]);          // 10 rows, MID: none
  });

  it('charges once per paid ball landing on a charging slot (not for free balls), then takes the server\'s count', async () => {
    const { actions } = await table(100);
    actions.balls(3);
    const answer = round(100, 1, [0.5, 0.5, 0.5, 0.5], { slots: [0, 8], bets: [1, 1], wins: [5.6, 5.6] }, undefined,
      { ...METER, charges: 0 });
    answer.slots = [0, 8, 4, 0];    // prize positions 4, 4, 0, 4: three charge the meter
    play.mockResolvedValueOnce(answer);
    actions.play();
    await vi.runAllTimersAsync();
    const meter = one('Meter');
    expect(meter.calledWith('charge')).toHaveLength(3);
    expect(meter.calledWith('set').at(-1)).toEqual([true, 0, 5]);
  });

  it('the meter switched off by an admin is hidden at the next round', async () => {
    const { actions } = await table(100);
    play.mockResolvedValueOnce(round(100, 1, [0.5], null, undefined, { enabled: false, charges: 0, of: 0, positions: {} }));
    actions.play();
    await vi.runAllTimersAsync();
    expect(one('Meter').calledWith('set').at(-1)).toEqual([false, 0, 0]);
    expect(board().last('setMeterPositions')).toEqual([]);
  });
});
