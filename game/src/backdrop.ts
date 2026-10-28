import { Texture, VideoSource } from 'pixi.js';
import { calm } from './effects';
import type { Theme } from './theme';
import { assetUrl } from './version';

const MOTION_KEY = 'plinko.background';

/** Whether this player stopped the moving background last time on this device (storage may be blocked: it moves). */
function savedStopped(): boolean {
  try { return window.localStorage.getItem(MOTION_KEY) === 'still'; } catch { return false; }
}

/**
 * A theme's moving background: a short muted loop per orientation, from the theme's own folder (the
 * Content-Security-Policy allows media from the game's own address only). The still background stays on screen until
 * the video really plays, so a phone that refuses autoplay (power saving) or a slow connection just keeps the picture.
 * Players who ask their device for less motion keep the picture too, and any player can stop it in the settings
 * column (remembered on this device). Purely visual: nothing here touches the game.
 */
/** Review 4.6: data saver on, or a slow connection: the still picture only (the video would cost the player data). */
function slowConnection(): boolean {
  const connection = (navigator as Navigator & { connection?: { saveData?: boolean; effectiveType?: string } }).connection;
  return Boolean(connection?.saveData) || ['slow-2g', '2g', '3g'].includes(connection?.effectiveType ?? '');
}

/**
 * Review 4.x / N15.3: when the moving background loads. It used to wait for the player's first round, so it was never
 * part of a first visit; the owner chose to have it move from the start: it now loads once the game itself has loaded
 * (it never delays the start screen), except where it never plays (a phone saving data or on a slow connection, a
 * player asking for less motion, or one who stopped it in the settings column). The first visit then includes it: the
 * size check counts it, with a limit of its own (scripts/theme-size-budget.mjs reads this flag).
 */
export const VIDEO_WAITS_FOR_FIRST_ROUND = false;

export class BackdropVideo {
  private readonly ready = new Map<'wide' | 'tall', Texture>();
  private readonly videos = new Map<'wide' | 'tall', HTMLVideoElement>();
  private wanted: 'wide' | 'tall' | null = null;
  private stopped = savedStopped();
  /** Review 4.x: no video until the player's first round (it never delays the game, nor weighs on the first visit) */
  private started = false;

  constructor(private readonly theme: Theme, private readonly onReady: () => void) {
    // nothing is loaded here: start() comes after the player's first round (main.ts, the game's roundPlayed)
    if (!VIDEO_WAITS_FOR_FIRST_ROUND) window.setTimeout(() => this.start(), 0);
    // a hidden tab stops the loop (battery); it starts again when the player comes back
    document.addEventListener('visibilitychange', () => {
      this.videos.forEach((video, shape) => {
        if (document.hidden) video.pause();
        else if (shape === this.wanted && !this.stopped) void video.play().catch(() => undefined);
      });
    });
    // a phone that blocked autoplay usually allows it after the first tap
    window.addEventListener('pointerdown', () => {
      const video = this.wanted ? this.videos.get(this.wanted) : undefined;
      if (video?.paused && !document.hidden && !this.stopped) void video.play().catch(() => undefined);
    }, { passive: true });
  }

  /** Lets the video start: after the player's first round (VIDEO_WAITS_FOR_FIRST_ROUND). */
  start(): void {
    if (this.started) return;
    this.started = true;
    this.onReady();
  }

  /** True when this theme has a moving background the player could stop or start. */
  get available(): boolean {
    return !calm && !slowConnection() && Boolean(this.theme.video.wide || this.theme.video.tall);
  }

  get moving(): boolean {
    return !this.stopped;
  }

  /** The player's stop / start: a stopped background shows the still picture and decodes nothing. */
  toggle(): void {
    this.stopped = !this.stopped;
    // the player asked for it: no waiting for the first round
    if (!this.stopped) this.started = true;
    try { window.localStorage.setItem(MOTION_KEY, this.stopped ? 'still' : 'moving'); } catch { /* this visit only */ }
    if (this.stopped) this.videos.forEach(video => video.pause());
    this.wanted = null; // the next texture() call starts the orientation on screen again
    this.onReady();
  }

  /** The video texture for this orientation once it is playing (null: show the still background). */
  texture(landscape: boolean): Texture | null {
    const shape = landscape ? 'wide' : 'tall';
    const file = this.theme.video[shape];
    if (!file || calm || this.stopped || !this.started || slowConnection()) return null;
    if (this.wanted !== shape) {
      // only the orientation on screen plays
      this.videos.forEach((video, other) => { if (other !== shape) video.pause(); });
      this.wanted = shape;
      const loaded = this.videos.get(shape);
      if (loaded) void loaded.play().catch(() => undefined);
      else this.load(shape, file);
    }
    return this.ready.get(shape) ?? null;
  }

  private load(shape: 'wide' | 'tall', file: string): void {
    const video = document.createElement('video');
    video.muted = true;
    video.defaultMuted = true;
    video.loop = true;
    video.playsInline = true;
    video.setAttribute('playsinline', '');
    video.setAttribute('muted', '');
    video.preload = 'auto';
    // the browser starts it by itself as soon as it may (a muted, inline video needs no tap)
    video.autoplay = true;
    video.setAttribute('autoplay', '');
    // on the page, 1 pixel and invisible: Safari only starts a video without a tap when it counts as on screen, and a
    // video outside the page never does (the board draws its frames; this element is never seen)
    Object.assign(video.style, { position: 'fixed', left: '0', top: '0', width: '1px', height: '1px', opacity: '0.01', pointerEvents: 'none' });
    video.setAttribute('aria-hidden', 'true');
    document.body.appendChild(video);
    // once enough has arrived, start it if it is still the one wanted (a play() refused while loading is not retried)
    video.addEventListener('canplay', () => {
      if (video.paused && this.wanted === shape && !this.stopped && !document.hidden) void video.play().catch(() => undefined);
    });
    // H.264 where the browser has it (Chrome, Safari, Edge); otherwise the WebM copy next to it (open-source builds)
    const h264 = video.canPlayType('video/mp4; codecs="avc1.640028"') !== '';
    video.src = assetUrl(this.theme.path + (file.endsWith('.mp4') && !h264 ? file.replace(/\.mp4$/, '.webm') : file));
    this.videos.set(shape, video);
    video.addEventListener('playing', () => {
      if (this.ready.has(shape)) return;
      this.ready.set(shape, new Texture({ source: new VideoSource({ resource: video, autoPlay: false, loop: true, muted: true }) }));
      if (this.wanted === shape) this.onReady();
    }, { once: false });
    void video.play().catch(() => undefined);
  }
}
