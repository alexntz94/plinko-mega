import { Container, Graphics, GraphicsContext, Rectangle, type Text } from 'pixi.js';
import { gsap } from 'gsap';
import type { Viewport } from './viewport';
import { label, typeface } from './ui';
import type { ThemeFx } from './theme';

// Visual effects only: sparks, bursts, shakes and the big-win banner. They never decide or change a result; they
// dress up what the server already decided. Players who ask their device for less motion get calmer versions.

export const calm = typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches === true;

const MAX_PARTICLES = 350;     // above this, new effects are skipped (100 balls at once must stay smooth on a phone)
const shapes = new Map<string, GraphicsContext>();

/** One shared shape per colour and kind: particles are cheap copies of it. */
type Kind = 'dot' | 'chip' | 'ring' | 'crown' | 'bill';

function shape(colour: string, kind: Kind): GraphicsContext {
  const key = kind + colour;
  let context = shapes.get(key);
  if (!context) {
    context = new GraphicsContext();
    if (kind === 'dot') context.circle(0, 0, 1).fill(colour);
    else if (kind === 'chip') context.rect(-1, -0.5, 2, 1).fill(colour);
    else if (kind === 'ring') context.circle(0, 0, 1).stroke({ color: colour, width: 0.12 });
    else if (kind === 'bill') context.rect(-1.6, -0.8, 3.2, 1.6).fill('#4f8a4a').rect(-1.35, -0.58, 2.7, 1.16).stroke({ color: '#bfe3a8', width: 0.12 })
      .circle(0, 0, 0.36).fill('#bfe3a8');
    else context.poly([-1, 0.6, -1.15, -0.7, -0.5, -0.1, 0, -0.9, 0.5, -0.1, 1.15, -0.7, 1, 0.6]).fill(colour);
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

  /**
   * A fountain thrown up from a point that falls back under gravity (a ball landing in a slot). Negative gravity
   * makes it float away upwards; crowns mixes crown shapes in, bills banknotes.
   */
  burst(x: number, y: number, colours: string[], count: number, power: number, gravity = 900, crowns = false, bills = false): void {
    for (let i = 0; i < (calm ? Math.ceil(count / 3) : count); i++) {
      const kind: Kind = bills && i % 2 === 0 ? 'bill' : crowns && i % 4 === 0 ? 'crown' : i % 3 === 0 ? 'chip' : 'dot';
      const p = this.spawn(colours[i % colours.length], kind, x, y, kind === 'bill' ? random(4, 6) : kind === 'crown' ? random(5, 8) : random(1.6, 3.2));
      if (!p) return;
      const vx = random(-0.6, 0.6) * power;
      const vy = -random(0.6, 1.2) * power;
      const life = random(0.5, 0.9);
      const spin = random(-8, 8);
      const state = { t: 0 };
      gsap.to(state, {
        t: life, duration: life, ease: 'none',
        onUpdate: () => {
          p.x = x + vx * state.t;
          p.y = y + vy * state.t + gravity * state.t * state.t;
          p.rotation = spin * state.t;
          p.alpha = 1 - (state.t / life) ** 2;
        },
        onComplete: () => this.release(p),
      });
    }
  }

  /** An expanding ring of light (a big landing); inward, it shrinks onto the point instead. */
  ring(x: number, y: number, colour: string, radius: number, inward = false): void {
    const p = this.spawn(colour, 'ring', x, y, inward ? radius : radius * 0.3);
    if (!p) return;
    gsap.to(p.scale, { x: inward ? radius * 0.05 : radius, y: inward ? radius * 0.05 : radius, duration: 0.45, ease: inward ? 'power2.in' : 'power2.out' });
    gsap.to(p, { alpha: 0, duration: 0.45, ease: 'power1.in', onComplete: () => this.release(p) });
  }

  /** A spray-paint splat with drips that stays a moment on the board, then fades (Drop Riot). */
  splat(x: number, y: number, colour: string, size: number): void {
    const blobs = calm ? 4 : 9;
    for (let i = 0; i < blobs; i++) {
      const angle = random(0, Math.PI * 2);
      const distance = i === 0 ? 0 : random(0.3, 1) * size;
      const p = this.spawn(colour, 'dot', x + Math.cos(angle) * distance, y + Math.sin(angle) * distance * 0.6,
        i === 0 ? size * 0.7 : random(0.12, 0.35) * size);
      if (!p) return;
      const final = p.scale.x;
      p.scale.set(final * 0.2);
      p.alpha = 0.85;
      gsap.to(p.scale, { x: final, y: final, duration: 0.12, ease: 'power3.out' });
      gsap.to(p, { alpha: 0, delay: 1.1, duration: 0.6, onComplete: () => this.release(p) });
    }
    for (let i = 0; i < (calm ? 1 : 3); i++) {
      const drip = this.spawn(colour, 'dot', x + random(-0.6, 0.6) * size, y + size * 0.3, random(0.12, 0.2) * size);
      if (!drip) return;
      drip.scale.y *= 1.8;
      gsap.to(drip, { y: drip.y + random(0.8, 1.6) * size, duration: 1.4, ease: 'power1.in' });
      gsap.to(drip, { alpha: 0, delay: 1, duration: 0.6, onComplete: () => this.release(drip) });
    }
  }

  /** Soft puffs of steam that rise and spread (Bad Bounce). */
  steam(x: number, y: number, count: number): void {
    for (let i = 0; i < (calm ? Math.ceil(count / 2) : count); i++) {
      const p = this.spawn('#d9d4cf', 'dot', x + random(-8, 8), y, random(3, 5));
      if (!p) return;
      p.alpha = 0.35;
      const rise = random(30, 55);
      gsap.to(p, { x: p.x + random(-14, 14), y: y - rise, alpha: 0, duration: random(0.7, 1.1), ease: 'sine.out', onComplete: () => this.release(p) });
      gsap.to(p.scale, { x: p.scale.x * 2.6, y: p.scale.y * 2.6, duration: 1 });
    }
  }

  /** Particles pulled in from all around onto a point (Gravity Issues). */
  implode(x: number, y: number, colours: string[], count: number, reach: number): void {
    for (let i = 0; i < (calm ? Math.ceil(count / 3) : count); i++) {
      const angle = random(0, Math.PI * 2);
      const distance = random(0.6, 1) * reach;
      const p = this.spawn(colours[i % colours.length], 'dot', x + Math.cos(angle) * distance, y + Math.sin(angle) * distance * 0.7, random(1.5, 2.8));
      if (!p) return;
      p.alpha = 0;
      gsap.to(p, { alpha: 1, duration: 0.1 });
      gsap.to(p, { x, y, duration: random(0.3, 0.5), ease: 'power2.in', onComplete: () => this.release(p) });
    }
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
  return times >= 50 ? 'EPIC WIN' : times >= 25 ? 'MEGA WIN' : times >= 10 ? 'BIG WIN' : null;
}

/** The big-win celebration over the table: banner, the amount counting up, confetti. Tap to skip. */
export class BigWin extends Container {
  private readonly dim = new Graphics();
  private readonly banner: Text;
  private readonly amount: Text;
  private readonly confetti = new Particles();
  private view: Viewport | null = null;
  private done: (() => void) | null = null;
  private timer: gsap.core.Tween | null = null;

  constructor(private readonly colours: string[], private readonly fx: ThemeFx = 'standard') {
    super();
    const outline = { stroke: { color: '#000000', width: 10, join: 'round' as const } };
    this.banner = label('', 96, 'center', { fill: colours[0], ...outline,
      dropShadow: { color: '#000000', alpha: 0.8, blur: 0, distance: 6, angle: Math.PI / 2 } });
    this.amount = label('', 64, 'center', { fill: typeface.colour, ...outline });
    this.addChild(this.dim, this.confetti, this.banner, this.amount);
    this.eventMode = 'static';
    this.on('pointertap', () => this.close());
    this.visible = false;
  }

  layout(view: Viewport): void {
    this.view = view;
    this.dim.clear().rect(0, 0, view.stageW, view.stageH).fill({ color: '#000000', alpha: 0.55 });
    this.hitArea = new Rectangle(0, 0, view.stageW, view.stageH);
    this.banner.position.set(view.stageW / 2, view.stageH * 0.45);
    this.amount.position.set(view.stageW / 2, view.stageH * 0.45 + 90);
  }

  /** Plays the celebration; resolves when it ends by itself (about 3.5 s) or the player taps. */
  show(level: WinLevel, total: number, format: (value: number) => string): Promise<void> {
    this.visible = true;
    this.alpha = 0;
    this.banner.text = level;
    this.amount.text = format(0);
    gsap.to(this, { alpha: 1, duration: 0.2 });
    const bannerY = this.view ? this.view.stageH * 0.45 : this.banner.y;
    if (calm) {
      gsap.fromTo(this.banner.scale, { x: 0.8, y: 0.8 }, { x: 1, y: 1, duration: 0.2 });
    } else if (this.fx === 'bounce') {
      // Bad Bounce: the banner drops in from above and bounces badly, then rattles
      this.banner.scale.set(1);
      gsap.fromTo(this.banner, { y: bannerY - 500 }, { y: bannerY, duration: 0.9, ease: 'bounce.out' });
      gsap.to(this.banner, { rotation: 0.05, duration: 0.07, yoyo: true, repeat: 9, delay: 0.9, ease: 'none' });
    } else if (this.fx === 'nana') {
      // Shady Nana: the banner is stamped down like a bingo dauber, with a jolt
      gsap.fromTo(this.banner.scale, { x: 2.6, y: 2.6 }, { x: 1, y: 1, duration: 0.28, ease: 'power3.in' });
      gsap.fromTo(this.banner, { alpha: 0, rotation: -0.12 }, { alpha: 1, rotation: -0.04, duration: 0.28, ease: 'power3.in' });
      gsap.delayedCall(0.28, () => { if (this.visible) shake(this, 8); });
    } else if (this.fx === 'gravity') {
      // Gravity Issues: the banner floats and drifts as if nothing holds it down
      gsap.fromTo(this.banner.scale, { x: 0.6, y: 0.6 }, { x: 1, y: 1, duration: 1.2, ease: 'sine.out' });
      gsap.to(this.banner, { y: bannerY - 14, duration: 1.1, yoyo: true, repeat: -1, ease: 'sine.inOut' });
      gsap.to(this.banner, { rotation: -0.06, duration: 1.7, yoyo: true, repeat: -1, ease: 'sine.inOut' });
    } else {
      gsap.fromTo(this.banner.scale, { x: 0.2, y: 0.2 }, { x: 1, y: 1, duration: 0.7, ease: 'elastic.out(1, 0.5)' });
      gsap.to(this.banner, { rotation: 0.03, duration: 0.6, yoyo: true, repeat: -1, ease: 'sine.inOut' });
    }
    const counter = { value: 0 };
    gsap.to(counter, { value: total, duration: 1.6, ease: 'power2.out', onUpdate: () => { this.amount.text = format(counter.value); } });
    if (!calm && this.view) {
      const rain = level === 'EPIC WIN' ? 5 : level === 'MEGA WIN' ? 4 : 3;
      for (let wave = 0; wave < rain; wave++) {
        gsap.delayedCall(wave * 0.35, () => this.confettiWave());
      }
    }
    return new Promise(resolve => {
      this.done = resolve;
      this.timer = gsap.delayedCall(3.5, () => this.close());
    });
  }

  private confettiWave(): void {
    if (!this.visible || !this.view) return;
    const { stageW, stageH } = this.view;
    for (let i = 0; i < 6; i++) {
      if (this.fx === 'gravity') {
        // confetti that falls up
        this.confetti.burst(random(stageW * 0.15, stageW * 0.85), stageH * 0.95, this.colours, 8, 260, -320);
      } else {
        this.confetti.burst(random(stageW * 0.15, stageW * 0.85), stageH * 0.55, this.colours, 8, 700, 900, this.fx === 'riot', this.fx === 'nana');
      }
    }
  }

  private close(): void {
    if (!this.done) return;
    const done = this.done;
    this.done = null;
    this.timer?.kill();
    this.timer = null;
    gsap.killTweensOf(this.banner);
    gsap.to(this, { alpha: 0, duration: 0.25, onComplete: () => {
      this.visible = false;
      this.banner.rotation = 0;
      this.banner.alpha = 1;
      if (this.view) this.banner.y = this.view.stageH * 0.45;
      this.confetti.clear();
    } });
    done();
  }
}
