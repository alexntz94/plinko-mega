// Plinko Mega's fortune wheel: always on screen under the board, its top half showing, with the jackpot display and
// the SPINS counter above it. A ball in a SPIN slot turns it (the server has already decided the segment); a full meter
// turns it gold for a MEGA SPIN. A win ends in a golden burst with the amount counting up.

import { BlurFilter, Container, Graphics, Sprite, Text, Texture } from 'pixi.js';
import { gsap } from 'gsap';
import { label, textQuality, typeface, fit } from './ui';
import { Particles, calm, shake } from './effects';
import { goldText, shade } from './paint';
import type { Segment } from './api';
import type { WheelStyle } from './theme';

const random = (min: number, max: number) => min + Math.random() * (max - min);
const RAINBOW = ['#ff3b3b', '#ff9a1b', '#ffe14a', '#4ade5a', '#2ec4ff', '#8a5bff', '#ff4fd8'];

/** "×25", or the jackpot's name. */
export function segmentText(segment: Segment): string {
  return segment.name ? segment.name : '×' + String(Number(segment.multiplier.toFixed(2)));
}

export class FortuneWheel extends Container {
  private readonly glow = new Graphics();
  private readonly base = new Graphics();
  private readonly rainbow = new Graphics();
  private readonly disc = new Container();
  private readonly mask_ = new Graphics();
  private readonly pointer = new Container();
  private readonly header = new Container();
  private readonly jackpotName: Text;
  private readonly jackpotAmount: Text;
  private readonly spinsText: Text;
  private readonly burst = new Container();
  private readonly sparks = new Particles();
  private radius = 160;
  private segments: Segment[] = [];
  private gold = false;
  private jackpotTimer: gsap.core.Tween | null = null;
  private jackpotIndex = 0;
  private stake = 1;
  private hurried = false;
  private spin: gsap.core.Tween | null = null;
  /** Each segment edge that passes the pointer (for the ticking sound). */
  onTick: (() => void) | null = null;

  constructor(private readonly style: WheelStyle, private readonly hub: Texture | null, private readonly finger: Texture | null,
              private readonly format: (value: number) => string) {
    super();
    this.jackpotName = label('', 14, 'center', { fill: style.title });
    this.jackpotAmount = label('', 26, 'center', { fill: shade('#fff6c2', '#e9b53c'), stroke: { color: '#000000', width: 3 } });
    this.spinsText = label('SPINS 0', 18, 'center', { fill: '#ffffff' });
    this.rainbow.filters = [new BlurFilter({ strength: 10 })];
    this.rainbow.alpha = 0;
    // the glow and the rainbow ring are cut off where the disc is, and a base hides the cut
    const back = new Container();
    back.addChild(this.glow, this.rainbow, this.disc);
    this.addChild(back, this.mask_, this.base, this.pointer, this.header, this.sparks, this.burst);
    back.mask = this.mask_;
    // a tap hurries a turning wheel along (the segment is already decided)
    this.disc.eventMode = 'static';
    this.disc.on('pointertap', () => { if (this.spin?.isActive()) this.spin.timeScale(4); });
  }

  /** The wheel at (x, y) = the disc's centre, radius r; only the part above y + r * 0.12 shows. */
  place(x: number, y: number, r: number): void {
    this.radius = r;
    this.position.set(x, y);
    this.mask_.clear().rect(-r * 1.4, -r * 1.4, r * 2.8, r * 1.5).fill('#ffffff');
    // the base the wheel turns in: a dark plinth with a gold rim across the cut
    this.base.clear()
      .roundRect(-r * 1.32, r * 0.04, r * 2.64, r * 0.34, r * 0.08).fill({ color: '#000000', alpha: 0.5 })
      .roundRect(-r * 1.3, 0, r * 2.6, r * 0.32, r * 0.08).fill(shade('#3a2160', '#0b0614')).stroke({ color: this.style.title, width: 3 })
      .moveTo(-r * 1.2, r * 0.07).lineTo(r * 1.2, r * 0.07).stroke({ color: '#ffffff', width: 1, alpha: 0.25 });
    this.drawHeader();
    this.draw();
  }

  /** The segments of the normal wheel, and the stake its jackpots are shown for (the bet per ball). */
  setWheel(segments: Segment[], stake: number): void {
    this.segments = segments;
    this.stake = stake;
    if (!this.gold) this.draw();
    this.cycleJackpots();
  }

