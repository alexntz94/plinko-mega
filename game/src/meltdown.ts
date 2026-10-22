// Core meltdown (a Reactor Drop booster): after the capsules, the reactor overloads once. A pressure gauge in the middle
// of the screen climbs, trembling, through its marks (x0.5 to x20) to what the server drew, the core discharges, and
// the payout pops over it. The win is counted by the caller when the core blows.

import { Container, Graphics, Text } from 'pixi.js';
import { gsap } from 'gsap';
import type { Viewport } from './viewport';
import { kindBlast } from './effects';
import { goldText } from './paint';
import { label } from './ui';

/** The gauge's marks: the meltdown's payouts (x the round's bet), from the left end of the dial to the right. */
const MARKS = [0.5, 1, 2, 3, 5, 10, 20];
/** The dial sweeps 240 degrees, from lower left to lower right through the top. */
const START = Math.PI * (5 / 6), SWEEP = Math.PI * (4 / 3);

export class CoreMeltdown extends Container {
  private readonly dim = new Graphics();
  private readonly gauge = new Container();
  private area = { cx: 400, cy: 300, size: 300 };

  constructor(private readonly look: { gold: string; text: string; rim: string; title?: string }) {
    super();
    this.addChild(this.dim, this.gauge);
    this.visible = false;
  }

  layout(view: Viewport): void {
    const w = view.stageW - 2 * view.offsetX, h = view.stageH - 2 * view.offsetY;
    this.area = { cx: view.stageW / 2, cy: view.offsetY + h * 0.42, size: Math.min(w * 0.8, h * 0.5, 420) };
    this.dim.clear().rect(0, 0, view.stageW, view.stageH).fill({ color: '#02060a', alpha: 0.7 });
  }

  /** The angle of a payout on the dial (its mark's place; between marks, in between). */
  private dialAngle(pays: number): number {
    const k = MARKS.findIndex(m => m >= pays);
    const at = k <= 0 ? 0 : k - 1 + (pays - MARKS[k - 1]) / (MARKS[k] - MARKS[k - 1]);
    return START + SWEEP * (at / (MARKS.length - 1));
  }

  /** The gauge climbs to `pays`, then the core blows: onBlow (the caller adds the win), `text` over it. */
  async play(pays: number, text: string, onBlow: () => void, onRise: () => void): Promise<void> {
    const { cx, cy, size } = this.area;
    const r = size / 2;
    this.visible = true;
    this.alpha = 1;
    this.gauge.removeChildren().forEach(c => c.destroy());
    this.gauge.position.set(cx, cy);
    gsap.fromTo(this.dim, { alpha: 0 }, { alpha: 1, duration: 0.25 });

    // the dial: a dark glass face in a copper ring, the marks round it, the danger zone in red at the top end
    const face = new Graphics()
      .circle(0, 0, r * 1.06).fill('#0b0d10')
      .circle(0, 0, r).fill('#14191e').stroke({ color: this.look.rim, width: r * 0.07 })
      .arc(0, 0, r * 0.8, this.dialAngle(5), this.dialAngle(20)).stroke({ color: '#ff3b2a', width: r * 0.09, alpha: 0.75 })
      .arc(0, 0, r * 0.8, this.dialAngle(0.5), this.dialAngle(5)).stroke({ color: '#46d9f2', width: r * 0.09, alpha: 0.35 });
    this.gauge.addChild(face);
    const marks: Text[] = [];
    for (const m of MARKS) {
      const a = this.dialAngle(m);
      face.moveTo(Math.cos(a) * r * 0.68, Math.sin(a) * r * 0.68).lineTo(Math.cos(a) * r * 0.9, Math.sin(a) * r * 0.9)
        .stroke({ color: this.look.text, width: 3 });
      const t = label('x' + m, Math.round(r * 0.13), 'center', { fill: this.look.text, stroke: { color: '#000000', width: 3 } });
      t.anchor.set(0.5, 0.5);
      t.position.set(Math.cos(a) * r * 0.5, Math.sin(a) * r * 0.5);
      this.gauge.addChild(t);
      marks.push(t);
    }
    const title = label(this.look.title ?? 'CORE PRESSURE', Math.round(r * 0.12), 'center', { fill: this.look.gold, stroke: { color: '#000000', width: 4 } });
    title.anchor.set(0.5, 0.5);
    title.y = r * 0.55;
    // the needle (a copper hub in the middle)
    const needle = new Graphics().poly([0, -r * 0.035, r * 0.82, 0, 0, r * 0.035]).fill('#ff8a1a')
      .circle(0, 0, r * 0.09).fill(this.look.rim).circle(0, 0, r * 0.04).fill('#0b0d10');
    needle.rotation = START;
    this.gauge.addChild(title, needle);
    await gsap.from(this.gauge.scale, { x: 0.5, y: 0.5, duration: 0.3, ease: 'back.out(2)' });

    // the climb: mark by mark, each lit as the needle passes it, faster as it goes; a tremble on the way
    onRise();
    const target = this.dialAngle(pays);
    const tremble = gsap.to(this.gauge, { x: cx + 2, duration: 0.04, yoyo: true, repeat: -1 });
    const passed = MARKS.filter(m => m <= pays);
    for (let i = 0; i < passed.length; i++) {
      await gsap.to(needle, { rotation: this.dialAngle(passed[i]), duration: i === 0 ? 0.35 : 0.22 + 0.06 * i, ease: 'power1.inOut' });
      const t = marks[MARKS.indexOf(passed[i])];
      t.style.fill = this.look.gold;
      gsap.fromTo(t.scale, { x: 1.5, y: 1.5 }, { x: 1, y: 1, duration: 0.2 });
    }
    if (needle.rotation !== target) await gsap.to(needle, { rotation: target, duration: 0.25 });
    // the needle shudders against the top of its swing, then the core blows
    await gsap.to(needle, { rotation: target + 0.06, duration: 0.05, yoyo: true, repeat: 5 });
    tremble.kill();
    this.gauge.x = cx;
    const big = pays >= 5;
    kindBlast(this, big ? 'mushroom' : 'heavy', cx, cy, size * (big ? 1.5 : 1.1));
    kindBlast(this, 'napalm', cx, cy + r * 0.4, size * 1.6);
    onBlow();
    const pop = goldText(text, Math.round(r * 0.22));
    pop.position.set(cx, cy - r * 0.15);
    this.addChild(pop);
    gsap.fromTo(pop.scale, { x: 2.4, y: 2.4 }, { x: 1, y: 1, duration: 0.22, ease: 'power3.in' });
    await new Promise(resolve => gsap.delayedCall(big ? 1.5 : 1.1, resolve));
    await gsap.to(this, { alpha: 0, duration: 0.3 });
    this.visible = false;
    pop.destroy();
    this.gauge.removeChildren().forEach(c => { gsap.killTweensOf([c, c.scale]); c.destroy(); });
  }
}
