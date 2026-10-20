import { Container, Graphics, GraphicsContext, Rectangle, type Text } from 'pixi.js';
import { gsap } from 'gsap';
import type { Viewport } from './viewport';
import { goldText } from './paint';

// Visual effects only: sparks, bursts, shakes and the big-win banner. They never decide or change a result; they
// dress up what the server already decided. Players who ask their device for less motion get calmer versions.

export const calm = typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches === true;

const MAX_PARTICLES = 600;     // above this, new effects are skipped (100 balls at once must stay smooth on a phone)
const shapes = new Map<string, GraphicsContext>();

/** One shared shape per colour and kind: particles are cheap copies of it. */
type Kind = 'dot' | 'chip' | 'ring';

function shape(colour: string, kind: Kind): GraphicsContext {
  const key = kind + colour;
  let context = shapes.get(key);
  if (!context) {
    context = new GraphicsContext();
    if (kind === 'dot') context.circle(0, 0, 1).fill(colour);
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
  return times >= 50 ? 'EPIC WIN' : times >= 25 ? 'MEGA WIN' : times >= 10 ? 'BIG WIN' : null;
}

/** The big-win celebration over the table: banner, the amount counting up, confetti. Tap to skip. */
export class BigWin extends Container {
  private readonly dim = new Graphics();
  private readonly rays = new Graphics();
  private readonly banner: Text;
  private readonly amount: Text;
  private readonly confetti = new Particles();
  private view: Viewport | null = null;
  private done: (() => void) | null = null;
  private timer: gsap.core.Tween | null = null;

  constructor(private readonly colours: string[]) {
    super();
    // the win in gold over turning rays of light, like the wheel's win
    this.banner = goldText('', 92);
    this.amount = goldText('', 70);
    for (let i = 0; i < 24; i++) {
      const a = i * Math.PI * 2 / 24;
      this.rays.moveTo(0, 0).lineTo(Math.cos(a - 0.06) * 700, Math.sin(a - 0.06) * 700)
        .lineTo(Math.cos(a + 0.06) * 700, Math.sin(a + 0.06) * 700).lineTo(0, 0).fill({ color: '#ffd76a', alpha: 0.22 });
    }
    this.rays.blendMode = 'add';
    this.addChild(this.dim, this.rays, this.confetti, this.banner, this.amount);
    this.eventMode = 'static';
    this.on('pointertap', () => this.close());
    this.visible = false;
  }

  layout(view: Viewport): void {
    this.view = view;
    this.dim.clear().rect(0, 0, view.stageW, view.stageH).fill({ color: '#000000', alpha: 0.55 });
    this.hitArea = new Rectangle(0, 0, view.stageW, view.stageH);
    this.banner.position.set(view.stageW / 2, view.stageH * 0.45);
    this.amount.position.set(view.stageW / 2, view.stageH * 0.45 + 96);
    this.rays.position.set(view.stageW / 2, view.stageH * 0.45 + 20);
  }

  /** Plays the celebration; resolves when it ends by itself (about 3.5 s) or the player taps. */
  show(level: WinLevel, total: number, format: (value: number) => string): Promise<void> {
    this.visible = true;
    this.alpha = 0;
    this.banner.text = level;
    this.amount.text = format(0);
    gsap.to(this, { alpha: 1, duration: 0.2 });
    if (calm) {
      gsap.fromTo(this.banner.scale, { x: 0.8, y: 0.8 }, { x: 1, y: 1, duration: 0.2 });
    } else {
      gsap.fromTo(this.banner.scale, { x: 0.2, y: 0.2 }, { x: 1, y: 1, duration: 0.7, ease: 'elastic.out(1, 0.5)' });
      gsap.to(this.banner, { rotation: 0.03, duration: 0.6, yoyo: true, repeat: -1, ease: 'sine.inOut' });
      gsap.fromTo(this.rays, { rotation: 0, alpha: 0 }, { rotation: 1.2, alpha: 1, duration: 3.5, ease: 'none' });
      gsap.fromTo(this.rays.scale, { x: 0.2, y: 0.2 }, { x: 1, y: 1, duration: 0.6, ease: 'power2.out' });
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
      this.confetti.burst(random(stageW * 0.15, stageW * 0.85), stageH * 0.55, this.colours, 8, 700, 900);
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
