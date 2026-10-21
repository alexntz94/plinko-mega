// Plinko Mega's fortune wheel: always on screen under the board, its top half showing, with the jackpot display and
// the SPINS counter above it. A ball in a SPIN slot turns it (the server has already decided the segment); a full meter
// turns it gold for a MEGA SPIN. A win ends in a golden burst with the amount counting up.

import { BlurFilter, Container, FillGradient, Graphics, Sprite, Text, Texture } from 'pixi.js';
import { gsap } from 'gsap';
import { label, textQuality, typeface, fit } from './ui';
import { Particles, calm, photoBlast, shake } from './effects';
import { counting, goldText, mix, shade } from './paint';
import type { Segment } from './api';
import type { RevolverArt, WheelLook, WheelStyle } from './theme';
import { chamberLayout } from './revolver';

/** A revolver from pictures (theme wheel.revolverArt): its textures and its measured geometry. */
export interface PhotoRevolver {
  cylinder: Texture; dial: Texture; flash: Texture;
  cartridges: { plain: Texture; MINI: Texture; MAJOR: Texture; MEGA: Texture };
  spec: RevolverArt;
}

const random = (min: number, max: number) => min + Math.random() * (max - min);
const RAINBOW = ['#ff3b3b', '#ff9a1b', '#ffe14a', '#4ade5a', '#2ec4ff', '#8a5bff', '#ff4fd8'];

/** "×25", or the jackpot's name. */
export function segmentText(segment: Segment): string {
  return segment.name ? segment.name : '×' + String(Number(segment.multiplier.toFixed(2)));
}

export class FortuneWheel extends Container {
  private readonly glow = new Graphics();
  private readonly base = new Graphics();
  /** Marquee lights round the rim, chasing all the time (faster while the wheel turns). */
  private readonly lights = new Container();
  /** What stays still over the turning disc: the glass and the bezel. */
  private readonly cover = new Container();
  private chase = 0;
  private chaseSpeed = 6;
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
  private hurried = false;
  private spin: gsap.core.Timeline | null = null;
  /** Each segment edge that passes the pointer (for the ticking sound). */
  onTick: (() => void) | null = null;
  /** A win landing on the wheel (big: a jackpot or a MEGA SPIN), for its sound. */
  onWin: ((big: boolean) => void) | null = null;

  constructor(private readonly style: WheelStyle, private readonly hub: Texture | null, private readonly finger: Texture | null,
              private readonly format: (value: number) => string, private readonly panel = '#2a1747',
              private readonly art: { bezel: Texture | null; glass: Texture | null; explode?: boolean; photo?: PhotoRevolver | null } = { bezel: null, glass: null }) {
    super();
    this.jackpotName = label('', 14, 'center', { fill: style.title });
    this.jackpotAmount = label('', 26, 'center', { fill: '#ffd76a', stroke: { color: '#000000', width: 4, join: 'round' } });
    this.spinsText = label('SPINS 0', 18, 'center', { fill: '#ffffff' });
    this.rainbow.filters = [new BlurFilter({ strength: 10 })];
    this.rainbow.alpha = 0;
    this.rainbow.visible = false;
    // the glow and the rainbow ring are cut off where the disc is, and a base hides the cut
    const back = new Container();
    back.addChild(this.glow, this.rainbow, this.disc, this.cover, this.lights);
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
    // the photo revolver shows whole (it pops up over the board); a wheel only above its base
    if (this.photo()) this.mask_.clear().rect(-r * 3, -r * 3, r * 6, r * 6).fill('#ffffff');
    else this.mask_.clear().rect(-r * 1.4, -r * 1.4, r * 2.8, r * 1.5).fill('#ffffff');
    // the base the wheel turns in: a dark plinth with a gold rim across the cut
    this.base.clear()
      .roundRect(-r * 1.32, r * 0.04, r * 2.64, r * 0.34, r * 0.08).fill({ color: '#000000', alpha: 0.5 })
      .roundRect(-r * 1.3, 0, r * 2.6, r * 0.32, r * 0.08).fill(shade(mix(this.panel, '#ffffff', 0.08), mix(this.panel, '#000000', 0.7))).stroke({ color: this.style.title, width: 3 })
      .moveTo(-r * 1.2, r * 0.07).lineTo(r * 1.2, r * 0.07).stroke({ color: '#ffffff', width: 1, alpha: 0.25 });
    if (this.photo()) this.base.clear();
    this.drawHeader();
    this.draw();
  }

  /** The photo revolver, when the theme has one with a chamber per prize. */
  private photo(segments = this.segments): PhotoRevolver | null {
    const photo = this.art.photo;
    return photo && photo.spec.chambers.length === segments.length ? photo : null;
  }


