// Theme packs live in /themes/<id>/: the designers' images plus theme.json (title, colours, layout tweaks, and
// optionally the font, the board's pegs/slots/trail and sharper @2x images). Everything here is looks only.

import { assetUrl } from './version';
import { SOUND_NAMES, type SoundName } from './sound';

export interface ThemeFont { family: string; file: string | null; weight: string }
export interface SlotStyle { face: string; text: string }
export interface FitFrame { rowGap: number; x: number; y: number; spacing: number; frame: string; framePortrait: string }
export interface BoardStyle {
  peg: string | null;            // image for the pegs (else plain dots)
  pegFlash: string;              // glow when a ball touches a peg
  trail: string | null;          // colour of the streak behind a falling ball (none if null)
  slots: SlotStyle[] | null;     // centre -> edge, 5 tiers (else the original yellow-to-red)
  slotRadius: number;
  slotArt: boolean;              // prize slots drawn from item_slot_0..4.png (centre -> edge) instead of flat colour
  fx: ThemeFx;                   // the theme's signature effects (visual only)
  /**
   * Frames drawn for each board (a picture with its own triangle and slot compartments), by number of rows: the
   * pictures (wide, tall), how close the peg rows are (a share of the spacing across), where on the picture the top
   * centre of the pegs goes and how many of its pixels one peg spacing is (the picture's own pixels, at 1x). Only
   * these numbers of rows are offered.
   */
  fit: Record<number, FitFrame> | null;
}
/** The look of the parts the game draws in code (the bonus meter): 'flat', 'glass' (shaded) or 'ink' (flat colour with
 * thick ink outlines; `ink` and `panel` give its colours). */
export interface ThemeUi { style: 'flat' | 'glass' | 'ink'; ink: string; panel: string }
export type ThemeFx = 'standard' | 'war';
/**
 * Plinko Mega's wheels: segment faces and label colours (taken in turn), the rim, the ink of the outlines, the title
 * colour (also the jackpot segments' face) and the gold wheel's own faces and rim; hub, pointer and gate: optional
 * drawings (the wheel's centre, its pointer, a gate's arch).
 */
export interface WheelLook { faces: string[]; text: string[]; rim: string }
export interface WheelStyle extends WheelLook { ink: string; title: string; gold: WheelLook; hub: string | null; pointer: string | null;
  gate: string | null;
  /** optional drawings laid over the turning disc, centred, made for a disc of radius 160: the bezel round it (440
   *  across) and the glass over its face */
  bezel: string | null; glass: string | null;
  /** drawn as a revolver's cylinder (a cartridge per prize) that clicks into place, instead of a wheel of wedges */
  revolver: boolean;
  /** pictures for the revolver (see RevolverArt), or null: drawn in code */
  revolverArt: RevolverArt | null;
  /** a small picture marking the SPIN slots, or null */
  spinMark: string | null }
/**
 * A revolver from pictures: the cylinder that turns and the dial frame (with the hammer) that stays still, a cartridge
 * head per kind of prize, the muzzle flash of a win. Geometry is in shares of each picture's width: the cylinder's
 * centre and radius and its chambers' centres (one per prize, the first at the top), the dial's opening centre and
 * radius.
 */
