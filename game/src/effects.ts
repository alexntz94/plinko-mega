import { Container, Graphics, GraphicsContext, Rectangle, Sprite, type Text, type Texture } from 'pixi.js';
import { gsap } from 'gsap';
import type { Viewport } from './viewport';
import { counting, goldText } from './paint';

// Visual effects only: sparks, bursts, shakes and the big-win banner. They never decide or change a result; they
// dress up what the server already decided. Players who ask their device for less motion get calmer versions.

export const calm = typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches === true;

const MAX_PARTICLES = 600;     // above this, new effects are skipped (100 balls at once must stay smooth on a phone)
const shapes = new Map<string, GraphicsContext>();

/** One shared shape per colour and kind: particles are cheap copies of it. */
type Kind = 'dot' | 'chip' | 'ring' | 'soft';

function shape(colour: string, kind: Kind): GraphicsContext {
  const key = kind + colour;
  let context = shapes.get(key);
  if (!context) {
    context = new GraphicsContext();
    if (kind === 'dot') context.circle(0, 0, 1).fill(colour);
    // a dot with a soft edge (smoke, flame): rings of falling opacity
    if (kind === 'soft') context.circle(0, 0, 1).fill({ color: colour, alpha: 0.2 }).circle(0, 0, 0.75).fill({ color: colour, alpha: 0.3 })
      .circle(0, 0, 0.5).fill({ color: colour, alpha: 0.45 });
    else if (kind === 'chip') context.rect(-1, -0.5, 2, 1).fill(colour);
    else context.circle(0, 0, 1).stroke({ color: colour, width: 0.12 });
    shapes.set(key, context);
  }
  return context;
}

const random = (min: number, max: number) => min + Math.random() * (max - min);

/** A layer of short-lived particles, in the coordinates of whatever it is added to. */
export class Particles extends Container {
  private spawn(colour: string, kind: Kind, x: number, y: number, size: number): Graphics | null {
    if (this.children.length >= MAX_PARTICLES) return null;
    const particle = new Graphics(shape(colour, kind));
    particle.position.set(x, y);
    particle.scale.set(size);
    this.addChild(particle);
    return particle;
  }

  private release(particle: Graphics): void {
    gsap.killTweensOf([particle, particle.scale]);
    particle.destroy();
  }

  /** A few sparks flying out from a point (a ball touching a peg). */
  sparks(x: number, y: number, colour: string, count: number, reach = 14): void {
    for (let i = 0; i < (calm ? Math.ceil(count / 2) : count); i++) {
      const p = this.spawn(colour, 'dot', x, y, random(1, 1.8));
      if (!p) return;
      const angle = random(0, Math.PI * 2);
      const distance = random(reach * 0.5, reach);
      gsap.to(p, { x: x + Math.cos(angle) * distance, y: y + Math.sin(angle) * distance, alpha: 0, duration: random(0.18, 0.32),
        ease: 'power2.out', onComplete: () => this.release(p) });
    }
  }

  /** A lick of flame left behind: soft-edged, not added up into white, it shrinks and fades. */
  flame(x: number, y: number, colour: string, size: number, life: number): void {
    if (calm) return;
    const p = this.spawn(colour, 'soft', x, y, size);
    if (!p) return;
    p.alpha = 0.6;
    gsap.to(p, { y: y - random(2, 6), alpha: 0, duration: life, ease: 'power1.in', onComplete: () => this.release(p) });
    gsap.to(p.scale, { x: size * 0.3, y: size * 0.3, duration: life });
  }

  /** One spark spat out in a direction (radians), fading fast: a lit fuse. */
  spit(x: number, y: number, colour: string, angle: number, reach = 12): void {
    if (calm) return;
    const p = this.spawn(colour, 'dot', x, y, random(0.9, 1.6));
    if (!p) return;
    p.blendMode = 'add';
    const a = angle + random(-0.7, 0.7), d = random(reach * 0.4, reach);
    gsap.to(p, { x: x + Math.cos(a) * d, y: y + Math.sin(a) * d, alpha: 0, duration: random(0.05, 0.09),
      ease: 'power2.out', onComplete: () => this.release(p) });
  }

