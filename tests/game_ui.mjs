// Review 5.7 #6-#8, 4.4-4.6, N6: every theme under the production Content-Security-Policy.
//  1. Chromium, a 1x screen: landscape and portrait, turning the phone, a round of BALLS balls played to the end; no
//     console errors or CSP violations; the other orientation's pictures are loaded only when the phone is turned.
//  2. The first screen in every browser of BROWSERS (Chromium and WebKit, the engine of every iPhone browser) at 1, 2 and
//     3 device pixels per CSS pixel, resuming the same session: the big pictures come as AVIF or WebP (never PNG: N6),
//     the small ones from the theme's atlas, the @2x pictures only at 1.5 and above; and at 1x the screen, landscape
//     and portrait, matches its baseline picture (tests/baselines/<theme>-<orientation>.png, a quarter of the size)
//     within a tolerance. Players' "less motion" setting is on there, so the first screen stands still.
//   GAME=http://localhost:8080 TOKENS=theme1=token1,theme2=token2 OUT=/tmp/shots node tests/game_ui.mjs [--update-baselines]
// TOKENS: one fresh launch token per theme (CI: python3 tests/launch_tokens.py <test.env>); none is a failure.
// BALLS (default 100, the most a round may have): the round's ball count, set on the play request (the control center
// drops one ball per tap), so the board animates a full round.
// BROWSERS (default chromium,webkit). BASELINES: the baselines folder (default: baselines/ next to this script).
// --update-baselines: write the 1x first screens (Chromium) as the new baselines instead of comparing; check the
// pictures before committing them.
// (Playwright 1.48: mcr.microsoft.com/playwright:v1.48.2-jammy with `npm i playwright@1.48.2 pixelmatch@7.1.0 pngjs@7.0.0`)
import { chromium, webkit } from 'playwright';
import pixelmatch from 'pixelmatch';
import { PNG } from 'pngjs';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const GAME = process.env.GAME ?? 'http://localhost:8080';
const OUT = process.env.OUT ?? '/tmp/game-ui';
const BALLS = Number(process.env.BALLS ?? 100);
const BROWSERS = (process.env.BROWSERS ?? 'chromium,webkit').split(',').filter(Boolean);
const BASELINES = process.env.BASELINES ?? path.join(path.dirname(fileURLToPath(import.meta.url)), 'baselines');
const UPDATE = process.argv.includes('--update-baselines');
const SHRINK = 4;           // baselines are a quarter of the screen's size: small files, and blind to anti-aliasing
// at most this share of a baseline's pixels may differ (pixelmatch, threshold 0.15). The baselines are Chromium's; WebKit
// draws glows (blending) a little differently (Fatal Attraction's neon: 2.5%), so it gets more room
const TOLERANCE = { chromium: 0.02, webkit: 0.05 };
const tokens = Object.fromEntries((process.env.TOKENS ?? '').split(',').filter(Boolean).map(p => p.split('=')));
fs.mkdirSync(OUT, { recursive: true });
if (UPDATE) fs.mkdirSync(BASELINES, { recursive: true });
let failures = 0;
const check = (name, ok, detail = '') => {
  console.log((ok ? 'PASS ' : 'FAIL ') + name + (ok || !detail ? '' : `  [${detail}]`));
  if (!ok) failures++;
};
// review 5.7: with no tokens nothing would be checked, and that must not pass
check('launch tokens given (TOKENS=theme=token,...)', Object.keys(tokens).length > 0, 'TOKENS is empty');
if (failures) process.exit(1);

const engines = { chromium, webkit };
const launch = name => engines[name].launch(name === 'chromium' ? { args: ['--use-gl=swiftshader', '--enable-unsafe-swiftshader'] } : {});
const browsers = {};
for (const name of new Set(['chromium', ...BROWSERS])) browsers[name] = await launch(name);
const file = url => url.split('?')[0].split('/').pop();

/**
 * Messages caused by the test, not the game: the note that COOP needs https on a non-localhost test address; and in
 * WebKit, Playwright's screenshot adds a <style>body {}</style> to the page, which the game's CSP rightly refuses
 * (the game itself has no inline style: Chromium, which sees the same pages without that, would report one).
 */
const testNoise = (browser, text) => text.includes('Cross-Origin-Opener-Policy header has been ignored')
  || (browser === 'webkit' && text.startsWith('Refused to apply a stylesheet because its hash, its nonce, or \'unsafe-inline\''));

/** Console errors and page errors of a page. */
function watchErrors(page, browser) {
  const errors = [];
  page.on('console', m => { if (m.type() === 'error' && !testNoise(browser, m.text())) errors.push(m.text()); });
  page.on('pageerror', e => errors.push(String(e)));
  return errors;
}

/** A theme still switched off in the admin is shown by rewriting the session answer, in this test browser only. */
async function showTheme(page, theme) {
  for (const call of ['session', 'resume']) {
    await page.route(`**/api/game/${call}`, async route => {
      const response = await route.fetch();
      const body = await response.json();
      await route.fulfill({ response, json: { ...body, theme } });
    });
  }
}

