// Loads the game under the production CSP, plays a round and checks what the page did; exits 1 on any problem (CI runs
// it on the test stack). In headless Chromium with the launch token, then in WebKit (the engine of Safari and of every
// iPhone browser, review N6/5.7), which resumes the same session and plays a round too.
// docker run --rm --add-host=host.docker.internal:host-gateway -v "$PWD/tests":/out -w /tmp mcr.microsoft.com/playwright:v1.48.2-jammy \
//   sh -c "npm i -s playwright@1.48.2 && cp /out/browser_check.mjs . && node browser_check.mjs <mock launch token>"
// GAME: the game's address (default http://host.docker.internal:8080; CI, on the host network: http://localhost:8080)
// BROWSERS: default chromium,webkit (Chromium always first: it uses the launch token)
import { chromium, webkit } from 'playwright';
const token = process.argv[2];
const GAME = process.env.GAME ?? 'http://host.docker.internal:8080';
const BROWSERS = ['chromium', ...(process.env.BROWSERS ?? 'chromium,webkit').split(',').filter(b => b && b !== 'chromium')];
if (!token) { console.log('FAIL no launch token given'); process.exit(1); }

/**
 * Messages caused by the test, not the game: the note that COOP needs https on a non-localhost test address; and in
 * WebKit, Playwright's screenshot adds a <style>body {}</style> to the page, which the game's CSP rightly refuses
 * (the game itself has no inline style: Chromium, which sees the same pages without that, would report one).
 */
const testNoise = (browser, text) => text.includes('Cross-Origin-Opener-Policy header has been ignored')
  || (browser === 'webkit' && (text.startsWith('Refused to apply a stylesheet because its hash, its nonce, or \'unsafe-inline\'')
    || text === 'CSP violation: style-src-elem inline'));

/** One browser: load, play a round, report. The first uses the launch token, the others resume its session. */
async function run(name, session) {
  const engine = { chromium, webkit }[name];
  const browser = await engine.launch(name === 'chromium' ? { args: ['--use-gl=swiftshader', '--enable-unsafe-swiftshader'] } : {});
  const context = await browser.newContext({ viewport: { width: 1280, height: 768 } });
  if (session) await context.addInitScript(id => sessionStorage.setItem('plinkoSession', id), session);
  const page = await context.newPage();
  const problems = [];
  page.on('console', m => {
    if (['error', 'warning'].includes(m.type()) && !testNoise(name, m.text())) problems.push(`${m.type()}: ${m.text()}`);
  });
  page.on('pageerror', e => problems.push('pageerror: ' + e.message));
  await page.addInitScript(() => document.addEventListener('securitypolicyviolation', e =>
    console.error(`CSP violation: ${e.violatedDirective} ${e.blockedURI}`)));
  const rounds = [];
  const pictures = [];
  page.on('response', async r => { if (r.url().endsWith('/api/game/play')) rounds.push({ status: r.status(), body: await r.json().catch(() => null) }); });
  page.on('request', r => { if (/\/themes\/.+\.(png|webp|avif)/.test(r.url())) pictures.push(r.url().split('?')[0].split('/').pop()); });

  await page.goto(session ? `${GAME}/` : `${GAME}/?token=${token}`);
  await page.waitForSelector('#loader.done', { state: 'attached', timeout: 30000 });
  await page.waitForTimeout(800);
  console.log(`[${name}] URL after load (token must be gone):`, page.url());
  await page.screenshot({ path: `/tmp/1-menu-${name}.png` });
  // 1280x768 is exactly the landscape stage, so the menu's PLAY button is at (640, 768 * 0.77)
  await page.mouse.click(640, 591);
  await page.waitForTimeout(800);
  await page.screenshot({ path: `/tmp/2-board-${name}.png` });
  await page.keyboard.press('Space');
  await page.waitForTimeout(6000);
  await page.screenshot({ path: `/tmp/3-after-round-${name}.png` });
  const round = rounds[0];
  console.log(`[${name}] round:`, round ? `HTTP ${round.status} | win ${round.body?.totalWin} for bet ${round.body?.totalBet} | balance ${round.body?.balance} | slots ${JSON.stringify(round.body?.slots)}` : 'none played');
  // review N6: the big pictures as AVIF or WebP in every browser that shows them (Safari does; it can't make them)
  const background = pictures.find(p => /^background\./.test(p));
  console.log(`[${name}] background picture:`, background ?? 'none');
  // Nothing the page could tamper with lives in a global: the game is a module, and the server decides every slot.
  const globals = await page.evaluate(() => ['gameData', 'gameSettings', 'playerData'].filter(k => k in window));
  console.log(`[${name}] game state reachable from DevTools globals:`, globals);
  console.log(`[${name}] PROBLEMS:`, problems.length ? '\n  ' + problems.join('\n  ') : 'none');
  const id = await page.evaluate(() => sessionStorage.getItem('plinkoSession'));
  // warnings are shown but don't fail it (headless Chromium warns about its software WebGL); errors, CSP violations do
  const ok = !page.url().includes('token=') && round?.status === 200 && Array.isArray(round.body?.slots) && globals.length === 0
    && /^background\.(avif|webp)$/.test(background ?? '') && !problems.some(p => !p.startsWith('warning:'));
  console.log(`[${name}] ` + (ok ? 'browser check passed' : 'browser check FAILED'));
  await browser.close();
  return { ok, session: id };
}

let session = null;
let ok = true;
for (const name of BROWSERS) {
  const result = await run(name, session);
  ok &&= result.ok;
  session ??= result.session;
  if (!session) { console.log('FAIL no session to resume in the other browsers'); ok = false; break; }
}
console.log(ok ? 'browser check passed' : 'browser check FAILED');
process.exit(ok ? 0 : 1);