  /**
   * A fountain thrown up from a point that falls back under gravity (a ball landing in a slot, a win).
   */
  burst(x: number, y: number, colours: string[], count: number, power: number, gravity = 900): void {
    for (let i = 0; i < (calm ? Math.ceil(count / 3) : count); i++) {
      const kind: Kind = i % 3 === 0 ? 'chip' : 'dot';
      const p = this.spawn(colours[i % colours.length], kind, x, y, random(1.6, 3.2));
      if (!p) return;
      const vx = random(-0.6, 0.6) * power;
      const vy = -random(0.6, 1.2) * power;
      const life = random(0.5, 0.9);
      const spin = random(-8, 8);
      const state = { t: 0 };
      gsap.to(state, {
        t: life, duration: life, ease: 'none',
        onUpdate: () => {
          if (p.destroyed) { gsap.killTweensOf(state); return; }   // cleared while flying
          p.x = x + vx * state.t;
          p.y = y + vy * state.t + gravity * state.t * state.t;
          p.rotation = spin * state.t;
          p.alpha = 1 - (state.t / life) ** 2;
        },
        onComplete: () => this.release(p),
      });
    }
  }

  /** An expanding ring of light (a big landing). */
  ring(x: number, y: number, colour: string, radius: number): void {
    const p = this.spawn(colour, 'ring', x, y, radius * 0.3);
    if (!p) return;
    gsap.to(p.scale, { x: radius, y: radius, duration: 0.45, ease: 'power2.out' });
    gsap.to(p, { alpha: 0, duration: 0.45, ease: 'power1.in', onComplete: () => this.release(p) });
  }

  clear(): void {
    [...this.children].forEach(child => this.release(child as Graphics));
  }
}

/** Shakes a container for a moment (moves its pivot, so it never fights its layout position). */
export function shake(target: Container, strength: number, duration = 0.35): void {
  if (calm) return;
  const steps = 7;
  const timeline = gsap.timeline({ onComplete: () => target.pivot.set(0, 0) });
  for (let i = 0; i < steps; i++) {
    const fade = 1 - i / steps;
    timeline.to(target.pivot, { x: random(-1, 1) * strength * fade, y: random(-1, 1) * strength * fade, duration: duration / steps, ease: 'sine.inOut' });
  }
  timeline.to(target.pivot, { x: 0, y: 0, duration: duration / steps });
}

export type WinLevel = 'BIG WIN' | 'MEGA WIN' | 'EPIC WIN';

/** How a round compares with what it cost: 10x the bet is a big win, 25x mega, 50x epic. */
export function winLevel(totalWin: number, totalBet: number): WinLevel | null {
  if (totalBet <= 0) return null;
  const times = totalWin / totalBet;
  return times >= 20 ? 'EPIC WIN' : times >= 8 ? 'MEGA WIN' : times >= 3 ? 'BIG WIN' : null;
}

/** One gold coin, shared by every coin of the big win: a rim, a face and a star struck in it. */
let coinShape: GraphicsContext | null = null;
function coin(): GraphicsContext {
  if (!coinShape) {
    const star: number[] = [];
    for (let i = 0; i < 10; i++) {
      const a = -Math.PI / 2 + i * Math.PI / 5, r = i % 2 ? 4 : 9;
      star.push(Math.cos(a) * r, Math.sin(a) * r);
    }
    coinShape = new GraphicsContext()
      .circle(0, 0, 18).fill('#8a5a00')
      .circle(0, 0, 16).fill('#ffcf3a')
      .circle(0, 0, 12.5).fill('#f2a900')
      .poly(star).fill('#fff1a8')
      .circle(-6, -7, 3.5).fill({ color: '#ffffff', alpha: 0.7 });
  }
  return coinShape;
}

interface Coin { g: Graphics; vx: number; vy: number; spin: number; phase: number }

/**
 * Spinning gold coins with gravity: poured from the top of the screen, shot up out of a point (a fountain), or burst
 * out of a point in every direction. Runs only while it has coins or is pouring.
 */
/** Something that showers the screen: coins, or explosions for a war theme. */
export interface Shower extends Container {
  layout(view: Viewport): void;
  pour(rain: number, spout?: number, x?: number, y?: number, seconds?: number): void;
  stop(): void;
  burst(x: number, y: number, n: number, power?: number): void;
  clear(): void;
}

