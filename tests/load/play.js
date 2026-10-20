// Load test (review 5.6): many players on the real game API, a few of them on a slow or failing wallet, and admins
// watching the dashboard and Finance while it runs (the background jobs keep running under load: N4, N5, N10).
// Run it with scripts/loadtest.sh (k6 in Docker, against a running Compose stack); that script makes the wallets
// (tests/load/prepare.py) and passes the settings below as environment variables.
//
//   GAME_URL        the game API (default http://backend:8080: the backend directly, see README "Load test")
//   ADMIN_URL       the admin port, also the mock wallet's (default http://backend:8081)
//   WALLETS         the file prepare.py wrote (one wallet per virtual user, the admin's session cookie)
//   PLAYERS         players at the peak (default 50)
//   SLOW_PLAYERS    players whose wallet answers late or fails before each round (default 5; 0 = none)
//   SLOW_FAULTS     which faults, e.g. debit:slow,credit:timeout (default: all five below)
//   RAMP, DURATION  time to reach the peak, then time held there (default 30s, 3m)
//   THINK_MIN/MAX   seconds a player waits between rounds, like a player watching the balls (default 1, 4)
//   ADMINS          admins reloading the dashboard and Finance (default 2; 0 = none)
//   PLAY_P99_MS     the target for play's 99th percentile (default 1500; the real target is decision D7)
//   ADMIN_P95_MS    the target for an admin page's 95th percentile (default 5000)
//   PAM_API_KEY, PAM_SECRET   the mock wallet's key and secret, to inject faults (slow-wallet players)
//   OUT             where the summary is written (default /out); SUMMARY its file name (default summary.json)
//   MODE            steady (default: the players above) or ramp (review 5.6 #1: more and more rounds a second until
//                   errors or play's 99th percentile break; tests/load/scenarios.py ramp runs it and reads the steps)
//   RAMP_RATES      ramp: the rounds a second of each step (default 5,10,20,40,60,80,100,130,160,200,250,300)
//   RAMP_STEP       ramp: how long each step is held (default 45s), after 5 s to reach it
//   RAMP_MAX_VUS    ramp: players at most (default 300; each has its own wallet and keeps its session)
//   RAMP_ABORT_MS   ramp: a player whose last 20 rounds have a median over this stops the run (default 3 x PLAY_P99_MS)
//   NO_THRESHOLDS   1 = report only, no pass or fail (a wallet outage or database restart, where errors are expected and
//                   tests/load/scenarios.py judges the run itself)
import http from 'k6/http';
import crypto from 'k6/crypto';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import exec from 'k6/execution';

const GAME = __ENV.GAME_URL || 'http://backend:8080';
const ADMIN = __ENV.ADMIN_URL || 'http://backend:8081';
const OUT = __ENV.OUT || '/out';
const SUMMARY = __ENV.SUMMARY || 'summary.json';
const MODE = __ENV.MODE || 'steady';
const data = JSON.parse(open(__ENV.WALLETS || '/out/wallets.json'));
// one wallet per virtual user, by its number in the test (the scenarios share the numbering)
const wallets = new SharedArray('wallets', () => data.wallets);
const adminCookie = data.adminCookie || '';

const PLAYERS = Number(__ENV.PLAYERS || 50);
const SLOW_PLAYERS = Number(__ENV.SLOW_PLAYERS || 5);
const RAMP = __ENV.RAMP || '30s';
const DURATION = __ENV.DURATION || '3m';
const THINK_MIN = Number(__ENV.THINK_MIN || 1);
const THINK_MAX = Number(__ENV.THINK_MAX || 4);
const ADMINS = adminCookie ? Number(__ENV.ADMINS || 2) : 0;
const PLAY_P99_MS = Number(__ENV.PLAY_P99_MS || 1500);
const ADMIN_P95_MS = Number(__ENV.ADMIN_P95_MS || 5000);
const RAMP_RATES = (__ENV.RAMP_RATES || '5,10,20,40,60,80,100,130,160,200,250,300').split(',').map(Number).filter((r) => r > 0);
const RAMP_STEP = __ENV.RAMP_STEP || '45s';
const RAMP_MAX_VUS = Number(__ENV.RAMP_MAX_VUS || 300);
const RAMP_ABORT_MS = Number(__ENV.RAMP_ABORT_MS || 3 * PLAY_P99_MS);
const NO_THRESHOLDS = __ENV.NO_THRESHOLDS === '1';