  setStake(stake: number): void {
    this.stake = stake;
    this.showJackpot();
  }

  setSpins(count: number): void {
    this.spinsText.text = count === 1 ? 'SPIN 1' : `SPINS ${count}`;
    gsap.fromTo(this.spinsText.scale, { x: 1.4, y: 1.4 }, { x: 1, y: 1, duration: 0.3, ease: 'back.out(2)' });
  }

  /** Hurry: the wheel spins faster and the burst is shorter (autoplay, turbo). */
  setHurry(hurry: boolean): void {
    this.hurried = hurry;
  }

  /**
   * Turns to segment `index` of `segments` (the gold wheel's when gold), then bursts with the win; resolves when the
   * player has seen it. A tap on the wheel hurries it.
   */
  async turn(segments: Segment[], index: number, win: number, options: { gold: boolean; title: string }): Promise<void> {
    const wasGold = this.gold;
    this.gold = options.gold;
    if (options.gold || wasGold) this.draw(segments);
    this.jackpotTimer?.pause();
    this.jackpotName.text = options.title;
    this.jackpotAmount.text = options.gold ? 'MEGA SPIN' : 'SPIN!';
    fit(this.jackpotAmount, this.radius * 0.9);
    const step = Math.PI * 2 / segments.length;
    const turns = calm ? 1 : this.hurried ? 2 : 4 + Math.floor(Math.random() * 2);
    const start = this.disc.rotation % (Math.PI * 2);
    this.disc.rotation = start;
    const target = -index * step + random(-0.3, 0.3) * step - turns * Math.PI * 2 + Math.min(0, start);
    let lastTick = Math.floor(this.disc.rotation / step);
    // the rainbow ring lights up behind the wheel while it turns
    gsap.to(this.rainbow, { alpha: 0.9, duration: 0.3 });
    const glowSpin = gsap.to(this.rainbow, { rotation: '+=' + Math.PI * 2, duration: 1.2, repeat: -1, ease: 'none' });
    await new Promise<void>(resolve => {
      this.spin = gsap.to(this.disc, {
        rotation: target, duration: calm ? 1.2 : this.hurried ? 1.6 : random(3.6, 4.2), ease: 'power4.out',
        onUpdate: () => {
          const tick = Math.floor(this.disc.rotation / step);
          if (tick !== lastTick) {
            lastTick = tick;
            this.onTick?.();
            gsap.fromTo(this.pointer, { rotation: 0.25 }, { rotation: 0, duration: 0.12, overwrite: true });
          }
        },
        onComplete: resolve,
      });
    });
    glowSpin.kill();
    gsap.to(this.rainbow, { alpha: 0, duration: 0.6 });
    const segment = segments[index];
    this.jackpotName.text = segment.name ? `${segment.name} JACKPOT` : options.title;
    this.jackpotAmount.text = segmentText(segment);
    fit(this.jackpotAmount, this.radius * 0.9);
    await this.celebrate(win, !!segment.name || options.gold);
    if (options.gold) {
      this.gold = false;
      this.draw();
    }
    this.cycleJackpots();
  }