export class CoinShower extends Container implements Shower {
  private readonly coins: Coin[] = [];
  private view: Viewport | null = null;
  private rain = 0;                         // coins a second from the top
  private spout = 0;                        // coins a second up out of `from`
  private from = { x: 0, y: 0 };
  private owed = 0;
  private until = 0;                        // when pouring stops (seconds on the ticker's clock; 0: until stop())
  private running = false;

  constructor(private readonly max = 220) {
    super();
    this.eventMode = 'none';
  }

  layout(view: Viewport): void {
    this.view = view;
  }

  /** Coins a second from the top and up out of (x, y), for `seconds` (0: until stop()). Adds to what already pours. */
  pour(rain: number, spout = 0, x = 0, y = 0, seconds = 0): void {
    if (calm) return;
    this.rain += rain;
    this.spout += spout;
    if (spout) this.from = { x, y };
    this.until = seconds ? Math.max(this.until, gsap.ticker.time + seconds) : 0;
    this.run();
  }

  stop(): void {
    this.rain = 0;
    this.spout = 0;
  }

  /** n coins out of (x, y) in every direction, mostly upwards. */
  burst(x: number, y: number, n: number, power = 900): void {
    if (calm) return;
    for (let i = 0; i < n; i++) {
      const a = random(0, Math.PI * 2), speed = random(power * 0.35, power);
      this.add(x, y, Math.cos(a) * speed, Math.sin(a) * speed - power * 0.35);
    }
    this.run();
  }

  clear(): void {
    this.stop();
    this.coins.splice(0).forEach(c => c.g.destroy());
  }

  private run(): void {
    if (this.running) return;
    this.running = true;
    gsap.ticker.add(this.tick);
  }

  private add(x: number, y: number, vx: number, vy: number): void {
    if (this.coins.length >= this.max) return;
    const g = new Graphics(coin());
    g.position.set(x, y);
    g.scale.set(random(0.7, 1.3));
    this.addChild(g);
    this.coins.push({ g, vx, vy, spin: random(6, 14), phase: random(0, Math.PI * 2) });
  }

  /** Every frame: new coins while pouring, every coin falling and flipping, gone below the screen. */
  private readonly tick = (): void => {
    if (!this.view) return;
    const dt = Math.min(gsap.ticker.deltaRatio() / 60, 0.05);
    const { stageW, stageH } = this.view;
    if (this.until && gsap.ticker.time > this.until) { this.stop(); this.until = 0; }
    const rate = this.rain + this.spout;
    this.owed = rate > 0 ? this.owed + dt : 0;
    while (rate > 0 && this.owed > 0) {
      this.owed -= 1 / rate;
      if (Math.random() * rate < this.rain) this.add(random(0, stageW), -30, random(-60, 60), random(100, 400));
      else this.add(this.from.x + random(-40, 40), this.from.y, random(-380, 380), -random(900, 1400));
    }
    for (let i = this.coins.length - 1; i >= 0; i--) {
      const c = this.coins[i];
      c.vy += 1500 * dt;
      c.g.x += c.vx * dt;
      c.g.y += c.vy * dt;
      c.phase += c.spin * dt;
      c.g.scale.x = Math.abs(c.g.scale.y) * Math.cos(c.phase);   // the coin flipping over
      if (c.g.y > stageH + 60 && c.vy > 0) {
        c.g.destroy();
        this.coins.splice(i, 1);
      }
    }
    if (rate === 0 && this.coins.length === 0) {
      gsap.ticker.remove(this.tick);
      this.running = false;
    }
  };
}


/** A theme's photo explosion (a fireball, a ground dust ring, a smoke plume), or null: blasts are drawn in code. */
let blastArt: { fire: Texture; dust: Texture; smoke: Texture } | null = null;
export function setBlastArt(art: { fire: Texture; dust: Texture; smoke: Texture } | null): void {
  blastArt = art;
}
let liveBlasts = 0;
const MAX_BLASTS = 24;   // photo blasts on screen at once (100 balls landing must stay smooth on a phone)

/**
 * A photo explosion at (x, y) on `layer`, about `radius` across its fireball: the dust ring bursts out along the ground,
 * the fireball flashes up and burns out, and the smoke rolls up out of it and fades. False when the theme has no photo
 * explosion or too many are already on screen (the caller draws its own then).
 */