/** A screenshot shrunk to a quarter in the browser itself (no image library needed), as a PNG. */
async function shrunk(browser, page) {
  const shot = await page.screenshot();
  const scratch = await browser.newPage();
  const data = await scratch.evaluate(async ([uri, shrink]) => {
    const image = new Image();
    image.src = uri;
    await image.decode();
    const canvas = document.createElement('canvas');
    canvas.width = Math.round(image.width / shrink);
    canvas.height = Math.round(image.height / shrink);
    const context = canvas.getContext('2d');
    context.imageSmoothingQuality = 'high';
    context.drawImage(image, 0, 0, canvas.width, canvas.height);
    return canvas.toDataURL('image/png');
  }, ['data:image/png;base64,' + shot.toString('base64'), SHRINK]);
  await scratch.close();
  return Buffer.from(data.split(',')[1], 'base64');
}

/** Compares a shrunk first screen with its baseline (or writes it, with --update-baselines). */
async function baseline(browserName, page, theme, shape) {
  const name = `${theme}-${shape}.png`;
  const picture = await shrunk(browsers[browserName], page);
  if (UPDATE) {
    if (browserName === 'chromium') fs.writeFileSync(path.join(BASELINES, name), picture);
    return;
  }
  const want = path.join(BASELINES, name);
  if (!fs.existsSync(want)) { check(`${theme} ${browserName}: baseline ${name} exists`, false, 'run with --update-baselines'); return; }
  const a = PNG.sync.read(fs.readFileSync(want));
  const b = PNG.sync.read(picture);
  if (a.width !== b.width || a.height !== b.height) {
    check(`${theme} ${browserName}: ${shape} first screen matches its baseline`, false, `size ${b.width}x${b.height}, baseline ${a.width}x${a.height}`);
    return;
  }
  const diff = new PNG({ width: a.width, height: a.height });
  const differing = pixelmatch(a.data, b.data, diff.data, a.width, a.height, { threshold: 0.15 });
  const share = differing / (a.width * a.height);
  if (share > TOLERANCE[browserName]) {
    fs.writeFileSync(path.join(OUT, `${theme}-${shape}-${browserName}-got.png`), picture);
    fs.writeFileSync(path.join(OUT, `${theme}-${shape}-${browserName}-diff.png`), PNG.sync.write(diff));
  }
  check(`${theme} ${browserName}: ${shape} first screen matches its baseline`, share <= TOLERANCE[browserName],
    `${(share * 100).toFixed(1)}% of pixels differ (allowed ${TOLERANCE[browserName] * 100}%); see ${OUT}`);
}

/** Part 1: the whole game in Chromium on a 1x screen; returns the session, for the first-screen checks. */
async function fullRound(theme, token) {
  const context = await browsers.chromium.newContext({ viewport: { width: 1280, height: 720 } });
  const page = await context.newPage();
  const errors = watchErrors(page, 'chromium');
  const images = [];
  page.on('request', r => { if (/\/themes\/.+\.(png|webp|avif)/.test(r.url())) images.push(r.url()); });
  await showTheme(page, theme);
  // a full round: the tap's play request asks for BALLS balls (the server decides and signs every one of them)
  const plays = [];
  await page.route('**/api/game/play', route => route.continue({
    postData: JSON.stringify({ ...JSON.parse(route.request().postData() ?? '{}'), balls: BALLS }) }));
  page.on('response', async r => {
    if (r.url().endsWith('/api/game/play')) plays.push({ status: r.status(), body: await r.json().catch(() => null) });
  });
  await page.goto(`${GAME}/?token=${token}&theme=${theme}`);
  await page.waitForSelector('#loader.done', { timeout: 30000 });
  check(`${theme}: the launch token leaves the address bar`, !page.url().includes('token='), page.url());
  await page.waitForTimeout(3000);
  const tallLoaded = () => page.evaluate(() => performance.getEntriesByType('resource').filter(e => /_p(@2x)?\.(png|webp|avif)/.test(e.name)).length);
  check(`${theme}: the portrait pictures wait until the phone is turned (landscape start)`, await tallLoaded() === 0);
  await page.screenshot({ path: `${OUT}/${theme}-menu-landscape.png` });
  // start, play a round with the keyboard, turn the phone mid-round
  await page.mouse.click(640, 560);
  await page.waitForTimeout(800);
  await page.keyboard.press('Space');
  await page.waitForTimeout(400);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(4000);
  await page.screenshot({ path: `${OUT}/${theme}-game-portrait.png` });
  check(`${theme}: turning the phone loads the portrait pictures`, await tallLoaded() > 0);
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.waitForTimeout(1500);
  await page.screenshot({ path: `${OUT}/${theme}-game-landscape.png` });
  // the round finished: played with all its balls, and the server has it as completed (paid)
  const played = plays[0];
  check(`${theme}: the round was played with ${BALLS} balls`,
    plays.length === 1 && played.status === 200 && played.body?.slots?.length === BALLS,
    JSON.stringify({ rounds: plays.length, status: played?.status, code: played?.body?.code, balls: played?.body?.slots?.length }));
  let last = null;
  for (let i = 0; i < 30 && last?.status !== 'COMPLETED'; i++) {
    if (i) await page.waitForTimeout(1000);
    last = await page.evaluate(async () => {
      const r = await fetch('/api/game/history', { method: 'POST', body: '{}', headers: { 'Content-Type': 'application/json',
        Authorization: 'Bearer ' + sessionStorage.getItem('plinkoSession') } });
      return r.ok ? (await r.json()).rounds?.[0] ?? null : { status: 'HTTP ' + r.status };
    });
  }
  check(`${theme}: the round finished (completed and paid on the server)`,
    last?.status === 'COMPLETED' && last?.balls === BALLS, JSON.stringify({ status: last?.status, balls: last?.balls }));
  await page.waitForTimeout(Math.min(15000, 150 * BALLS));   // the last balls land
  await page.screenshot({ path: `${OUT}/${theme}-game-finished.png` });
  check(`${theme}: no console errors or CSP violations`, errors.length === 0, errors.slice(0, 3).join(' | '));
  const session = await page.evaluate(() => sessionStorage.getItem('plinkoSession'));
  await context.close();
  return session;
}

