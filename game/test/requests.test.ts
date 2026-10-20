import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import fixture from '../../tests/client_requests.json';
import { openSession, playRound, reportShown, resumeSession, roundHistory } from '../src/api';

// Review N9: tests/client_requests.json is this client's request contract. The next release's CI replays it against
// the new backend (tests/compat_smoke.py), so it must list exactly what the client sends.
const shape = (path: string, body: Record<string, unknown>) => `${path} ${Object.keys(body).sort().join(',')}`;

describe('the requests the client sends (tests/client_requests.json)', () => {
  const sent: string[] = [];
  let search = '';

  beforeEach(() => {
    sent.length = 0;
    const store = new Map<string, string>();
    vi.stubGlobal('window', {
      setTimeout, clearTimeout,
      location: { get search() { return search; }, pathname: '/' },
      history: { replaceState: () => undefined },
      sessionStorage: { getItem: (k: string) => store.get(k) ?? null, setItem: (k: string, v: string) => store.set(k, v),
        removeItem: (k: string) => store.delete(k) },
    });
    vi.stubGlobal('fetch', async (url: string, init: { body: string }) => {
      sent.push(shape(url, JSON.parse(init.body)));
      const answer = url.endsWith('/play') ? { slots: [], balance: 1, totalWin: 0 }
        : url.endsWith('/history') ? { rounds: [], next: null } : { sessionId: 's1' };
      return { ok: true, status: 200, json: async () => answer };
    });
  });

  afterEach(() => vi.unstubAllGlobals());

  it('match the recorded file, so the next release can replay them', async () => {
    search = '?token=t1';
    await openSession();
    search = '?token=t2&theme=drop-riot';
    await openSession();
    await resumeSession();
    await playRound(8, 0, 1, 2);
    await roundHistory();
    await roundHistory('c1');
    await reportShown('REALITY_CHECK_SHOWN');
    await reportShown('REALITY_CHECK_CONTINUED');
    await reportShown('SESSION_CLOCK_SHOWN');
    expect(sent).toEqual(fixture.requests.map(r => shape(r.path, r.body)));
  });
});