export function photoBlast(layer: Container, x: number, y: number, radius: number, smoke = true): boolean {
  if (!blastArt || calm) return false;
  if (liveBlasts >= MAX_BLASTS) return true;   // skipped quietly: the screen is full of them already
  liveBlasts++;
  const parts: Sprite[] = [];
  const part = (texture: Texture, ax: number, ay: number) => {
    const sprite = new Sprite({ texture, anchor: { x: ax, y: ay } });
    sprite.position.set(x, y);
    layer.addChild(sprite);
    parts.push(sprite);
    return sprite;
  };
  const size = (sprite: Sprite, width: number) => sprite.scale.set(width / sprite.texture.width);
  let smokeOut: Sprite | null = null;
  if (smoke) {
    smokeOut = part(blastArt.smoke, 0.5, 0.86);
    size(smokeOut, radius * 1.4);
    smokeOut.alpha = 0;
  }
  const dust = part(blastArt.dust, 0.5, 0.6);
  size(dust, radius * 1.2);
  const fire = part(blastArt.fire, 0.5, 0.5);
  fire.rotation = random(-0.6, 0.6);
  size(fire, radius * 0.3);
  const end = fire.scale.x * (2 * radius / (radius * 0.3)) * random(0.9, 1.1);
  const tl = gsap.timeline({ onComplete: () => {
    parts.forEach(p => { gsap.killTweensOf([p, p.scale]); if (!p.destroyed) p.destroy(); });
    liveBlasts = Math.max(0, liveBlasts - 1);
  } });
  tl.to(fire.scale, { x: end, y: end, duration: 0.2, ease: 'power3.out' }, 0)
    .to(fire.scale, { x: end * 1.12, y: end * 1.12, duration: 0.45, ease: 'none' }, 0.2)
    .to(fire, { alpha: 0, duration: 0.4, ease: 'power1.in' }, 0.25)
    .to(dust.scale, { x: dust.scale.x * 2.6, y: dust.scale.y * 2, duration: 0.55, ease: 'power2.out' }, 0)
    .to(dust, { alpha: 0, duration: 0.45, ease: 'power1.in' }, 0.25);
  if (smokeOut) {
    const s0 = smokeOut.scale.x;
    tl.to(smokeOut, { alpha: 0.85, duration: 0.25 }, 0.18)
      .fromTo(smokeOut.scale, { x: s0 * 0.5, y: s0 * 0.5 }, { x: s0 * 1.25, y: s0 * 1.25, duration: 1.4, ease: 'power1.out' }, 0.18)
      .to(smokeOut, { y: y - radius * 0.7, duration: 1.4, ease: 'power1.out' }, 0.18)
      .to(smokeOut, { alpha: 0, duration: 0.7, ease: 'power1.in' }, 0.9);
  }
  return true;
}

/** Soft-edged discs shared by every blast: one per colour (rings of falling opacity, no blur filter). */
const softDiscs = new Map<string, GraphicsContext>();
function softDisc(colour: string): GraphicsContext {
  let context = softDiscs.get(colour);
  if (!context) {
    context = new GraphicsContext().circle(0, 0, 1).fill({ color: colour, alpha: 0.25 }).circle(0, 0, 0.75).fill({ color: colour, alpha: 0.35 })
      .circle(0, 0, 0.5).fill({ color: colour, alpha: 0.5 });
    softDiscs.set(colour, context);
  }
  return context;
}

const FIRE = ['#c1121f', '#ff4a1a', '#ff8a1a', '#ffd76a'];
const SMOKE = ['#3d4046', '#55595f', '#2a2c30'];

/**
 * The war theme's shower: explosions instead of coins. A blast is a fireball with a white-hot core, a shockwave ring,
 * smoke rolling up and debris thrown out; it pours them across the screen, or bursts a cluster of them at a point.
 * Kept light: shared shapes, a cap on blasts at once, no filters.
 */
export class BlastShower extends Container implements Shower {
  private readonly particles = new Particles();
  private view: Viewport | null = null;
  private rain = 0;
  private spout = 0;
  private from = { x: 0, y: 0 };
  private owed = 0;
  private until = 0;
  private running = false;
  private live = 0;

  constructor(private readonly max = 40) {
    super();
    this.eventMode = 'none';
    this.addChild(this.particles);
  }