export interface RevolverArt {
  cylinder: string; dial: string; flash: string;
  cartridges: { plain: string; MINI: string; MAJOR: string; MEGA: string };
  centre: [number, number]; radius: number; chambers: [number, number][]; chamberRadius: number;
  opening: [number, number]; openingRadius: number;
}
export interface Theme {
  id: string;
  name: string;
  title: string;
  background: string;
  accent: string;
  text: string;
  playText: string;              // label on the play button (dark on light buttons)
  font: ThemeFont;
  board: BoardStyle;
  retina: boolean;               // images come as name@2x.png, drawn at double density (backgrounds excepted)
  ui: ThemeUi;
  /** a console under the board for the controls (its art: the DROP plate, a readout panel, a key), or null */
  console: { drop: string; panel: string; key: string; back: string | null } | null;
  /** a photo explosion (fireball, ground dust ring, smoke plume) for every blast, or null: drawn in code */
  blast: { fire: string; dust: string; smoke: string; kinds: Partial<Record<BlastName, string>>;
    /** animated explosions: per kind, a sprite sheet of 16 frames (4x4) */
    sheets: Partial<Record<BlastName, string>> } | null;
  landscapeExitFromPortrait: boolean;
  /** Looping, muted background videos (landscape / portrait) played over the still backgrounds once they run. */
  video: { wide: string | null; tall: string | null };
  /** A looping background track (.mp3 with an .ogg of the same name), quiet under the effects; null: none. */
  music: string | null;
  /** How many lit-neon layers the logo has (logo_neon_<n>.png and logo_neon_<n>_p.png, laid over the logo, whose
   *  tubes are drawn unlit): the start screen buzzes and flickers them like a neon sign. 0: a still logo. */
  logoNeon: number;
  /** The sound effects the theme ships itself (sfx_<name>.ogg / .mp3); the rest come from the shared set. */
  sounds: SoundName[];
  wheel: WheelStyle;
  /** What drifts over the scene all the time: its colours, and whether it rises (embers) or falls (petals, snow). */
  ambient: { colours: string[]; rise: boolean };
  path: string;
}

const DEFAULT_FONT: ThemeFont = { family: 'Azonix', file: null, weight: 'normal' };
const DEFAULT_BOARD: BoardStyle = { peg: null, pegFlash: '#FFBF00', trail: null, slots: null, slotRadius: 10, slotArt: false, fx: 'standard', fit: null };

// theme.json is our own static file, but only well-formed values are used
const colour = (value: unknown, fallback: string) => typeof value === 'string' && /^#[0-9a-fA-F]{6}$/.test(value) ? value : fallback;
const fileName = (value: unknown, ext: RegExp) => typeof value === 'string' && /^[a-z0-9_-]{1,40}\.[a-z0-9]{2,5}$/.test(value) && ext.test(value) ? value : null;
const number = (value: unknown, fallback: number) => typeof value === 'number' && Number.isFinite(value) ? value : fallback;

function readFont(json: unknown): ThemeFont {
  const f = (json ?? {}) as Partial<ThemeFont>;
  const family = typeof f.family === 'string' && /^[A-Za-z0-9 ]{1,40}$/.test(f.family) ? f.family : null;
  if (!family) return DEFAULT_FONT;
  return { family, file: fileName(f.file, /\.(woff2|woff|ttf)$/), weight: typeof f.weight === 'string' && /^[1-9]00$/.test(f.weight) ? f.weight : 'normal' };
}

/** Frames fitted to boards (see BoardStyle.fit), by rows, or null. */
function readFit(json: unknown): BoardStyle['fit'] {
  const all = (json ?? null) as Record<string, unknown> | null;
  if (!all) return null;
  const n = (v: unknown) => (typeof v === 'number' && Number.isFinite(v) && v > 0 ? v : null);
  const out: Record<number, FitFrame> = {};
  for (const [key, value] of Object.entries(all)) {
    const f = (value ?? {}) as Record<string, unknown>;
    const rows = Number(key);
    const [rowGap, x, y, spacing] = [n(f.rowGap), n(f.x), n(f.y), n(f.spacing)];
    const frame = fileName(f.frame, /\.png$/), framePortrait = fileName(f.framePortrait, /\.png$/);
    if (Number.isInteger(rows) && rowGap && x && y && spacing && frame && framePortrait) out[rows] = { rowGap, x, y, spacing, frame, framePortrait };
  }
  return Object.keys(out).length ? out : null;
}