  /** Every frame: the marquee lights chase round the rim, and the glow breathes. */
  update(deltaMs: number): void {
    if (calm || this.lights.children.length === 0) return;
    this.chase += Math.min(deltaMs, 50) / 1000 * this.chaseSpeed;
    const n = this.lights.children.length;
    this.lights.children.forEach((light, i) => {
      const on = (Math.floor(this.chase) + i) % 3 === 0;
      light.alpha = on ? 1 : 0.25;
      light.scale.set(on ? 1.25 : 1);
    });
    this.glow.alpha = 0.75 + 0.25 * Math.sin(this.chase * 0.8);
    void n;
  }

  /** The segments of the normal wheel. */
  setWheel(segments: Segment[]): void {
    this.segments = segments;
    if (!this.gold) this.draw();
    this.cycleJackpots();
  }

  setSpins(count: number): void {
    this.spinsText.text = count === 1 ? 'SPIN 1' : `SPINS ${count}`;
    fit(this.spinsText, this.radius * 0.38);
    // a pop that settles on the fitted size (never wider than its box)
    const fitted = this.spinsText.scale.x;
    gsap.fromTo(this.spinsText.scale, { x: fitted * 1.4, y: fitted * 1.4 }, { x: fitted, y: fitted, duration: 0.3, ease: 'back.out(2)', overwrite: true });
  }

  /** Hurry: the wheel spins faster and the burst is shorter (autoplay, turbo). */
  setHurry(hurry: boolean): void {
    this.hurried = hurry;
  }

  /**
   * Turns to segment `index` of `segments` (the gold wheel's when gold), then bursts with the win; resolves when the
   * player has seen it. A tap on the wheel hurries it.
   */
  /**
   * base: what the spin multiplies (the ball's win, or a MEGA SPIN's stake), shown as "base × ?" while it turns and
   * "base × segment" when it stops, so the win always adds up on screen.
   */
  async turn(segments: Segment[], index: number, win: number, options: { gold: boolean; title: string; base?: number }): Promise<void> {
    const wasGold = this.gold;
    this.gold = options.gold;
    if (options.gold || wasGold) this.draw(segments);
    this.jackpotTimer?.pause();
    this.jackpotName.text = options.title;
    this.jackpotAmount.text = options.base !== undefined ? `${this.format(options.base)} × ?` : options.gold ? 'MEGA SPIN' : 'SPIN!';
    fit(this.jackpotAmount, this.radius * 0.9);
    fit(this.jackpotName, this.radius * 0.38);
    fit(this.spinsText, this.radius * 0.38);
    const step = Math.PI * 2 / segments.length;
    // never the same stop twice: how many turns, how fast, and how many chambers click in one by one at the end
    const turns = calm ? 1 : this.hurried ? 2 : 2 + Math.floor(Math.random() * 3);
    // a drawn revolver clicks its last chambers in; the photo revolver turns smoothly and stops dead
    const clicks = calm || this.hurried || this.photo(segments) ? 0 : 1 + Math.floor(Math.random() * 3);
    const start = this.disc.rotation % (Math.PI * 2);
    this.disc.rotation = start;
    // a revolver stops dead on a chamber; a wheel anywhere within its segment
    const offset = this.style.revolver ? 0 : random(-0.3, 0.3) * step;
    // the photo revolver: its chamber stops exactly under the hammer (the chambers are not quite evenly spaced)
    const photo = this.photo(segments);
    const stop = photo ? chamberLayout(photo.spec, this.radius)[index].stop : -index * step;
    const target = stop + offset - turns * Math.PI * 2 + Math.min(0, start);
    let lastTick = Math.floor(this.disc.rotation / step);
    const tick = () => {
      const now = Math.floor(this.disc.rotation / step);
      if (now === lastTick) return;
      lastTick = now;
      this.onTick?.();
      gsap.fromTo(this.pointer, { rotation: 0.25 }, { rotation: 0, duration: 0.12, overwrite: true });
    };
    // the rainbow ring lights up behind the wheel while it turns
    this.rainbow.visible = !this.style.revolver;   // blurred: only while a wheel turns; never round a revolver
    gsap.to(this.rainbow, { alpha: 0.9, duration: 0.3 });
    this.chaseSpeed = 22;
    const glowSpin = gsap.to(this.rainbow, { rotation: '+=' + Math.PI * 2, duration: 1.2, repeat: -1, ease: 'none' });
    await new Promise<void>(resolve => {
      const spin = gsap.timeline({ onComplete: resolve });
      this.spin = spin;
      spin.to(this.disc, { rotation: target + clicks * step, duration: calm ? 1.2 : this.hurried ? 1.3 : random(1.4, 2.2),
        ease: clicks ? 'power3.out' : 'power4.out', onUpdate: tick });
      // the last chambers click in, each a snap and a pause that grows
      for (let c = clicks - 1; c >= 0; c--) {
        spin.to(this.disc, { rotation: target + c * step, duration: 0.09, ease: 'back.out(2.5)', onUpdate: tick },
          '+=' + (random(0.08, 0.2) * (1 + (clicks - 1 - c) * 0.5)).toFixed(3));
      }
    });
    glowSpin.kill();
    this.chaseSpeed = 6;
    gsap.to(this.rainbow, { alpha: 0, duration: 0.6, onComplete: () => { this.rainbow.visible = false; } });
    const segment = segments[index];
    this.jackpotName.text = segment.name ? `${segment.name} JACKPOT` : options.title;
    // a jackpot is called by its name (MAJOR JACKPOT, then its win), a plain chamber shows its sum
    this.jackpotAmount.text = segment.name ? `${segment.name}!`
      : options.base !== undefined ? `${this.format(options.base)} × ${segment.multiplier}` : segmentText(segment);
    fit(this.jackpotAmount, this.radius * 0.9);
    fit(this.jackpotName, this.radius * 0.38);
    fit(this.spinsText, this.radius * 0.38);
    await this.celebrate(win, !!segment.name || options.gold);
    if (options.gold) {
      this.gold = false;
      this.draw();
    }
    this.cycleJackpots();
  }