  layout(view: Viewport): void {
    this.view = view;
  }

  pour(rain: number, spout = 0, x = 0, y = 0, seconds = 0): void {
    if (calm) return;
    this.rain += rain * 0.4;     // a blast fills far more screen than a coin
    this.spout += spout * 0.4;
    if (spout) this.from = { x, y };
    this.until = seconds ? Math.max(this.until, gsap.ticker.time + seconds) : 0;
    if (!this.running) { this.running = true; gsap.ticker.add(this.tick); }
  }

  stop(): void {
    this.rain = 0;
    this.spout = 0;
  }

  /** A cluster at a point: one big blast and smaller ones round it, more for a bigger n. */
  burst(x: number, y: number, n: number, power = 900): void {
    if (calm) return;
    const size = Math.min(2.2, 0.6 + n / 30);
    this.blast(x, y, size);
    const around = Math.min(6, Math.floor(n / 10));
    const reach = power * 0.12;
    for (let i = 0; i < around; i++) {
      gsap.delayedCall(0.06 + i * 0.07, () => this.blast(x + random(-reach, reach), y + random(-reach, reach * 0.6), size * random(0.4, 0.7)));
    }
  }

  clear(): void {
    this.stop();
    this.particles.clear();
    for (const child of this.children.slice()) {
      if (child === this.particles) continue;
      gsap.killTweensOf([child, child.scale]);
      child.destroy();
    }
    this.live = 0;
  }

  /** One explosion at (x, y), size about 1 (a wheel win) to 2 (a big win). */
  private blast(x: number, y: number, size: number): void {
    if (photoBlast(this, x, y, 70 * size)) {
      this.particles.burst(x, y, ['#ffd76a', '#ff8a1a', '#fff3b0'], Math.round(8 * size), 380 * size, 600);
      return;
    }
    if (this.live >= this.max) return;
    this.live++;
    const parts: Graphics[] = [];
    const add = (colour: string, radius: number, blend = false) => {
      const g = new Graphics(softDisc(colour));
      g.position.set(x + random(-radius * 0.3, radius * 0.3), y + random(-radius * 0.3, radius * 0.3));
      g.scale.set(radius);
      if (blend) g.blendMode = 'add';
      this.addChildAt(g, 0);
      parts.push(g);
      return g;
    };
    const r = 34 * size;
    // smoke behind, rolling up
    for (let i = 0; i < 3; i++) {
      const puff = add(SMOKE[i % SMOKE.length], r * random(0.6, 0.9));
      puff.alpha = 0.9;
      gsap.to(puff.scale, { x: puff.scale.x * 2.2, y: puff.scale.y * 2.2, duration: 1.3, ease: 'power2.out' });
      gsap.to(puff, { y: puff.y - r * random(1, 1.6), alpha: 0, duration: 1.3, delay: 0.1, ease: 'power1.in' });
    }
    // the fireball and its white-hot core
    for (let i = 0; i < 4; i++) {
      const fire = add(FIRE[i], r * random(0.5, 0.8), true);
      gsap.fromTo(fire.scale, { x: fire.scale.x * 0.2, y: fire.scale.y * 0.2 }, { x: fire.scale.x * 1.5, y: fire.scale.y * 1.5, duration: 0.3, ease: 'power3.out' });
      gsap.to(fire, { alpha: 0, y: fire.y - r * 0.4, duration: 0.55, delay: 0.15, ease: 'power1.in' });
    }
    const core = add('#ffffff', r * 0.45, true);
    gsap.fromTo(core.scale, { x: core.scale.x * 0.3, y: core.scale.y * 0.3 }, { x: core.scale.x * 1.4, y: core.scale.y * 1.4, duration: 0.18 });
    gsap.to(core, { alpha: 0, duration: 0.3, delay: 0.08 });
    // the shockwave
    const ring = new Graphics().circle(0, 0, r).stroke({ color: '#fff3b0', width: 4 });
    ring.blendMode = 'add';
    ring.position.set(x, y);
    this.addChild(ring);
    parts.push(ring);
    gsap.fromTo(ring.scale, { x: 0.2, y: 0.2 }, { x: 1.8, y: 1.8, duration: 0.45, ease: 'power2.out' });
    gsap.to(ring, { alpha: 0, duration: 0.45, ease: 'power1.in' });
    // debris and embers
    this.particles.burst(x, y, ['#1a1d21', '#4b5320', '#2b3036', '#6e5216'], Math.round(8 * size), 420 * size, 1500);
    this.particles.burst(x, y, ['#ffd76a', '#ff8a1a', '#fff3b0'], Math.round(10 * size), 380 * size, 600);
    gsap.delayedCall(1.5, () => {
      parts.forEach(p => { gsap.killTweensOf([p, p.scale]); if (!p.destroyed) p.destroy(); });
      this.live = Math.max(0, this.live - 1);
    });
  }