  /** The golden burst: rays, coins and the amount counting up in gold over the wheel. */
  celebrate(win: number, big: boolean): Promise<void> {
    this.burst.removeChildren().forEach(child => child.destroy({ children: true }));
    const r = this.radius;
    const rays = new Graphics();
    for (let i = 0; i < 18; i++) {
      const a = i * Math.PI * 2 / 18;
      rays.moveTo(0, 0).lineTo(Math.cos(a - 0.07) * r * 1.6, Math.sin(a - 0.07) * r * 1.6)
        .lineTo(Math.cos(a + 0.07) * r * 1.6, Math.sin(a + 0.07) * r * 1.6).lineTo(0, 0).fill({ color: '#ffd76a', alpha: 0.35 });
    }
    rays.blendMode = 'add';
    rays.y = -r * 0.45;
    const flash = new Graphics().circle(0, -r * 0.45, r * 0.7).fill({ color: '#fff3b0', alpha: 0.8 });
    flash.filters = [new BlurFilter({ strength: 24 })];
    flash.blendMode = 'add';
    const amount = goldText(this.format(0), big ? r * 0.42 : r * 0.34);
    amount.y = -r * 0.32;
    this.burst.addChild(flash, rays, amount);
    if (!calm) {
      const colours = ['#ffd76a', '#fff3b0', '#e9a91a', '#ffffff'];
      for (let i = 0; i < (big ? 6 : 3); i++) {
        gsap.delayedCall(i * 0.15, () => this.sparks.burst(random(-r * 0.6, r * 0.6), -r * 0.5, colours, big ? 18 : 12, big ? 700 : 500));
      }
      if (big) shake(this, 8);
    }
    const counter = { value: 0 };
    const hold = this.hurried ? 0.7 : big ? 2.2 : 1.4;
    return new Promise(resolve => {
      gsap.timeline({ onComplete: () => { this.burst.removeChildren().forEach(child => child.destroy({ children: true })); resolve(); } })
        .from(flash.scale, { x: 0.2, y: 0.2, duration: 0.35, ease: 'power2.out' }, 0)
        .to(flash, { alpha: 0, duration: 0.8 }, 0.3)
        .from(rays.scale, { x: 0.1, y: 0.1, duration: 0.5, ease: 'back.out(1.6)' }, 0)
        .to(rays, { rotation: 0.6, duration: hold + 0.8, ease: 'none' }, 0)
        .from(amount.scale, { x: 0.2, y: 0.2, duration: 0.45, ease: 'back.out(2.4)' }, 0)
        .to(counter, { value: win, duration: big ? 1.4 : 0.8, ease: 'power2.out',
          onUpdate: () => { amount.text = this.format(counter.value); } }, 0)
        .to([rays, amount], { alpha: 0, duration: 0.35 }, hold + 0.5);
    });
  }

  /** The jackpot display shows MINI, MAJOR and MEGA in turn, each as money at the current stake. */
  private cycleJackpots(): void {
    this.jackpotTimer?.kill();
    this.showJackpot();
    this.jackpotTimer = gsap.delayedCall(2.6, () => { this.jackpotIndex++; this.cycleJackpots(); });
  }

  private showJackpot(): void {
    const jackpots = this.segments.filter(s => s.name);
    if (jackpots.length === 0) {
      this.jackpotName.text = 'WHEEL';
      this.jackpotAmount.text = '';
      return;
    }
    const jackpot = jackpots[this.jackpotIndex % jackpots.length];
    this.jackpotName.text = `${jackpot.name} JACKPOT`;
    this.jackpotAmount.text = this.format(this.stake * jackpot.multiplier);
    fit(this.jackpotAmount, this.radius * 0.9);
    gsap.fromTo(this.jackpotAmount, { alpha: 0 }, { alpha: 1, duration: 0.35 });
  }

  /** The bar over the wheel: the jackpot on display in the middle, the SPINS counter on the right. */
  private drawHeader(): void {
    const r = this.radius;
    const texts: Container[] = [this.jackpotName, this.jackpotAmount, this.spinsText];
    this.header.removeChildren().forEach(child => { if (!texts.includes(child)) child.destroy(); });
    const w = r * 2.3, h = r * 0.34, y = -r * 1.42;
    const bar = new Graphics()
      .roundRect(-w / 2, y + 3, w, h, h * 0.3).fill({ color: '#000000', alpha: 0.5 })
      .roundRect(-w / 2, y, w, h, h * 0.3).fill(shade('#3a2160', '#140a26')).stroke({ color: this.style.title, width: 3 })
      .roundRect(-r * 0.55, y + 4, r * 1.1, h - 8, 6).fill('#0b0614').stroke({ color: this.style.title, width: 1.5 })
      .roundRect(w / 2 - r * 0.5, y + 4, r * 0.46, h - 8, 6).fill(shade('#2e7fbf', '#163e6b')).stroke({ color: '#ffffff', width: 1, alpha: 0.6 })
      .roundRect(-w / 2 + r * 0.04, y + 4, r * 0.46, h - 8, 6).fill(shade('#7a1a2c', '#3a0a14')).stroke({ color: '#ffffff', width: 1, alpha: 0.6 });
    this.jackpotName.style.fontSize = Math.round(h * 0.26);
    this.jackpotName.position.set(-w / 2 + r * 0.27, y + h * 0.62);
    this.jackpotAmount.style.fontSize = Math.round(h * 0.6);
    this.jackpotAmount.position.set(0, y + h * 0.74);
    this.spinsText.style.fontSize = Math.round(h * 0.36);
    this.spinsText.position.set(w / 2 - r * 0.27, y + h * 0.68);
    this.header.addChild(bar, this.jackpotName, this.jackpotAmount, this.spinsText);
  }