  /** Where a win bursts out of the wheel, on the screen. */
  winPoint(): { x: number; y: number } {
    return this.toGlobal({ x: 0, y: -this.radius * 0.45 });
  }

  /** Empties the burst layer, stopping what still moves in it first (smoke can outlast the count). */
  private clearBurst(): void {
    for (const child of this.burst.removeChildren()) {
      gsap.killTweensOf(child);
      gsap.killTweensOf(child.scale);
      child.destroy({ children: true });
    }
  }

  /** The golden burst: rays, coins and the amount counting up in gold over the wheel. */
  celebrate(win: number, big: boolean): Promise<void> {
    this.clearBurst();
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
    // a war theme blows up instead of the golden rays
    const explodes = this.art.explode && !calm;
    if (explodes) this.explode(big);
    const photo = this.photo();
    if (photo && !calm) {
      // the shot: a muzzle flash out of the chamber under the hammer
      const flash = new Sprite({ texture: photo.flash, anchor: 0.5 });
      flash.blendMode = 'add';
      flash.position.set(0, -r * 0.7);
      this.burst.addChild(flash);
      gsap.timeline()
        .fromTo(flash, { width: r * 0.3, height: r * 0.3, alpha: 1 }, { width: r * (big ? 2.4 : 1.7), height: r * (big ? 2.4 : 1.7), duration: 0.12, ease: 'power3.out' })
        .to(flash, { alpha: 0, duration: 0.3 }, 0.1);
    }
    this.burst.addChild(...(explodes ? [] : [flash, rays]), amount);
    this.onWin?.(big);
    if (!calm) {
      const colours = ['#ffd76a', '#fff3b0', '#e9a91a', '#ffffff'];
      for (let i = 0; i < (big ? 6 : 3); i++) {
        gsap.delayedCall(i * 0.15, () => this.sparks.burst(random(-r * 0.6, r * 0.6), -r * 0.5, colours, big ? 18 : 12, big ? 700 : 500));
      }
      if (big) shake(this, 8);
    }
    const counter = { value: 0 };
    const show = counting(v => { amount.text = this.format(v); });
    const hold = this.hurried ? 0.5 : big ? 1.3 : 0.8;
    return new Promise(resolve => {
      gsap.timeline({ onComplete: () => { this.clearBurst(); resolve(); } })
        .from(flash.scale, { x: 0.2, y: 0.2, duration: 0.35, ease: 'power2.out' }, 0)
        .to(flash, { alpha: 0, duration: 0.8 }, 0.3)
        .from(rays.scale, { x: 0.1, y: 0.1, duration: 0.5, ease: 'back.out(1.6)' }, 0)
        .to(rays, { rotation: 0.6, duration: hold + 0.8, ease: 'none' }, 0)
        .from(amount.scale, { x: 0.2, y: 0.2, duration: 0.45, ease: 'back.out(2.4)' }, 0)
        .to(counter, { value: win, duration: big ? 0.9 : 0.5, ease: 'power2.out',
          onUpdate: () => show(counter.value), onComplete: () => show(win, true) }, 0)
        .to([rays, amount], { alpha: 0, duration: 0.35 }, hold + 0.5);
    });
  }

