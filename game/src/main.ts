import 'pixi.js/unsafe-eval'; // PixiJS without eval/new Function, required by the strict Content-Security-Policy
import { Application, Assets, Container, Sprite, Spritesheet, Texture, type SpritesheetData } from 'pixi.js';
import { gsap } from 'gsap';

// No deferred first render: a tween killed before it starts is gone for good. (GSAP otherwise queues a tween's first
// render for the next tick, and a target destroyed in between, like a ticker row dropped while two balls land at once,
// is then read after it is gone.)
gsap.defaults({ lazy: false });
import './style.css';
import { errorText, openSession, type ApiError } from './api';
import { loadTheme, loadThemeFont, neonLayers, propFiles, type Theme } from './theme';
import { atlasUrls, imageUrls, inAtlas, type Formats } from './images';
import { computeViewport } from './viewport';
import { initSounds, startMusic } from './sound';
import { setTextQuality, setTypeface } from './ui';
import { setBlastArt, setBlastKinds, setBlastSheets, setSplatters } from './effects';
import { setMegaLook, setSlotArt, setSlotLooks, setSpinMark } from './board';
import { Game } from './game';
import { BackdropVideo } from './backdrop';
import { Clips } from './clips';
import { assetUrl } from './version';

const IMAGES = [
  'background.png', 'background_p.png', 'logo.png', 'logo_p.png', 'button_start.png', 'item_ball.png', 'item_plinko.png', 'item_plinko_p.png',
  'button_confirm.png', 'button_cancel.png', 'item_exit.png', 'item_exit_p.png', 'button_continue.png',
  'item_result.png', 'item_result_p.png', 'button_fullscreen.png', 'button_sound_on.png', 'button_sound_off.png',
  'button_exit.png', 'button_settings.png',
];

const SLOT_ART = [0, 1, 2, 3, 4].map(tier => `item_slot_${tier}.png`);

/** A chest pick's pictures. */
function chestFiles(c: NonNullable<Theme['wheel']['chests']>): string[] {
  return [c.closed, c.open, c.goldClosed, c.goldOpen, c.shelf, c.glow, c.burst, ...Object.values(c.tokens),
    ...(c.doors?.phone ?? []).map(p => p.wall), ...(c.jars ? Object.values(c.jars.kinds) : []),
    ...(c.socks ? [c.socks.peg, ...Object.values(c.socks.kinds)] : [])];
}

/** The images only some themes have: pegs, drawn slots, a neon logo's layers, the wheel's hub and pointer, the gate. */
function themeExtras(theme: Theme): string[] {
  return [
    ...(theme.board.peg ? [theme.board.peg] : []),
    ...(theme.board.slotArt ? SLOT_ART : []),
    ...neonLayers(theme).flat(),
    ...[theme.wheel.hub, theme.wheel.pointer, theme.wheel.gate, theme.wheel.bezel, theme.wheel.glass].filter((file): file is string => file !== null),
    ...(theme.console ? [theme.console.drop, theme.console.panel, theme.console.key, ...(theme.console.back ? [theme.console.back] : []),
      ...(theme.console.ledger ? [theme.console.ledger.book] : [])] : []),
    ...(theme.wheel.revolverArt ? [theme.wheel.revolverArt.cylinder, theme.wheel.revolverArt.dial, theme.wheel.revolverArt.flash,
      ...Object.values(theme.wheel.revolverArt.cartridges)] : []),
    ...(theme.wheel.tesla ? [theme.wheel.tesla.orb, theme.wheel.tesla.flash, ...Object.values(theme.wheel.tesla.cells),
      ...(theme.wheel.tesla.plate ? [theme.wheel.tesla.plate.plate, theme.wheel.tesla.plate.coil] : [])] : []),
    ...(theme.wheel.chests ? chestFiles(theme.wheel.chests) : []),
    ...(theme.wheel.gacha ? [theme.wheel.gacha.machine, theme.wheel.gacha.crank, theme.wheel.gacha.open, theme.wheel.gacha.burst,
      ...Object.values(theme.wheel.gacha.capsules), ...Object.values(theme.wheel.gacha.tokens)] : []),
    ...(theme.wheel.spinMark ? [theme.wheel.spinMark] : []),
    ...(theme.character ? [theme.character.body, theme.character.head, theme.character.arm, theme.character.eyes,
      theme.character.cheer, theme.character.point, ...(theme.character.shrug ? [theme.character.shrug] : []),
      ...(theme.character.face ? [theme.character.face.idle, theme.character.face.happy, theme.character.face.meh, theme.character.face.pupil] : [])] : []),
    ...(theme.props ? propFiles(theme.props) : []),
    ...(theme.board.fire ? [theme.board.fire.sheet, ...(theme.board.fire.glow ? [theme.board.fire.glow] : [])] : []),
    ...(theme.lines ? [theme.lines.rope, ...theme.lines.socks.map(s => s.file)] : []),
    ...(theme.blast ? [theme.blast.fire, theme.blast.dust, theme.blast.smoke, ...Object.values(theme.blast.kinds),
      ...Object.values(theme.blast.sheets), ...(theme.blast.tank ? [theme.blast.tank.body, theme.blast.tank.target] : []),
      ...theme.blast.splatters] : []),
    ...Object.values(theme.board.fit ?? {}).flatMap(f => [f.frame, f.framePortrait]),
  ];
}