function readRevolverArt(json: unknown): RevolverArt | null {
  const r = (json ?? null) as Record<string, unknown> | null;
  if (!r) return null;
  const png = (v: unknown) => fileName(v, /\.png$/);
  const c = (r.cartridges ?? {}) as Record<string, unknown>;
  const share = (v: unknown) => (typeof v === 'number' && v > 0 && v < 1 ? v : null);
  const pair = (v: unknown): [number, number] | null =>
    Array.isArray(v) && v.length === 2 && share(v[0]) && share(v[1]) ? [v[0] as number, v[1] as number] : null;
  const chambers = Array.isArray(r.chambers) ? r.chambers.map(pair) : [];
  const parts = [png(r.cylinder), png(r.dial), png(r.flash), png(c.plain), png(c.MINI), png(c.MAJOR), png(c.MEGA)];
  const centre = pair(r.centre), opening = pair(r.opening), radius = share(r.radius), openingRadius = share(r.openingRadius);
  const chamberRadius = share(r.chamberRadius);
  if (parts.some(p => !p) || !centre || !opening || !radius || !openingRadius || !chamberRadius || chambers.length < 3 || chambers.some(c => !c)) return null;
  const [cylinder, dial, flash, plain, MINI, MAJOR, MEGA] = parts as string[];
  return { cylinder, dial, flash, cartridges: { plain, MINI, MAJOR, MEGA }, centre, radius, chambers: chambers as [number, number][],
    chamberRadius, opening, openingRadius };
}

const BLAST_NAMES = ['small', 'fireball', 'heavy', 'napalm', 'impact', 'mushroom'] as const;
type BlastName = typeof BLAST_NAMES[number];

function readBlast(json: unknown): Theme['blast'] {
  const b = (json ?? null) as Record<string, unknown> | null;
  if (!b) return null;
  const [fire, dust, smoke] = [fileName(b.fire, /\.png$/), fileName(b.dust, /\.png$/), fileName(b.smoke, /\.png$/)];
  const files = (json: unknown) => {
    const k = (json ?? {}) as Record<string, unknown>;
    const out: Partial<Record<BlastName, string>> = {};
    for (const name of BLAST_NAMES) {
      const file = fileName(k[name], /\.png$/);
      if (file) out[name] = file;
    }
    return out;
  };
  return fire && dust && smoke ? { fire, dust, smoke, kinds: files(b.kinds), sheets: files(b.sheets) } : null;
}

function readConsole(json: unknown): Theme['console'] {
  const c = (json ?? null) as Record<string, unknown> | null;
  if (!c) return null;
  const [drop, panel, key] = [fileName(c.drop, /\.png$/), fileName(c.panel, /\.png$/), fileName(c.key, /\.png$/)];
  return drop && panel && key ? { drop, panel, key, back: fileName(c.back, /\.png$/) } : null;
}

function readBoard(json: unknown): BoardStyle {
  const b = (json ?? {}) as Record<string, unknown>;
  const slots = Array.isArray(b.slots) && b.slots.length === 5
    ? (b.slots as Partial<SlotStyle>[]).map(s => ({ face: colour(s?.face, '#ffffff'), text: colour(s?.text, '#000000') }))
    : null;
  return {
    peg: fileName(b.peg, /\.png$/),
    pegFlash: colour(b.pegFlash, DEFAULT_BOARD.pegFlash),
    trail: b.trail === undefined ? null : colour(b.trail, '#ffffff'),
    slots,
    slotRadius: Math.min(20, Math.max(0, number(b.slotRadius, DEFAULT_BOARD.slotRadius))),
    slotArt: b.slotArt === true,
    fit: readFit(b.fit),
    fx: b.fx === 'war' ? 'war' : 'standard',
  };
}

function readUi(json: unknown): ThemeUi {
  const u = (json ?? {}) as Record<string, unknown>;
  const style = u.style === 'glass' || u.style === 'ink' ? u.style : 'flat';
  return { style, ink: colour(u.ink, '#111111'),
    panel: colour(u.panel, '#222222') };
}

const colours = (value: unknown, fallback: string[]) =>
  Array.isArray(value) && value.length > 0 && value.length <= 16 ? value.map(v => colour(v, fallback[0])) : fallback;