  /**
   * A war theme's win on the wheel: it blows up like a bomb. A fireball bursts out of the middle, a shockwave ring
   * runs out, black smoke rolls up, debris and embers fly and the wheel shakes; bigger for a jackpot or a MEGA SPIN.
   */
  private explode(big: boolean): void {
    const r = this.radius;
    const y = -r * 0.42;
    // soft edges drawn in (rings of falling opacity), not blurred: cheap on a phone
    // the theme's photo explosion over the wheel, when it has one
    if (photoBlast(this.burst, 0, y, r * (big ? 2.2 : 1.6))) {
      this.sparks.burst(0, y, ['#ffd76a', '#ff8a1a', '#fff3b0'], big ? 40 : 22, big ? 850 : 600, 500);
      const word = goldText(big ? 'KABOOM!' : 'BOOM!', r * (big ? 0.34 : 0.26));
      word.position.set(0, y - r * 0.55);
      this.burst.addChild(word);
      gsap.timeline()
        .fromTo(word.scale, { x: 2.6, y: 2.6 }, { x: 1, y: 1, duration: 0.2, ease: 'power3.in' }, 0)
        .to(word, { y: word.y - r * 0.2, alpha: 0, duration: 0.6, ease: 'power1.in' }, 0.9);
      return;
    }
    const soft = (radius: number, colour: string, alpha = 1) => new Graphics()
      .circle(0, 0, radius).fill({ color: colour, alpha: 0.25 * alpha }).circle(0, 0, radius * 0.8).fill({ color: colour, alpha: 0.35 * alpha })
      .circle(0, 0, radius * 0.6).fill({ color: colour, alpha: 0.5 * alpha });
    // smoke first, so the fire is in front of it: grey enough to show on the dark wheel, rolling up and out
    // smoke only round a drawn wheel (over the photo revolver, flat puffs look fake)
    for (let i = 0; i < (this.art.photo ? 0 : big ? 12 : 7); i++) {
      const puff = soft(r * random(0.2, 0.32), ['#5c5f66', '#474a50', '#6e7178'][i % 3], 1.6);
      const a = random(0, Math.PI * 2);
      puff.position.set(Math.cos(a) * r * 0.25, y + Math.sin(a) * r * 0.15);
      this.burst.addChild(puff);
      gsap.timeline({ delay: 0.15 + i * 0.05 })
        .fromTo(puff.scale, { x: 0.3, y: 0.3 }, { x: random(1.8, 2.8), y: random(1.8, 2.8), duration: 2.2, ease: 'power2.out' }, 0)
        .to(puff, { y: puff.y - r * random(0.7, 1.1), x: puff.x * 2.2, duration: 2.2, ease: 'power1.out' }, 0)
        .to(puff, { alpha: 0, duration: 1.2, ease: 'power1.in' }, 1.0);
    }
    // the fireball: a white-hot core in yellow, orange and red flames that swell, hang and burn out
    const flames = ['#c1121f', '#ff4a1a', '#ff8a1a', '#ffd76a', '#fff3b0', '#ffffff'];
    for (let i = 0; i < (big ? 14 : 9); i++) {
      const hot = i >= (big ? 10 : 6);
      const fire = soft(r * (hot ? random(0.12, 0.18) : random(0.2, 0.3)), flames[hot ? 4 + (i % 2) : i % 4], 1.8);
      fire.blendMode = 'add';
      const a = random(0, Math.PI * 2), d = hot ? r * 0.06 : r * random(0.1, 0.3);
      fire.position.set(Math.cos(a) * d, y + Math.sin(a) * d * 0.7);
      this.burst.addChild(fire);
      const grow = random(1.5, big ? 2.6 : 2.0);
      gsap.timeline({ delay: i * 0.025 })
        .fromTo(fire.scale, { x: 0.1, y: 0.1 }, { x: grow, y: grow, duration: 0.35, ease: 'power3.out' }, 0)
        .to(fire, { x: fire.x * 1.5, y: fire.y - r * 0.3, duration: 1.1, ease: 'power1.out' }, 0.1)
        .to(fire, { alpha: 0, duration: 0.7, ease: 'power1.in' }, big ? 0.8 : 0.55);
    }
    // the shockwave, and the BOOM
    for (const [delay, width] of [[0, 8], [0.08, 4]] as const) {
      const ring = new Graphics().circle(0, 0, r * 0.4).stroke({ color: '#fff3b0', width });
      ring.blendMode = 'add';
      ring.position.set(0, y);
      this.burst.addChild(ring);
      gsap.timeline({ delay })
        .fromTo(ring.scale, { x: 0.1, y: 0.1 }, { x: big ? 4.5 : 3, y: big ? 4.5 : 3, duration: 0.6, ease: 'power2.out' }, 0)
        .to(ring, { alpha: 0, duration: 0.6, ease: 'power1.in' }, 0);
    }
    const word = goldText(big ? 'KABOOM!' : 'BOOM!', r * (big ? 0.34 : 0.26));
    word.position.set(0, y - r * 0.55);
    word.rotation = random(-0.12, 0.12);
    this.burst.addChild(word);
    gsap.timeline()
      .fromTo(word.scale, { x: 2.6, y: 2.6 }, { x: 1, y: 1, duration: 0.2, ease: 'power3.in' }, 0)
      .to(word, { y: word.y - r * 0.2, alpha: 0, duration: 0.6, ease: 'power1.in' }, 0.9);
    // debris and embers
    this.sparks.burst(0, y, ['#1a1d21', '#4b5320', '#2b3036', '#6e5216'], big ? 34 : 20, big ? 950 : 700, 1500);
    this.sparks.burst(0, y, ['#ffd76a', '#ff8a1a', '#fff3b0'], big ? 46 : 26, big ? 850 : 600, 500);
    if (!this.art.photo) shake(this, big ? 18 : 9);   // the photo revolver stays still under its blast
  }