const needed = MODE === 'ramp' ? RAMP_MAX_VUS : PLAYERS + SLOW_PLAYERS + ADMINS;
if (needed > wallets.length) {
  throw new Error(`${needed} virtual users but only ${wallets.length} wallets were made`);
}

// what a slow-wallet player's round must answer for each fault (the mock wallet's _fault kinds)
const FAULTS = {
  'debit:slow': { status: 200 },                                // applied, answered late: the round plays
  'credit:slow': { status: 200 },
  'debit:timeout': { status: 503, code: 'WALLET_UNAVAILABLE' }, // no answer: the bet is rolled back
  'debit:error': { status: 503, code: 'WALLET_UNAVAILABLE' },
  'credit:timeout': { status: 200 },                            // the round waits as CREDIT_PENDING, retried later
};
const faultKinds = (__ENV.SLOW_FAULTS || Object.keys(FAULTS).join(',')).split(',').filter((f) => FAULTS[f]);

const playTime = new Trend('play_duration', true);
const sessionTime = new Trend('session_duration', true);
const adminTime = new Trend('admin_page_duration', true);
const slowPlayTime = new Trend('slow_wallet_play_duration', true);
const playerErrors = new Rate('player_errors');
const slowWrong = new Rate('slow_wallet_wrong_answer');
const adminErrors = new Rate('admin_errors');
const rounds = new Counter('rounds');
const balls = new Counter('balls');

const scenarios = {};
if (MODE === 'ramp') {
  // rounds started per second, step by step, whatever the answers take (an open model: a slow server gets the same
  // arrivals and needs more players, up to RAMP_MAX_VUS; past that k6 counts dropped_iterations)
  scenarios.ramp = {
    executor: 'ramping-arrival-rate', exec: 'rampPlayer', startRate: RAMP_RATES[0], timeUnit: '1s',
    preAllocatedVUs: Math.min(RAMP_MAX_VUS, Math.max(10, RAMP_RATES[0] * 2)), maxVUs: RAMP_MAX_VUS,
    stages: RAMP_RATES.flatMap((rate) => [{ duration: '5s', target: rate }, { duration: RAMP_STEP, target: rate }]),
  };
} else {
  scenarios.players = {
    executor: 'ramping-vus', exec: 'player', startVUs: 0,
    stages: [{ duration: RAMP, target: PLAYERS }, { duration: DURATION, target: PLAYERS }],
    gracefulRampDown: '30s',
  };
  if (SLOW_PLAYERS > 0) {
    scenarios.slow_wallet = { executor: 'constant-vus', exec: 'slowPlayer', vus: SLOW_PLAYERS, duration: DURATION, startTime: RAMP };
  }
  if (ADMINS > 0) {
    scenarios.admin = { executor: 'constant-vus', exec: 'admin', vus: ADMINS, duration: DURATION, startTime: RAMP };
  }
}

const thresholds = MODE === 'ramp' ? {
  // the ramp passes or fails on its steps (scenarios.py); this only stops a run that is clearly broken
  player_errors: [{ threshold: 'rate<0.2', abortOnFail: true, delayAbortEval: '20s' }],
} : {
  player_errors: ['rate<0.005'],
  'http_req_failed{scenario:players}': ['rate<0.005'],
  play_duration: [`p(99)<${PLAY_P99_MS}`],
  session_duration: ['p(95)<3000'],
  slow_wallet_wrong_answer: ['rate<0.005'],
  admin_errors: ['rate<0.005'],
  admin_page_duration: [`p(95)<${ADMIN_P95_MS}`],
};

export const options = {
  scenarios,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: NO_THRESHOLDS ? {} : thresholds,
  // fewer tags on every point: the scenarios read the points (k6 --out json) by time, metric and name
  systemTags: ['status', 'name', 'scenario', 'expected_response'],
};

function post(url, body, sid, tags) {
  const headers = { 'Content-Type': 'application/json' };
  if (sid) headers.Authorization = 'Bearer ' + sid;
  return http.post(url, JSON.stringify(body), { headers, tags, timeout: '30s', responseCallback: http.expectedStatuses(200, 503) });
}

function uuid() {
  const h = crypto.randomBytes(16);
  const b = new Uint8Array(h);
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const x = Array.from(b, (v) => v.toString(16).padStart(2, '0')).join('');
  return `${x.slice(0, 8)}-${x.slice(8, 12)}-${x.slice(12, 16)}-${x.slice(16, 20)}-${x.slice(20)}`;
}

