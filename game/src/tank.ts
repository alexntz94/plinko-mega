// Tank assault (a power-up of the arsenal): after the balls, a tank rolls in at the foot of the screen and fires at a row
// of bullseye targets, one shot each; every hit blows its target up and shows what it paid (the server's draw). Then
// the tank rolls out. The wins are counted by the caller as each target is hit.

import { Container, Graphics, Sprite, type Texture } from 'pixi.js';
import { gsap } from 'gsap';
import type { Viewport } from './viewport';
import { kindBlast } from './effects';
import { goldText } from './paint';

/** Where the barrel ends on the tank picture (fractions of its width and height). */
const MUZZLE: [number, number] = [0.99, 0.285];

export class TankAssault extends Container {
  private readonly dim = new Graphics();
  private readonly stageLayer = new Container();
  /** the part of the stage on screen: its middle, width, top and height */
  private area = { cx: 400, w: 800, top: 0, h: 600 };

  constructor(private readonly art: { tank: Texture; target: Texture }) {
    super();
    this.addChild(this.dim, this.stageLayer);
    this.visible = false;
  }

  layout(view: Viewport): void {
    const visibleH = view.stageH - 2 * view.offsetY;
    this.area = { cx: view.stageW / 2, w: view.stageW - 2 * view.offsetX, top: view.offsetY, h: visibleH };
    this.dim.clear().rect(0, 0, view.stageW, view.stageH).fill({ color: '#000000', alpha: 0.6 });
  }

  /**
   * The assault: one target per prize in `pays` (x the round's bet), each hit in turn; `onHit(i)` as target i is hit
   * (the caller adds its win), `label(i)` what is written over it.
   */
  async play(pays: number[], label: (i: number) => string, onHit: (i: number) => void, onShot: () => void): Promise<void> {
    if (!pays.length) return;
    const { cx, w, top, h } = this.area;
    this.visible = true;
    this.alpha = 1;
    gsap.fromTo(this.dim, { alpha: 0 }, { alpha: 1, duration: 0.25 });

    // the targets in a row across the upper half, the tank at the foot on the left
    const n = pays.length;
    const span = Math.min(w * 0.86, 760);
    const targetW = Math.min(span / n * 0.82, 110);
    const targetsY = top + h * 0.42;
    const targets = pays.map((_, i) => {
      const t = new Sprite({ texture: this.art.target, anchor: { x: 0.5, y: 1 } });
      t.width = targetW;
      t.scale.y = t.scale.x;
      t.position.set(cx - span / 2 + span * (i + 0.5) / n, targetsY + (i % 2) * h * 0.04);
      this.stageLayer.addChild(t);
      gsap.from(t, { y: t.y + 60, alpha: 0, duration: 0.3, delay: i * 0.06, ease: 'back.out(2)' });
      return t;
    });
    const tank = new Sprite({ texture: this.art.tank, anchor: { x: 0.5, y: 1 } });
    tank.width = Math.min(w * 0.42, 340);
    tank.scale.y = tank.scale.x;
    const tankX = cx - Math.min(w * 0.22, 220);
    const ground = top + h * 0.86;
    tank.position.set(cx - w / 2 - tank.width, ground);
    this.stageLayer.addChild(tank);
    await gsap.to(tank, { x: tankX, duration: 0.7, ease: 'power2.out' });

    const muzzle = () => ({ x: tank.x + tank.width * (MUZZLE[0] - 0.5), y: tank.y - tank.height * (1 - MUZZLE[1]) });
    for (let i = 0; i < n; i++) {
      const target = targets[i];
      const aim = { x: target.x, y: target.y - target.height * 0.62 };
      const from = muzzle();
      // the shot: a muzzle flash, the tank rocks back, a tracer streaks to the target
      kindBlast(this.stageLayer, 'muzzle', from.x, from.y, tank.width * 0.55, Math.atan2(aim.y - from.y, aim.x - from.x));
      onShot();
      gsap.fromTo(tank, { x: tankX - 10 }, { x: tankX, duration: 0.3, ease: 'power2.out' });
      const tracer = new Graphics().moveTo(0, 0).lineTo(-26, 0).stroke({ color: '#ffe08a', width: 4, cap: 'round' });
      tracer.blendMode = 'add';
      tracer.position.set(from.x, from.y);
      tracer.rotation = Math.atan2(aim.y - from.y, aim.x - from.x);
      this.stageLayer.addChild(tracer);
      await gsap.to(tracer, { x: aim.x, y: aim.y, duration: 0.18, ease: 'none' });
      tracer.destroy();
      // the hit: the target blows (a bigger prize, a bigger blast), what it paid pops over it
      const big = pays[i] >= 2;
      kindBlast(this.stageLayer, big ? 'heavy' : 'impact', aim.x, big ? aim.y : target.y, targetW * (big ? 2.2 : 1.6));
      gsap.to(target, { rotation: (Math.random() < 0.5 ? -1 : 1) * 0.5, alpha: 0.25, y: target.y + 14, duration: 0.35, ease: 'power2.out' });
      const text = goldText(label(i), big ? 24 : 18);
      text.position.set(aim.x, aim.y - targetW * 0.55);
      this.stageLayer.addChild(text);
      gsap.fromTo(text.scale, { x: 2.2, y: 2.2 }, { x: 1, y: 1, duration: 0.2, ease: 'power3.in' });
      onHit(i);
      await new Promise(resolve => gsap.delayedCall(big ? 0.55 : 0.35, resolve));
    }
    await new Promise(resolve => gsap.delayedCall(0.6, resolve));
    await gsap.to(tank, { x: cx + w / 2 + tank.width, duration: 0.6, ease: 'power2.in' });
    await gsap.to(this, { alpha: 0, duration: 0.25 });
    this.visible = false;
    this.stageLayer.removeChildren().forEach(child => { gsap.killTweensOf([child, child.scale]); child.destroy(); });
  }
}