/**
 * Review 4.5: the other orientation's pictures, loaded only when the player turns the phone (most never do): the
 * background, logo (and its neon layers), board frame, game-over plate and exit screen of the shape not on screen.
 */
function otherOrientation(theme: Theme, landscape: boolean): string[] {
  // (a theme whose wide screen shows the tall board too, its controls in a bar under it or written on a ledger beside
  // it, needs that one at once)
  const tallBoard = theme.console?.bar === true || !!theme.console?.ledger;
  const other = landscape ? ['background_p.png', 'logo_p.png', ...(tallBoard ? [] : ['item_plinko_p.png']), 'item_result_p.png']
    : ['background.png', 'logo.png', 'item_plinko.png', 'item_result.png'];
  const exit = theme.landscapeExitFromPortrait ? [] : [landscape ? 'item_exit_p.png' : 'item_exit.png'];
  return [...other, ...exit, ...neonLayers(theme).map(([wide, tall]) => (landscape ? tall : wide))];
}

/** Needed at the end only: the game-over plate of the orientation on screen, loaded once the first screen is up. */
const endOnly = (landscape: boolean) => [landscape ? 'item_result.png' : 'item_result_p.png'];

/**
 * Review 4.4: the @2x pictures only on screens dense enough to show their detail; the rest get the half-size copies the
 * build makes (a quarter of the pixels).
 */
const sharp = (window.devicePixelRatio || 1) >= 1.5;

/** The first of the addresses that loads. Review N15.2: each is tried twice, so one dropped request never leaves a picture blank. */
async function loadFirst(urls: string[]): Promise<Texture> {
  let failure: unknown = new Error('no address');
  for (const url of urls) {
    try {
      return await Assets.load<Texture>(url, { strategy: 'retry', retryCount: 1, retryDelay: 500 });
    } catch (error) {
      failure = error;
    }
  }
  throw failure;
}

/**
 * One picture, from the first of its addresses that loads (images.ts): AVIF, WebP, PNG; the half-size copy, then the
 * @2x one. A format that fails falls back to the next, file by file.
 */
const loadImage = (theme: Theme, file: string, formats: Formats): Promise<Texture> =>
  loadFirst(imageUrls(theme, file, formats, sharp));

/** Loads images one by one (one that fails doesn't take the others with it); done() counts each one loaded. */
async function loadImages(theme: Theme, files: string[], formats: Formats, done?: (count: number) => void): Promise<Record<string, Texture>> {
  const loaded = await Promise.all(files.map(async file => {
    const texture = await loadImage(theme, file, formats);
    done?.(1);
    return [file, texture] as const;
  }));
  return Object.fromEntries(loaded);
}

/**
 * Review 4.4: the theme's small pictures (buttons, ball, pegs, slots, sliders) from its texture atlas: two requests
 * instead of about 25. Empty when there is no atlas or it fails: the game then loads those pictures one by one.
 */
async function loadAtlas(theme: Theme, formats: Formats): Promise<Record<string, Texture>> {
  const atlas = atlasUrls(theme, formats);
  if (!atlas) return {};
  try {
    const [data, texture] = await Promise.all([
      fetch(atlas.json).then(response => {
        if (!response.ok) throw new Error(`atlas: HTTP ${response.status}`);
        return response.json() as Promise<SpritesheetData>;
      }),
      loadFirst(atlas.images),
    ]);
    return await new Spritesheet(texture, data).parse();
  } catch (error) {
    console.warn('atlas not loaded, the small pictures come one by one', error);
    return {};
  }
}