  /** The jackpot display shows MINI, MAJOR and MEGA in turn, each as what it multiplies a ball's win by. */
  private cycleJackpots(): void {
    this.jackpotTimer?.kill();
    this.showJackpot();
    this.jackpotTimer = gsap.delayedCall(2.6, () => { this.jackpotIndex++; this.cycleJackpots(); });
  }

  private showJackpot(): void {
    const jackpots = this.segments.filter(s => s.name);
    if (jackpots.length === 0) {
      this.jackpotName.text = 'WHEEL';
      fit(this.jackpotName, this.radius * 0.38);
      this.jackpotAmount.text = '';
      return;
    }
    const jackpot = jackpots[this.jackpotIndex % jackpots.length];
    this.jackpotName.text = `${jackpot.name} JACKPOT`;
    // a spin multiplies the ball's win, so a jackpot is shown as what it multiplies by
    this.jackpotAmount.text = 'x' + jackpot.multiplier;
    fit(this.jackpotAmount, this.radius * 0.9);
    fit(this.jackpotName, this.radius * 0.38);
    fit(this.spinsText, this.radius * 0.38);
    gsap.fromTo(this.jackpotAmount, { alpha: 0 }, { alpha: 1, duration: 0.35 });
  }

  /** The bar over the wheel: the jackpot on display in the middle, the SPINS counter on the right. */
  private drawHeader(): void {
    const r = this.radius;
    const texts: Container[] = [this.jackpotName, this.jackpotAmount, this.spinsText];
    this.header.removeChildren().forEach(child => { if (!texts.includes(child)) child.destroy(); });
    // the photo revolver's header goes over its hammer (the bonus meter steps aside while it shows)
    const w = r * 2.3, h = r * 0.34, y = this.art.photo ? -r * 2.28 : -r * 1.42;
    const bar = new Graphics()
      .roundRect(-w / 2, y + 3, w, h, h * 0.3).fill({ color: '#000000', alpha: 0.5 })
      .roundRect(-w / 2, y, w, h, h * 0.3).fill(shade(mix(this.panel, '#ffffff', 0.08), mix(this.panel, '#000000', 0.6))).stroke({ color: this.style.title, width: 3 })
      .roundRect(-r * 0.55, y + 4, r * 1.1, h - 8, 6).fill(mix(this.panel, '#000000', 0.8)).stroke({ color: this.style.title, width: 1.5 })
      .roundRect(w / 2 - r * 0.5, y + 4, r * 0.46, h - 8, 6).fill(shade(mix(this.panel, this.style.title, 0.3), mix(this.panel, '#000000', 0.5))).stroke({ color: this.style.title, width: 1, alpha: 0.8 })
      .roundRect(-w / 2 + r * 0.04, y + 4, r * 0.46, h - 8, 6).fill(shade(mix(this.panel, '#c1121f', 0.6), mix('#c1121f', '#000000', 0.7))).stroke({ color: this.style.title, width: 1, alpha: 0.8 });
    this.jackpotName.style.fontSize = Math.round(h * 0.26);
    this.jackpotName.position.set(-w / 2 + r * 0.27, y + h * 0.62);
    this.jackpotAmount.style.fontSize = Math.round(h * 0.6);
    this.jackpotAmount.position.set(0, y + h * 0.74);
    this.spinsText.style.fontSize = Math.round(h * 0.36);
    this.spinsText.position.set(w / 2 - r * 0.27, y + h * 0.68);
    fit(this.jackpotName, r * 0.38);
    fit(this.spinsText, r * 0.38);
    this.header.addChild(bar, this.jackpotName, this.jackpotAmount, this.spinsText);
  }