/** The wheels' look; a theme without one gets its slot colours and accent. */
function readWheel(json: unknown, board: BoardStyle, accent: string, text: string): WheelStyle {
  const w = (json ?? {}) as Record<string, unknown>;
  const g = (w.gold ?? {}) as Record<string, unknown>;
  const faces = colours(w.faces, board.slots?.map(s => s.face) ?? ['#FFBF00', '#EF7C11', '#EF521E', '#EF282C']);
  const texts = colours(w.text, board.slots?.map(s => s.text) ?? [text]);
  return {
    faces, text: texts, rim: colour(w.rim, '#222222'), ink: colour(w.ink, '#000000'), title: colour(w.title, accent),
    gold: { faces: colours(g.faces, ['#f3c64a', '#fff0b8', '#d99a1e']), text: colours(g.text, ['#2a1a00']), rim: colour(g.rim, '#8a5d12') },
    hub: fileName(w.hub, /\.png$/), pointer: fileName(w.pointer, /\.png$/), gate: fileName(w.gate, /\.png$/),
    bezel: fileName(w.bezel, /\.png$/), glass: fileName(w.glass, /\.png$/),
    revolver: w.style === 'revolver',
    revolverArt: readRevolverArt(w.revolverArt),
    spinMark: fileName(w.spinMark, /\.png$/),
  };
}

export async function loadTheme(id: string): Promise<Theme> {
  const safe = /^[a-z0-9-]{1,32}$/.test(id) ? id : 'bombs-away';
  const path = `/themes/${safe}/`;
  let json: Record<string, unknown> = {};
  try {
    const response = await fetch(path + 'theme.json', { cache: 'no-cache' });
    if (response.ok) json = await response.json();
  } catch { /* keep defaults */ }
  const theme: Theme = {
    id: safe,
    name: typeof json.name === 'string' ? json.name : 'Plinko',
    title: typeof json.title === 'string' ? json.title : 'Plinko',
    background: colour(json.background, '#0c011a'),
    accent: colour(json.accent, '#ffffff'),
    text: colour(json.text, '#ffffff'),
    playText: colour(json.playText, colour(json.text, '#ffffff')),
    font: readFont(json.font),
    board: readBoard(json.board),
    retina: json.retina === true,
    ui: readUi(json.ui),
    console: readConsole(json.console),
    blast: readBlast(json.blast),
    landscapeExitFromPortrait: json.landscapeExitFromPortrait === true,
    video: {
      wide: fileName((json.video as Record<string, unknown> | undefined)?.wide, /\.(mp4|webm)$/),
      tall: fileName((json.video as Record<string, unknown> | undefined)?.tall, /\.(mp4|webm)$/),
    },
    music: fileName(json.music, /\.mp3$/),
    logoNeon: Math.min(3, Math.max(0, Math.floor(number(json.logoNeon, 0)))),
    sounds: Array.isArray(json.sounds) ? SOUND_NAMES.filter(name => (json.sounds as unknown[]).includes(name)) : [],
    wheel: readWheel(json.wheel, readBoard(json.board), colour(json.accent, '#ffffff'), colour(json.text, '#ffffff')),
    ambient: {
      colours: colours((json.ambient as Record<string, unknown> | undefined)?.colours, [colour(json.accent, '#ffffff')]),
      rise: (json.ambient as Record<string, unknown> | undefined)?.rise === true,
    },
    path,
  };
  document.title = theme.title;
  document.documentElement.style.setProperty('--theme-bg', theme.background);
  document.documentElement.style.setProperty('--theme-accent', theme.accent);
  const icon = document.getElementById('favicon') as HTMLLinkElement | null;
  if (icon) { icon.href = assetUrl(path + 'favicon.png'); icon.type = 'image/png'; }
  return theme;
}

/** The lit layers of a neon logo: [landscape, portrait] per layer. */
export const neonLayers = (theme: Theme): [string, string][] =>
  Array.from({ length: theme.logoNeon }, (_, i) => [`logo_neon_${i + 1}.png`, `logo_neon_${i + 1}_p.png`]);

/** Loads the theme's own font (served from the theme folder, so the Content-Security-Policy allows it). */
export async function loadThemeFont(theme: Theme): Promise<void> {
  if (!theme.font.file) {
    await document.fonts.load(`30px ${theme.font.family}`).catch(() => undefined);
    return;
  }
  try {
    const face = new FontFace(theme.font.family, `url(${assetUrl(`${theme.path}fonts/${theme.font.file}`)})`, { weight: '100 900' });
    document.fonts.add(await face.load());
  } catch { /* the text falls back to the default font */ }
}
