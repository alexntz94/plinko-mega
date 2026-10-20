import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ApiError } from '../src/api';

// Review 5.7 #1: the launch token is used once and taken out of the address bar; a reload resumes this tab's session;
// a play whose answer was lost is asked again with the same request id until the round has finished.
type Answer = { status: number; body: unknown } | 'network';

let search = '';
let replaced: string[] = [];
let store: Map<string, string>;
let answers: Answer[];
let sent: { url: string; body: Record<string, unknown>; auth?: string }[];

beforeEach(() => {
  vi.resetModules();   // api.ts keeps the session id in the module: a fresh copy per test, as after a reload
  search = '';
  replaced = [];
  store = new Map();
  answers = [];
  sent = [];
  vi.stubGlobal('window', {
    // through the global ones, so fake timers apply
    setTimeout: (run: () => void, ms: number) => globalThis.setTimeout(run, ms),
    clearTimeout: (timer: ReturnType<typeof setTimeout>) => globalThis.clearTimeout(timer),
    location: { get search() { return search; }, pathname: '/play/' },
    history: { replaceState: (_s: unknown, _t: string, url: string) => { replaced.push(url); } },
    sessionStorage: { getItem: (k: string) => store.get(k) ?? null, setItem: (k: string, v: string) => store.set(k, v),
      removeItem: (k: string) => store.delete(k) },
  });
  vi.stubGlobal('fetch', async (url: string, init: { body: string; headers: Record<string, string> }) => {
    sent.push({ url, body: JSON.parse(init.body), auth: init.headers.Authorization });
    const answer = answers.shift() ?? { status: 200, body: {} };
    if (answer === 'network') throw new TypeError('Failed to fetch');
    return { ok: answer.status < 400, status: answer.status, json: async () => answer.body };
  });
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

const api = () => import('../src/api');
const failure = (promise: Promise<unknown>) => promise.then(() => { throw new Error('expected a failure'); }, e => e as ApiError);

describe('opening and resuming the session (review 5.7 #1)', () => {
  it('the launch token is taken out of the address bar before it is sent, and the session kept for this tab', async () => {
    search = '?token=launch-1&theme=noir';
    answers.push({ status: 200, body: { sessionId: 'sess-1', balance: 50 } });
    const session = await (await api()).openSession();
    expect(session.sessionId).toBe('sess-1');
    expect(replaced).toEqual(['/play/']);
    expect(sent[0]).toMatchObject({ url: '/api/game/session', body: { token: 'launch-1', theme: 'noir' } });
    expect(store.get('plinkoSession')).toBe('sess-1');
  });

  it('a reload resumes this tab\'s session with its id, without a token', async () => {
    store.set('plinkoSession', 'sess-1');
    answers.push({ status: 200, body: { sessionId: 'sess-1', balance: 42.5 } });
    const session = await (await api()).openSession();
    expect(session.balance).toBe(42.5);
    expect(sent).toEqual([{ url: '/api/game/resume', body: {}, auth: 'Bearer sess-1' }]);
  });

  it('a session the server no longer knows is forgotten, so the next reload asks for a new launch', async () => {
    store.set('plinkoSession', 'sess-1');
    answers.push({ status: 401, body: { code: 'SESSION_EXPIRED' } });
    expect(await failure((await api()).openSession())).toMatchObject({ status: 401, code: 'SESSION_EXPIRED' });
    expect(store.has('plinkoSession')).toBe(false);
  });

  it('without a token or a kept session there is nothing to open', async () => {
    expect(await failure((await api()).openSession())).toMatchObject({ code: 'NO_SESSION' });
    expect(sent).toEqual([]);
  });

  it('a launch whose answer is lost can\'t be retried with the used token: the player is told to relaunch', async () => {
    search = '?token=launch-1';
    answers.push('network');
    const { openSession, errorText } = await api();
    const error = await failure(openSession());
    expect(error.code).toBe('SESSION_LOST');
    expect(errorText(error)).toMatch(/relaunch/);
    expect(sent).toHaveLength(1);
  });

  it('later calls carry the session id', async () => {
    search = '?token=launch-1';
    answers.push({ status: 200, body: { sessionId: 'sess-9' } });
    const { openSession, resumeSession } = await api();
    await openSession();
    await resumeSession();
    expect(sent[1]).toEqual({ url: '/api/game/resume', body: {}, auth: 'Bearer sess-9' });
  });
});

describe('a play whose answer is lost (review 2.3, N14.6)', () => {
  const round = { roundId: 'r1', slots: [3], multipliers: [1.1], totalBet: 1, totalWin: 1.1, balance: 100.1 };

  it('is sent again with the same request id, and the round already played is shown', async () => {
    vi.useFakeTimers();
    answers.push('network', { status: 409, body: { code: 'ROUND_STILL_RUNNING' } }, { status: 409, body: { code: 'ROUND_IN_PROGRESS' } },
      { status: 200, body: round });
    const playing = (await api()).playRound(8, 1, 1, 1);
    await vi.runAllTimersAsync();
    expect(await playing).toEqual(round);
    expect(sent).toHaveLength(4);
    const ids = new Set(sent.map(s => s.body.requestId));
    expect(ids.size).toBe(1);
    expect([...ids][0]).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  });

  it('each tap has its own request id', async () => {
    answers.push({ status: 200, body: round }, { status: 200, body: round });
    const { playRound } = await api();
    await playRound(8, 1, 1, 1);
    await playRound(8, 1, 1, 1);
    expect(sent[0].body.requestId).not.toBe(sent[1].body.requestId);
  });

  it('stops asking after 30 seconds of a round still running', async () => {
    vi.useFakeTimers();
    answers.push('network', ...Array.from({ length: 40 }, () => ({ status: 409, body: { code: 'ROUND_STILL_RUNNING' } })));
    const playing = failure((await api()).playRound(8, 1, 1, 1));
    await vi.runAllTimersAsync();
    expect(await playing).toMatchObject({ code: 'ROUND_STILL_RUNNING' });
    expect(sent).toHaveLength(31);
  });

  it('any other refusal of the resend is final', async () => {
    vi.useFakeTimers();
    answers.push('network', { status: 402, body: { code: 'INSUFFICIENT_FUNDS' } });
    const playing = failure((await api()).playRound(8, 1, 1, 1));
    await vi.runAllTimersAsync();
    expect(await playing).toMatchObject({ status: 402, code: 'INSUFFICIENT_FUNDS' });
    expect(sent).toHaveLength(2);
  });

  it('a first refusal is not sent again (no second bet is ever risked)', async () => {
    answers.push({ status: 503, body: { code: 'WALLET_UNAVAILABLE' } });
    expect(await failure((await api()).playRound(8, 1, 1, 1))).toMatchObject({ status: 503, code: 'WALLET_UNAVAILABLE' });
    expect(sent).toHaveLength(1);
  });

  it('an answer that is not a round is treated as lost, never shown', async () => {
    answers.push({ status: 200, body: { slots: [1], totalWin: 0 } });
    expect(await failure((await api()).playRound(8, 1, 1, 1))).toMatchObject({ code: 'NETWORK' });
  });
});