  /** The disc: a wedge per segment, labels along the radius, the rim with studs, the hub; the pointer on top. */
  private draw(segments = this.segments): void {
    this.disc.removeChildren().forEach(child => child.destroy({ children: true }));
    this.pointer.removeChildren().forEach(child => child.destroy({ children: true }));
    this.cover.removeChildren().forEach(child => child.destroy({ children: true }));
    // with the theme's bezel the disc is smaller, so the whole wheel keeps its size
    const r = this.art.bezel ? this.radius * 0.84 : this.radius;
    const look = this.gold ? this.style.gold : this.style;
    this.glow.visible = !this.style.revolver;   // no coloured glow round a revolver
    this.glow.clear().circle(0, 0, r * 1.32).fill({ color: this.gold ? '#ffd76a' : this.style.title, alpha: 0.18 });
    this.rainbow.clear();
    RAINBOW.forEach((colour, i) => {
      const a0 = i * Math.PI * 2 / RAINBOW.length;
      this.rainbow.moveTo(0, 0).arc(0, 0, r * 1.36, a0, a0 + Math.PI * 2 / RAINBOW.length).lineTo(0, 0).fill(colour);
    });
    if (segments.length === 0) return;
    const step = Math.PI * 2 / segments.length;
    // the bezel: the theme's drawing, or a plain ring
    const bulbRadius = this.art.bezel ? r * 178 / 160 : r * 1.165;
    const photo = this.photo(segments);
    if (photo) {
      // the dial frame (with its hammer) is the bezel
    } else if (this.art.bezel) {
      const bezel = new Sprite({ texture: this.art.bezel, anchor: 0.5 });
      bezel.width = bezel.height = r * 440 / 160;
      this.cover.addChild(bezel);
    } else {
      this.disc.addChild(new Graphics().circle(0, 0, r * 1.09).fill(look.rim).stroke({ color: this.style.ink, width: 5 })
        .circle(0, 0, r * 1.03).stroke({ color: this.gold ? '#fff0b8' : this.style.title, width: 2 }));
    }
    this.lights.removeChildren().forEach(child => child.destroy());
    for (let i = 0; i < 30; i++) {
      const a = i / 30 * Math.PI * 2;
      const bulb = new Graphics().circle(0, 0, r * 0.05).fill({ color: this.gold ? '#fff3b0' : this.style.title, alpha: 0.5 })
        .circle(0, 0, r * 0.028).fill('#ffffff');
      bulb.blendMode = 'add';
      bulb.position.set(Math.cos(a) * bulbRadius, Math.sin(a) * bulbRadius);
      this.lights.addChild(bulb);
    }
    this.lights.visible = !photo;
    if (photo) this.drawPhoto(r, segments, photo);
    else if (this.style.revolver) this.drawRevolver(r, step, segments);
    else this.drawWedges(r, step, segments, look);
    if (this.art.glass && !photo) {
      const glass = new Sprite({ texture: this.art.glass, anchor: 0.5 });
      glass.width = glass.height = r * 440 / 160;
      this.cover.addChildAt(glass, 0);
    }
    if (this.style.revolver || photo) {
      // the cylinder's own centre: the ejector star (drawn with it)
    } else if (this.hub) {
      const hub = new Sprite({ texture: this.hub, anchor: 0.5 });
      hub.scale.set(r * 0.62 / Math.max(hub.texture.width, 1));
      this.disc.addChild(hub);
    } else {
      this.disc.addChild(new Graphics().circle(0, 0, r * 0.28).fill(look.rim).stroke({ color: this.style.ink, width: 4 }));
    }
    if (photo) {
      // the hammer on the dial points at the chamber that wins
    } else if (this.finger) {
      const finger = new Sprite({ texture: this.finger, anchor: { x: 0.5, y: 0.9 } });
      finger.scale.set(r * (this.art.bezel ? 0.42 : 0.3) / Math.max(finger.texture.height, 1));
      this.pointer.addChild(finger);
    } else {
      this.pointer.addChild(new Graphics().poly([-r * 0.08, -r * 0.14, r * 0.08, -r * 0.14, 0, r * 0.06]).fill(this.style.title)
        .stroke({ color: this.style.ink, width: 3, join: 'round' }));
    }
    this.pointer.y = this.art.bezel ? -r * 1.04 : -r * 1.02;
  }

  /**
   * The photo revolver: the cylinder (turning, with the disc) with a cartridge in every chamber and its prize stamped
   * on the cartridge, upright when its chamber is under the hammer; the dial frame over it, still.
   */
  private drawPhoto(r: number, segments: Segment[], photo: PhotoRevolver): void {
    const { spec } = photo;
    const w = photo.cylinder.width;
    const s = r / (spec.radius * w);                   // cylinder picture to screen
    const cylinder = new Sprite({ texture: photo.cylinder, anchor: { x: spec.centre[0], y: spec.centre[1] } });
    cylinder.scale.set(s);
    if (this.gold) cylinder.tint = '#ffcf6a';   // the MEGA SPIN: a gold-plated cylinder
    this.disc.addChild(cylinder);
    const places = chamberLayout(spec, r);
    segments.forEach((segment, i) => {
      const place = places[i];
      const kind = segment.name === 'MINI' || segment.name === 'MAJOR' || segment.name === 'MEGA' ? segment.name : 'plain';
      const head = new Sprite({ texture: photo.cartridges[kind], anchor: 0.5 });
      head.width = head.height = place.size;
      head.position.set(place.x, place.y);
      head.rotation = place.rotation;
      const words = new Text({ text: segmentText(segment), anchor: 0.5, resolution: textQuality(),
        style: { fontFamily: typeface.family, fontWeight: typeface.weight as 'normal', fontSize: Math.round(r * (segment.name ? 0.11 : 0.12)),
          fill: segment.name ? '#ffd76a' : '#fff6dc', stroke: { color: '#000000', width: r * 0.03, join: 'round' } } });
      words.position.copyFrom(head.position);
      words.rotation = head.rotation;
      if (words.width > place.size * 0.86) words.scale.set(place.size * 0.86 / words.width);
      this.disc.addChild(head, words);
    });
    const dial = new Sprite({ texture: photo.dial, anchor: { x: spec.opening[0], y: spec.opening[1] } });
    dial.scale.set(r * 1.03 / (spec.openingRadius * photo.dial.width));
    this.cover.addChild(dial);
  }

