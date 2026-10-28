// Review 4.x: what a player downloads on a first visit, per theme, for one orientation (the worse of the two): the
// first screen's pictures and the game-over plate, the font, the sound effects and the music. Prints a table with the
// bytes and the number of requests, and fails when a theme is over its budget:
//   - every theme: 2 MB (the target, review 4.x), the moving background included when it loads before the first round;
//   - a theme listed in theme-size-budget.json: also its size when the list was written, plus a small slack (it can't
//     grow).
// The moving background (video) is part of the first visit only if it loads before the player's first round. It no
// longer does (src/backdrop.ts, VIDEO_WAITS_FOR_FIRST_ROUND, read here): its bytes are then reported apart, in the
// "after 1st round" column (the MP4 a browser with H.264 gets), and are not in the total.
//
//   node scripts/theme-size-budget.mjs <folder with themes/ and assets/> [--built] [--update]
//
// Point it at a built copy (the image build adds the half-size, atlas, WebP and AVIF copies, deploy/nginx/Dockerfile;
// CI copies the game stage's dist/ out): it then counts what a browser with AVIF on a sharp screen gets (the atlas for
// the small pictures; AVIF, else WebP, else PNG for the rest), as the game asks for them (src/images.ts).
// --built: fail if the folder is not a built copy (CI, so a plain checkout's PNGs are never checked by mistake).
// A plain checkout (default: public) is counted as its PNGs, and only reported: the budget applies to built copies.
// --update: write the current sizes (+ slack) as the budgets of the themes that exist now (after making a theme lighter,
// or with the owner's agreement); new themes stay at 2 MB.
// The lists below follow game/src/main.ts (IMAGES, themeExtras, otherOrientation, endOnly), images.ts and sound.ts;
// keep them in step.
import { existsSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const KB = 1024;
const MB = 1024 * KB;
const TARGET = 2 * MB;    // every theme's budget (review 4.x)
const SLACK = 40 * KB;    // on top of an existing theme's size: room for encoder differences between machines, not for new art
const HERE = dirname(fileURLToPath(import.meta.url));
const BUDGETS = join(HERE, 'theme-size-budget.json');
// whether the game waits for the first round before it loads the video (no flag found: counted as loading at once)
const VIDEO_AFTER_FIRST_ROUND = /export const VIDEO_WAITS_FOR_FIRST_ROUND = true;/
  .test(existsSync(join(HERE, '../src/backdrop.ts')) ? readFileSync(join(HERE, '../src/backdrop.ts'), 'utf8') : '');

const IMAGES = [
  'background.png', 'background_p.png', 'logo.png', 'logo_p.png', 'button_start.png', 'item_ball.png', 'item_plinko.png', 'item_plinko_p.png',
  'button_confirm.png', 'button_cancel.png', 'item_exit.png', 'item_exit_p.png', 'button_continue.png',
  'item_result.png', 'item_result_p.png', 'button_fullscreen.png', 'button_sound_on.png', 'button_sound_off.png',
  'button_exit.png', 'button_settings.png',
];
const SLOT_ART = [0, 1, 2, 3, 4].map(tier => `item_slot_${tier}.png`);
const SHARED_SOUNDS = { click: 'sound_button', change: 'sound_change', ball: 'sound_ball', hit: 'sound_hit',
  score: 'sound_score', noScore: 'sound_noscore', win: 'sound_win', lose: 'sound_nowin', result: 'sound_result' };
const SOUND_NAMES = ['click', 'change', 'ball', 'hit', 'clack', 'noScore', 'score', 'big', 'huge', 'golden', 'win', 'lose', 'bigWin', 'result'];

const args = process.argv.slice(2);
const root = resolve(args.find(a => !a.startsWith('--')) ?? 'public');
const size = file => (existsSync(file) ? statSync(file).size : 0);
const has = (dir, file) => existsSync(join(dir, file));
const isBuilt = dir => readdirSync(dir).some(f => /^atlas(@2x)?\.json$/.test(f) || /\.(webp|avif)$/.test(f));

/** The file the game would fetch for one picture on a sharp screen with AVIF (images.ts imageUrls). */
function picture(dir, theme, file) {
  if (file === 'item_exit.png' && theme.landscapeExitFromPortrait) file = 'item_exit_p.png';
  let name = file.replace(/\.png$/, '');
  if (theme.retina === true && !name.startsWith('background')) name += '@2x';
  return [name + '.avif', name + '.webp'].find(f => has(dir, f)) ?? name + '.png';
}

/** The atlas the game would use and the pictures in it (images.ts atlasUrls), or null. */
function atlas(dir) {
  for (const name of ['atlas@2x', 'atlas']) {
    const image = [name + '.avif', name + '.webp'].find(f => has(dir, f));
    if (image && has(dir, name + '.json')) {
      return { files: [name + '.json', image], frames: new Set(Object.keys(JSON.parse(readFileSync(join(dir, name + '.json'), 'utf8')).frames)) };
    }
  }
  return null;
}

function firstVisit(dir, theme, landscape) {
  const neon = Array.from({ length: Math.min(3, Math.max(0, Math.floor(Number(theme.logoNeon) || 0))) }, (_, i) => [`logo_neon_${i + 1}.png`, `logo_neon_${i + 1}_p.png`]);
  const other = new Set([
    ...(landscape ? ['background_p.png', 'logo_p.png', 'item_plinko_p.png', 'item_result_p.png']
      : ['background.png', 'logo.png', 'item_plinko.png', 'item_result.png']),
    ...(theme.landscapeExitFromPortrait ? [] : [landscape ? 'item_exit_p.png' : 'item_exit.png']),
    ...neon.map(([wide, tall]) => (landscape ? tall : wide)),
  ]);
  const board = theme.board ?? {};
  const ui = theme.ui ?? {};
  // a console's pictures (main.ts themeExtras): its DROP plate, panel, key and back plate, and a ledger's open book
  const desk = theme.console ? [theme.console.drop, theme.console.panel, theme.console.key, theme.console.back, theme.console.ledger?.book]
    .filter(file => typeof file === 'string') : [];
  const files = [...new Set([...IMAGES, ...(board.peg ? [board.peg] : []), ...(board.slotArt ? SLOT_ART : []),
    ...neon.flat(), ...desk])].filter(file => !other.has(file));
  const sheet = atlas(dir);
  const fetched = [...new Set([...(sheet?.files ?? []),
    ...files.filter(file => !sheet?.frames.has(file)).map(file => picture(dir, theme, file))])];
  const images = fetched.reduce((sum, file) => sum + size(join(dir, file)), 0);
  const font = theme.font?.file ? size(join(dir, 'fonts', theme.font.file)) : 0;
  // Chrome takes the .ogg (listed first), Safari the .mp3: the .ogg counted, or the .mp3 where there is none
  const audio = base => size(base + '.ogg') || size(base + '.mp3');
  const themed = Array.isArray(theme.sounds) ? theme.sounds : [];
  const soundFiles = SOUND_NAMES.map(name => (themed.includes(name) ? join(dir, `sfx_${name}`)
    : SHARED_SOUNDS[name] ? join(root, 'assets', 'sounds', SHARED_SOUNDS[name]) : null)).filter(Boolean);
  const sounds = soundFiles.reduce((sum, base) => sum + audio(base), 0);
  const music = typeof theme.music === 'string' ? audio(join(dir, theme.music.replace(/\.mp3$/, ''))) : 0;
  const clip = theme.video?.[landscape ? 'wide' : 'tall'];
  const video = typeof clip === 'string' ? size(join(dir, clip)) : 0;
  const early = VIDEO_AFTER_FIRST_ROUND ? 0 : video;
  const requests = fetched.length + (font ? 1 : 0) + new Set(soundFiles).size + (music ? 1 : 0) + (early ? 1 : 0);
  return { images, font, sounds, music, video: early, later: video - early, requests, total: images + font + sounds + music + early };
}

const themesDir = join(root, 'themes');
const ids = readdirSync(themesDir).filter(id => existsSync(join(themesDir, id, 'theme.json'))).sort();
const built = ids.some(id => isBuilt(join(themesDir, id)));
if (args.includes('--built') && !built) {
  console.error(`FAIL: ${root} is not a built copy (no atlas, WebP or AVIF files): run it on the image build's files`);
  process.exit(1);
}
const budgets = existsSync(BUDGETS) ? JSON.parse(readFileSync(BUDGETS, 'utf8')).themes ?? {} : {};
const budgetOf = id => (typeof budgets[id] === 'number' ? Math.min(budgets[id] * KB, TARGET) : TARGET);
const mb = bytes => (bytes / MB).toFixed(2);
const rows = ids.map(id => {
  const dir = join(themesDir, id);
  const theme = JSON.parse(readFileSync(join(dir, 'theme.json'), 'utf8'));
  const wide = firstVisit(dir, theme, true);
  const tall = firstVisit(dir, theme, false);
  return { id, worst: wide.total >= tall.total ? { ...wide, shape: 'landscape' } : { ...tall, shape: 'portrait' } };
});

console.log(`First visit per theme in MB (${root}${built ? ', built copy' : ', plain checkout: PNGs only, not checked'}), the heavier orientation`);
const head = ['theme', 'shape', 'images', 'font', 'sounds', 'music', 'video', 'total', 'budget', 'requests', 'after 1st round', ''];
const table = rows.map(({ id, worst: w }) => [id, w.shape, mb(w.images), mb(w.font), mb(w.sounds), mb(w.music), mb(w.video),
  mb(w.total), mb(budgetOf(id)) + (id in budgets ? '' : '*'), String(w.requests), mb(w.later),
  !built ? '' : w.total > budgetOf(id) ? 'OVER BUDGET' : 'ok']);
const widths = head.map((h, i) => Math.max(h.length, ...table.map(row => row[i].length)));
for (const row of [head, ...table]) console.log(row.map((cell, i) => (i < 2 ? cell.padEnd(widths[i]) : cell.padStart(widths[i]))).join('  ').trimEnd());
console.log('budget: 2 MB, or less for a theme listed in theme-size-budget.json (its size then + slack); * not listed.');
console.log(VIDEO_AFTER_FIRST_ROUND
  ? 'video: loads after the first round (src/backdrop.ts), so in "after 1st round", not in the total.'
  : 'video: loads before the first round (src/backdrop.ts), so counted in the first visit.');

if (args.includes('--update')) {
  if (!built) { console.error('FAIL: --update needs a built copy'); process.exit(1); }
  const themes = Object.fromEntries(rows.map(({ id, worst }) => [id, Math.ceil((worst.total + SLACK) / KB)]));
  writeFileSync(BUDGETS, JSON.stringify({
    note: 'Review 4.x: first-visit budget per existing theme in KB (scripts/theme-size-budget.mjs --update): its size then + 40 KB, never above 2 MB. A theme not listed: 2 MB. The video loads after the first round and is not counted.',
    themes }, null, 2) + '\n');
  console.log(`wrote ${BUDGETS}`);
}
if (!built) process.exit(0);
const over = rows.filter(row => row.worst.total > budgetOf(row.id));
if (over.length) {
  console.error(`FAIL: over the budget: ${over.map(row => `${row.id} (${mb(row.worst.total)} MB, budget ${mb(budgetOf(row.id))} MB)`).join(', ')}`);
  process.exit(1);
}
