// The background's moving pieces (Hell of a Deal's pawnshop): pictures laid over the still background at places
// measured on it, so a lamp hangs where the painting has room for it and a candle stands on the painted counter. Each
// piece can swing from its anchor (a hanging lamp), breathe or flicker (a flame's light, the hellfire under the floor),
// or play a 4x4 sheet in a loop (a candle flame, smoke, rising embers); pieces on a piece move with it (a lamp's flame
// and glow swing with the lamp). A big win flares every light and sets the lamps swinging. Pieces marked `front` lie
// in a layer of their own (front) over the game: embers rising past the board. Characters filmed dancing (actor.ts)
// stand in a layer of their own too (actors), over the moving background, under the game. Purely visual.

import { AnimatedSprite, Container, Graphics, Rectangle, Sprite, Texture } from 'pixi.js';
import { gsap } from 'gsap';
import { Actor } from './actor';
import { calm, splatter } from './effects';
import type { Prop, Props as PropsArt } from './theme';

interface Live { view: Container; prop: Prop; base: number; phase: number; swing: number; swingV: number; flare: number; span: number }

const RANDOM = (min: number, max: number) => min + Math.random() * (max - min);

export class Props extends Container {
  private live: Live[] = [];
  private readonly frames = new Map<string, Texture[]>();
  private time = 0;
  private shape: 'wide' | 'tall' | null = null;
  /** the pieces in front of the game (main.ts puts this layer over it) */
  readonly front = new Container();
  /** the dancing characters: over the moving background (which hides this layer's pieces), under the game */
  readonly actors = new Container();
  private dancers: Actor[] = [];
  private dancing = false;
  /** the background picture's size as laid out (its own pixels) */
  private size = { w: 0, h: 0 };

  constructor(private readonly art: PropsArt, private readonly tex: (file: string) => Texture,
              private readonly url: (file: string) => string = file => file) {
    super();
    for (const [name, sheet] of Object.entries(art.sheets)) {
      const t = tex(sheet.file), [cols, rows] = sheet.grid;
      const w = t.frame.width / cols, h = t.frame.height / rows;
      // the last cell of a ChatGPT sheet is often empty: left out of a loop
      const count = cols * rows - (sheet.skipLast ? 1 : 0);
      this.frames.set(name, Array.from({ length: count }, (_, i) => new Texture({
        source: t.source, frame: new Rectangle(t.frame.x + (i % cols) * w, t.frame.y + Math.floor(i / cols) * h, w, h) })));
    }
  }

  /**
   * Lays the pieces over the background as it is drawn now: its picture `w` by `h` (logical pixels) placed at (x, y) and
   * scaled by `scale` on the screen. The pieces of the orientation on screen; the others are let go.
   */
  layout(landscape: boolean, x: number, y: number, scale: number, w: number, h: number): void {
    for (const layer of [this, this.front, this.actors]) {
      layer.position.set(x, y);
      layer.scale.set(scale);
    }
    this.size = { w, h };
    const shape = landscape ? 'wide' : 'tall';
    if (shape === this.shape && this.live.length) return;
    this.shape = shape;
    for (const layer of [this, this.front, this.actors]) layer.removeChildren().forEach(child => child.destroy({ children: true }));
    this.live = [];
    this.dancers.forEach(actor => actor.run(false));
    this.dancers = [];
    for (const prop of this.art[shape]) {
      if (prop.actor && prop.file) this.actors.addChild(this.actor(prop, w, h));
      else (prop.front ? this.front : this).addChild(this.make(prop, w, h, h));
    }
    this.dancers.forEach(actor => actor.run(this.dancing));
  }

  /** A dancing character: its first frame until its videos play, the share of the picture's height it stands. */
  private actor(prop: Prop, w: number, h: number): Actor {
    const actor = new Actor(this.tex(prop.file as string), prop.anchor,
      { idle: this.url(prop.actor!.idle), win: this.url(prop.actor!.win) }, prop.actor!.part);
    actor.scale.set(prop.width !== null ? (prop.width * w) / actor.size.w : (prop.height * h) / actor.size.h);
    actor.position.set(prop.at[0] * w, prop.at[1] * h);
    if (this.played) actor.allowWin();
    this.dancers.push(actor);
    return actor;
  }

  /** After the player's first round: the characters' win clips may load (actor.ts). */
  firstRoundPlayed(): void {
    this.played = true;
    this.dancers.forEach(actor => actor.allowWin());
  }
  private played = false;