  /** Every frame while pouring: new blasts across the screen (rain) and out of the spout. */
  private readonly tick = (): void => {
    if (!this.view) return;
    if (this.until && gsap.ticker.time > this.until) { this.stop(); this.until = 0; }
    const rate = this.rain + this.spout;
    if (rate <= 0) { gsap.ticker.remove(this.tick); this.running = false; this.owed = 0; return; }
    this.owed += Math.min(gsap.ticker.deltaRatio() / 60, 0.05);
    const { stageW, stageH } = this.view;
    while (this.owed > 1 / rate) {
      this.owed -= 1 / rate;
      if (Math.random() * rate < this.rain) this.blast(random(stageW * 0.05, stageW * 0.95), random(stageH * 0.1, stageH * 0.75), random(0.8, 1.5));
      else this.blast(this.from.x + random(-60, 60), this.from.y - random(0, stageH * 0.4), random(0.8, 1.4));
    }
  };
}

const LEVELS: [number, WinLevel][] = [[3, 'BIG WIN'], [8, 'MEGA WIN'], [20, 'EPIC WIN']];

/**
 * The big-win celebration over the table: a rain and a fountain of spinning gold coins, the amount counting up, and
 * the title climbing BIG WIN, MEGA WIN, EPIC WIN as the count passes each (a flash and a burst of coins at each step).
 * Tap to skip.
 */
export class BigWin extends Container {
  private readonly dim = new Graphics();
  private readonly rays = new Graphics();
  private readonly glow = new Graphics();
  private readonly banner: Text;
  private readonly amount: Text;
  private readonly shower: Shower;
  private readonly confetti = new Particles();
  private view: Viewport | null = null;
  private done: (() => void) | null = null;
  private timer: gsap.core.Tween | null = null;

  /** explosive: a war theme, whose big win sets off explosions instead of showering coins */
  constructor(private readonly colours: string[], explosive = false) {
    super();
    this.shower = explosive ? new BlastShower() : new CoinShower(260);
    this.banner = goldText('', 92);
    this.amount = goldText('', 70);
    for (let i = 0; i < 24; i++) {
      const a = i * Math.PI * 2 / 24;
      this.rays.moveTo(0, 0).lineTo(Math.cos(a - 0.06) * 900, Math.sin(a - 0.06) * 900)
        .lineTo(Math.cos(a + 0.06) * 900, Math.sin(a + 0.06) * 900).lineTo(0, 0).fill({ color: '#ffd76a', alpha: 0.22 });
    }
    this.rays.blendMode = 'add';
    this.glow.circle(0, 0, 260).fill({ color: '#ffb000', alpha: 0.25 });
    this.glow.blendMode = 'add';
    this.addChild(this.dim, this.rays, this.glow, this.confetti, this.shower, this.banner, this.amount);
    this.eventMode = 'static';
    this.on('pointertap', () => this.close());
    this.visible = false;
  }

  layout(view: Viewport): void {
    this.view = view;
    this.shower.layout(view);
    this.dim.clear().rect(0, 0, view.stageW, view.stageH).fill({ color: '#000000', alpha: 0.6 });
    this.hitArea = new Rectangle(0, 0, view.stageW, view.stageH);
    this.banner.position.set(view.stageW / 2, view.stageH * 0.42);
    this.amount.position.set(view.stageW / 2, view.stageH * 0.42 + 100);
    this.rays.position.set(view.stageW / 2, view.stageH * 0.42 + 30);
    this.glow.position.copyFrom(this.rays.position);
  }

