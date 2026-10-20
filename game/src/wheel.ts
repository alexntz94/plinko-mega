// Plinko Mega's wheel: shown over the table when a ball lands in a SPIN slot (the wheel) or a full meter pays a MEGA
// SPIN (the gold wheel). The server has already decided the segment; the wheel only turns until it stops on it.

import { Container, Graphics, Rectangle, Sprite, Text, Texture } from 'pixi.js';
import { gsap } from 'gsap';
import type { Viewport } from './viewport';
import { label, textQuality, typeface } from './ui';
import { Particles, calm } from './effects';
import type { Segment } from './api';
import type { WheelStyle } from './theme';

const random = (min: number, max: number) => min + Math.random() * (max - min);

/** "×25", or the jackpot's name over it. */
export function segmentText(segment: Segment): string {
  const times = '×' + String(Number(segment.multiplier.toFixed(2)));
  return segment.name ? segment.name : times;
}

export class Wheel extends Container {
  private readonly dim = new Graphics();
  private readonly disc = new Container();
  private readonly pointer = new Container();
  private readonly title: Text;
  private readonly result: Text;
  private readonly sparks = new Particles();
  private view: Viewport | null = null;
  private radius = 200;
  private spin: gsap.core.Tween | null = null;
  private done: (() => void) | null = null;
  /** Called each time a segment edge passes the pointer (for the ticking sound). */
  onTick: (() => void) | null = null;

  constructor(private readonly style: WheelStyle, private readonly hub: Texture | null, private readonly finger: Texture | null) {
    super();
    const outline = { stroke: { color: '#000000', width: 8, join: 'round' as const } };
    this.title = label('', 54, 'center', { fill: style.title, ...outline });
    this.result = label('', 46, 'center', { fill: typeface.colour, ...outline });
    this.addChild(this.dim, this.sparks, this.disc, this.pointer, this.title, this.result);
    this.eventMode = 'static';
    // a tap hurries the wheel along (the segment is already decided)
    this.on('pointertap', () => { if (this.spin?.isActive()) this.spin.timeScale(4); });
    this.visible = false;
  }

  layout(view: Viewport): void {
    this.view = view;
    this.dim.clear().rect(0, 0, view.stageW, view.stageH).fill({ color: '#000000', alpha: 0.6 });
    this.hitArea = new Rectangle(0, 0, view.stageW, view.stageH);
    const visibleH = view.stageH - 2 * view.offsetY;
    this.radius = Math.min(view.stageW * 0.36, visibleH * 0.3, 260);
    const cy = view.stageH / 2 + this.radius * 0.08;
    this.disc.position.set(view.stageW / 2, cy);
    this.pointer.position.set(view.stageW / 2, cy - this.radius * 1.04);
    this.title.position.set(view.stageW / 2, cy - this.radius * 1.32);
    this.result.position.set(view.stageW / 2, cy + this.radius * 1.24);
  }

  /**
   * Spins to the segment the server decided and shows what it paid; resolves when the player has seen it (about 2 s
   * after it stops, or at once in a hurry).
   */
  show(segments: Segment[], index: number, options: { gold: boolean; title: string; amount: string; hurry: boolean }): Promise<void> {
    this.draw(segments, options.gold);
    this.visible = true;
    this.alpha = 0;
    this.title.text = options.title;
    this.result.text = '';
    gsap.to(this, { alpha: 1, duration: 0.2 });
    gsap.fromTo(this.disc.scale, { x: 0.6, y: 0.6 }, { x: 1, y: 1, duration: 0.45, ease: 'back.out(1.6)' });
    gsap.fromTo(this.title.scale, { x: 0.3, y: 0.3 }, { x: 1, y: 1, duration: 0.6, ease: 'elastic.out(1, 0.5)' });
    const step = Math.PI * 2 / segments.length;
    // segment i is centred at the top when the disc is turned back by i steps; land a little off its centre
    const turns = calm ? 1 : options.hurry ? 3 : 5 + Math.floor(Math.random() * 2);
    const target = -index * step + random(-0.32, 0.32) * step - turns * Math.PI * 2;
    this.disc.rotation = random(0, Math.PI * 2);
    let lastTick = Math.floor(this.disc.rotation / step);
    return new Promise(resolve => {
      this.spin = gsap.to(this.disc, {
        rotation: target, duration: calm ? 1.2 : options.hurry ? 1.8 : random(3.8, 4.4), ease: 'power4.out',
        onUpdate: () => {
          const tick = Math.floor(this.disc.rotation / step);
          if (tick !== lastTick) {
            lastTick = tick;
            this.onTick?.();
            gsap.fromTo(this.pointer, { rotation: 0.22 }, { rotation: 0, duration: 0.12, overwrite: true });
          }
        },
        onComplete: () => {
          this.landed(segments[index], options);
          this.done = resolve;
          gsap.delayedCall(options.hurry ? 0.9 : 2.2, () => this.close());
        },
      });
    });
  }