  /** The characters dance while the moving background plays (main.ts), and stand still on the still picture. */
  animate(on: boolean): void {
    this.dancing = on && !calm;
    this.dancers.forEach(actor => actor.run(this.dancing));
  }

  /** One piece, `h` its parent's height (the background's, or the piece it stands on). */
  private make(prop: Prop, parentW: number, parentH: number, screenH: number): Container {
    const frames = prop.sheet ? this.frames.get(prop.sheet) : undefined;
    let view: Sprite;
    if (frames?.length) {
      const anim = new AnimatedSprite({ textures: frames, autoUpdate: !calm });
      anim.animationSpeed = prop.fps / 60;
      anim.gotoAndPlay(Math.floor(Math.random() * frames.length));
      if (calm) anim.stop();
      view = anim;
    } else {
      view = new Sprite(this.tex(prop.file as string));
    }
    view.anchor.set(prop.anchor[0], prop.anchor[1]);
    const tw = view.texture.frame.width, th = view.texture.frame.height;
    // its size: a share of the screen picture's height (or width), whatever it stands on
    const scale = prop.width !== null ? (prop.width * parentW) / tw : (prop.height * screenH) / th;
    view.scale.set(scale);
    view.position.set(prop.at[0] * parentW, prop.at[1] * parentH);
    view.alpha = prop.alpha;
    if (prop.blend === 'add') view.blendMode = 'add';
    // what stands on it, in its own picture's place (it moves with it): scaled back so its sizes are the screen's
    for (const child of prop.on) {
      const c = this.make(child, tw, th, screenH / scale);
      c.position.set((child.at[0] - prop.anchor[0]) * tw, (child.at[1] - prop.anchor[1]) * th);
      view.addChild(c);
    }
    this.live.push({ view, prop, base: prop.alpha, phase: RANDOM(0, Math.PI * 2), swing: 0, swingV: 0, flare: 0, span: parentH });
    return view;
  }

  /** A big win: every light flares and the lamps swing. */
  flare(): void {
    if (calm) return;
    this.dancers.forEach(actor => actor.cheer());
    for (const l of this.live) {
      if (l.prop.swing) l.swingV += RANDOM(1.2, 2.2) * (Math.random() < 0.5 ? -1 : 1);
      if (l.prop.pulse || l.prop.flicker) gsap.fromTo(l, { flare: 1 }, { flare: 0, duration: 1.6, ease: 'power2.out', overwrite: true });
    }
  }

  /**
   * A wall of fire across the foot of the screen, in front of the game (the bonus starts, a big win): a row of the
   * theme's fire bursts (art.wipe) going up from the middle outwards, and the whole screen glowing hot for a moment.
   */
  wipe(): void {
    const spec = this.art.wipe;
    if (spec?.kind === 'glitch') return this.glitch(spec);
    if (spec?.kind === 'ink') return this.ink(spec);
    const frames = spec ? this.frames.get(spec.sheet) : undefined;
    if (calm || !spec || !frames?.length || !this.size.h) return;
    const { w, h } = this.size;
    const glow = new Graphics().rect(0, 0, w, h).fill({ color: '#ff5a14', alpha: 1 });
    glow.blendMode = 'add';
    this.front.addChild(glow);
    gsap.timeline({ onComplete: () => glow.destroy() })
      .fromTo(glow, { alpha: 0 }, { alpha: 0.14, duration: 0.15 })
      .to(glow, { alpha: 0, duration: 0.7, ease: 'power2.in' });
    for (let i = 0; i < spec.count; i++) {
      const u = (i + 0.5) / spec.count;
      const fire = new AnimatedSprite({ textures: frames, anchor: { x: 0.5, y: 0.88 }, autoUpdate: true });
      fire.loop = false;
      fire.blendMode = 'add';
      fire.alpha = 0.8;
      // a frame-cell's height for the theme's share of the screen, a little different each
      const k = spec.height * h / frames[0].frame.height * RANDOM(0.8, 1.2);
      fire.scale.set(Math.random() < 0.5 ? -k : k, k);
      fire.position.set(u * w + RANDOM(-0.3, 0.3) * w / spec.count, h * 1.02);
      fire.animationSpeed = frames.length / (spec.seconds * 60);
      fire.onComplete = () => fire.destroy();
      fire.visible = false;
      this.front.addChild(fire);
      // from the middle outwards
      gsap.delayedCall(Math.abs(u - 0.5) * 0.35, () => { if (!fire.destroyed) { fire.visible = true; fire.play(); } });
    }
  }