/** Part 2: the first screen in one browser at one pixel density, resuming the session. */
async function firstScreen(theme, session, browserName, dpr) {
  const label = `${theme} ${browserName} at ${dpr}x`;
  const context = await browsers[browserName].newContext({ viewport: { width: 1280, height: 720 }, deviceScaleFactor: dpr, reducedMotion: 'reduce' });
  await context.addInitScript(id => { try { sessionStorage.setItem('plinkoSession', id); } catch { /* checked below */ } }, session);
  const page = await context.newPage();
  const errors = watchErrors(page, browserName);
  const pictures = [];
  const pending = new Set();
  page.on('request', r => {
    if (!r.url().includes(`/themes/${theme}/`)) return;
    pending.add(r);
    if (/\.(png|webp|avif|json)(\?|$)/.test(r.url())) pictures.push(file(r.url()));
  });
  page.on('requestfinished', r => pending.delete(r));
  page.on('requestfailed', r => pending.delete(r));
  await showTheme(page, theme);
  await page.goto(`${GAME}/`);
  await page.waitForSelector('#loader.done', { timeout: 30000 });
  await page.waitForTimeout(1500);
  const shown = pictures.filter(name => name !== 'theme.json' && name !== 'favicon.png');
  const list = shown.join(' ');
  check(`${label}: the game is up (session resumed)`, await page.evaluate(() => document.getElementById('message')?.hidden !== false), list);
  const background = shown.find(name => /^background\./.test(name));
  // N6: Safari (WebKit) shows WebP and, from 16, AVIF: neither may fall back to the PNG
  check(`${label}: the background comes as ${browserName === 'chromium' ? 'AVIF' : 'AVIF or WebP'}`,
    browserName === 'chromium' ? background === 'background.avif' : /^background\.(avif|webp)$/.test(background ?? ''), list);
  check(`${label}: the small pictures come from the atlas`,
    shown.some(name => /^atlas(@2x)?\.json$/.test(name)) && shown.some(name => /^atlas(@2x)?\.(avif|webp)$/.test(name))
      && !shown.some(name => /^button_/.test(name)), list);
  // review 4.4: @2x pictures only where the screen can show them (the atlas is @2x on every screen: smaller that way)
  const sharp = shown.filter(name => /@2x\./.test(name) && !/^atlas@2x\./.test(name));
  if (dpr < 1.5) check(`${label}: no @2x pictures`, sharp.length === 0, sharp.join(' '));
  else check(`${label}: the @2x pictures`, shown.some(name => /^logo@2x\./.test(name)) && !shown.some(name => /^logo\./.test(name)), list);
  if (dpr === 1) {
    await baseline(browserName, page, theme, 'landscape');
    await page.setViewportSize({ width: 390, height: 844 });
    // the portrait pictures (background, logo and its neon layers, ...) load on turning: wait until every theme file
    // asked for has arrived, then for them to be drawn
    await page.waitForTimeout(500);
    for (let i = 0; i < 30 && pending.size > 0; i++) await page.waitForTimeout(500);
    await page.waitForTimeout(1500);
    await baseline(browserName, page, theme, 'portrait');
  }
  check(`${label}: no console errors or CSP violations`, errors.length === 0, errors.slice(0, 3).join(' | '));
  await context.close();
}

for (const [theme, token] of Object.entries(tokens)) {
  const session = await fullRound(theme, token);
  if (!session) { check(`${theme}: a session to resume`, false); continue; }
  for (const browserName of BROWSERS) {
    for (const dpr of [1, 2, 3]) await firstScreen(theme, session, browserName, dpr);
  }
}
for (const browser of Object.values(browsers)) await browser.close();
if (UPDATE) console.log(`baselines written to ${BASELINES} (Chromium, 1x): look at them before committing`);
console.log(failures === 0 ? 'all game UI checks passed' : `${failures} game UI check(s) failed`);
process.exit(failures === 0 ? 0 : 1);