  /** The disc: a wedge per segment, labels along the radius, the rim with studs, the hub; the pointer on top. */
  private draw(segments = this.segments): void {
    this.disc.removeChildren().forEach(child => child.destroy({ children: true }));
    this.pointer.removeChildren().forEach(child => child.destroy({ children: true }));
    const r = this.radius;
    const look = this.gold ? this.style.gold : this.style;
    this.glow.clear().circle(0, 0, r * 1.18).fill({ color: this.gold ? '#ffd76a' : this.style.title, alpha: 0.18 });
    this.rainbow.clear();
    RAINBOW.forEach((colour, i) => {
      const a0 = i * Math.PI * 2 / RAINBOW.length;
      this.rainbow.moveTo(0, 0).arc(0, 0, r * 1.22, a0, a0 + Math.PI * 2 / RAINBOW.length).lineTo(0, 0).fill(colour);
    });
    if (segments.length === 0) return;
    const step = Math.PI * 2 / segments.length;
    this.disc.addChild(new Graphics().circle(0, 0, r * 1.09).fill(look.rim).stroke({ color: this.style.ink, width: 5 })
      .circle(0, 0, r * 1.03).stroke({ color: this.gold ? '#fff0b8' : this.style.title, width: 2 }));
    segments.forEach((segment, i) => {
      const a0 = -Math.PI / 2 + (i - 0.5) * step;
      const face = segment.name ? (segment.name === 'MEGA' ? '#c41a1f' : this.style.title) : look.faces[i % look.faces.length];
      const ink = segment.name ? (segment.name === 'MEGA' ? '#ffe9a0' : this.style.ink) : look.text[i % look.text.length];
      const wedge = new Graphics().moveTo(0, 0).arc(0, 0, r, a0, a0 + step).lineTo(0, 0).fill(face)
        .stroke({ color: this.style.ink, width: 3, join: 'round' });
      const words = new Text({ text: segmentText(segment), anchor: 0.5, resolution: textQuality(),
        style: { fontFamily: typeface.family, fontWeight: typeface.weight as 'normal', fontSize: Math.round(r * (segment.name ? 0.11 : 0.15)), fill: ink } });
      const mid = -Math.PI / 2 + i * step;
      words.position.set(Math.cos(mid) * r * 0.68, Math.sin(mid) * r * 0.68);
      words.rotation = mid + Math.PI / 2;
      if (words.width > r * 0.5) words.scale.set(r * 0.5 / words.width);
      this.disc.addChild(wedge, words);
    });
    for (let i = 0; i < segments.length; i++) {
      const a = -Math.PI / 2 + (i - 0.5) * step;
      this.disc.addChild(new Graphics().circle(Math.cos(a) * r * 1.055, Math.sin(a) * r * 1.055, r * 0.024).fill('#ffffff')
        .stroke({ color: this.style.ink, width: 1.5 }));
    }
    if (this.hub) {
      const hub = new Sprite({ texture: this.hub, anchor: 0.5 });
      hub.scale.set(r * 0.62 / Math.max(hub.texture.width, 1));
      this.disc.addChild(hub);
    } else {
      this.disc.addChild(new Graphics().circle(0, 0, r * 0.28).fill(look.rim).stroke({ color: this.style.ink, width: 4 }));
    }
    if (this.finger) {
      const finger = new Sprite({ texture: this.finger, anchor: { x: 0.5, y: 0.9 } });
      finger.scale.set(r * 0.3 / Math.max(finger.texture.height, 1));
      this.pointer.addChild(finger);
    } else {
      this.pointer.addChild(new Graphics().poly([-r * 0.08, -r * 0.14, r * 0.08, -r * 0.14, 0, r * 0.06]).fill(this.style.title)
        .stroke({ color: this.style.ink, width: 3, join: 'round' }));
    }
    this.pointer.y = -r * 1.02;
  }
}
