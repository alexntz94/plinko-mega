// Plinko Mega's fortune wheel: always on screen under the board, its top half showing, with the jackpot display and
// the SPINS counter above it. A ball in a SPIN slot turns it (the server has already decided the segment); a full meter
// turns it gold for a MEGA SPIN. A win ends in a golden burst with the amount counting up.

import { AnimatedSprite, BlurFilter, Container, FillGradient, Graphics, Rectangle, Sprite, Text, Texture } from 'pixi.js';
import { gsap } from 'gsap';
import { label, textQuality, typeface, fit } from './ui';
import { Particles, calm, kindBlast, photoBlast, shake } from './effects';
import { counting, goldText, mix, shade } from './paint';
import type { Segment } from './api';
import type { GachaArt, RevolverArt, TeslaPlate, WheelLook, WheelStyle } from './theme';
import { chamberLayout, spinTarget } from './revolver';

/** A revolver from pictures (theme wheel.revolverArt): its textures and its measured geometry. */
export interface PhotoRevolver {
  cylinder: Texture; dial: Texture; flash: Texture;
  cartridges: { plain: Texture; MINI: Texture; MAJOR: Texture; MEGA: Texture };
  spec: RevolverArt;
}

/** A tesla ring's textures (theme wheel.tesla): an energy cell per kind of prize, the coil's plasma, a win's flash. */
export interface TeslaRing {
  /** a bolt hopping round the cells, or the tide filling the sockets (see theme TeslaArt) */
  motion: 'bolt' | 'tide';
  cells: { plain: Texture; MINI: Texture; MAJOR: Texture; MEGA: Texture };
  orb: Texture; flash: Texture;
  /** the ring's own pictures and their measured geometry, or null: drawn in code */
  plate: { plate: Texture; coil: Texture; spec: TeslaPlate } | null;
}

/** A chest pick's textures (theme wheel.chests): see ChestArt. */
export interface ChestPick {
  closed: Texture; open: Texture; goldClosed: Texture; goldOpen: Texture; shelf: Texture; glow: Texture; burst: Texture;
  plank: number; span: [number, number];
  tokens: { plain: Texture; MINI: Texture; MAJOR: Texture; MEGA: Texture };
  doors: { arches: number[]; archWidth: number; archTop: number; archBottom: number;
    phone: { wall: Texture; arches: number[] }[] | null; phoneArchWidth: number; phoneGap: number } | null;
}

/** A gacha machine's textures (theme wheel.gacha), and its measured geometry (see GachaArt). */
export interface GachaPick {
  machine: Texture; crank: Texture; open: Texture; burst: Texture;
  capsules: { plain: Texture; MINI: Texture; MAJOR: Texture; MEGA: Texture };
  tokens: { plain: Texture; MINI: Texture; MAJOR: Texture; MEGA: Texture };
  spec: GachaArt;
}

/** A chest on the shelf: the prize it holds (the segment's index), the slot it stands in now. */
interface ChestView { chest: Sprite; glow: Sprite; token: Container; slot: number; segment: number;
  /** the shelf line it stands on */
  feet: number;
  /** a door's doorway (it sinks out of it when it opens), its height */
  arch?: Graphics; archH?: number }