function pick(list) {
  return list[Math.floor(Math.random() * list.length)];
}

function think() {
  sleep(THINK_MIN + Math.random() * (THINK_MAX - THINK_MIN));
}

// a round as the client sends it: a board, a bet and a number of balls, with its own request id
function round() {
  return { rows: 8 + Math.floor(Math.random() * 7), risk: Math.floor(Math.random() * 3), bet: pick([0.1, 0.2, 0.5, 1]),
    balls: pick([1, 1, 5, 10, 20, 50, 100]), requestId: uuid() };
}

// each player opens its session once (a launch token works only once) and keeps it for the whole run
let sid = null;
let lost = false;

function openSession(wallet, scenario) {
  const res = post(GAME + '/api/game/session', { token: wallet.token }, null, { name: 'session' });
  sessionTime.add(res.timings.duration);
  const ok = check(res, { 'session opens': (r) => r.status === 200 && !!r.json('sessionId') });
  if (scenario === 'players') playerErrors.add(!ok);
  return ok ? res.json('sessionId') : null;
}

export function player() {
  if (lost) {
    sleep(5);
    return;
  }
  if (!sid) {
    sid = openSession(wallets[exec.vu.idInTest - 1], 'players');
    if (!sid) {
      lost = true;    // counted once as an error; the token is used up
      return;
    }
    think();
  }
  const body = round();
  const res = post(GAME + '/api/game/play', body, sid, { name: 'play' });
  playTime.add(res.timings.duration);
  const ok = check(res, { 'round plays': (r) => r.status === 200 && !!r.json('roundId') });
  playerErrors.add(!ok);
  if (ok) {
    rounds.add(1);
    balls.add(body.balls);
  }
  think();
}

// the ramp: one round per arrival, no waiting (the arrival rate is the load); the player keeps its session
const recent = [];

export function rampPlayer() {
  if (lost) return;
  if (!sid) {
    sid = openSession(wallets[exec.vu.idInTest - 1], 'players');
    if (!sid) {
      lost = true;
      return;
    }
  }
  const body = round();
  const res = post(GAME + '/api/game/play', body, sid, { name: 'play' });
  playTime.add(res.timings.duration);
  const ok = check(res, { 'round plays': (r) => r.status === 200 && !!r.json('roundId') });
  playerErrors.add(!ok);
  if (ok) {
    rounds.add(1);
    balls.add(body.balls);
  }
  // broken: this player's last 20 rounds are mostly failing or slow (the steps' own figures come from scenarios.py)
  recent.push(ok ? res.timings.duration : Infinity);
  if (recent.length > 20) recent.shift();
  if (recent.length === 20) {
    const sorted = recent.slice().sort((a, b) => a - b);
    if (sorted[10] > RAMP_ABORT_MS) exec.test.abort(`a player's median over its last 20 rounds is over ${RAMP_ABORT_MS} ms (or they failed)`);
  }
}

// a wallet that answers late or not at all, one fault before each round; the other players must not notice
function fault(playerId, endpoint, kind) {
  const body = JSON.stringify({ playerId, endpoint, fault: kind, times: 1 });
  const res = http.post(ADMIN + '/mock-pam/_fault', body, {
    headers: { 'Content-Type': 'application/json', 'X-Api-Key': __ENV.PAM_API_KEY, 'X-Signature': crypto.hmac('sha256', __ENV.PAM_SECRET, body, 'hex') },
    tags: { name: 'fault' },
  });
  return res.status === 200;
}

export function slowPlayer() {
  const wallet = wallets[exec.vu.idInTest - 1];
  if (lost) {
    sleep(5);
    return;
  }
  if (!sid) {
    sid = openSession(wallet, 'slow_wallet');
    if (!sid) {
      slowWrong.add(true);
      lost = true;
      return;
    }
  }
  const kind = pick(faultKinds);
  const [endpoint, how] = kind.split(':');
  if (!fault(wallet.playerId, endpoint, how)) {
    slowWrong.add(true);
    sleep(5);
    return;
  }
  const res = post(GAME + '/api/game/play', round(), sid, { name: 'play_slow_wallet', fault: kind });
  slowPlayTime.add(res.timings.duration, { fault: kind });
  const want = FAULTS[kind];
  const right = res.status === want.status && (!want.code || (res.json('code') === want.code));
  check(res, { 'slow wallet: the expected answer': () => right });
  slowWrong.add(!right, { fault: kind });
  think();
}

