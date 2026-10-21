// Game server API. The server decides every outcome; this client only asks for rounds and shows the results.

export interface RowConfig { rows: number; prizes: number[][] }          // prizes[prizeIndex] = [low, mid, high]
export interface GameConfig { minBet: number; maxBet: number; defaultBet: number; maxBalls: number; rows: RowConfig[];
  holdToDrop?: boolean;
  /** the market's responsible gambling options (review 7.4), set in the admin's Settings */
  sessionClock?: boolean; realityCheckMinutes?: number; quietSmallWins?: boolean; minRoundSeconds?: number;
  mega?: MegaConfig }
/** A wheel segment: what it pays (times the bet, or the pot on the gold wheel), and MINI / MAJOR / MEGA on a jackpot. */
export interface Segment { name?: string | null; multiplier: number }
/**
 * Plinko Mega's board: per rows count ("8".."14") the gates as [step, rights] (after `step` bounces the ball has gone
 * right `rights` times) and the two SPIN slots; what a gate multiplies by (null: gates off); the wheels (null: off).
 */
export interface MegaConfig { gates: Record<string, number[][]>; spinSlots: Record<string, number[]>; gateBoost?: number | null;
  wheel?: Segment[] | null; megaWheel?: Segment[] | null }
/** A wheel spin: the paid ball whose SPIN slot started it, the segment it stopped on, what that pays and the win. */
export interface Spin { ball: number; segment: number; multiplier: number; win: number }
/** A MEGA SPIN of the gold wheel, paid by a full meter: its stake (the pot), the segment, what that pays and the win. */
export interface MegaSpin { stake: number; segment: number; multiplier: number; win: number }
export interface Session {
  sessionId: string;
  player: { username: string | null };
  currency: string;
  currencySymbol: string;
  balance: number;
  config: GameConfig;
  theme: string;
  meter?: MeterInfo;
  /** the public demo server: fake credits */
  demo?: boolean;
}

/** The player's bonus meter, and per board ("rows:risk") the prize positions that charge it. */
export interface MeterInfo { enabled: boolean; charges: number; of: number; positions: Record<string, number[]> }
/** multipliers: what each ball paid (its gates included). */
export interface Round { roundId: string; slots: number[]; multipliers: number[]; totalBet: number; totalWin: number; balance: number;
  meter?: MeterInfo;
  /** what each ball paid, exactly as the server added them up */
  wins?: number[];
  /** every ball's path (bit i set: bounce i+1 goes right), the gates each went through, the wheel spins from SPIN
   *  slots and the MEGA SPINs a full meter paid; the balls that dropped as a MEGA BALL (their multiplier is already in
   *  the ball's) */
  paths?: number[]; gates?: number[]; spins?: Spin[]; megaSpins?: MegaSpin[]; megaBalls?: MegaBall[] }
/** Paid ball `ball` was a MEGA BALL that multiplied its win by `multiplier`. */
export interface MegaBall { ball: number; multiplier: number }
export interface ApiError { status: number; code: string; message?: string; balance?: number }

const BASE = '/api/game';
const SESSION_KEY = 'plinkoSession'; // this tab only, so a reload resumes the game

let sessionId: string | null = null;

async function request<T>(path: string, body: unknown): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (sessionId) headers.Authorization = 'Bearer ' + sessionId;
  let response: Response;
  // a call that hangs would keep the game locked: give up after 20 seconds (the round's result is still the server's)
  const abort = new AbortController();
  const timer = window.setTimeout(() => abort.abort(), 20_000);
  try {
    response = await fetch(BASE + path, { method: 'POST', headers, body: JSON.stringify(body), credentials: 'omit', cache: 'no-store',
      signal: abort.signal });
  } catch {
    throw { status: 0, code: 'NETWORK' } as ApiError;
  } finally {
    window.clearTimeout(timer);
  }
  const data = await response.json().catch(() => null);
  if (!response.ok) throw { status: response.status, ...(data ?? {}) } as ApiError;
  if (data === null || typeof data !== 'object') throw { status: response.status, code: 'NETWORK' } as ApiError;
  return data as T;
}

function stored(): string | null {
  try { return window.sessionStorage.getItem(SESSION_KEY); } catch { return null; }
}

function store(id: string | null): void {
  try {
    if (id) window.sessionStorage.setItem(SESSION_KEY, id); else window.sessionStorage.removeItem(SESSION_KEY);
  } catch { /* private mode: no resume after reload */ }
}

/** Opens the session from the launch link (token used once, then removed from the address bar) or resumes this tab's session. */
export async function openSession(): Promise<Session> {
  const params = new URLSearchParams(window.location.search);
  const token = params.get('token');
  const theme = params.get('theme');
  if (token) {
    window.history.replaceState(null, '', window.location.pathname); // keep the token out of history and bookmarks
    let session: Session;
    try {
      session = await request<Session>('/session', theme ? { token, theme } : { token });
    } catch (error) {
      // the launch link is used up even if its answer was lost on the way: only a fresh link from the casino works now
      if ((error as ApiError).code === 'NETWORK') throw { status: 0, code: 'SESSION_LOST' } as ApiError;
      throw error;
    }
    sessionId = session.sessionId;
    store(sessionId);
    return session;
  }
  sessionId = stored();
  if (!sessionId) throw { status: 401, code: 'NO_SESSION' } as ApiError;
  try {
    return await request<Session>('/resume', {});
  } catch (error) {
    store(null);
    throw error;
  }
}

