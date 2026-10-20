import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { GameConfig, ShownEvent } from '../src/api';
import { ResponsiblePlay } from '../src/responsible';

// Review 7.4: the reality check and the session clock are reported to the server when shown, and when the player
// continues (after the "shown" report, before the next round); the server's REALITY_CHECK_DUE shows the check at once.

/** Just enough of an element for the dialog and the clock: children, hidden, text, click handlers. */
class FakeElement {
  hidden = false;
  className = '';
  textContent = '';
  type = '';
  children: FakeElement[] = [];
  private clicks: (() => void)[] = [];
  constructor(readonly tag: string) {}
  setAttribute(): void { /* roles only */ }
  append(...items: FakeElement[]): void { this.children.push(...items); }
  replaceChildren(): void { this.children = []; }
  addEventListener(_: string, handler: () => void): void { this.clicks.push(handler); }
  focus(): void { /* nothing to focus in Node */ }
  click(): void { this.clicks.forEach(handler => handler()); }
  /** Every element below this one. */
  all(): FakeElement[] { return this.children.flatMap(child => [child, ...child.all()]); }
}

let body: FakeElement;
let reported: ShownEvent[];
let answers: { resolve: () => void }[];

const CONFIG = { minBet: 0.1, maxBet: 100, defaultBet: 1, maxBalls: 10, rows: [] } as GameConfig;

/** The report function the game is given: records each report, answered when the test says so (or at once). */
function reporter(waitForTest = false) {
  return (type: ShownEvent) => {
    reported.push(type);
    if (!waitForTest) return Promise.resolve();
    return new Promise<void>(resolve => answers.push({ resolve }));
  };
}

const dialog = () => body.children.find(e => e.className === 'reality-check')!;
const button = (text: string) => dialog().all().find(e => e.tag === 'button' && e.textContent === text)!;

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date('2026-10-19T12:00:00Z'));
  body = new FakeElement('body');
  reported = [];
  answers = [];
  const store = new Map<string, string>();
  vi.stubGlobal('document', { body, createElement: (tag: string) => new FakeElement(tag) });
  vi.stubGlobal('window', { setInterval: () => 0,
    sessionStorage: { getItem: (k: string) => store.get(k) ?? null, setItem: (k: string, v: string) => store.set(k, v) } });
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe('what the game reports showing (review 7.4)', () => {
  it('reports the session clock when it is shown', () => {
    new ResponsiblePlay({ ...CONFIG, sessionClock: true }, 's1', v => `€${v}`, () => undefined, reporter());
    expect(reported).toEqual(['SESSION_CLOCK_SHOWN']);
    new ResponsiblePlay({ ...CONFIG, sessionClock: false }, 's2', v => `€${v}`, () => undefined, reporter());
    expect(reported).toEqual(['SESSION_CLOCK_SHOWN']);
  });

  it('reports the reality check shown, then the player continuing, before the next round goes ahead', async () => {
    const play = new ResponsiblePlay({ ...CONFIG, realityCheckMinutes: 30 }, 's1', v => `€${v}`, () => undefined, reporter(true));
    expect(await play.allowed()).toBe(true);
    vi.advanceTimersByTime(30 * 60_000);
    let goOn: boolean | undefined;
    void play.allowed().then(answer => { goOn = answer; });
    expect(play.pausing).toBe(true);
    expect(reported).toEqual(['REALITY_CHECK_SHOWN']);

    button('Continue playing').click();
    await vi.advanceTimersByTimeAsync(0);
    // "continued" waits for "shown" to be recorded, and the round waits for both
    expect(reported).toEqual(['REALITY_CHECK_SHOWN']);
    expect(play.pausing).toBe(true);
    answers.shift()!.resolve();
    await vi.advanceTimersByTimeAsync(0);
    expect(reported).toEqual(['REALITY_CHECK_SHOWN', 'REALITY_CHECK_CONTINUED']);
    expect(goOn).toBeUndefined();
    answers.shift()!.resolve();
    await vi.advanceTimersByTimeAsync(0);
    expect(goOn).toBe(true);
    expect(play.pausing).toBe(false);
    expect(await play.allowed()).toBe(true);    // the next one in 30 minutes
  });

  it('a player who stops is not reported as continuing', async () => {
    let left = false;
    const play = new ResponsiblePlay({ ...CONFIG, realityCheckMinutes: 30 }, 's1', v => `€${v}`, () => { left = true; }, reporter());
    vi.advanceTimersByTime(30 * 60_000);
    const answer = play.allowed();
    button('Stop playing').click();
    expect(await answer).toBe(false);
    expect(left).toBe(true);
    expect(reported).toEqual(['REALITY_CHECK_SHOWN']);
  });

  it('a report that fails does not stop the game', async () => {
    const play = new ResponsiblePlay({ ...CONFIG, realityCheckMinutes: 30, sessionClock: true }, 's1', v => `€${v}`, () => undefined,
      type => { reported.push(type); return Promise.reject({ status: 0, code: 'NETWORK' }); });
    vi.advanceTimersByTime(30 * 60_000);
    const answer = play.allowed();
    button('Continue playing').click();
    expect(await answer).toBe(true);
    expect(reported).toEqual(['SESSION_CLOCK_SHOWN', 'REALITY_CHECK_SHOWN', 'REALITY_CHECK_CONTINUED']);
  });
});

describe('the server says the reality check is due (REALITY_CHECK_DUE)', () => {
  it('shows it at once, even before the game\'s own clock says so, and rounds wait for the answer', async () => {
    const play = new ResponsiblePlay({ ...CONFIG, realityCheckMinutes: 30 }, 's1', v => `€${v}`, () => undefined, reporter());
    vi.advanceTimersByTime(29 * 60_000);
    play.checkNow();
    expect(play.pausing).toBe(true);
    expect(reported).toEqual(['REALITY_CHECK_SHOWN']);
    play.checkNow();                                  // already on screen: shown once
    expect(reported).toEqual(['REALITY_CHECK_SHOWN']);
    const next = play.allowed();                      // a tap meanwhile waits for the player's answer
    button('Continue playing').click();
    expect(await next).toBe(true);
    expect(reported).toEqual(['REALITY_CHECK_SHOWN', 'REALITY_CHECK_CONTINUED']);
    expect(play.pausing).toBe(false);
  });

  it('shows it even when the game had no reality check of its own', async () => {
    const play = new ResponsiblePlay(CONFIG, 's1', v => `€${v}`, () => undefined, reporter());
    expect(await play.allowed()).toBe(true);
    play.checkNow();
    expect(play.pausing).toBe(true);
    button('Continue playing').click();
    await vi.advanceTimersByTimeAsync(0);
    expect(play.pausing).toBe(false);
    expect(await play.allowed()).toBe(true);           // and not again by itself
    expect(reported).toEqual(['REALITY_CHECK_SHOWN', 'REALITY_CHECK_CONTINUED']);
  });
});