/** True when this browser shows a picture of the given type, found by decoding a 1x1 one; no answer within a second: no. */
function decodes(dataUri: string): Promise<boolean> {
  return new Promise(resolve => {
    const image = new Image();
    const timer = window.setTimeout(() => resolve(false), 1000);
    const done = (ok: boolean) => { window.clearTimeout(timer); resolve(ok); };
    image.onload = () => done(image.width === 1 && image.height === 1);
    image.onerror = () => done(false);
    image.src = dataUri;
  });
}

/**
 * Review N6, 4.4: the formats the browser can show, found by decoding a 1x1 picture of each with transparency (the kind
 * the build makes). Asking a canvas to make one is not the same: Safari shows WebP but can't make it, so every iPhone
 * got the PNGs.
 */
async function imageFormats(): Promise<Formats> {
  const [avif, webp] = await Promise.all([
    decodes('data:image/avif;base64,AAAAIGZ0eXBhdmlmAAAAAGF2aWZtaWYxbWlhZk1BMUEAAAGGbWV0YQAAAAAAAAAhaGRscgAAAAAAAAAAcGljdAAAAAAAAAAAAAAAAAAAAAAOcGl0bQAAAAAAAQAAACxpbG9jAAAAAEQAAAIAAQAAAAEAAAG/AAAAJgACAAAAAQAAAa4AAAARAAAAQmlpbmYAAAAAAAIAAAAaaW5mZQIAAAAAAQAAYXYwMUNvbG9yAAAAABppbmZlAgAAAAACAABhdjAxQWxwaGEAAAAAGmlyZWYAAAAAAAAADmF1eGwAAgABAAEAAADDaXBycAAAAJ1pcGNvAAAAFGlzcGUAAAAAAAAAAQAAAAEAAAAQcGl4aQAAAAADCAgIAAAADGF2MUOBIAAAAAAAE2NvbHJuY2x4AAEADQAGgAAAAA5waXhpAAAAAAEIAAAADGF2MUOBABwAAAAAOGF1eEMAAAAAdXJuOm1wZWc6bXBlZ0I6Y2ljcDpzeXN0ZW1zOmF1eGlsaWFyeTphbHBoYQAAAAAeaXBtYQAAAAAAAAACAAEEAQKDBAACBAEFhgcAAAA/bWRhdBIACgQYAAYVMgcYACihABCgEgAKBzgABpAQ0GkyGRlCYwTDzzzzQSAAkEER1USVmeCk7lb+QoY='),
    decodes('data:image/webp;base64,UklGRkoAAABXRUJQVlA4WAoAAAAQAAAAAAAAAAAAQUxQSAwAAAARBxAR/Q9ERP8DAABWUDggGAAAABQBAJ0BKgEAAQAAAP4AAA3AAP7mtQAAAA=='),
  ]);
  return { avif, webp };
}

function showMessage(text: string): void {
  const box = document.getElementById('message')!;
  box.textContent = text;
  box.hidden = false;
  document.getElementById('loader')!.classList.add('done');
  document.getElementById('game')!.hidden = true;
}

