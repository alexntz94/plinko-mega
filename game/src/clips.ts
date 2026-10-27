// A theme's clips for its big moments (Disco Is Dead: the needle dropping on a record as the bonus starts, the whole
// disco celebrating a big win): a short video over the whole game, a few seconds, then it fades away; a tap ends it
// sooner. The game goes on under it (nothing waits for it). Loaded with the moving background (after the player's
// first round, not when the phone saves data or the player stopped the motion); a clip not loaded yet is skipped.
// Purely visual.

import { Container, Graphics, Sprite, Texture, VideoSource } from 'pixi.js';
import { gsap } from 'gsap';
import { pageVideo } from './actor';
import type { Theme } from './theme';

/** how long a clip stays (its videos are longer: it fades out before its end) */
const SHOWN = 3.2;

export class Clips extends Container {
  private readonly videos = new Map<string, { video: HTMLVideoElement; texture: Texture | null }>();
  private readonly picture = new Sprite();
  private readonly hit = new Graphics();
  private playing: { video: HTMLVideoElement; stop: gsap.core.Tween } | null = null;

  constructor(private readonly clips: Theme['clips'], private readonly url: (file: string) => string) {
    super();
    this.visible = false;
    this.addChild(this.hit, this.picture);
    this.hit.eventMode = 'static';
    this.hit.on('pointertap', () => this.end());
  }

  get any(): boolean {
    return Boolean(this.clips.bonus || this.clips.win);
  }

  /** Starts loading every clip (once). */
  load(): void {
    if (this.videos.size) return;
    for (const pair of [this.clips.bonus, this.clips.win]) {
      for (const file of pair ? [pair.wide, pair.tall] : []) {
        const entry = { video: pageVideo(this.url(file), false), texture: null as Texture | null };
        entry.video.addEventListener('canplaythrough', () => {
          entry.texture ??= new Texture({ source: new VideoSource({ resource: entry.video, autoPlay: false, loop: false, muted: true }) });
        });
        this.videos.set(file, entry);
      }
    }
  }

  /** Plays a moment's clip over the screen (`w` by `h`), if it is loaded. */
  play(kind: 'bonus' | 'win', landscape: boolean, w: number, h: number): void {
    const pair = this.clips[kind];
    const entry = pair ? this.videos.get(landscape ? pair.wide : pair.tall) : undefined;
    if (!entry?.texture || this.playing) return;
    const { video, texture } = entry;
    video.currentTime = 0;
    void video.play().then(() => {
      this.picture.texture = texture;
      this.layout(w, h);
      this.visible = true;
      gsap.fromTo(this, { alpha: 0 }, { alpha: 1, duration: 0.25, overwrite: true });
      this.playing = { video, stop: gsap.delayedCall(SHOWN, () => this.end()) };
    }).catch(() => undefined);
  }

  /** Covers the screen (cropped, never stretched). */
  layout(w: number, h: number): void {
    this.hit.clear().rect(0, 0, w, h).fill({ color: 0x000000, alpha: 0.001 });
    const t = this.picture.texture;
    if (!t || t === Texture.EMPTY) return;
    const k = Math.max(w / t.width, h / t.height);
    this.picture.scale.set(k);
    this.picture.position.set((w - t.width * k) / 2, (h - t.height * k) / 2);
  }

  private end(): void {
    const now = this.playing;
    if (!now) return;
    this.playing = null;
    now.stop.kill();
    gsap.to(this, { alpha: 0, duration: 0.4, overwrite: true, onComplete: () => { this.visible = false; now.video.pause(); } });
  }
}