/** The session again, with the latest balance from the casino. */
export function resumeSession(): Promise<Session> {
  return request<Session>('/resume', {});
}

/**
 * One tap, with its own id: if the answer is lost on the way, the same request is sent again and the server returns
 * the round it already played instead of taking a second bet (review 2.3). While the first copy is still being played
 * (a slow wallet), the server says so and the same request is asked again, for up to 30 seconds (N14.6).
 */
export async function playRound(rows: number, risk: number, bet: number, balls: number): Promise<Round> {
  const body = { rows, risk, bet, balls, requestId: newRequestId() };
  let round: Round;
  try {
    round = await request<Round>('/play', body);
  } catch (error) {
    if ((error as ApiError).code !== 'NETWORK') throw error;
    round = await askAgain(body);
  }
  if (!Array.isArray(round.slots) || typeof round.balance !== 'number' || typeof round.totalWin !== 'number') {
    throw { status: 200, code: 'NETWORK' } as ApiError;
  }
  return round;
}

/** Sends a play request again after its answer was lost, until the round it started has finished. */
async function askAgain(body: object): Promise<Round> {
  const wait = () => new Promise(resolve => window.setTimeout(resolve, 1000));
  for (let attempt = 0; ; attempt++) {
    await wait();
    try {
      return await request<Round>('/play', body);
    } catch (error) {
      const code = (error as ApiError).code;
      // still running, or still holding the round lock before the round was recorded
      const again = code === 'ROUND_STILL_RUNNING' || code === 'ROUND_IN_PROGRESS' || (code === 'NETWORK' && attempt === 0);
      if (!again || attempt >= 29) throw error;
    }
  }
}

function newRequestId(): string {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/** One earlier round of the player (review 7.5): the round as the game shows it, and the prizes it was played on. */
export interface HistoryEntry { roundId: string; time: string; status: string; rows: number; risk: number; bet: number; balls: number;
  /** the multipliers by prize position of the odds it was played on (null for rounds from before they were recorded) */
  prizes: number[] | null;
  /** null when the stored round can't be replayed */
  round: Round | null }

/** The player's own rounds of the last 90 days, newest first; pass the last page's next to get the one after. */
export function roundHistory(cursor?: string | null): Promise<{ rounds: HistoryEntry[]; next: string | null }> {
  return request('/history', cursor ? { cursor } : {});
}

/** What the game reports having shown the player (review 7.4). */
export type ShownEvent = 'REALITY_CHECK_SHOWN' | 'REALITY_CHECK_CONTINUED' | 'SESSION_CLOCK_SHOWN';

/**
 * Tells the server the game showed the reality check or the session clock, or that the player chose to continue: kept
 * as proof where a market requires it (with that required, the server refuses rounds while a reality check is due).
 */
export async function reportShown(type: ShownEvent): Promise<void> {
  await request<{ recorded: boolean }>('/event', { type, at: Date.now() });
}

/** Text for a failed call, shown to the player. */
export function errorText(error: ApiError): string {
  switch (error.code) {
    case 'INVALID_TOKEN': return 'This game link has already been used or has expired. Please relaunch the game from your casino account.';
    case 'NO_SESSION':
    case 'SESSION_EXPIRED': return 'Your game session has ended. Please relaunch the game from your casino account.';
    case 'CURRENCY_NOT_SUPPORTED': return "This game is not available in your account's currency.";
    case 'PLAYER_BLOCKED': return 'Your account cannot play this game. Please contact the casino.';
    case 'GAME_DISABLED': return 'The game is temporarily unavailable. Please try again later.';
    case 'INSUFFICIENT_FUNDS': return 'Not enough balance for this bet.';
    case 'ROUND_IN_PROGRESS': return 'A round is already in progress.';
    case 'ROUND_STILL_RUNNING': return 'Your round is still being played. Your balance will update shortly.';
    case 'SESSION_LOST': return "We couldn't confirm your game session: the connection dropped. Please relaunch the game from your casino account.";
    case 'REQUEST_MISMATCH': return 'This bet could not be placed. Please try again.';
    case 'REQUEST_NOT_PLAYED': return 'That round was not played and no bet was kept. Please try again.';
    case 'ROUND_UNDER_REVIEW': return 'This round is being checked before it is paid. Your win is safe: please contact the casino\'s support.';
    case 'LIMIT_REACHED': return 'A limit set on your casino account has been reached. You can review your limits in your casino account.';
    case 'TOO_FAST': return 'Please wait a moment before the next round.';
    case 'REALITY_CHECK_DUE': return 'Time for a reality check: please look at your session before playing on.';
    case 'BET_TOO_HIGH': return 'This bet could win more than the maximum per round. Lower the bet or the number of balls.';
    default: return 'Unable to connect to the game server. Please try again later.';
  }
}

export function formatAmount(symbol: string, value: number): string {
  return symbol + value.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}
