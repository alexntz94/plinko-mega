import { Howl, Howler } from 'howler';
import { assetUrl } from './version';

// Sound effects. Every theme can bring its own (theme.json "sounds": the names it ships as sfx_<name>.ogg with an
// .mp3 for Safari, in its folder); anything it leaves out comes from the shared set (/assets/sounds).
export const SOUND_NAMES = ['click', 'change', 'ball', 'hit', 'clack', 'noScore', 'score', 'big', 'huge', 'golden', 'win',
  'lose', 'bigWin', 'result'] as const;
export type SoundName = typeof SOUND_NAMES[number];

const SHARED: Partial<Record<SoundName, string>> = {
  click: 'sound_button', change: 'sound_change', ball: 'sound_ball', hit: 'sound_hit',
  score: 'sound_score', noScore: 'sound_noscore', win: 'sound_win', lose: 'sound_nowin', result: 'sound_result',
};
/** What plays when neither the theme nor the shared set has a sound (none: silent). */
const STANDS_IN: Partial<Record<SoundName, SoundName>> = { clack: 'hit', big: 'score', huge: 'score', golden: 'win' };

const MUTE_KEY = 'plinkoMuted';
const sounds = new Map<SoundName, Howl>();
let userMuted = false;
let lastHit = 0;

export function initSounds(path = '', themed: readonly SoundName[] = []): void {
  try { userMuted = window.localStorage.getItem(MUTE_KEY) === '1'; } catch { /* storage blocked: sound on */ }
  for (const name of SOUND_NAMES) {
    const base = themed.includes(name) ? `${path}sfx_${name}` : SHARED[name] ? `/assets/sounds/${SHARED[name]}` : null;
    if (!base) continue;
    // peg ticks overlap a lot: a bigger pool, so a busy board never cuts one off
    sounds.set(name, new Howl({ src: [assetUrl(`${base}.ogg`), assetUrl(`${base}.mp3`)], preload: true, pool: name === 'hit' ? 12 : 5 }));
  }
  applyMute();
  // quiet while the game is in the background (another tab, phone locked)
  document.addEventListener('visibilitychange', applyMute);
}

function applyMute(): void {
  Howler.mute(userMuted || document.hidden);
}

/** Plays a sound; rate changes its pitch (1 = as recorded), pan places it left (-1) or right (1). */
export function play(name: SoundName, { volume = 1, rate = 1, pan = 0 }: { volume?: number; rate?: number; pan?: number } = {}): void {
  if (name === 'hit' || name === 'clack') {
    // many balls touch pegs at once: one tick per 30 ms is enough and keeps it from crackling
    const now = performance.now();
    if (now - lastHit < 30) return;
    lastHit = now;
  }
  const sound = sounds.get(name) ?? (STANDS_IN[name] ? sounds.get(STANDS_IN[name]!) : undefined);
  if (!sound) return;
  const id = sound.play();
  sound.volume(volume, id);
  if (rate !== 1) sound.rate(rate, id);
  if (pan !== 0) sound.stereo(Math.max(-1, Math.min(1, pan)), id);
}

let music: Howl | null = null;

/**
 * The theme's background music: a seamless loop, quiet under the effects, .ogg with the .mp3 for Safari. The sound
 * button and a hidden tab silence it with everything else. Browsers hold sound until the player's first tap, so it
 * never starts on its own; the first tap starts it if it isn't already playing.
 */
export function startMusic(path: string, file: string): void {
  if (music) return;
  const ogg = file.replace(/\.mp3$/, '.ogg');
  const track = music = new Howl({ src: [assetUrl(path + ogg), assetUrl(path + file)], loop: true, volume: 0.35 });
  applyMute();
  // plays as soon as it has loaded, where the browser allows sound before a tap (Chrome counts the click in the
  // lobby, the same site); Safari and Firefox hold it back until the player touches the page: the first tap, click
  // or key starts it. One sound only: a tap resumes this one, never a second copy on top.
  const id = track.play();
  const kick = () => {
    if (Howler.ctx?.state === 'suspended') void Howler.ctx.resume();
    if (track.state() === 'loaded' && !track.playing(id)) track.play(id);
    if (track.playing(id)) for (const type of GESTURES) window.removeEventListener(type, kick, true);
  };
  for (const type of GESTURES) window.addEventListener(type, kick, true);
}

// what browsers count as the player touching the page (on phones only the finger lifting counts)
const GESTURES = ['pointerdown', 'pointerup', 'touchend', 'click', 'keydown'];

export function isMuted(): boolean {
  return userMuted;
}

export function setMuted(muted: boolean): void {
  userMuted = muted;
  try { window.localStorage.setItem(MUTE_KEY, muted ? '1' : '0'); } catch { /* not remembered */ }
  applyMute();
}