  /**
   * The screen glitching (PLINKO.exe): bars of the theme's colours torn across it at random heights, jumping about for a
   * moment in hard steps, in front of the game, and a quick flash.
   */
  private glitch(spec: NonNullable<PropsArt['wipe']>): void {
    if (calm || !this.size.h) return;
    const { w, h } = this.size;
    const layer = new Container();
    this.front.addChild(layer);
    const flash = new Graphics().rect(0, 0, w, h).fill({ color: spec.colours[0], alpha: 1 });
    flash.blendMode = 'add';
    flash.alpha = 0;
    layer.addChild(flash);
    const bars = Array.from({ length: spec.count }, (_, i) => {
      const bar = new Graphics().rect(0, -0.5, 1, 1).fill({ color: spec.colours[i % spec.colours.length], alpha: 1 });
      bar.blendMode = 'add';
      bar.visible = false;
      layer.addChild(bar);
      return bar;
    });
    // a few hard jumps: every bar to a new place and size, some hidden
    const jump = () => {
      for (const bar of bars) {
        bar.visible = Math.random() < 0.7;
        const x0 = RANDOM(-0.2, 0.7) * w;
        bar.position.set(x0, RANDOM(0, 1) * h);
        bar.scale.set(RANDOM(0.2, 0.9) * w, spec.height * h * RANDOM(0.3, 2.5));
        bar.alpha = RANDOM(0.15, 0.55);
      }
    };
    const steps = Math.max(3, Math.round(spec.seconds / 0.06));
    const tl = gsap.timeline({ onComplete: () => layer.destroy({ children: true }) });
    tl.to(flash, { alpha: 0.18, duration: 0.05 }).to(flash, { alpha: 0, duration: 0.35, ease: 'power2.out' });
    for (let i = 0; i < steps; i++) tl.call(jump, [], i * 0.06);
    tl.to(layer, { alpha: 0, duration: 0.12 }, steps * 0.06);
  }

  /**
   * Ink thrown across the screen (Death & Taxes): big blots of the theme's splatters hitting both sides of the screen
   * one after another, in front of the game, running a little and fading (the board in the middle stays clear).
   */
  private ink(spec: NonNullable<PropsArt['wipe']>): void {
    if (calm || !this.size.h) return;
    const { w, h } = this.size;
    for (let i = 0; i < spec.count; i++) {
      const left = i % 2 === 0;
      gsap.delayedCall(i * 0.06, () => splatter(this.front, (left ? RANDOM(0.02, 0.26) : RANDOM(0.74, 0.98)) * w, RANDOM(0.08, 0.92) * h,
        spec.height * h * RANDOM(0.7, 1.3), spec.seconds, 0.1));
    }
  }

  /** Every frame: swings, breathing and flickering lights. */
  update(deltaMs: number): void {
    if (calm || !(this.visible || this.front.visible) || !this.live.length) return;
    const dt = Math.min(deltaMs, 50) / 1000;
    this.time += dt;
    const t = this.time;
    for (const l of this.live) {
      const p = l.prop;
      if (p.swing) {
        // a slow pendulum, and a damped spring on top of it (a big win's push)
        l.swingV += (-9 * l.swing - 1.1 * l.swingV) * dt;
        l.swing += l.swingV * dt;
        l.view.rotation = p.swing * Math.sin(t * 0.9 + l.phase) + 0.12 * Math.tanh(l.swing);
      }
      // a scan line: down over its parent, again and again
      if (p.scan) l.view.y = (((t / p.scan + l.phase / (Math.PI * 2)) % 1) * 1.2 - 0.1) * l.span;
      let a = l.base;
      if (p.pulse) a *= 1 - p.pulse + p.pulse * (0.5 + 0.5 * Math.sin(t * 1.3 + l.phase));
      if (p.flicker) {
        // a flame's light: a quick shimmer over a slower waver
        const k = 0.55 * Math.sin(t * 11 + l.phase) + 0.3 * Math.sin(t * 23.7 + l.phase * 2) + 0.15 * Math.sin(t * 3.1);
        a *= 1 - p.flicker * (0.5 - 0.5 * k);
      }
      l.view.alpha = Math.min(1, a + l.flare * 0.6);
    }
  }
}