/** A cell of the tesla ring: where it sits round the coil (the first at the top), what lights up, its own scale. */
interface TeslaNode { x: number; y: number; cell: Sprite; halo: Graphics; base: number;
  /** the tide ring's water welling up in the socket (scale 0: dry, 1: full) */
  water: Graphics }

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
  /** The tesla ring: its cells, the cell the bolt is on, the bolt, the plasma in the coil, the time to the next idle spark. */
  private nodes: TeslaNode[] = [];
  private lit = 0;
  private readonly bolt = new Graphics();
  private orb: Sprite | null = null;
  private idleSpark = 1;
  /**
   * The chest pick: its chests, where each slot is (x and the shelf line it stands on), the top shelf's line, the
   * bottom shelf's (the same on one shelf), a chest's size, the chest that opened.
   */
  private chests: ChestView[] = [];
  private chestSlots: { x: number; y: number }[] = [];
  private chestFeet = 0;
  private chestLow = 0;
  private chestSize = 0;
  /** how far under the (lower) shelf's line the win is counted */
  private chestBelow = 0;
  private chestOpen: ChestView | null = null;
  /** The gacha machine's parts, and where the last capsule popped (null: none yet). */
  private gachaParts: { machine: Sprite; crank: Sprite; capsule: Sprite; opened: Sprite; token: Container } | null = null;
  private gachaAt: { x: number; y: number } | null = null;
  private get words(): [string, string] {
    return this.art.words ?? ['BOOM!', 'KABOOM!'];
  }

  /** Each segment edge that passes the pointer (for the ticking sound). */
  onTick: (() => void) | null = null;
  /** A win landing on the wheel (big: a jackpot or a MEGA SPIN), for its sound. */
  onWin: ((big: boolean) => void) | null = null;

  constructor(private readonly style: WheelStyle, private readonly hub: Texture | null, private readonly finger: Texture | null,
              private readonly format: (value: number) => string, private readonly panel = '#2a1747',
              private readonly art: { bezel: Texture | null; glass: Texture | null; explode?: boolean; photo?: PhotoRevolver | null;
                tesla?: TeslaRing | null;
                chests?: ChestPick | null;
                gacha?: GachaPick | null;
                /** what a win shouts: [a win, a jackpot] */
                words?: [string, string] } = { bezel: null, glass: null }) {
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
    this.bolt.blendMode = 'add';
    this.addChild(back, this.mask_, this.base, this.pointer, this.header, this.bolt, this.sparks, this.burst);
    back.mask = this.mask_;
    // a tap hurries a turning wheel along (the segment is already decided)
    this.disc.eventMode = 'static';
    this.disc.on('pointertap', () => { if (this.spin?.isActive()) this.spin.timeScale(4); });
  }

  /** The wheel at (x, y) = the disc's centre, radius r; only the part above y + r * 0.12 shows. */
  /** How wide a chest pick's shelf may be (what is on screen across), and whether the screen is tall (two shelves). */
  setRoom(width: number, tall = false, height = Infinity): void {
    this.room = width;
    this.tall = tall;
    this.tallRoom = height;
  }
  private tallRoom = Infinity;
  private room = Infinity;
  private tall = false;

  place(x: number, y: number, r: number): void {
    this.radius = r;
    this.position.set(x, y);
    // the photo revolver shows whole (it pops up over the board); a wheel only above its base
    // (a chest pick's shelves may reach far: on a phone they stand over the console too)
    if (this.art.chests) this.mask_.clear().rect(-4000, -4000, 8000, 8000).fill('#ffffff');
    else if (this.popsUp) this.mask_.clear().rect(-r * 3, -r * 3, r * 6, r * 6).fill('#ffffff');
    else this.mask_.clear().rect(-r * 1.4, -r * 1.4, r * 2.8, r * 1.5).fill('#ffffff');
    // the base the wheel turns in: a dark plinth with a gold rim across the cut
    this.base.clear()
      .roundRect(-r * 1.32, r * 0.04, r * 2.64, r * 0.34, r * 0.08).fill({ color: '#000000', alpha: 0.5 })
      .roundRect(-r * 1.3, 0, r * 2.6, r * 0.32, r * 0.08).fill(shade(mix(this.panel, '#ffffff', 0.08), mix(this.panel, '#000000', 0.7))).stroke({ color: this.style.title, width: 3 })
      .moveTo(-r * 1.2, r * 0.07).lineTo(r * 1.2, r * 0.07).stroke({ color: '#ffffff', width: 1, alpha: 0.25 });
    if (this.popsUp) this.base.clear();
    this.drawHeader();
    this.draw();
  }

  /** A revolver from pictures or a tesla ring: shown whole over the board (not the top half of a wheel). */
  private get popsUp(): boolean {
    return !!(this.art.photo || this.art.tesla || this.art.chests || this.art.gacha);
  }

  /** The chest pick, when the theme has one. */
  private chestPick(segments = this.segments): ChestPick | null {
    return this.art.chests && segments.length >= 2 ? this.art.chests : null;
  }

  /** The tesla ring, when the theme has one. */
  private tesla(segments = this.segments): TeslaRing | null {
    return this.art.tesla && segments.length >= 3 ? this.art.tesla : null;
  }

  /** The photo revolver, when the theme has one with a chamber per prize. */
  private photo(segments = this.segments): PhotoRevolver | null {
    const photo = this.art.photo;
    return photo && photo.spec.chambers.length === segments.length ? photo : null;
  }


  /** Every frame: the marquee lights chase round the rim, and the glow breathes. */
  update(deltaMs: number): void {
    if (this.nodes.length) { this.teslaIdle(deltaMs); return; }
    if (this.chests.length) { this.chestIdle(deltaMs); return; }
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
    if (this.art.chests) this.drawHeader();   // the bar sits over the chests, whose size depends on how many there are
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
    // other prizes than the wheel's (a bonus played on it, as Tomb Raid on the tomb doors): drawn for this turn
    const other = segments !== this.segments && !options.gold;
    if (options.gold || wasGold || other) this.draw(segments);
    this.jackpotTimer?.pause();
    this.jackpotName.text = options.title;
    this.jackpotAmount.text = options.base !== undefined ? `${this.format(options.base)} × ?` : options.gold ? 'MEGA SPIN' : 'SPIN!';
    fit(this.jackpotAmount, this.radius * 0.9);
    fit(this.jackpotName, this.radius * 0.38);
    fit(this.spinsText, this.radius * 0.38);
    const ring = this.tesla(segments);
    if (this.art.gacha && this.gachaParts) await this.gachaTurn(segments, index);
    else if (this.chestPick(segments)?.doors) await this.doorTurn(segments, index);
    else if (this.chestPick(segments)) await this.chestTurn(segments, index);
    else if (ring?.motion === 'tide') await this.tideTurn(segments, index);
    else if (ring) await this.teslaTurn(segments, index);
    else await this.turnDisc(segments, index);
    const segment = segments[index];
    this.jackpotName.text = segment.name ? `${segment.name} JACKPOT` : options.title;
    // a jackpot is called by its name (MAJOR JACKPOT, then its win), a plain chamber shows its sum
    this.jackpotAmount.text = segment.name ? `${segment.name}!`
      : options.base !== undefined ? `${this.format(options.base)} × ${segment.multiplier}` : segmentText(segment);
    fit(this.jackpotAmount, this.radius * 0.9);
    fit(this.jackpotName, this.radius * 0.38);
    fit(this.spinsText, this.radius * 0.38);
    await this.celebrate(win, !!segment.name || options.gold);
    if (options.gold || other) {
      this.gold = false;
      this.draw();
    }
    this.cycleJackpots();
  }

  /** A wheel or a revolver turns to segment `index`. */
  private async turnDisc(segments: Segment[], index: number): Promise<void> {
    const step = Math.PI * 2 / segments.length;
    // never the same stop twice: how many turns, how fast, and how many chambers click in one by one at the end
    const turns = calm ? 1 : this.hurried ? 2 : 2 + Math.floor(Math.random() * 3);
    // a drawn revolver clicks its last chambers in; the photo revolver turns smoothly and stops dead
    const clicks = calm || this.hurried || this.photo(segments) ? 0 : 1 + Math.floor(Math.random() * 3);
    const start = this.disc.rotation % (Math.PI * 2);
    this.disc.rotation = start;   // where the last spin left it (any angle)
    // a revolver stops dead on a chamber; a wheel anywhere within its segment
    const offset = this.style.revolver ? 0 : random(-0.3, 0.3) * step;
    // the photo revolver: its chamber stops exactly under the hammer (the chambers are not quite evenly spaced)
    const photo = this.photo(segments);
    const stop = photo ? chamberLayout(photo.spec, this.radius)[index].stop : -index * step;
    const target = spinTarget(start, stop + offset, turns);
    let lastTick = Math.floor(this.disc.rotation / step);
    const tick = () => {
      const now = Math.floor(this.disc.rotation / step);
      if (now === lastTick) return;
      lastTick = now;
      this.onTick?.();
      gsap.fromTo(this.pointer, { rotation: 0.25 }, { rotation: 0, duration: 0.12, overwrite: true });
    };
    // the rainbow ring lights up behind the wheel while it turns
    this.rainbow.visible = false;   // no rainbow ring round any wheel (the user asked for none)
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
  }

  /**
   * The tesla ring: a bolt from the coil hops round the cells, fast, then slower and slower (a relay click a hop), and
   * stops on the prize; a last strike lights it. A tap hurries it.
   */
  private async teslaTurn(segments: Segment[], index: number): Promise<void> {
    const n = this.nodes.length;
    if (n !== segments.length) return;
    const loops = calm ? 0 : this.hurried ? 1 : 2 + Math.floor(Math.random() * 2);
    const from = this.lit % n;
    const hops = loops * n + (((index - from) % n) + n) % n;
    this.nodes.forEach(node => { node.halo.alpha = 0; });
    let at = -1;
    const hop = { k: 0 };
    const show = () => {
      const k = Math.min(Math.floor(hop.k), hops);
      const node = this.nodes[(from + k) % n];
      if (k !== at) {
        if (at >= 0) this.nodes[(from + at) % n].halo.alpha = 0;
        at = k;
        node.halo.alpha = 1;
        gsap.fromTo(node.cell.scale, { x: node.base * 1.12, y: node.base * 1.12 }, { x: node.base, y: node.base, duration: 0.12, overwrite: true });
        if (k > 0) this.onTick?.();
      }
      this.drawBolt(node.x, node.y, 1);
    };
    show();
    await new Promise<void>(resolve => {
      const spin = gsap.timeline({ onComplete: resolve });
      this.spin = spin;
      // the bolt crackles all the way (redrawn every frame) and slows down like a wheel running down
      spin.to(hop, { k: hops + 0.999, duration: calm ? 0.8 : this.hurried ? 1.3 : random(2.2, 2.9), ease: 'power3.out', onUpdate: show });
    });
    this.lit = index;
    const node = this.nodes[index];
    // the strike: the bolt flares onto the cell and dies, the theme's arc from coil to cell, a spark on the cell
    const strike = { w: 2.2 };
    gsap.to(strike, { w: 0, duration: calm ? 0.2 : 0.5, ease: 'power2.in', onUpdate: () => this.drawBolt(node.x, node.y, strike.w),
      onComplete: () => { this.bolt.clear(); } });
    if (!calm) {
      kindBlast(this.burst, 'arc', node.x / 2, node.y / 2, Math.hypot(node.x, node.y) / 0.8, Math.atan2(node.y, node.x));
      kindBlast(this.burst, 'small', node.x, node.y, this.radius * 0.6);
    }
    gsap.fromTo(node.cell.scale, { x: node.base * 1.35, y: node.base * 1.35 }, { x: node.base, y: node.base, duration: 0.35, ease: 'back.out(3)', overwrite: true });
  }

  /**
   * The tide ring: water runs round the ring from socket to socket, each one welling up as the tide reaches it and
   * draining behind it, fast at first, then slower and slower (a marimba note a socket), and stops on the prize: its
   * socket fills to the brim and overflows in a splash. A tap hurries it.
   */
  private async tideTurn(segments: Segment[], index: number): Promise<void> {
    const n = this.nodes.length;
    if (n !== segments.length) return;
    const loops = calm ? 0 : this.hurried ? 1 : 2 + Math.floor(Math.random() * 2);
    const from = this.lit % n;
    const hops = loops * n + (((index - from) % n) + n) % n;
    this.nodes.forEach(node => { node.halo.alpha = 0; gsap.to(node.water.scale, { x: 0, y: 0, duration: 0.2, overwrite: true }); });
    let at = -1;
    const hop = { k: 0 };
    const show = () => {
      const k = Math.min(Math.floor(hop.k), hops);
      if (k !== at) {
        at = k;
        const node = this.nodes[(from + k) % n];
        // the tide reaches this socket: it wells up; the one it left drains as the water runs on
        gsap.to(node.water.scale, { x: 1, y: 1, duration: 0.14, ease: 'back.out(2)', overwrite: true });
        if (k > 0) {
          const left = this.nodes[(from + k - 1) % n];
          gsap.to(left.water.scale, { x: 0, y: 0, duration: 0.35, ease: 'power1.in', overwrite: true });
          this.onTick?.();
        }
      }
      // the stream between the socket it left and the one it is filling, thinning as it goes
      const left = this.nodes[(from + Math.max(0, k - 1)) % n], to = this.nodes[(from + k) % n];
      this.drawStream(left, to, k > 0 ? Math.min(1, hop.k - k + 0.4) : 0);
    };
    show();
    await new Promise<void>(resolve => {
      const spin = gsap.timeline({ onComplete: resolve });
      this.spin = spin;
      spin.to(hop, { k: hops + 0.999, duration: calm ? 0.8 : this.hurried ? 1.3 : random(2.2, 2.9), ease: 'power3.out', onUpdate: show });
    });
    this.bolt.clear();
    this.lit = index;
    const node = this.nodes[index];
    // it overflows: the socket brims over, a splash out of it, ripples running out
    gsap.timeline()
      .to(node.water.scale, { x: 1.25, y: 1.25, duration: 0.18, ease: 'power2.out', overwrite: true })
      .to(node.water.scale, { x: 1, y: 1, duration: 0.3, ease: 'power1.inOut' });
    node.halo.alpha = 1;
    if (!calm) {
      kindBlast(this.burst, 'impact', node.x, node.y + this.radius * 0.12, this.radius * 0.9);
      this.ripple(node, 1);
    }
    gsap.fromTo(node.cell.scale, { x: node.base * 1.25, y: node.base * 1.25 }, { x: node.base, y: node.base, duration: 0.4, ease: 'back.out(3)', overwrite: true });
  }

  /** The water running round the ring from one socket to the next: a curving turquoise stream with a white crest. */
  private drawStream(a: TeslaNode, b: TeslaNode, weight: number): void {
    this.bolt.clear();
    if (weight <= 0) return;
    const r = this.radius;
    const colour = this.gold ? '#ffd76a' : this.style.title;
    const a0 = Math.atan2(a.y, a.x), d0 = Math.hypot(a.x, a.y);
    let a1 = Math.atan2(b.y, b.x);
    const d1 = Math.hypot(b.x, b.y);
    while (a1 < a0) a1 += Math.PI * 2;   // always clockwise, the way the tide runs
    for (const [width, c, alpha] of [[r * 0.1, colour, 0.3], [r * 0.045, colour, 0.8], [r * 0.015, '#ffffff', 0.9]] as const) {
      for (let k = 0; k <= 12; k++) {
        const t = k / 12, ang = a0 + (a1 - a0) * t, d = d0 + (d1 - d0) * t + Math.sin(t * Math.PI * 3 + this.chase * 8) * r * 0.015;
        if (k === 0) this.bolt.moveTo(Math.cos(ang) * d, Math.sin(ang) * d);
        else this.bolt.lineTo(Math.cos(ang) * d, Math.sin(ang) * d);
      }
      this.bolt.stroke({ color: c, width: width * weight, alpha, cap: 'round', join: 'round' });
    }
    this.chase += 0.016;
  }

  /** Rings of water running out from a socket (strength 0.5: an idle ripple; 1: an overflow). */
  private ripple(node: TeslaNode, strength: number): void {
    const size = node.cell.width;
    for (let k = 0; k < (strength >= 1 ? 3 : 1); k++) {
      const ring = new Graphics().circle(0, 0, size * 0.42).stroke({ color: '#ffffff', width: size * 0.05, alpha: 0.8 });
      ring.position.set(node.x, node.y);
      this.burst.addChild(ring);
      gsap.timeline({ delay: k * 0.15, onComplete: () => ring.destroy() })
        .fromTo(ring.scale, { x: 0.6, y: 0.6 }, { x: 1 + strength * 0.9, y: 1 + strength * 0.9, duration: 0.7, ease: 'power2.out' }, 0)
        .fromTo(ring, { alpha: strength }, { alpha: 0, duration: 0.7, ease: 'power1.in' }, 0);
    }
  }

  /** The bolt from the coil to (x, y): a glow, a core and a white-hot middle, jagged differently every frame. */
  private drawBolt(x: number, y: number, weight: number): void {
    this.bolt.clear();
    if (weight <= 0) return;
    const r = this.radius;
    const colour = this.gold ? '#ffd76a' : this.style.title;
    const d = Math.hypot(x, y), nx = -y / d, ny = x / d;
    const from = r * 0.16 / d;   // out of the top of the coil
    for (const [width, c, alpha] of [[r * 0.1, colour, 0.25], [r * 0.04, colour, 0.75], [r * 0.016, '#ffffff', 1]] as const) {
      this.bolt.moveTo(x * from, y * from);
      for (let k = 1; k < 7; k++) {
        const t = from + (1 - from) * k / 7, j = (Math.random() - 0.5) * r * 0.12;
        this.bolt.lineTo(x * t + nx * j, y * t + ny * j);
      }
      this.bolt.lineTo(x, y).stroke({ color: c, width: width * weight, alpha, cap: 'round', join: 'round' });
    }
  }

  /** Between spins the coil's plasma breathes and now and then spits a short spark at a cell. */
  private teslaIdle(deltaMs: number): void {
    if (calm || this.spin?.isActive()) return;
    const dt = Math.min(deltaMs, 50) / 1000;
    this.chase += dt;
    const tide = this.tesla()?.motion === 'tide';
    // the coil's plasma breathes; the tide's pool in the middle turns slowly
    if (this.orb) {
      this.orb.alpha = tide ? 0.45 + 0.15 * Math.sin(this.chase * 1.5) : 0.75 + 0.25 * Math.sin(this.chase * 3);
      if (tide) this.orb.rotation += dt * 0.3;
    }
    this.idleSpark -= dt;
    if (this.idleSpark > 0 || !this.visible) return;
    this.idleSpark = random(1.2, 2.6);
    const node = this.nodes[Math.floor(Math.random() * this.nodes.length)];
    if (tide) { this.ripple(node, 0.5); return; }
    const zap = { w: 0.6 };
    let frames = 0;
    gsap.to(zap, { w: 0, duration: 0.22, ease: 'power1.in', onUpdate: () => {
      // a flicker, short of the cell: on and off frame by frame
      if (this.spin?.isActive()) return;
      if (frames++ % 2 === 0) this.drawBolt(node.x * 0.8, node.y * 0.8, zap.w); else this.bolt.clear();
    }, onComplete: () => { if (!this.spin?.isActive()) this.bolt.clear(); } });
  }

  /**
   * The tesla ring: the theme's plate with an energy cell in each socket and the coil in its well (the middle of the
   * well is the ring's centre, where the bolt starts), or, without pictures, the same drawn: a dark plate in a copper
   * rim, a conduit ring through the cells, rails from the coil to each cell. A cell per prize (copper; steel for MINI,
   * cyan for MAJOR, red for MEGA) with its prize on it, plasma in the coil. Gold for a MEGA SPIN.
   */
  private drawTesla(r: number, segments: Segment[], art: TeslaRing): void {
    const glow = this.gold ? '#ffd76a' : this.style.title;
    const pictures = art.plate && art.plate.spec.sockets.length === segments.length ? art.plate : null;
    let places: { x: number; y: number }[], size: number, dome: number;
    if (pictures) {
      const { spec } = pictures;
      const s = r / (spec.radius * pictures.plate.width), w = pictures.plate.width * s;   // plate picture to screen
      const [wx, wy] = spec.well;
      this.disc.addChild(new Graphics().circle((spec.centre[0] - wx) * w, (spec.centre[1] - wy) * w, r * 1.07).fill({ color: glow, alpha: 0.12 }));
      const plate = new Sprite({ texture: pictures.plate, anchor: { x: wx, y: wy } });
      plate.scale.set(s);
      if (this.gold) plate.tint = '#ffcf6a';
      const coil = new Sprite({ texture: pictures.coil, anchor: 0.5 });
      const coilR = spec.wellRadius * w * 1.08;
      coil.scale.set(coilR / (spec.coilRadius * pictures.coil.width));
      this.disc.addChild(plate, coil);
      places = spec.sockets.map(([x, y]) => ({ x: (x - wx) * w, y: (y - wy) * w }));
      size = spec.socket * w * 1.2;
      dome = coilR * spec.dome * 2.1;
    } else {
      const step = Math.PI * 2 / segments.length, ring = r * 0.74;
      const rim = this.gold ? '#ffd76a' : this.style.rim;
      size = r * 0.46;
      dome = r * 0.4;
      places = segments.map((_, i) => ({ x: Math.cos(-Math.PI / 2 + i * step) * ring, y: Math.sin(-Math.PI / 2 + i * step) * ring }));
      this.disc.addChild(new Graphics()
        .circle(0, 0, r * 1.07).fill({ color: glow, alpha: 0.1 })
        .circle(0, 0, r).fill('#0d1216').stroke({ color: rim, width: r * 0.05 })
        .circle(0, 0, r * 0.95).stroke({ color: '#000000', width: r * 0.02, alpha: 0.6 })
        .circle(0, 0, ring).stroke({ color: '#05080a', width: r * 0.09 })
        .circle(0, 0, ring).stroke({ color: glow, width: r * 0.012, alpha: 0.45 }));
      const rails = new Graphics();
      for (const { x, y } of places) {
        const d = Math.hypot(x, y), c = x / d, s = y / d, out = ring - size * 0.45;
        rails.moveTo(c * r * 0.24, s * r * 0.24).lineTo(c * out, s * out).stroke({ color: '#05080a', width: r * 0.05 })
          .moveTo(c * r * 0.24, s * r * 0.24).lineTo(c * out, s * out).stroke({ color: rim, width: r * 0.012, alpha: 0.7 });
      }
      // the coil: copper windings round a dark core
      const coil = new Graphics().circle(0, 0, r * 0.27).fill('#05080a');
      for (let k = 0; k < 5; k++) coil.circle(0, 0, r * (0.25 - k * 0.022)).stroke({ color: k % 2 ? '#6e3a1a' : '#c47a3e', width: r * 0.016 });
      coil.circle(0, 0, r * 0.14).fill('#0b1a22').stroke({ color: glow, width: r * 0.012 });
      this.disc.addChild(rails, coil);
    }
    // the plasma in the coil's glass
    const orb = new Sprite({ texture: art.orb, anchor: 0.5 });
    orb.width = orb.height = dome;
    orb.blendMode = 'add';
    if (this.gold) orb.tint = '#ffd76a';
    this.orb = orb;
    this.disc.addChild(orb);
    this.nodes = segments.map((segment, i) => {
      const { x, y } = places[i];
      const halo = new Graphics().circle(0, 0, size * 0.78).fill({ color: glow, alpha: 0.16 }).circle(0, 0, size * 0.62).fill({ color: glow, alpha: 0.3 });
      halo.blendMode = 'add';
      halo.position.set(x, y);
      halo.alpha = 0;
      const kind = segment.name === 'MINI' || segment.name === 'MAJOR' || segment.name === 'MEGA' ? segment.name : 'plain';
      const cell = new Sprite({ texture: art.cells[kind], anchor: 0.5 });
      cell.width = cell.height = size;
      cell.position.set(x, y);
      const words = new Text({ text: segmentText(segment), anchor: 0.5, resolution: textQuality(),
        style: { fontFamily: typeface.family, fontWeight: typeface.weight as 'normal', fontSize: Math.round(size * (segment.name ? 0.22 : 0.26)),
          fill: segment.name ? '#ffd76a' : '#fff6dc', stroke: { color: '#000000', width: size * 0.065, join: 'round' } } });
      words.position.set(x, y);
      if (words.width > size * 0.8) words.scale.set(size * 0.8 / words.width);
      // the tide's water in the socket, over the token but under its prize (still readable through it)
      const water = new Graphics()
        .circle(0, 0, size * 0.4).fill({ color: glow, alpha: 0.55 })
        .circle(0, 0, size * 0.3).fill({ color: '#ffffff', alpha: 0.18 })
        .circle(0, 0, size * 0.4).stroke({ color: '#ffffff', width: size * 0.04, alpha: 0.7 });
      water.position.set(x, y);
      water.scale.set(0);
      this.disc.addChild(halo, cell, water, words);
      return { x, y, cell, halo, base: cell.scale.y, water };
    });
  }

  /**
   * The chest pick: a chest per prize on the shelf (wood; gold for a MEGA SPIN), its prize token hidden inside, and a
   * glow behind each for when it is picked. The shelf's plank is the line the chests stand on.
   */
  private drawChests(segments: Segment[], art: ChestPick): void {
    const { w, h, size, gap, perRow } = this.chestGeometry(segments.length);
    const feet = h * 0.1;
    // one shelf, or (a tall screen) two: the first half of the chests on the upper one
    const rows = this.tall ? 2 : 1;
    const x0 = (art.span[0] - 0.5) * w, x1 = (art.span[1] - 0.5) * w;
    this.chestSlots = [];
    for (let row = 0; row < rows; row++) {
      const y = feet + row * gap;
      const shelf = new Sprite({ texture: art.shelf, anchor: { x: 0.5, y: art.plank } });
      shelf.width = w;
      shelf.height = h;
      shelf.y = y;
      this.disc.addChild(shelf);
      const count = row === 0 ? perRow : segments.length - perRow;
      const slot = (x1 - x0) / perRow;
      for (let i = 0; i < count; i++) this.chestSlots.push({ x: (i - (count - 1) / 2) * slot, y });
    }
    this.chestFeet = feet;
    this.chestLow = feet + (rows - 1) * gap;
    this.chestBelow = h * 0.75;
    this.chestSize = size;
    const glows = new Container(), bodies = new Container(), tokens = new Container();
    this.disc.addChild(glows, bodies, tokens);
    this.chests = segments.map((segment, i) => {
      const glow = new Sprite({ texture: art.glow, anchor: 0.5 });
      glow.width = glow.height = size * 1.7;
      glow.blendMode = 'add';
      glow.alpha = 0;
      const chest = new Sprite({ texture: this.gold ? art.goldClosed : art.closed, anchor: { x: 0.5, y: 310 / 320 } });
      chest.width = chest.height = size;
      const kind = segment.name === 'MINI' || segment.name === 'MAJOR' || segment.name === 'MEGA' ? segment.name : 'plain';
      const token = new Container();
      const coin = new Sprite({ texture: art.tokens[kind], anchor: 0.5 });
      coin.width = coin.height = size * 0.66;
      const words = new Text({ text: segmentText(segment), anchor: 0.5, resolution: textQuality(),
        style: { fontFamily: typeface.family, fontWeight: typeface.weight as 'normal', fontSize: Math.round(size * (segment.name ? 0.15 : 0.18)),
          fill: segment.name ? '#ffd76a' : '#fff6dc', stroke: { color: '#000000', width: size * 0.04, join: 'round' } } });
      if (words.width > size * 0.56) words.scale.set(size * 0.56 / words.width);
      token.addChild(coin, words);
      token.alpha = 0;
      const { x, y } = this.chestSlots[i];
      glow.position.set(x, y - size * 0.4);
      chest.position.set(x, y);
      token.position.set(x, y - size * 0.45);
      glows.addChild(glow);
      bodies.addChild(chest);
      tokens.addChild(token);
      return { chest, glow, token, slot: i, segment: i, feet: y };
    });
  }

  /**
   * The shelves' size: a shelf's width and height, a chest's size, the distance between two shelves' lines and how many
   * chests stand on the upper one. One shelf as wide as there is room; on a tall screen two, wider than the screen (their
   * flowered ends may run off it) so that half the chests on each are big enough to tap.
   */
  private chestGeometry(n: number): { w: number; h: number; size: number; gap: number; perRow: number } {
    const art = this.art.chests!;
    const span = art.span[1] - art.span[0];
    const perRow = this.tall ? Math.ceil(n / 2) : n;
    const w = this.tall ? this.room * 0.97 / span : Math.min(this.radius * 6.2, this.room);
    const h = w * art.shelf.height / art.shelf.width;
    const size = w * span / perRow * (this.tall ? 0.95 : 1.1);   // the picture's body is 300 of its 320 pixels
    // the lower shelf clear of the tokens risen out of the upper chests
    return { w, h, size, gap: size * 1.55 + h * 0.5, perRow };
  }

  /** How far above the (upper) shelf's line the prize bar goes: over the tokens risen out of the chests (over the wall). */
  private chestHeader(): number {
    const doors = this.art.chests!.doors;
    if (doors) {
      const { h, rows } = this.doorGeometry();
      return doors.archBottom * h - rows[0].y + this.radius * 0.3;
    }
    const { h, size } = this.chestGeometry(Math.max(this.segments.length, 2));
    return size * 1.9 + this.radius * 0.34 - h * 0.1;
  }

  /** Puts a chest (with its glow and token) at x on the shelf line y. */
  private placeChest(view: ChestView, x: number, y = view.chest.y): void {
    view.chest.position.set(x, y);
    view.glow.position.set(x, y - this.chestSize * 0.4);
    view.token.x = x;
    view.token.y += y - view.feet;
    view.feet = y;
  }

  /**
   * The chest pick: the chests open to show what each holds, the prizes drop in and the lids shut, the chests shuffle
   * (a note a swap), the one holding the prize glows and rattles, bursts open, and its token rises out; the others then
   * open faintly to show what they held. A tap hurries it.
   */
  private async chestTurn(segments: Segment[], index: number): Promise<void> {
    const art = this.art.chests!;
    const n = this.chests.length;
    if (n !== segments.length) return;
    const size = this.chestSize;
    const closed = this.gold ? art.goldClosed : art.closed, open = this.gold ? art.goldOpen : art.open;
    // a token above its chest, and inside it
    const up = (v: ChestView) => v.feet - size * 1.05, inside = (v: ChestView) => v.feet - size * 0.45;
    const tl = gsap.timeline();
    this.spin = tl;
    // the last pick shut, every token back inside
    for (const view of this.chests) {
      view.chest.texture = closed;
      view.chest.alpha = 1;
      view.glow.alpha = 0;
      gsap.killTweensOf([view.token, view.token.scale]);
      view.token.alpha = 0;
      view.token.y = inside(view);
      view.token.scale.set(1);
    }
    // what each holds: lids up, the tokens show above them, then sink back in and the lids shut
    if (!calm && !this.hurried) {
      tl.call(() => { for (const v of this.chests) v.chest.texture = open; this.onTick?.(); });
      for (const v of this.chests) {
        tl.to(v.token, { alpha: 1, y: up(v) + size * 0.12, duration: 0.25, ease: 'back.out(2)' }, '<');
      }
      tl.to({}, { duration: 0.7 });
      for (const v of this.chests) {
        tl.to(v.token, { alpha: 0, y: inside(v), duration: 0.2, ease: 'power2.in' }, '<');
      }
      tl.call(() => { for (const v of this.chests) v.chest.texture = closed; this.onTick?.(); });
      tl.to({}, { duration: 0.15 });
    }
    // the shuffle: pairs of chests swap places, one hopping over the other, quicker and quicker, then slower again
    const swaps = calm ? 0 : this.hurried ? 3 : 6 + Math.floor(Math.random() * 3);
    const where = this.chests.map(v => v.slot);   // the slot each chest ends up in, as the swaps are planned
    for (let s = 0; s < swaps; s++) {
      const a = Math.floor(Math.random() * n);
      let b = Math.floor(Math.random() * (n - 1));
      if (b >= a) b++;
      const va = this.chests[where.indexOf(a)], vb = this.chests[where.indexOf(b)];
      where[where.indexOf(a)] = -1;
      where[where.indexOf(b)] = a;
      where[where.indexOf(-1)] = b;
      const pa = this.chestSlots[a], pb = this.chestSlots[b];
      const pace = 0.2 + 0.1 * Math.abs(s - swaps / 2) / Math.max(1, swaps / 2);
      const hopA = { x: pa.x, y: pa.y, t: 0 }, hopB = { x: pb.x, y: pb.y };
      tl.call(() => this.onTick?.());
      // the one going over hops in an arc; the other slides under it (between shelves too)
      tl.to(hopA, { x: pb.x, y: pb.y, t: 1, duration: pace, ease: 'power1.inOut',
        onUpdate: () => this.placeChest(va, hopA.x, hopA.y - Math.sin(hopA.t * Math.PI) * size * 0.35) });
      tl.to(hopB, { x: pa.x, y: pa.y, duration: pace, ease: 'power1.inOut', onUpdate: () => this.placeChest(vb, hopB.x, hopB.y) }, '<');
      tl.call(() => { this.placeChest(va, pb.x, pb.y); this.placeChest(vb, pa.x, pa.y); });
    }
    this.chests.forEach((v, i) => { v.slot = where[i]; });
    await new Promise<void>(resolve => tl.eventCallback('onComplete', () => resolve()));
    // the game waits for the player to pick a chest (even in autoplay or turbo); the prize the server drew is in
    // whichever is picked: its token changes places with the chest holding it (hidden inside, unseen)
    const pick = await this.chooseChest();
    const holder = this.chests.find(v => v.segment === index)!;
    if (holder !== pick) {
      [pick.token, holder.token] = [holder.token, pick.token];
      [pick.segment, holder.segment] = [holder.segment, pick.segment];
      this.placeChest(pick, pick.chest.x);
      this.placeChest(holder, holder.chest.x);
      pick.token.y = inside(pick);
      holder.token.y = inside(holder);
    }
    // the picked chest glows and rattles
    const rattle = gsap.timeline();
    this.spin = rattle;
    rattle.to(pick.glow, { alpha: 1, duration: 0.25 });
    rattle.to(pick.chest, { rotation: 0.06, duration: 0.05, yoyo: true, repeat: calm ? 1 : 7, ease: 'sine.inOut' }, '<');
    rattle.set(pick.chest, { rotation: 0 });
    await new Promise<void>(resolve => rattle.eventCallback('onComplete', () => resolve()));
    // it bursts open: the lid flies up, light pours out, the token rises
    pick.chest.texture = open;
    this.chestOpen = pick;
    this.onTick?.();
    if (!calm) this.chestBurst(art, pick.chest.x, pick.feet - size * 0.5, size * 1.8);
    gsap.timeline()
      .to(pick.token, { alpha: 1, y: up(pick), duration: 0.35, ease: 'back.out(2)' }, 0)
      .fromTo(pick.token.scale, { x: 0.5, y: 0.5 }, { x: 1.25, y: 1.25, duration: 0.35, ease: 'back.out(2)' }, 0);
    gsap.to(pick.glow.scale, { x: pick.glow.scale.x * 1.2, y: pick.glow.scale.y * 1.2, duration: 0.6, yoyo: true, repeat: 1 });
    // the others show what they held, faintly
    for (const v of this.chests) {
      if (v === pick) continue;
      gsap.delayedCall(calm ? 0 : 0.5, () => {
        v.chest.texture = open;
        v.chest.alpha = 0.55;
        gsap.to(v.token, { alpha: 0.6, y: up(v) + size * 0.2, duration: 0.25 });
        gsap.to(v.token.scale, { x: 0.8, y: 0.8, duration: 0.25 });
      });
    }
  }

  /**
   * The tomb wall's size: its width and height, a door's width (a doorway's), a doorway's height, and its rows: each a
   * run of doorways (their indexes), the part of the wall picture it shows (shares of its width) and the line its doors
   * stand on. One wall; on a phone two (4 doors above, the rest below), each doorway twice as wide.
   */
  private doorGeometry(): { w: number; h: number; doorW: number; archH: number;
    rows: { doors: number[]; x0: number; x1: number; y: number; wall?: Texture; arches?: number[] }[] } {
    const art = this.art.chests!, doors = art.doors!, n = doors.arches.length;
    if (this.tall && doors.phone) {
      // a phone: the shorter walls drawn for it, one under the other, the first (the widest) as wide as the screen
      const first = doors.phone[0].wall;
      // (taller walls need more room between them: the screen's height may make them narrower)
      const fit = this.tallRoom / ((doors.phoneGap + 0.95) * first.height / first.width);
      const w = Math.min(this.room * 1.12, fit), h = w * first.height / first.width;
      const archH = (doors.archBottom - doors.archTop) * h;
      const gap = h * doors.phoneGap;
      const top = (doors.archBottom - 0.5) * h - (doors.phone.length - 1) * gap / 2;
      let next = 0;
      const rows = doors.phone.map((p, k) => {
        const run = p.arches.map(() => next++);
        return { doors: run, x0: 0, x1: 1, y: top + k * gap, wall: p.wall, arches: p.arches };
      });
      return { w, h, doorW: doors.phoneArchWidth * w, archH, rows };
    }
    const margin = doors.archWidth * 0.6;
    const runs = this.tall ? [[...Array(Math.ceil(n / 2)).keys()], [...Array(n).keys()].slice(Math.ceil(n / 2))] : [[...Array(n).keys()]];
    const span = (run: number[]) => doors.arches[run[run.length - 1]] - doors.arches[run[0]] + 2 * margin;
    // a phone: the longest run as wide as the screen; else the whole wall as wide as there is room
    const w = this.tall ? this.room * 0.97 / span(runs[0]) : Math.min(this.radius * 6.2, this.room);
    const h = w * art.shelf.height / art.shelf.width;
    const archH = (doors.archBottom - doors.archTop) * h;
    const gap = h * 0.62;   // the second wall under the first: its top trim just under the first's floor
    const top = (doors.archBottom - 0.5) * h - (runs.length - 1) * gap / 2;
    const rows = runs.map((run, k) => this.tall
      ? { doors: run, x0: doors.arches[run[0]] - margin, x1: doors.arches[run[run.length - 1]] + margin, y: top + k * gap }
      : { doors: run, x0: 0, x1: 1, y: top + k * gap });
    return { w, h, doorW: doors.archWidth * w, archH, rows };
  }

  /**
   * The tomb doors: the wall, a prize token in each doorway (behind its door), the door closing it (stone; gold for a
   * MEGA SPIN), a glow over each for the light that runs across them. A door sinks out of its doorway when it opens.
   */
  private drawDoors(segments: Segment[], art: ChestPick, doors: NonNullable<ChestPick['doors']>): void {
    const { w, h, doorW, archH, rows } = this.doorGeometry();
    const tex = art.shelf;
    this.chestSlots = [];
    for (const row of rows) {
      if (row.wall && row.arches) {
        // a wall drawn for the phone, whole
        const wall = new Sprite({ texture: row.wall, anchor: { x: 0.5, y: doors.archBottom } });
        wall.width = w;
        wall.height = h;
        wall.y = row.y;
        this.disc.addChild(wall);
        row.doors.forEach((d, k) => { this.chestSlots[d] = { x: (row.arches![k] - 0.5) * w, y: row.y }; });
        continue;
      }
      // the part of the wall with this row's doorways, centred
      const fw = tex.frame.width, fh = tex.frame.height;
      const part = new Texture({ source: tex.source, frame: new Rectangle(tex.frame.x + row.x0 * fw, tex.frame.y, (row.x1 - row.x0) * fw, fh) });
      const wall = new Sprite({ texture: part, anchor: { x: 0.5, y: doors.archBottom } });
      wall.width = (row.x1 - row.x0) * w;
      wall.height = h;
      wall.y = row.y;
      this.disc.addChild(wall);
      const mid = (row.x0 + row.x1) / 2;
      for (const d of row.doors) this.chestSlots[d] = { x: (doors.arches[d] - mid) * w, y: row.y };
    }
    const feet = rows[0].y;
    this.chestFeet = feet;
    this.chestLow = rows[rows.length - 1].y;
    this.chestBelow = (1 - doors.archBottom) * h + this.radius * 0.35;
    this.chestSize = doorW;
    const tokens = new Container(), bodies = new Container(), glows = new Container();
    this.disc.addChild(tokens, bodies, glows);
    this.chests = segments.map((segment, i) => {
      const { x, y: feet } = this.chestSlots[i] ?? { x: 0, y: this.chestFeet };
      const kind = segment.name === 'MINI' || segment.name === 'MAJOR' || segment.name === 'MEGA' ? segment.name : 'plain';
      const token = new Container();
      const coin = new Sprite({ texture: art.tokens[kind], anchor: 0.5 });
      coin.width = coin.height = doorW * 0.92;
      const words = new Text({ text: segmentText(segment), anchor: 0.5, resolution: textQuality(),
        style: { fontFamily: typeface.family, fontWeight: typeface.weight as 'normal', fontSize: Math.round(doorW * (segment.name ? 0.2 : 0.26)),
          fill: segment.name ? '#ffd76a' : '#fff6dc', stroke: { color: '#000000', width: doorW * 0.05, join: 'round' } } });
      if (words.width > doorW * 0.8) words.scale.set(doorW * 0.8 / words.width);
      token.addChild(coin, words);
      token.position.set(x, feet - archH * 0.48);
      const door = new Sprite({ texture: this.gold ? art.goldClosed : art.closed, anchor: { x: 0.5, y: 1 } });
      door.width = doorW * 1.04;
      door.height = archH * 1.02;
      door.position.set(x, feet);
      // the doorway: the door sinks out of it and is not seen below the floor
      const arch = new Graphics().rect(x - doorW * 0.6, feet - archH * 1.1, doorW * 1.2, archH * 1.1).fill('#ffffff');
      door.mask = arch;
      const glow = new Sprite({ texture: art.glow, anchor: 0.5 });
      glow.width = glow.height = doorW * 1.8;
      glow.blendMode = 'add';
      glow.alpha = 0;
      glow.position.set(x, feet - archH * 0.5);
      tokens.addChild(token);
      bodies.addChild(door, arch);
      glows.addChild(glow);
      return { chest: door, glow, token, slot: i, segment: i, feet, arch, archH };
    });
  }

  /**
   * The tomb doors: a light runs across the doors and back, slowing, then the game waits for the player to pick a door
   * (the prize the server drew is behind whichever is picked); it shakes, grinds down into the floor in a burst of
   * sand, and the treasure behind it shines out; the other doors then sink halfway to show what they hid.
   */
  private async doorTurn(segments: Segment[], index: number): Promise<void> {
    const art = this.art.chests!;
    const n = this.chests.length;
    if (n !== segments.length) return;
    const closed = this.gold ? art.goldClosed : art.closed;
    for (const v of this.chests) {
      gsap.killTweensOf([v.chest, v.token.scale, v.glow]);
      v.chest.texture = closed;
      v.chest.y = v.feet;
      v.chest.alpha = 1;
      v.glow.alpha = 0;
      v.token.alpha = 1;
      v.token.scale.set(1);
    }
    // the light runs along the doors and back, a tick a door, slowing down
    const tl = gsap.timeline();
    this.spin = tl;
    const order = calm ? [] : [...Array(n).keys(), ...[...Array(n).keys()].reverse().slice(1)];
    order.forEach((k, i) => {
      const v = this.chests[k], pace = 0.06 + 0.1 * (i / Math.max(1, order.length - 1)) ** 2;
      tl.call(() => this.onTick?.());
      tl.to(v.glow, { alpha: 0.9, duration: pace * 0.4 });
      tl.to(v.glow, { alpha: 0, duration: pace * 1.6 }, '>');
    });
    await new Promise<void>(resolve => tl.eventCallback('onComplete', () => resolve()));
    const pick = await this.chooseChest();
    const holder = this.chests.find(v => v.segment === index)!;
    if (holder !== pick) {
      [pick.token, holder.token] = [holder.token, pick.token];
      [pick.segment, holder.segment] = [holder.segment, pick.segment];
      const px = pick.token.x;
      pick.token.x = holder.token.x;
      holder.token.x = px;
    }
    // the picked door glows and shakes, then grinds down into the floor
    const archH = pick.archH ?? this.chestSize * 1.6;
    const open = gsap.timeline();
    this.spin = open;
    open.to(pick.glow, { alpha: 1, duration: 0.25 });
    open.to(pick.chest, { x: pick.chest.x + this.chestSize * 0.03, duration: 0.04, yoyo: true, repeat: calm ? 1 : 9, ease: 'sine.inOut' }, '<');
    open.call(() => {
      this.onTick?.();
      if (!calm) this.chestBurst(art, pick.chest.x, pick.feet - archH * 0.15, this.chestSize * 2.2);
    });
    open.to(pick.chest, { y: pick.feet + archH, duration: calm ? 0.2 : 0.7, ease: 'power2.in' });
    await new Promise<void>(resolve => open.eventCallback('onComplete', () => resolve()));
    this.chestOpen = pick;
    gsap.fromTo(pick.token.scale, { x: 0.8, y: 0.8 }, { x: 1.2, y: 1.2, duration: 0.35, ease: 'back.out(2.5)' });
    // the others sink halfway to show what they hid
    for (const v of this.chests) {
      if (v === pick) continue;
      gsap.to(v.chest, { y: v.feet + (v.archH ?? archH) * 0.6, alpha: 0.7, duration: 0.4, delay: calm ? 0 : 0.5, ease: 'power1.in' });
      gsap.to(v.token, { alpha: 0.55, duration: 0.4, delay: calm ? 0 : 0.5 });
    }
  }

  /**
   * Waits for the player to tap a chest: PICK A CHEST! on the bar, the chests bobbing a little. The tap is caught over
   * the whole row (the chests sit under the spinner's mask, which takes no taps) and goes to the nearest chest.
   */
  private chooseChest(): Promise<ChestView> {
    const said = this.jackpotAmount.text;
    const doors = !!this.art.chests?.doors;
    this.jackpotAmount.text = doors ? 'PICK A DOOR!' : 'PICK A CHEST!';
    fit(this.jackpotAmount, this.radius * 0.9);
    const size = this.chestSize;
    // chests bob; doors in a wall glow in turn instead
    const bobbing = this.chests.map((v, i) => doors
      ? gsap.to(v.glow, { alpha: 0.5, duration: 0.5, yoyo: true, repeat: -1, delay: i * 0.12, ease: 'sine.inOut' })
      : gsap.to(v.chest, { y: v.feet - size * 0.05, duration: 0.45, yoyo: true, repeat: -1, delay: i * 0.07, ease: 'sine.inOut' }));
    const xs = this.chests.map(v => v.chest.x);
    const left = Math.min(...xs) - size / 2, right = Math.max(...xs) + size / 2;
    const catcher = new Container();
    catcher.eventMode = 'static';
    catcher.cursor = 'pointer';
    // (a door is taller than a chest: its whole doorway)
    const tall = doors ? (this.chests[0]?.archH ?? size * 1.6) : size * 1.1;
    catcher.hitArea = new Rectangle(left, this.chestFeet - tall, right - left, this.chestLow - this.chestFeet + tall + size * 0.1);
    this.addChild(catcher);
    // a tap goes to the chest whose middle is nearest
    const mid = (v: ChestView) => v.feet - (v.archH ?? size * 0.8) / 2;
    const near = (p: { x: number; y: number }) => this.chests.reduce((a, b) =>
      (Math.hypot(b.chest.x - p.x, mid(b) - p.y) < Math.hypot(a.chest.x - p.x, mid(a) - p.y) ? b : a));
    return new Promise(resolve => {
      catcher.on('pointertap', event => {
        const view = near(event.getLocalPosition(this));
        bobbing.forEach(b => b.kill());
        for (const v of this.chests) {
          v.chest.y = v.feet;
          if (doors) v.glow.alpha = 0;
        }
        catcher.destroy();
        this.onTick?.();
        this.jackpotAmount.text = said;
        fit(this.jackpotAmount, this.radius * 0.9);
        resolve(view);
      });
    });
  }

  /** Light and sparkles bursting out of an opening chest (the theme's 4x4 sheet). */
  private chestBurst(art: ChestPick, x: number, y: number, width: number): void {
    const cols = 4, w = art.burst.frame.width / cols, h = art.burst.frame.height / cols;
    const frames = Array.from({ length: 16 }, (_, i) => new Texture({ source: art.burst.source,
      frame: new Rectangle(art.burst.frame.x + (i % cols) * w, art.burst.frame.y + Math.floor(i / cols) * h, w, h) }));
    const burst = new AnimatedSprite({ textures: frames, anchor: { x: 0.5, y: 0.75 }, autoUpdate: true });
    burst.scale.set(width / w);
    burst.position.set(x, y + width * 0.2);
    burst.blendMode = 'add';
    burst.loop = false;
    burst.animationSpeed = 16 / (0.9 * 60);
    burst.onComplete = () => { if (!burst.destroyed) burst.destroy(); };
    this.burst.addChild(burst);
    burst.play();
  }

  /** Between picks a chest now and then rattles its lid. */
  private chestIdle(deltaMs: number): void {
    if (calm || this.spin?.isActive() || !this.visible) return;
    this.idleSpark -= Math.min(deltaMs, 50) / 1000;
    if (this.idleSpark > 0) return;
    this.idleSpark = random(1.8, 3.5);
    const view = this.chests[Math.floor(Math.random() * this.chests.length)];
    if (view === this.chestOpen) return;
    gsap.to(view.chest, { rotation: 0.05, duration: 0.06, yoyo: true, repeat: 3, ease: 'sine.inOut', onComplete: () => { view.chest.rotation = 0; } });
  }

  /** The gacha machine's size and where its parts are (centred on the spinner's middle): see GachaArt. */
  private gachaGeometry(): { w: number; h: number; socket: { x: number; y: number }; chute: { x: number; y: number }; capsule: number } {
    const art = this.art.gacha!;
    const h = this.radius * 3.1, w = h * art.machine.width / art.machine.height;
    return {
      w, h,
      socket: { x: (art.spec.socket[0] - 0.5) * w, y: (art.spec.socket[1] - 0.5) * h },
      chute: { x: (art.spec.chute[0] - 0.5) * w, y: (art.spec.chute[1] - 0.5) * h },
      capsule: w * 0.24,
    };
  }

  /**
   * The gacha machine: the machine (gold-tinted for a MEGA SPIN), its crank in the socket, and the capsule, the popped
   * capsule and the prize token, hidden until a turn.
   */
  private drawGacha(art: GachaPick): void {
    const { w, h, socket } = this.gachaGeometry();
    const glow = new Graphics();
    for (let k = 6; k > 0; k--) glow.circle(0, -h * 0.12, w * 0.12 * k).fill({ color: this.gold ? '#ffd76a' : this.style.title, alpha: 0.025 });
    glow.blendMode = 'add';
    const machine = new Sprite({ texture: art.machine, anchor: 0.5 });
    machine.width = w;
    machine.height = h;
    if (this.gold) machine.tint = '#ffd98a';
    const crank = new Sprite({ texture: art.crank, anchor: { x: art.spec.pivot[0], y: art.spec.pivot[1] } });
    crank.scale.set(art.spec.socketRadius * w * 1.3 / (art.spec.plateRadius * art.crank.width));
    crank.position.set(socket.x, socket.y);
    const capsule = new Sprite({ texture: art.capsules.plain, anchor: 0.5 });
    const opened = new Sprite({ texture: art.open, anchor: 0.5 });
    const token = new Container();
    capsule.visible = opened.visible = token.visible = false;
    this.disc.addChild(glow, machine, crank, opened, token, capsule);
    this.gachaParts = { machine, crank, capsule, opened, token };
  }

  /**
   * The gacha: TURN THE CRANK! and the game waits for the player's tap on the machine; the crank turns round twice with
   * a click a quarter, the machine shudders, a capsule (its colour the prize's kind) rolls out of the chute towards the
   * player, wobbles and pops open, and the prize rises out of it.
   */
  private async gachaTurn(segments: Segment[], index: number): Promise<void> {
    const art = this.art.gacha!, parts = this.gachaParts;
    if (!parts) return;
    const { w, h, chute, capsule: size } = this.gachaGeometry();
    const segment = segments[index];
    const kind = segment.name === 'MINI' || segment.name === 'MAJOR' || segment.name === 'MEGA' ? segment.name : 'plain';
    const { machine, crank, capsule, opened, token } = parts;
    gsap.killTweensOf([capsule, capsule.scale, opened, opened.scale, token, token.scale, crank, machine]);
    capsule.visible = opened.visible = token.visible = false;
    machine.x = 0;
    // the prize the server drew: its token, inside the capsule
    token.removeChildren().forEach(c => c.destroy());
    const coin = new Sprite({ texture: art.tokens[kind], anchor: 0.5 });
    coin.width = coin.height = size * 1.05;
    const words = new Text({ text: segmentText(segment), anchor: 0.5, resolution: textQuality(),
      style: { fontFamily: typeface.family, fontWeight: typeface.weight as 'normal', fontSize: Math.round(size * (segment.name ? 0.24 : 0.3)),
        fill: segment.name ? '#ffd76a' : '#ffffff', stroke: { color: '#000000', width: size * 0.05, join: 'round' } } });
    if (words.width > size * 0.82) words.scale.set(size * 0.82 / words.width);
    token.addChild(coin, words);

    // the player turns the crank
    await this.waitForCrank(w, h);
    const turn = gsap.timeline();
    this.spin = turn;
    const turns = { a: crank.rotation };
    let clicks = Math.floor(turns.a / (Math.PI / 4));
    turn.to(turns, { a: turns.a + Math.PI * 4, duration: calm ? 0.4 : 1.1, ease: 'power2.inOut', onUpdate: () => {
      crank.rotation = turns.a;
      const now = Math.floor(turns.a / (Math.PI / 4));
      if (now !== clicks) { clicks = now; this.onTick?.(); }
    } });
    turn.to(machine, { x: w * 0.006, duration: 0.05, yoyo: true, repeat: calm ? 1 : 15, ease: 'sine.inOut' }, 0);
    turn.set(machine, { x: 0 });
    await new Promise<void>(resolve => turn.eventCallback('onComplete', () => resolve()));
    // a capsule rolls out of the chute and comes towards the player
    capsule.texture = art.capsules[kind];
    capsule.width = capsule.height = size;
    const base = capsule.scale.x;
    capsule.position.set(chute.x, chute.y);
    capsule.rotation = 0;
    capsule.alpha = 0;
    capsule.visible = true;
    const at = { x: chute.x + w * (Math.random() < 0.5 ? -0.12 : 0.12), y: h * 0.5 + size * 0.35 };   // out in front, on the ground
    this.gachaAt = at;
    const roll = gsap.timeline();
    this.spin = roll;
    roll.to(capsule, { alpha: 1, duration: 0.1 })
      .to(capsule, { x: at.x, y: at.y, rotation: Math.PI * 2 * (at.x > chute.x ? 1 : -1), duration: calm ? 0.2 : 0.6, ease: 'bounce.out' }, 0)
      .to(capsule.scale, { x: base * 1.6, y: base * 1.6, duration: calm ? 0.2 : 0.6, ease: 'power2.out' }, 0)
      // it wobbles, about to burst
      .to(capsule, { rotation: '+=0.25', duration: 0.07, yoyo: true, repeat: calm ? 1 : 7, ease: 'sine.inOut' });
    await new Promise<void>(resolve => roll.eventCallback('onComplete', () => resolve()));
    // it pops: the two halves, light and confetti, the prize rising out
    capsule.visible = false;
    opened.width = opened.height = size * 2;
    opened.position.set(at.x, at.y - size * 0.25);
    opened.alpha = 1;
    opened.visible = true;
    this.onTick?.();
    if (!calm) this.chestBurst({ burst: art.burst } as ChestPick, at.x, at.y, size * 3.2);
    token.position.set(at.x, at.y);
    token.visible = true;
    gsap.timeline()
      .fromTo(token, { alpha: 0 }, { alpha: 1, y: at.y - size * 1.25, duration: 0.4, ease: 'back.out(2)' }, 0)
      .fromTo(token.scale, { x: 0.4, y: 0.4 }, { x: 1.35, y: 1.35, duration: 0.4, ease: 'back.out(2)' }, 0)
      .to(opened, { alpha: 0.6, duration: 0.6 }, 0.6);
  }

  /** TURN THE CRANK! on the bar, the crank pulsing; resolves on a tap anywhere on the machine. */
  private waitForCrank(w: number, h: number): Promise<void> {
    const crank = this.gachaParts!.crank;
    const said = this.jackpotAmount.text;
    this.jackpotAmount.text = 'TURN THE CRANK!';
    fit(this.jackpotAmount, this.radius * 0.9);
    const s = crank.scale.x;
    const pulse = gsap.to(crank.scale, { x: s * 1.12, y: s * 1.12, duration: 0.4, yoyo: true, repeat: -1, ease: 'sine.inOut' });
    const catcher = new Container();
    catcher.eventMode = 'static';
    catcher.cursor = 'pointer';
    catcher.hitArea = new Rectangle(-w / 2, -h / 2, w, h);
    this.addChild(catcher);
    return new Promise(resolve => {
      catcher.on('pointertap', () => {
        pulse.kill();
        crank.scale.set(s);
        catcher.destroy();
        this.jackpotAmount.text = said;
        fit(this.jackpotAmount, this.radius * 0.9);
        resolve();
      });
    });
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
    const ring = this.tesla(), opened = this.chestPick() ? this.chestOpen : null;
    // over the tesla ring's coil; over the chests
    amount.y = ring ? 0 : opened ? this.chestLow + this.chestBelow : this.gachaAt ? -r * 0.7 : -r * 0.32;   // (under the shelf; over the globe)
    // a war theme blows up instead of the golden rays
    const explodes = this.art.explode && !calm;
    if (explodes) this.explode(big);
    const photo = this.photo();
    const lit = ring ? this.nodes[this.lit % this.nodes.length] ?? null
      : opened ? { x: opened.chest.x, y: opened.feet - this.chestSize * 0.6 } : this.gachaAt;
    if ((photo || ring || opened) && !calm) {
      // the shot: a muzzle flash out of the chamber under the hammer, or a plasma flash out of the cell that won
      const flash = new Sprite({ texture: photo?.flash ?? ring?.flash ?? this.art.chests!.glow, anchor: 0.5 });
      flash.blendMode = 'add';
      flash.position.set(lit?.x ?? 0, lit?.y ?? -r * 0.7);
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
    // the theme's explosion over the wheel: a mushroom cloud for a jackpot, a fireball for a win; else the photo blast
    // on a tesla ring a win blows on its cell (a jackpot still over the whole ring)
    // (a chest's blast, even a jackpot's, comes out of the chest that opened)
    const opened = this.chestPick() ? this.chestOpen : null;
    const cell = this.tesla() && !big ? this.nodes[this.lit % this.nodes.length] ?? null
      : opened ? { x: opened.chest.x, y: opened.feet - this.chestSize * 0.5 } : this.gachaAt;
    const bx = cell?.x ?? 0, by = cell?.y ?? y;
    if (kindBlast(this.burst, big ? 'mushroom' : 'heavy', bx, big ? (opened ? opened.feet : r * 0.55) : by,
        r * (big ? (opened ? 2.2 : 3.2) : cell ? 1.8 : 2.4))
        || photoBlast(this.burst, bx, by, r * (big ? 2.2 : 1.6))) {
      this.sparks.burst(bx, by, ['#ffd76a', '#ff8a1a', '#fff3b0'], big ? 40 : 22, big ? 850 : 600, 500);
      const word = goldText(big ? this.words[1] : this.words[0], r * (big ? 0.34 : 0.26));
      if (opened) word.position.set(Math.max(-r * 1.6, Math.min(r * 1.6, bx)), opened.feet - this.chestSize * 1.7);   // over the chest
      else word.position.set(cell ? 0 : bx, cell ? -r * 1.25 : by - r * 0.55);
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
    for (let i = 0; i < (this.popsUp ? 0 : big ? 12 : 7); i++) {
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
    const word = goldText(big ? this.words[1] : this.words[0], r * (big ? 0.34 : 0.26));
    word.position.set(0, y - r * 0.55);
    word.rotation = random(-0.12, 0.12);
    this.burst.addChild(word);
    gsap.timeline()
      .fromTo(word.scale, { x: 2.6, y: 2.6 }, { x: 1, y: 1, duration: 0.2, ease: 'power3.in' }, 0)
      .to(word, { y: word.y - r * 0.2, alpha: 0, duration: 0.6, ease: 'power1.in' }, 0.9);
    // debris and embers
    this.sparks.burst(0, y, ['#1a1d21', '#4b5320', '#2b3036', '#6e5216'], big ? 34 : 20, big ? 950 : 700, 1500);
    this.sparks.burst(0, y, ['#ffd76a', '#ff8a1a', '#fff3b0'], big ? 46 : 26, big ? 850 : 600, 500);
    if (!this.popsUp) shake(this, big ? 18 : 9);   // the photo revolver stays still under its blast
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
    const w = r * 2.3, h = r * 0.34, y = this.art.photo ? -r * 2.28 : this.art.tesla ? -r * 1.5 : this.art.chests ? -this.chestHeader() : this.art.gacha ? -r * 1.95 : -r * 1.42;
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
    this.nodes = [];
    this.orb = null;
    this.chests = [];
    this.chestOpen = null;
    this.gachaParts = null;
    this.gachaAt = null;
    const gacha = this.art.gacha ?? null;
    const chest = gacha ? null : this.chestPick(segments);
    this.bolt.clear();
    const ring = this.tesla(segments);
    const r = this.art.bezel && !ring && !chest && !gacha ? this.radius * 0.84 : this.radius;
    const look = this.gold ? this.style.gold : this.style;
    this.glow.visible = !this.style.revolver && !ring && !chest && !gacha;   // no coloured glow round a revolver
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
    if (photo || ring || chest || gacha) {
      // the dial frame (with its hammer) is the bezel; the tesla ring has its own rim; the chests stand on a shelf
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
    this.lights.visible = !photo && !ring && !chest && !gacha;
    if (gacha) this.drawGacha(gacha);
    else if (chest?.doors) this.drawDoors(segments, chest, chest.doors);
    else if (chest) this.drawChests(segments, chest);
    else if (photo) this.drawPhoto(r, segments, photo);
    else if (ring) this.drawTesla(r, segments, ring);
    else if (this.style.revolver) this.drawRevolver(r, step, segments);
    else this.drawWedges(r, step, segments, look);
    if (this.art.glass && !photo && !ring && !chest && !gacha) {
      const glass = new Sprite({ texture: this.art.glass, anchor: 0.5 });
      glass.width = glass.height = r * 440 / 160;
      this.cover.addChildAt(glass, 0);
    }
    if (this.style.revolver || photo || ring || chest || gacha) {
      // the cylinder's own centre: the ejector star (drawn with it)
    } else if (this.hub) {
      const hub = new Sprite({ texture: this.hub, anchor: 0.5 });
      hub.scale.set(r * 0.62 / Math.max(hub.texture.width, 1));
      this.disc.addChild(hub);
    } else {
      this.disc.addChild(new Graphics().circle(0, 0, r * 0.28).fill(look.rim).stroke({ color: this.style.ink, width: 4 }));
    }
    if (photo || ring || chest || gacha) {
      // the hammer on the dial points at the chamber that wins; on the tesla ring, the bolt; the chest that opens
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