  /**
   * Plays the celebration for a win of `total` on a round that cost `bet`; resolves when it ends by itself (longer the
   * bigger the win) or the player taps.
   */
  show(level: WinLevel, total: number, format: (value: number) => string, bet = 0): Promise<void> {
    this.visible = true;
    this.alpha = 0;
    const steps = bet > 0 ? LEVELS.filter(([times]) => total >= times * bet) : [[0, level] as [number, WinLevel]];
    const top = Math.max(1, steps.length);
    const count = calm ? 1 : 0.9 + top * 0.7;   // seconds to count up
    this.banner.text = (steps[0] ?? [0, level])[1];
    this.amount.text = format(0);
    gsap.to(this, { alpha: 1, duration: 0.2 });
    this.pop(this.banner, 0.2);
    if (!calm && this.view) {
      gsap.to(this.banner, { rotation: 0.03, duration: 0.6, yoyo: true, repeat: -1, ease: 'sine.inOut' });
      gsap.to(this.rays, { rotation: Math.PI * 2, duration: 12, repeat: -1, ease: 'none' });
      gsap.fromTo(this.rays.scale, { x: 0.2, y: 0.2 }, { x: 1, y: 1, duration: 0.6, ease: 'power2.out' });
      gsap.fromTo(this.glow.scale, { x: 0.8, y: 0.8 }, { x: 1.2, y: 1.2, duration: 0.5, yoyo: true, repeat: -1, ease: 'sine.inOut' });
      this.shower.pour(18, 10, this.view.stageW / 2, this.view.stageH + 30);
      this.shower.burst(this.view.stageW / 2, this.view.stageH * 0.45, 30);
    }
    // the count, and the title climbing at each level it passes
    const counter = { value: 0 };
    let shown = 0;
    const show = counting(v => { this.amount.text = format(v); });
    gsap.to(counter, {
      value: total, duration: count, ease: 'power1.inOut',
      onUpdate: () => {
        show(counter.value);
        while (shown + 1 < steps.length && counter.value >= steps[shown + 1][0] * bet) {
          shown++;
          this.banner.text = steps[shown][1];
          this.pop(this.banner, 0.5);
          this.level(shown);
        }
      },
      onComplete: () => {
        show(total, true);
        this.pop(this.amount, 0.6);
        if (!calm) gsap.to(this.amount.scale, { x: 1.08, y: 1.08, duration: 0.35, yoyo: true, repeat: -1, ease: 'sine.inOut', delay: 0.4 });
      },
    });
    return new Promise(resolve => {
      this.done = resolve;
      this.timer = gsap.delayedCall(count + 1.2, () => this.close());
    });
  }

  /** A new level reached: a white flash, more coins for the rest of the show, and a burst of them. */
  private level(step: number): void {
    if (calm || !this.view) return;
    this.shower.pour(14, 8, this.view.stageW / 2, this.view.stageH + 30);
    this.shower.burst(this.view.stageW / 2, this.view.stageH * 0.45, 30 + step * 15);
    const flash = new Graphics().rect(0, 0, this.view.stageW, this.view.stageH).fill('#fff3b0');
    flash.blendMode = 'add';
    this.addChildAt(flash, 1);
    gsap.fromTo(flash, { alpha: 0.6 }, { alpha: 0, duration: 0.5, onComplete: () => flash.destroy() });
    for (let i = 0; i < 4; i++) this.confetti.burst(random(this.view.stageW * 0.15, this.view.stageW * 0.85), this.view.stageH * 0.5, this.colours, 10, 700, 900);
  }

  private pop(text: Text, from: number): void {
    gsap.fromTo(text.scale, { x: from, y: from }, { x: 1, y: 1, duration: 0.6, ease: 'elastic.out(1, 0.5)' });
  }

  private close(): void {
    if (!this.done) return;
    const done = this.done;
    this.done = null;
    this.timer?.kill();
    this.timer = null;
    this.shower.stop();
    gsap.killTweensOf(this.banner);
    gsap.killTweensOf(this.rays);
    gsap.killTweensOf(this.glow.scale);
    gsap.killTweensOf(this.amount.scale);
    gsap.to(this, { alpha: 0, duration: 0.3, onComplete: () => {
      this.visible = false;
      this.shower.clear();
      this.banner.rotation = 0;
      this.amount.scale.set(1);
      this.confetti.clear();
    } });
    done();
  }
}
