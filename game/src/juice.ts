// The slot-machine feel: things that move all the time (petals drifting over the scene), the WIN banner that counts a
// round's win up in gold, the white flash of a big hit and the dark edges of slow motion. Looks only.

import { Container, FillGradient, Graphics, Rectangle } from 'pixi.js';
import { gsap } from 'gsap';
import type { Viewport } from './viewport';
import { Particles, calm } from './effects';
import { goldText } from './paint';

const random = (min: number, max: number) => min + Math.random() * (max - min);

interface Petal { g: Graphics; vx: number; vy: number; spin: number; sway: number; phase: number }

/** Petals (or whatever the theme's colours make them) drifting down over the scene, always. */
export class Ambient extends Container {
  private readonly petals: Petal[] = [];
  private view: Viewport | null = null;
  private time = 0;

  constructor(private readonly colours: string[], private readonly count = 26) {
    super();
    this.eventMode = 'none';
  }

  layout(view: Viewport): void {
    this.view = view;
    if (this.petals.length === 0 && !calm) {
      for (let i = 0; i < this.count; i++) this.petals.push(this.spawn(random(0, view.stageH)));
    }
  }

  private spawn(y: number): Petal {
    const view = this.view!;
    const g = new Graphics().ellipse(0, 0, random(3, 6), random(6, 10)).fill(this.colours[Math.floor(Math.random() * this.colours.length)]);
    g.alpha = random(0.45, 0.85);
    g.position.set(random(0, view.stageW), y);
    this.addChild(g);
    return { g, vx: random(-12, 12), vy: random(28, 60), spin: random(-2, 2), sway: random(10, 26), phase: random(0, Math.PI * 2) };
  }

  update(deltaMs: number): void {
    if (!this.view || calm) return;
    const dt = Math.min(deltaMs, 50) / 1000;
    this.time += dt;
    for (const p of this.petals) {
      p.g.x += (p.vx + Math.sin(this.time * 1.7 + p.phase) * p.sway) * dt;
      p.g.y += p.vy * dt;
      p.g.rotation += p.spin * dt;
      p.g.scale.x = Math.cos(this.time * 3 + p.phase);   // turning over as it falls
      if (p.g.y > this.view.stageH + 20) {
        p.g.y = -20;
        p.g.x = random(0, this.view.stageW);
      }
    }
  }
}

/**
 * A round's win, counted up in gold over the board with a burst of coins; resolves when it has been shown (shorter in
 * a hurry). onTick plays each step of the count.
 */
export class WinBanner extends Container {
  private readonly coins = new Particles();
  private readonly glow = new Graphics();
  onTick: (() => void) | null = null;

  constructor() {
    super();
    this.addChild(this.glow, this.coins);
    this.eventMode = 'none';
  }

  show(amount: number, times: number, format: (value: number) => string, hurry: boolean): Promise<void> {
    const size = times >= 5 ? 64 : times >= 2 ? 52 : 40;
    const title = goldText(times >= 5 ? 'GREAT WIN' : 'WIN', size * 0.55);
    title.y = -size * 0.75;
    const value = goldText(format(0), size);
    this.glow.clear().ellipse(0, -size * 0.3, size * 3.2, size * 1.3).fill({ color: '#ffd76a', alpha: 0.28 });
    this.glow.blendMode = 'add';
    this.addChild(title, value);
    const colours = ['#ffd76a', '#fff3b0', '#e9a91a'];
    if (!calm) this.coins.burst(0, 0, colours, times >= 5 ? 30 : 16, times >= 5 ? 520 : 380);
    const counter = { value: 0 };
    const count = hurry ? 0.4 : Math.min(1.6, 0.6 + times * 0.12);
    let last = -1;
    return new Promise(resolve => {
      gsap.timeline({ onComplete: () => { title.destroy(); value.destroy(); this.coins.clear(); resolve(); } })
        .fromTo(this, { alpha: 0 }, { alpha: 1, duration: 0.12 }, 0)
        .from([title.scale, value.scale], { x: 0.3, y: 0.3, duration: 0.4, ease: 'back.out(2.6)' }, 0)
        .to(counter, { value: amount, duration: count, ease: 'power1.out', onUpdate: () => {
          value.text = format(counter.value);
          const step = Math.floor(counter.value / Math.max(amount / 14, 0.01));
          if (step !== last) { last = step; this.onTick?.(); }
        } }, 0.05)
        .to(value.scale, { x: 1.12, y: 1.12, duration: 0.12, yoyo: true, repeat: 1 }, count + 0.05)
        .to(this.glow, { alpha: 0.4, duration: 0.3, yoyo: true, repeat: 1 }, 0)
        .to(this, { alpha: 0, duration: 0.3 }, count + (hurry ? 0.3 : 0.9));
    });
  }
}

/** A full-screen flash (a big hit) and the dark edges of slow motion. */
export class Screen extends Container {
  private readonly white = new Graphics();
  private readonly edges = new Graphics();

  constructor() {
    super();
    this.white.alpha = 0;
    this.edges.alpha = 0;
    this.addChild(this.edges, this.white);
    this.eventMode = 'none';
  }

  layout(view: Viewport): void {
    this.hitArea = new Rectangle(0, 0, 0, 0);
    this.white.clear().rect(0, 0, view.stageW, view.stageH).fill('#ffffff');
    const vignette = new FillGradient({ type: 'radial', center: { x: 0.5, y: 0.5 }, innerRadius: 0.25, outerCenter: { x: 0.5, y: 0.5 },
      outerRadius: 0.75, textureSpace: 'local', colorStops: [{ offset: 0, color: 'rgba(0,0,0,0)' }, { offset: 1, color: 'rgba(0,0,0,0.85)' }] });
    this.edges.clear().rect(0, 0, view.stageW, view.stageH).fill(vignette);
  }

  flash(strength = 0.6): void {
    if (calm) return;
    gsap.fromTo(this.white, { alpha: strength }, { alpha: 0, duration: 0.45, ease: 'power2.out', overwrite: true });
  }

  /** Slow motion's dark edges (they don't slow down with the rest). */
  dim(on: boolean): void {
    gsap.to(this.edges, { alpha: on ? 1 : 0, duration: on ? 0.25 : 0.5, overwrite: true });
  }
}