  private landed(segment: Segment, options: { gold: boolean; amount: string }): void {
    this.result.text = (segment.name ? segment.name + ' JACKPOT  ' : segmentText(segment) + '  ') + options.amount;
    gsap.fromTo(this.result.scale, { x: 0.4, y: 0.4 }, { x: 1, y: 1, duration: 0.5, ease: 'back.out(2.5)' });
    if (this.view && !calm) {
      const colours = [this.style.title, ...(options.gold ? this.style.gold.faces : this.style.faces)];
      const burst = segment.name ? 6 : 2;
      for (let i = 0; i < burst; i++) {
        gsap.delayedCall(i * 0.18, () => this.sparks.burst(this.disc.x + random(-1, 1) * this.radius, this.disc.y - this.radius * 0.6,
          colours, 10, 650));
      }
    }
  }

  private close(): void {
    if (!this.done) return;
    const done = this.done;
    this.done = null;
    gsap.to(this, { alpha: 0, duration: 0.25, onComplete: () => { this.visible = false; this.sparks.clear(); } });
    done();
  }

  /** The disc: one wedge per segment in the theme's colours (jackpots in the title colour), labels along the radius. */
  private draw(segments: Segment[], gold: boolean): void {
    this.disc.removeChildren().forEach(child => child.destroy({ children: true }));
    this.pointer.removeChildren().forEach(child => child.destroy({ children: true }));
    const r = this.radius;
    const look = gold ? this.style.gold : this.style;
    const step = Math.PI * 2 / segments.length;
    const rim = new Graphics().circle(4, 8, r * 1.08).fill({ color: '#000000', alpha: 0.45 })
      .circle(0, 0, r * 1.08).fill(look.rim).stroke({ color: this.style.ink, width: 5 });
    this.disc.addChild(rim);
    segments.forEach((segment, i) => {
      // segment i is centred on angle i * step, clockwise from the top
      const a0 = -Math.PI / 2 + (i - 0.5) * step;
      const a1 = a0 + step;
      const face = segment.name ? this.style.title : look.faces[i % look.faces.length];
      const text = segment.name ? this.style.ink : look.text[i % look.text.length];
      const wedge = new Graphics().moveTo(0, 0).arc(0, 0, r, a0, a1).lineTo(0, 0).fill(face)
        .stroke({ color: this.style.ink, width: 3, join: 'round' });
      const words = new Text({ text: segmentText(segment), anchor: { x: 0.5, y: 0.5 }, resolution: textQuality(),
        style: { fontFamily: typeface.family, fontWeight: typeface.weight as 'normal', fontSize: Math.round(r * (segment.name ? 0.1 : 0.13)), fill: text } });
      const mid = -Math.PI / 2 + i * step;
      words.position.set(Math.cos(mid) * r * 0.68, Math.sin(mid) * r * 0.68);
      words.rotation = mid + Math.PI / 2;
      if (words.width > r * 0.5) words.scale.set(r * 0.5 / words.width);
      this.disc.addChild(wedge, words);
    });
    for (let i = 0; i < segments.length; i++) {
      const a = -Math.PI / 2 + (i - 0.5) * step;
      this.disc.addChild(new Graphics().circle(Math.cos(a) * r * 1.04, Math.sin(a) * r * 1.04, r * 0.025).fill('#ffffff')
        .stroke({ color: this.style.ink, width: 1.5 }));
    }
    if (this.hub) {
      const hub = new Sprite({ texture: this.hub, anchor: 0.5 });
      hub.scale.set(r * 0.7 / Math.max(hub.texture.width, 1));
      this.disc.addChild(hub);
    } else {
      this.disc.addChild(new Graphics().circle(0, 0, r * 0.3).fill(look.rim).stroke({ color: this.style.ink, width: 4 }));
    }
    // the pointer: the theme's drawing, or a plain marker pointing down at the top segment
    if (this.finger) {
      const finger = new Sprite({ texture: this.finger, anchor: { x: 0.5, y: 0.9 } });
      finger.scale.set(r * 0.42 / Math.max(finger.texture.height, 1));
      this.pointer.addChild(finger);
    } else {
      this.pointer.addChild(new Graphics().poly([-r * 0.08, -r * 0.14, r * 0.08, -r * 0.14, 0, r * 0.06]).fill(this.style.title)
        .stroke({ color: this.style.ink, width: 3, join: 'round' }));
    }
  }
}