  /** A wheel of coloured wedges, labels along the radius, brass spokes. */
  private drawWedges(r: number, step: number, segments: Segment[], look: WheelLook): void {
    segments.forEach((segment, i) => {
      const a0 = -Math.PI / 2 + (i - 0.5) * step;
      const mega = segment.name === 'MEGA';
      const face = segment.name ? (mega ? '#c41a1f' : this.style.title) : look.faces[i % look.faces.length];
      const wedge = new Graphics().moveTo(0, 0).arc(0, 0, r, a0, a0 + step).lineTo(0, 0).fill(face);
      this.disc.addChild(wedge);
      // the jackpot plates: MEGA in hazard stripes, every jackpot with a glow at its edge
      if (segment.name) {
        const stripes = new Graphics();
        for (let k = -12; k <= 12; k++) stripes.poly([k * 14, -r, k * 14 + 7, -r, k * 14 + 7 + r * 2, r, k * 14 + r * 2, r]).fill({ color: mega ? '#0b0d10' : '#ffffff', alpha: mega ? 0.35 : 0.18 });
        const mask = new Graphics().moveTo(0, 0).arc(0, 0, r, a0, a0 + step).lineTo(0, 0).fill('#ffffff');
        stripes.mask = mask;
        const shine = new Graphics().moveTo(0, 0).arc(0, 0, r * 0.98, a0 + 0.02, a0 + step - 0.02).lineTo(0, 0).fill({ color: mega ? '#ff6a3a' : '#fff3b0', alpha: 0.25 });
        shine.blendMode = 'add';
        this.disc.addChild(stripes, mask, shine);
      }
      // a highlight along the plate's outer edge
      this.disc.addChild(new Graphics().arc(0, 0, r * 0.965, a0 + 0.03, a0 + step - 0.03).stroke({ color: '#ffffff', width: r * 0.018, alpha: 0.35 }));
    });
    // depth: the plates darken towards the hub
    const depth = new FillGradient({ type: 'radial', center: { x: 0.5, y: 0.5 }, innerRadius: 0, outerCenter: { x: 0.5, y: 0.5 }, outerRadius: 0.5,
      textureSpace: 'local', colorStops: [{ offset: 0, color: 'rgba(0,0,0,0.55)' }, { offset: 0.4, color: 'rgba(0,0,0,0.1)' },
        { offset: 0.9, color: 'rgba(0,0,0,0)' }, { offset: 1, color: 'rgba(0,0,0,0.3)' }] });
    this.disc.addChild(new Graphics().circle(0, 0, r).fill(depth));
    // brass spokes between the plates
    for (let i = 0; i < segments.length; i++) {
      const a = -Math.PI / 2 + (i - 0.5) * step;
      const spoke = new Graphics()
        .moveTo(Math.cos(a) * r * 0.3, Math.sin(a) * r * 0.3).lineTo(Math.cos(a) * r, Math.sin(a) * r).stroke({ color: this.style.ink, width: r * 0.035 })
        .moveTo(Math.cos(a) * r * 0.3, Math.sin(a) * r * 0.3).lineTo(Math.cos(a) * r, Math.sin(a) * r).stroke({ color: this.gold ? '#fff0b8' : '#c8a24a', width: r * 0.014 });
      this.disc.addChild(spoke);
    }
    // the labels, big, along the radius
    segments.forEach((segment, i) => {
      const mega = segment.name === 'MEGA';
      // light lettering with a dark outline on every plate (dark ink on the shaded jackpot plates was hard to read)
      const ink = segment.name ? (mega ? '#ffe9a0' : '#ffffff') : look.text[i % look.text.length];
      const words = new Text({ text: segmentText(segment), anchor: 0.5, resolution: textQuality(),
        style: { fontFamily: typeface.family, fontWeight: typeface.weight as 'normal', fontSize: Math.round(r * (segment.name ? 0.13 : 0.18)), fill: ink,
          stroke: { color: '#000000', width: r * 0.035, join: 'round' } } });
      const mid = -Math.PI / 2 + i * step;
      words.position.set(Math.cos(mid) * r * 0.66, Math.sin(mid) * r * 0.66);
      words.rotation = mid + Math.PI / 2;
      if (words.width > r * 0.52) words.scale.set(r * 0.52 / words.width);
      this.disc.addChild(words);
    });
  }