// an admin with the dashboard open (it refreshes itself) who also looks at Finance and the rounds now and then
export function admin() {
  const params = (name, extra) => ({ headers: Object.assign({ Cookie: adminCookie }, extra || {}), redirects: 0, tags: { name }, timeout: '60s' });
  for (const [path, name, extra] of [['/admin', 'admin_dashboard'], ['/admin', 'admin_dashboard_live', { 'X-Live-Refresh': '1' }],
    ['/admin/finance', 'admin_finance'], ['/admin/rounds', 'admin_rounds']]) {
    const res = http.get(ADMIN + path, params(name, extra));
    adminTime.add(res.timings.duration, { page: name });
    const ok = check(res, { 'admin page renders': (r) => r.status === 200 });    // a 302 is a lost sign-in
    adminErrors.add(!ok);
    sleep(2);
  }
  sleep(5);
}

function metric(d, name, stats) {
  const m = d.metrics[name];
  if (!m) return null;
  const out = {};
  for (const s of stats) if (m.values[s] !== undefined) out[s] = Math.round(m.values[s] * 100) / 100;
  return out;
}

export function handleSummary(d) {
  const thresholds = {};
  let passed = true;
  for (const [name, m] of Object.entries(d.metrics)) {
    if (!m.thresholds) continue;
    for (const [t, r] of Object.entries(m.thresholds)) {
      thresholds[`${name}: ${t}`] = r.ok ? 'PASS' : 'FAIL';
      passed = passed && r.ok;
    }
  }
  const seconds = d.state.testRunDurationMs / 1000;
  const summary = {
    passed,
    settings: { mode: MODE, rampRates: MODE === 'ramp' ? RAMP_RATES : undefined, rampStep: MODE === 'ramp' ? RAMP_STEP : undefined,
      players: PLAYERS, slowWalletPlayers: SLOW_PLAYERS, admins: ADMINS, ramp: RAMP, duration: DURATION,
      thinkSeconds: [THINK_MIN, THINK_MAX], playP99TargetMs: PLAY_P99_MS, adminP95TargetMs: ADMIN_P95_MS, gameUrl: GAME },
    seconds,
    // when the run started (ms since 1970), for reading k6's own points by step (tests/load/scenarios.py)
    startedAt: Date.now() - d.state.testRunDurationMs,
    rounds: metric(d, 'rounds', ['count', 'rate']),
    balls: metric(d, 'balls', ['count', 'rate']),
    playMs: metric(d, 'play_duration', ['med', 'p(95)', 'p(99)', 'max']),
    sessionMs: metric(d, 'session_duration', ['med', 'p(95)', 'p(99)', 'max']),
    playerErrorRate: metric(d, 'player_errors', ['rate']),
    slowWalletPlayMs: metric(d, 'slow_wallet_play_duration', ['med', 'p(95)', 'max']),
    slowWalletWrongAnswerRate: metric(d, 'slow_wallet_wrong_answer', ['rate']),
    adminPageMs: metric(d, 'admin_page_duration', ['med', 'p(95)', 'max']),
    adminErrorRate: metric(d, 'admin_errors', ['rate']),
    thresholds,
  };
  summary.droppedIterations = metric(d, 'dropped_iterations', ['count']);
  const who = MODE === 'ramp' ? `ramp of ${RAMP_RATES.join(', ')} rounds a second, ${RAMP_STEP} each`
    : `${PLAYERS} players (+${SLOW_PLAYERS} on a slow wallet, ${ADMINS} admins) for ${DURATION}`;
  const lines = [`load test ${passed ? 'PASSED' : 'FAILED'}: ${who}`,
    `rounds: ${summary.rounds ? summary.rounds.count : 0} (${summary.rounds ? summary.rounds.rate : 0}/s)   play ms: ${JSON.stringify(summary.playMs)}`,
    `session ms: ${JSON.stringify(summary.sessionMs)}   slow-wallet play ms: ${JSON.stringify(summary.slowWalletPlayMs)}`,
    `admin page ms: ${JSON.stringify(summary.adminPageMs)}`];
  for (const [t, r] of Object.entries(thresholds)) lines.push(`${r} ${t}`);
  return { [`${OUT}/${SUMMARY}`]: JSON.stringify(summary, null, 2) + '\n', stdout: lines.join('\n') + '\n' };
}