async function start(): Promise<void> {
  let session;
  try {
    session = await openSession();
  } catch (error) {
    showMessage(errorText(error as ApiError));
    return;
  }
  const demo = session.demo === true;
  document.getElementById('demo-badge')!.hidden = !demo;
  // in the demo, anything that ends the game offers a fresh demo wallet
  const fatal = (text: string) => {
    showMessage(text);
    document.getElementById('demo-again')!.hidden = !demo;
  };
  // the format checks run while the theme's settings arrive
  const [theme, formats] = await Promise.all([loadTheme(session.theme), imageFormats()]);
  // Load images on the main thread: workers would need blob: URLs, which the Content-Security-Policy blocks.
  Assets.setPreferences({ preferWorkers: false });
  const progressText = document.getElementById('loader-text')!;
  // the first screen's images first; the rest stand in as empty pictures and are filled in once loaded (review 4.5)
  const firstLandscape = computeViewport(window.innerWidth, window.innerHeight).landscape;
  const turned = otherOrientation(theme, firstLandscape);
  const later = new Set([...turned, ...endOnly(firstLandscape)]);
  const first = [...new Set([...IMAGES, ...themeExtras(theme)])].filter(file => !later.has(file));
  let done = 0;
  const progress = (count: number) => { done += count; progressText.textContent = Math.round(Math.min(1, done / first.length) * 100) + '%'; };
  // the big pictures one by one, the small ones from the atlas at the same time; any the atlas lacks, then one by one
  const [big, atlas] = await Promise.all([
    loadImages(theme, first.filter(file => !inAtlas(file)), formats, progress),
    loadAtlas(theme, formats).then(frames => { progress(first.filter(file => frames[file]).length); return frames; }),
    loadThemeFont(theme),
  ]);
  const small = first.filter(inAtlas);
  const missing = await loadImages(theme, small.filter(file => !atlas[file]), formats, progress);
  const textures: Record<string, Texture> = {
    ...big, ...Object.fromEntries(small.filter(file => atlas[file]).map(file => [file, atlas[file]])), ...missing };
  // Review N15.1: the music once the first screen's pictures are in (it is as big as all of them together, and on a slow
  // connection would have doubled the wait); it plays as soon as it has loaded, or on the first tap where sound waits
  if (theme.music) startMusic(theme.path, theme.music);
  const waiting = new Set(later);
  for (const file of later) textures[file] = new Texture({ source: Texture.EMPTY.source, dynamic: true });
  setTypeface(theme.font.family, theme.font.weight, theme.text);
  setSlotLooks(theme.board);
  const tex = (file: string) => textures[file];
  if (theme.board.slotArt) setSlotArt(SLOT_ART.map(tex));
  setMegaLook(theme.wheel.title, theme.wheel.ink, theme.wheel.gate ? tex(theme.wheel.gate) : null);
  setSpinMark(theme.wheel.spinMark ? tex(theme.wheel.spinMark) : null);
  setBlastArt(theme.blast ? { fire: tex(theme.blast.fire), dust: tex(theme.blast.dust), smoke: tex(theme.blast.smoke) } : null);
  setSplatters((theme.blast?.splatters ?? []).map(tex));
  setBlastKinds(Object.fromEntries(Object.entries(theme.blast?.kinds ?? {}).map(([kind, file]) => [kind, tex(file)])),
    theme.blast?.anchors ?? {}, theme.board.fx === 'reactor' || theme.board.fx === 'neon');   // energy and neon glow; water and fire don't
  setBlastSheets(Object.fromEntries(Object.entries(theme.blast?.sheets ?? {}).map(([kind, file]) =>
    [kind, { sheet: tex(file), grid: theme.blast?.grids[kind as keyof typeof theme.blast.grids] ?? [4, 4] }])));
  initSounds(theme.path, theme.sounds);

  const app = new Application();
  const holder = document.getElementById('game')!;
  // a sharp (Retina) screen needs no antialiasing and no more than 2x: both cost the graphics card a lot on a big
  // window; positions are not rounded to whole pixels, so the balls glide instead of stepping
  await app.init({ width: window.innerWidth, height: window.innerHeight, background: theme.background, antialias: !sharp,
    autoDensity: false, roundPixels: false, resolution: Math.min(window.devicePixelRatio || 1, 2) });
  holder.appendChild(app.canvas);
  // one clock for everything: GSAP moves things on Pixi's tick, right before Pixi draws the frame (no stutter
  // from two separate animation loops drifting against each other)
  gsap.ticker.remove(gsap.updateRoot);
  // one broken effect (a tween on something already removed) must never stop the game's clock: it is logged, and the
  // frame goes on
  app.ticker.add(ticker => {
    try {
      gsap.updateRoot(ticker.lastTime / 1000);
    } catch (e) {
      console.error('animation error', e);
    }
  });

  // the background covers the whole screen; the game is drawn on the designers' stage and scaled to fit
  const background = new Sprite();
  const world = new Container();
  // a theme's moving background replaces the still one once it plays; it starts after the first round (backdrop.ts)
  const backdrop = new BackdropVideo(theme, () => layout());
  const game = new Game(session, theme, tex, fatal,
    backdrop.available ? { moving: () => backdrop.moving, onTap: () => backdrop.toggle() } : null,
    () => {
      backdrop.start();
      game.props?.firstRoundPlayed();
      // the big moments' clips (several MB) only after the first round, where the moving background may play
      if (clips.any && backdrop.available && backdrop.moving) clips.load();
    });
  world.addChild(game);
  // the background's moving pieces lie on it, under the game
  // the big moments' clips cover everything (clips.ts)
  const clips = new Clips(theme.clips, file => assetUrl(theme.path + file));
  game.onClip = kind => clips.play(kind, landscape !== false, app.screen.width, app.screen.height);
  app.stage.addChild(background, ...(game.props ? [game.props, game.props.actors] : []), world,
    ...(game.props ? [game.props.front] : []), clips);

  let landscape: boolean | null = null;
  let fillTurned: (() => void) | null = null;
  const layout = () => {
    const view = computeViewport(app.screen.width, app.screen.height);
    // turned before the other orientation's picture arrived: the one already here, until it does
    const still = view.landscape ? 'background.png' : 'background_p.png';
    const video = backdrop.texture(view.landscape);
    background.texture = video ?? tex(waiting.has(still) ? (view.landscape ? 'background_p.png' : 'background.png') : still);
    const cover = Math.max(app.screen.width / background.texture.width, app.screen.height / background.texture.height);
    background.scale.set(cover);
    background.position.set((app.screen.width - background.width) / 2, (app.screen.height - background.height) / 2);
    // (a moving background shows the lamps and candles itself: the pieces behind the game only lie on the still one;
    // the ones in front stay, laid out on the still picture's place)
    if (game.props) {
      game.props.visible = !video;
      const picture = video ? tex(still) : background.texture;
      const k = Math.max(app.screen.width / picture.width, app.screen.height / picture.height);
      game.props.layout(view.landscape, (app.screen.width - picture.width * k) / 2, (app.screen.height - picture.height * k) / 2, k,
        picture.width, picture.height);
      // the characters dance from the start (not only once the background video plays, after the first round), unless
      // the player stopped the motion, asks for less of it, or saves data
      game.props.animate(backdrop.available && backdrop.moving);
    }
    clips.layout(app.screen.width, app.screen.height);
    world.scale.set(view.scale);
    world.position.set(view.x, view.y);
    game.layout(view);
    setTextQuality(Math.min(4, Math.ceil(app.renderer.resolution * view.scale * 4) / 4), world);
    // Turning the phone: the new layout fades in. Only the transparency is animated, never the size or position:
    // a phone resizes several times per rotation (rotation, then its toolbars), and an animation aimed at the first
    // size would overrule the later, correct ones.
    if (landscape !== null && landscape !== view.landscape) {
      gsap.fromTo(game, { alpha: 0 }, { alpha: 1, duration: 0.3, ease: 'sine.out', overwrite: true });
    }
    landscape = view.landscape;
    // review 4.5: turned for the first time: this orientation's pictures now
    if (view.landscape !== firstLandscape && fillTurned) { fillTurned(); fillTurned = null; }
  };

  // Follow the visible size of the screen. Checked every frame (two numbers, no cost): iPhone browsers finish a
  // rotation and move their toolbars at different moments, and events alone sometimes report a size too early.
  let width = 0;
  let height = 0;
  const resize = () => {
    const w = window.innerWidth;
    const h = window.innerHeight;
    if (w === width && h === height) return;
    width = w;
    height = h;
    holder.style.width = w + 'px';
    holder.style.height = h + 'px';
    app.renderer.resolution = Math.min(window.devicePixelRatio || 1, 2);
    app.renderer.resize(w, h);
    layout();
  };
  resize();
  app.ticker.add(resize);
  app.ticker.add(ticker => game.tick(ticker.deltaMS));
  document.getElementById('loader')!.classList.add('done');

  // Each stand-in takes its picture once it has loaded, then the layout follows. One that fails for good stays empty
  // (or, for a background, shows the other orientation's): the rest of the game still plays (review N15.2).
  const fill = (list: string[]) => {
    for (const file of list) {
      loadImage(theme, file, formats).then(texture => {
        const standIn = textures[file];
        standIn.source = texture.source;
        standIn.frame.copyFrom(texture.frame);
        standIn.update();
        waiting.delete(file);
        width = 0;   // lay out again on the next frame
      }).catch(() => { /* kept as a stand-in */ });
    }
  };
  fill(endOnly(firstLandscape));
  fillTurned = () => fill(turned);
  if (landscape !== firstLandscape) { fillTurned(); fillTurned = null; }
}

start();