  /**
   * A revolver's cylinder: gunmetal with flutes between the chambers, a cartridge in every chamber with its prize
   * stamped on the head (brass; nickel for MINI, copper for MAJOR, red with a gold ring for MEGA), the ejector star in
   * the middle. Gold-plated for a MEGA SPIN.
   */
  private drawRevolver(r: number, step: number, segments: Segment[]): void {
    const gold = this.gold;
    const body = new FillGradient({ type: 'radial', center: { x: 0.42, y: 0.38 }, innerRadius: 0, outerCenter: { x: 0.5, y: 0.5 }, outerRadius: 0.5,
      textureSpace: 'local', colorStops: gold
        ? [{ offset: 0, color: '#fff1b8' }, { offset: 0.5, color: '#d9a93a' }, { offset: 1, color: '#6e5216' }]
        : [{ offset: 0, color: '#8a939c' }, { offset: 0.5, color: '#454c55' }, { offset: 1, color: '#1b1f24' }] });
    const cylinder = new Graphics().circle(0, 0, r * 0.99).fill(body).stroke({ color: '#0b0d10', width: r * 0.03 })
      .circle(0, 0, r * 0.93).stroke({ color: gold ? '#fff3b0' : '#9aa3ac', width: r * 0.01, alpha: 0.5 });
    this.disc.addChild(cylinder);
    // flutes: long cuts in the cylinder between the chambers
    for (let i = 0; i < segments.length; i++) {
      const a = -Math.PI / 2 + (i + 0.5) * step;
      const flute = new Graphics().ellipse(0, 0, r * 0.07, r * 0.2).fill(gold ? '#7a5a14' : '#15181c')
        .ellipse(r * 0.015, -r * 0.03, r * 0.035, r * 0.12).fill({ color: '#ffffff', alpha: 0.08 });
      flute.position.set(Math.cos(a) * r * 0.86, Math.sin(a) * r * 0.86);
      flute.rotation = a + Math.PI / 2;
      this.disc.addChild(flute);
    }
    // the chambers, each with its cartridge
    const metals: Record<string, [string, string, string]> = {
      plain: ['#f3dc8e', '#c8a24a', '#6e5216'], MINI: ['#f2f4f6', '#b8bfc6', '#5c636b'],
      MAJOR: ['#f0b38a', '#c06a33', '#5c2a0e'], MEGA: ['#ff8a7a', '#c1121f', '#4a060a'],
    };
    segments.forEach((segment, i) => {
      const a = -Math.PI / 2 + i * step;
      const [hi, mid, lo] = metals[segment.name ?? 'plain'] ?? metals.plain;
      const head = new FillGradient({ type: 'radial', center: { x: 0.38, y: 0.32 }, innerRadius: 0, outerCenter: { x: 0.5, y: 0.5 },
        outerRadius: 0.5, textureSpace: 'local', colorStops: [{ offset: 0, color: hi }, { offset: 0.6, color: mid }, { offset: 1, color: lo }] });
      const chamber = new Graphics()
        .circle(0, 0, r * 0.215).fill('#07080a')
        .circle(0, 0, r * 0.185).fill(head).stroke({ color: lo, width: r * 0.012 })
        .circle(0, 0, r * 0.15).stroke({ color: '#000000', width: r * 0.008, alpha: 0.35 })
        .circle(0, r * 0.1, r * 0.035).fill(lo).circle(0, r * 0.1, r * 0.022).fill(mid);   // the primer
      if (segment.name === 'MEGA') chamber.circle(0, 0, r * 0.2).stroke({ color: '#ffd76a', width: r * 0.02 });
      chamber.position.set(Math.cos(a) * r * 0.6, Math.sin(a) * r * 0.6);
      chamber.rotation = a + Math.PI / 2;
      const words = new Text({ text: segmentText(segment), anchor: 0.5, resolution: textQuality(),
        style: { fontFamily: typeface.family, fontWeight: typeface.weight as 'normal', fontSize: Math.round(r * (segment.name ? 0.085 : 0.11)),
          fill: segment.name === 'MEGA' ? '#ffe9a0' : '#1a1206', stroke: segment.name === 'MEGA' ? { color: '#000000', width: r * 0.02 } : undefined } });
      words.y = -r * 0.03;
      if (words.width > r * 0.3) words.scale.set(r * 0.3 / words.width);
      chamber.addChild(words);
      this.disc.addChild(chamber);
    });
    // the ejector star in the middle
    const star: number[] = [];
    const points = segments.length;
    for (let i = 0; i < points * 2; i++) {
      const a = -Math.PI / 2 + i * Math.PI / points;
      const rr = r * (i % 2 ? 0.16 : 0.24);
      star.push(Math.cos(a) * rr, Math.sin(a) * rr);
    }
    this.disc.addChild(new Graphics().circle(0, 0, r * 0.3).fill(gold ? '#8a6a1c' : '#1b1f24')
      .poly(star).fill(gold ? '#f3dc8e' : '#6b737c').stroke({ color: '#0b0d10', width: r * 0.012 })
      .circle(0, 0, r * 0.07).fill(gold ? '#fff1b8' : '#9aa3ac').stroke({ color: '#0b0d10', width: r * 0.01 }));
  }
}
