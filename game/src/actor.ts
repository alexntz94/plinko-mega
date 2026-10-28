// A character filmed dancing (Disco Is Dead's skeletons): its idle loop and its win clip are ordinary MP4s with the
// colour picture in the top half and its shape (white where it is, black around it) in the bottom half, so they show
// with a see-through background on every browser (Safari has no WebM with transparency): the colour half is drawn
// through the shape half as its mask. Until the video plays (the moving background is off, the player's first round
// is not over, the phone saves data), its first frame stands there as a picture. The win clip (the bigger one) loads
// only after the player's first round, like the moving background: until then a big win keeps the idle loop. Purely
// visual.

import { Container, Rectangle, Sprite, Texture, VideoSource } from 'pixi.js';

interface Clip { video: HTMLVideoElement; colour: Texture | null; shape: Texture | null }

/** Each video once, however many characters are cut from it (the two dancers on a phone): its element and frames. */
const shared = new Map<string, { video: HTMLVideoElement; source: VideoSource | null }>();

/** A muted inline video on the page (1 pixel, invisible: Safari only starts one that counts as on screen). */
export function pageVideo(src: string, loop: boolean): HTMLVideoElement {
  const video = document.createElement('video');
  video.muted = true;
  video.defaultMuted = true;
  video.loop = loop;
  video.playsInline = true;
  video.setAttribute('playsinline', '');
  video.setAttribute('muted', '');
  video.preload = 'auto';
  Object.assign(video.style, { position: 'fixed', left: '0', top: '0', width: '1px', height: '1px', opacity: '0.01', pointerEvents: 'none' });
  video.setAttribute('aria-hidden', 'true');
  document.body.appendChild(video);
  // H.264 where the browser has it; otherwise the WebM copy next to it
  const h264 = video.canPlayType('video/mp4; codecs="avc1.640028"') !== '';
  video.src = h264 ? src : src.replace(/\.mp4$/, '.webm');
  return video;
}

export class Actor extends Container {
  private readonly still: Sprite;
  private readonly colour = new Sprite();
  private readonly shape = new Sprite();
  private idle: Clip | null = null;
  private win: Clip | null = null;
  private running = false;
  private showing: Clip | null = null;
  /** the win clip may load (after the player's first round) */
  private winAllowed = false;

  /** `still` the first frame, `files` the two clips' addresses, `part` the share of their width this character is
   *  ([0, 1]: all of it; a phone shows the two dancers of one video apart). */
  constructor(still: Texture, anchor: [number, number], private readonly files: { idle: string; win: string },
              private readonly part: [number, number] = [0, 1]) {
    super();
    still = part[0] === 0 && part[1] === 1 ? still : new Texture({ source: still.source,
      frame: new Rectangle(still.frame.x + part[0] * still.frame.width, still.frame.y, (part[1] - part[0]) * still.frame.width, still.frame.height) });
    this.still = new Sprite({ texture: still });
    for (const part of [this.still, this.colour, this.shape]) part.position.set(-anchor[0] * still.width, -anchor[1] * still.height);
    this.colour.mask = this.shape;
    this.colour.visible = this.shape.visible = false;
    this.addChild(this.still, this.colour, this.shape);
  }

  /** The picture's size (as for a still prop). */
  get size(): { w: number; h: number } {
    return { w: this.still.texture.width, h: this.still.texture.height };
  }

  /** Dancing (the moving background plays) or standing still (it doesn't). */
  run(on: boolean): void {
    if (on === this.running) return;
    this.running = on;
    if (!on) {
      for (const clip of [this.idle, this.win]) clip?.video.pause();
      this.show(null);
      return;
    }
    this.idle ??= this.load(this.files.idle, true);
    if (this.winAllowed) this.win ??= this.load(this.files.win, false);
    void this.idle.video.play().catch(() => undefined);
    if (this.idle.colour) this.show(this.idle);
  }

  /** After the player's first round: the win clip loads (now, or when the character starts dancing). */
  allowWin(): void {
    this.winAllowed = true;
    if (this.running) this.win ??= this.load(this.files.win, false);
  }

  /** A big win: the win clip once, then back to the idle loop. */
  cheer(): void {
    const win = this.win;
    if (!this.running || !win?.colour || this.showing === win) return;
    win.video.currentTime = 0;
    void win.video.play().then(() => this.show(win)).catch(() => undefined);
  }

  private load(src: string, loop: boolean): Clip {
    let one = shared.get(src);
    if (!one) shared.set(src, one = { video: pageVideo(src, loop), source: null });
    const video = one.video, clip: Clip = { video, colour: null, shape: null };
    const frames = () => {
      if (clip.colour || !video.videoWidth) return;
      one.source ??= new VideoSource({ resource: video, autoPlay: false, loop, muted: true });
      const w = video.videoWidth, h = video.videoHeight / 2, x = this.part[0] * w, cw = (this.part[1] - this.part[0]) * w;
      clip.colour = new Texture({ source: one.source, frame: new Rectangle(x, 0, cw, h) });
      clip.shape = new Texture({ source: one.source, frame: new Rectangle(x, h, cw, h) });
    };
    // (a video shared with a character before this one may be playing already)
    if (!video.paused) frames();
    video.addEventListener('playing', () => {
      frames();
      if (loop && this.running && !this.showing) this.show(clip);
    });
    if (!loop) clip.video.addEventListener('ended', () => { if (this.showing === clip) this.show(this.running ? this.idle : null); });
    return clip;
  }

  /** Shows a clip's frames (null: the still picture), at the still picture's size. */
  private show(clip: Clip | null): void {
    this.showing = clip?.colour ? clip : null;
    const on = this.showing !== null;
    this.still.visible = !on;
    this.colour.visible = this.shape.visible = on;
    if (!clip?.colour || !clip.shape) return;
    this.colour.texture = clip.colour;
    this.shape.texture = clip.shape;
    for (const part of [this.colour, this.shape]) {
      part.width = this.still.texture.width;
      part.height = this.still.texture.height;
    }
  }
}
