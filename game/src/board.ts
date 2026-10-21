import { Container, Graphics, GraphicsContext, Sprite, Text, Texture } from 'pixi.js';
import { gsap } from 'gsap';
import type { Viewport } from './viewport';
import { label, textQuality, typeface } from './ui';
import { Particles, kindBlast, photoBlast, shake } from './effects';
import { goldText } from './paint';
import type { BoardStyle, FitFrame, ThemeFx } from './theme';
import { prizeIndexOfSlot, slotTier } from './rules';

export { prizeIndexOfSlot, slotTier };

// Board geometry (same as the original game): pegs 45 apart, row r has 3 + r pegs, slots under the last row.
export const PEG_SPACING = 45;
/** The status line's widest: the top of the board, between the frame's edges. */
const STATUS_MAX_WIDTH = 250;
const PEG_RADIUS = 5;
/** How far apart the peg rows are (PEG_SPACING, or less for a theme whose frame is a wide, low triangle). */
let ROW = 45;
/** A theme's row spacing, as a share of the peg spacing across (1: rows as far apart as pegs in a row). */
export function setRowGap(share: number): void {
  ROW = 45 * share;
}
const BALL_REST = 14.5;            // how far above a peg's centre the ball sits when it touches it
const BALL_RADIUS = BALL_REST - PEG_RADIUS;   // the ball's size for knocking into other balls
/** The colours a grenade's fuse spits (Bombs Away). */
const FUSE = ['#fff3b0', '#ffd76a', '#ff8a1a', '#ffb000'];
/** A grenade's fire after one, two, three gates (Bombs Away). */
const WAR_FIRE = ['#ff6a1a', '#ff8a1a', '#ffb000'];
/** The tail of a ball through 1, 2 and 3 gate bars. */
const HEAT = ['#ffd76a', '#ff8a1a', '#ff3b7a'].map(colour => new GraphicsContext().circle(0, 0, 1).fill(colour));
const SLOT_W = PEG_SPACING / 1.2;  // 37.5
const SLOT_H = PEG_SPACING;
const MAX_ROWS = 14;
// centre -> edge colours of the prize slots (normal, and while a ball lands)
const SLOT_COLOURS = ['#FFBF00', '#EF7C11', '#EF521E', '#EF282C', '#ED0000'];
const SLOT_WIN_COLOURS = ['#F4A800', '#FC7E15', '#FF4A1A', '#FC1521', '#FF0000'];

interface SlotLook { face: string; flash: string; text: string }
let slotLooks: SlotLook[] = SLOT_COLOURS.map((face, i) => ({ face, flash: SLOT_WIN_COLOURS[i], text: '#ffffff' }));
let slotRadius = 10;
let slotArt: Texture[] | null = null;

/** A colour mixed towards white: the lit-up version of a themed slot. */
function lighten(hex: string, amount: number): string {
  const n = parseInt(hex.slice(1), 16);
  const mix = (c: number) => Math.round(c + (255 - c) * amount);
  return '#' + [n >> 16, (n >> 8) & 255, n & 255].map(c => mix(c).toString(16).padStart(2, '0')).join('');
}

/** The theme's slot colours (board and history use the same slots); set once before the board is built. */
export function setSlotLooks(style: BoardStyle): void {
  slotRadius = style.slotRadius;
  if (style.slots) slotLooks = style.slots.map(s => ({ face: s.face, flash: lighten(s.face, 0.35), text: s.text }));
}

/** The theme's drawn slots (item_slot_0..4, centre -> edge), used instead of the flat colours. */
export function setSlotArt(textures: Texture[]): void {
  slotArt = textures;
}

const random = (min: number, max: number) => min + Math.random() * (max - min);


/** Plinko Mega: the colours of the gates and SPIN marks (the theme's accent and its ink), set before the board is built. */
let megaLook = { accent: '#FFBF00', ink: '#000000', art: null as Texture | null };
export function setMegaLook(accent: string, ink: string, gateArt: Texture | null): void {
  megaLook = { accent, ink, art: gateArt };
}

/** A tiny wheel over a SPIN slot: a ring with spokes and SPIN under it. */
/** A theme's own mark for the SPIN slots (a small picture that turns), instead of the drawn wheel. */
let spinMarkArt: Texture | null = null;
export function setSpinMark(texture: Texture | null): void {
  spinMarkArt = texture;
}

function spinBadge(): Container {
  const badge = new Container();
  if (spinMarkArt) {
    const mark = new Sprite({ texture: spinMarkArt, anchor: 0.5 });
    mark.width = mark.height = 24;
    badge.addChild(mark);
    gsap.to(mark, { rotation: Math.PI * 2, duration: 5, repeat: -1, ease: 'none' });
    return badge;
  }
  const ring = new Graphics().circle(0, 0, 9).fill(megaLook.ink).stroke({ color: megaLook.accent, width: 2.5 });
  for (let i = 0; i < 4; i++) {
    const a = i * Math.PI / 4;
    ring.moveTo(Math.cos(a) * -8, Math.sin(a) * -8).lineTo(Math.cos(a) * 8, Math.sin(a) * 8).stroke({ color: megaLook.accent, width: 1.5 });
  }
  ring.circle(0, 0, 2.5).fill(megaLook.accent);
  badge.addChild(ring);
  gsap.to(ring, { rotation: Math.PI * 2, duration: 4, repeat: -1, ease: 'none' });
  return badge;
}

/** Multiplier label without the "x": the slots are read as multipliers, and short labels can be larger. */
function multiplierText(value: number): string {
  return String(Number(value.toFixed(2)));
}

export class Slot extends Container {
  readonly text: Text;
  private readonly flash: Container;
  /** Marks a slot that charges the bonus meter. */
  readonly meterMark: Graphics;
  /** Plinko Mega: marks a SPIN slot (a ball landing in it also spins the wheel). */
  readonly spinMark: Container;
  baseY = 0;

  /** A light that runs over the slot now and then (the row's idle wave). */
  readonly glint = new Graphics();

  constructor(readonly prizeIndex: number, readonly tier: number) {
    super();
    const look = slotLooks[tier];
    const r = slotRadius;
    let body: Container[];
    if (slotArt) {
      // the theme's drawing (it carries its own lip, shine and glow); a brighter copy of it lights up on a landing
      body = [new Sprite({ texture: slotArt[tier], anchor: 0.5 })];
      this.flash = new Sprite({ texture: slotArt[tier], anchor: 0.5 });
      (this.flash as Sprite).blendMode = 'add';
    } else {
      const shadow = new Graphics().roundRect(-SLOT_W / 2, -SLOT_H / 2 + 10, SLOT_W, SLOT_H, r).fill(look.face);
      const dim = new Graphics().roundRect(-SLOT_W / 2, -SLOT_H / 2 + 10, SLOT_W, SLOT_H, r).fill({ color: '#000000', alpha: 0.3 });
      const face = new Graphics().roundRect(-SLOT_W / 2, -SLOT_H / 2, SLOT_W, SLOT_H, r).fill(look.face);
      body = [shadow, dim, face];
      this.flash = new Graphics().roundRect(-SLOT_W / 2, -SLOT_H / 2, SLOT_W, SLOT_H, r).fill(look.flash);
    }
    this.flash.alpha = 0;
    this.text = new Text({ text: '', anchor: 0.5, resolution: textQuality(),
      style: { fontFamily: typeface.family, fontWeight: typeface.weight as 'normal', fontSize: 22, fill: look.text } });
    this.text.y = -3;
    this.meterMark = new Graphics().poly([0, -5, 4, 0, 0, 5, -4, 0]).fill('#ffffff').stroke({ color: '#000000', width: 1.5 });
    this.meterMark.position.set(0, -SLOT_H / 2 - 7);
    this.meterMark.visible = false;
    this.spinMark = spinBadge();
    this.spinMark.position.set(0, -SLOT_H / 2 - 13);
    this.spinMark.visible = false;
    this.glint.roundRect(-SLOT_W / 2, -SLOT_H / 2, SLOT_W, SLOT_H, slotRadius).fill('#ffffff');
    this.glint.blendMode = 'add';
    this.glint.alpha = 0;
    this.addChild(...body, this.flash, this.glint, this.text, this.meterMark, this.spinMark);
  }

  /** A SPIN slot (a theme with its own board) reads SPIN instead of its prize. */
  spinLabel = false;

  setValue(value: number): void {
    this.text.text = this.spinLabel ? 'SPIN' : multiplierText(value);
    this.text.scale.set(1);
    const max = SLOT_W - 5;
    if (this.text.width > max) this.text.scale.set(max / this.text.width);
  }

  override destroy(options?: Parameters<Container['destroy']>[0]): void {
    gsap.killTweensOf([this, this.scale, this.flash, this.spinMark.children[0]]);
    super.destroy(options);
  }

  /** A ball landed: the slot dips and lights up. */
  hit(): void {
    // always dips from and back to its resting place, however many balls land at once, and squashes like a drum
    gsap.killTweensOf(this);
    gsap.fromTo(this.scale, { x: 1.25, y: 0.7 }, { x: 1, y: 1, duration: 0.5, ease: 'elastic.out(1.4, 0.35)', overwrite: true });
    gsap.timeline()
      .to(this, { y: this.baseY + 6, duration: 0.1, ease: 'power2.out' })
      .to(this, { y: this.baseY, duration: 0.25, ease: 'power2.inOut' });
    gsap.timeline()
      .to(this.flash, { alpha: 1, duration: 0.2 })
      .to(this.flash, { alpha: 0, duration: 0.2 });
  }
}

/**
 * Left/right steps (+1 / -1) for a ball that must end in `slot`: exactly `slot` of the `rows` steps go right. Of a
 * few random orders it prefers the ones that swing wide before coming back, so the drops look lively. Every
 * order stays on the board and ends in the same slot; only the route changes, never the result.
 */
export function wanderingSteps(rows: number, slot: number): number[] {
  const candidates: { steps: number[]; swing: number }[] = [];
  for (let n = 0; n < 8; n++) {
    const steps: number[] = Array.from({ length: rows }, (_, i) => (i < slot ? 1 : -1));
    for (let i = steps.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1));
      [steps[i], steps[j]] = [steps[j], steps[i]];
    }
    // how far the route strays from the straight line to its slot
    let position = 0;
    let swing = 0;
    const end = 2 * slot - rows;
    steps.forEach((step, r) => {
      position += step;
      swing = Math.max(swing, Math.abs(position - end * (r + 1) / rows));
    });
    candidates.push({ steps, swing });
  }
  // a weighted draw (not always the widest), so drops still vary
  const weights = candidates.map(c => (c.swing + 0.5) ** 2);
  let pick = Math.random() * weights.reduce((a, b) => a + b, 0);
  for (let i = 0; i < candidates.length; i++) {
    pick -= weights[i];
    if (pick <= 0) return candidates[i].steps;
  }
  return candidates[candidates.length - 1].steps;
}

/** The steps of a path decided by the server (bit i set: bounce i + 1 goes right). */
/** A peg by its row (steps taken to reach it) and how many of those went right: the server's numbering. */
export function pegKey(row: number, rights: number): number {
  return row * 64 + rights;
}

/** The pegs a MEGA BALL on this path smashes (on the board's smash rows), as peg keys. */
export function smashedPegs(path: number, smashRows: number[]): Set<number> {
  return new Set(smashRows.map(row => pegKey(row, popCount(path & ((1 << row) - 1)))));
}

function popCount(n: number): number {
  let count = 0;
  for (; n; n &= n - 1) count++;
  return count;
}

export function pathSteps(rows: number, path: number): number[] {
  return Array.from({ length: rows }, (_, i) => ((path >> i) & 1 ? 1 : -1));
}

export interface Landing { slot: number; prizeIndex: number; x: number; y: number; pan: number }
/** A ball touching a peg, for the sound: which row (of how many), and where across the board (-1 left .. 1 right). */
export interface PegHit { row: number; rows: number; pan: number }

/**
 * A ball in the air. (x, y) is where its route says it is; (ox, oy) is a visual knock from bumping into another ball,
 * which a spring pulls back to nothing. Only the drawing uses the knock: the route, and so the slot, never changes.
 */
interface Flight { sprite: Sprite; x: number; y: number; ox: number; oy: number; vx: number; vy: number; free: boolean; cooldown: number;
  /** How many gate bars the ball has crossed so far: it burns hotter with each. */
  heat: number;
  /** Its size (a MEGA BALL is bigger, a hot ball swells); the bounce on a peg squashes around this. */
  size: number;
  timeline?: gsap.core.Timeline }

export class Board extends Container {
  readonly field = new Container();     // pegs, slots and balls, in board coordinates (x = 0 is the centre)
  private readonly frame: Sprite;
  private readonly framePortrait: Sprite;
  private readonly pegs: Container[][] = [];
  private readonly pegFlashes: Graphics[][] = [];
  private readonly trailLayer = new Container();
  private readonly trailDot: GraphicsContext | null;
  private readonly particles = new Particles();
  private readonly flights = new Set<Flight>();
  private readonly pegFlashColour: string;
  private readonly fx: ThemeFx;
  /** Called when two balls knock together (for the sound). */
  onClack: (() => void) | null = null;
  private readonly slotLayer = new Container();
  private readonly ballLayer = new Container();
  private readonly holeLayer = new Container();   // balls going into a slot, drawn behind the slots
  private fuseTick = 0;
  /** What moves with a ball (a MEGA BALL's halo and label), placed in the same frame as the balls. */
  private readonly followers = new Set<() => void>();
  private readonly textLayer = new Container();
  private slots: Slot[] = [];
  private readonly amounts = new Map<number, Text>();
  private readonly status = label('', 30, 'center');
  rows = 8;
  private prizes: number[][] = [];
  private risk = 0;
  /** Plinko Mega: gates per rows count ([step, rights]), what each multiplies by, and the SPIN slots per rows count. */
  private megaGates: Record<string, number[][]> = {};
  private gateBoost = 0;
  private spinSlots: Record<string, number[]> = {};
  private readonly gateLayer = new Container();
  private gates: { step: number; from: number; to: number; view: Container }[] = [];

  /** The fitted frame of the board on show (see BoardStyle.fit), and the textures of every fitted frame. */
  private fitNow: FitFrame | null = null;
  private lastPlace: { view: Viewport; place: Parameters<Board['layout']>[1] } | null = null;

  constructor(frame: Texture, framePortrait: Texture, private readonly ballTexture: Texture, private readonly style: BoardStyle,
              pegTexture: Texture | null, private readonly fitFrames: Record<number, { wide: Texture; tall: Texture }> = {}) {
    super();
    this.trailDot = style.trail ? new GraphicsContext().circle(0, 0, 1).fill(style.trail) : null;
    this.pegFlashColour = style.pegFlash;
    this.fx = style.fx;
    this.frame = new Sprite({ texture: frame, anchor: 0.5 });
    this.framePortrait = new Sprite({ texture: framePortrait, anchor: 0.5 });
    // all 14 rows of pegs exist; rows below the chosen count are hidden
    for (let r = 0; r < MAX_ROWS; r++) {
      const count = 3 + r;
      const row: Container[] = [];
      const flashes: Graphics[] = [];
      for (let c = 0; c < count; c++) {
        const x = -PEG_SPACING * (count - 1) / 2 + PEG_SPACING * c;
        const y = ROW * (r + 1);
        const flash = new Graphics().circle(0, 0, PEG_RADIUS).fill(style.pegFlash);
        flash.position.set(x, y);
        flash.alpha = 0;
        const peg = pegTexture ? new Sprite({ texture: pegTexture, anchor: 0.5 }) : new Graphics().circle(0, 0, PEG_RADIUS).fill('#ffffff');
        peg.position.set(x, y);
        this.field.addChild(flash, peg);
        row.push(peg);
        flashes.push(flash);
      }
      this.pegs.push(row);
      this.pegFlashes.push(flashes);
    }
    this.status.y = -10;
    gsap.fromTo(this.status, { alpha: 1 }, { alpha: 0.3, duration: 0.3, repeat: -1, yoyo: true, ease: 'none' });
    this.field.addChild(this.status, this.gateLayer, this.holeLayer, this.slotLayer, this.trailLayer, this.ballLayer, this.particles, this.textLayer);
    this.addChild(this.frame, this.framePortrait, this.field);
  }

  /** Rows (8..14) and the multipliers per prize position ([low, mid, high] each), from the server's config. */
  setRows(rows: number, prizes: number[][]): void {
    this.rows = rows;
    // a fitted frame for this board: its picture, and its row spacing (the pegs move to it)
    const fit = this.style.fit?.[rows];
    if (fit && this.fitFrames[rows]) {
      this.fitNow = fit;
      setRowGap(fit.rowGap);
      this.frame.texture = this.fitFrames[rows].wide;
      this.framePortrait.texture = this.fitFrames[rows].tall;
      this.pegs.forEach((row, r) => row.forEach(peg => { peg.y = ROW * (r + 1); }));
      this.pegFlashes.forEach((row, r) => row.forEach(flash => { flash.y = ROW * (r + 1); }));
      if (this.lastPlace) this.layout(this.lastPlace.view, this.lastPlace.place);
    }
    this.prizes = prizes;
    this.pegs.forEach((row, r) => row.forEach(peg => { peg.visible = r < rows; }));
    this.slotLayer.removeChildren().forEach(child => child.destroy({ children: true }));
    const positions = rows + 1;
    this.slots = [];
    for (let p = 0; p < positions; p++) {
      const prizeIndex = prizeIndexOfSlot(rows, p);
      const slot = new Slot(prizeIndex, slotTier(rows, prizeIndex));
      slot.position.set(this.slotX(p), this.slotY());
      slot.baseY = slot.y;
      this.slotLayer.addChild(slot);
      this.slots.push(slot);
    }
    this.setRisk(this.risk);
    this.drawMega();
  }

  /** Plinko Mega's gates and SPIN slots (from the server's config; no gates when gateBoost is null). */
  setMega(gates: Record<string, number[][]>, gateBoost: number | null, spinSlots: Record<string, number[]>): void {
    this.megaGates = gates;
    this.gateBoost = gateBoost ?? 0;
    this.spinSlots = spinSlots;
    this.drawMega();
  }

  /** The SPIN slots of the current board ([] when the wheel is off or on a replay without one). */
  currentSpinSlots(): number[] {
    return this.spinSlots[String(this.rows)] ?? [];
  }

  /** The gates this round stands on (the server moves them every round): the bars spring up at their new places. */
  showGates(layout: number[][]): void {
    const key = JSON.stringify(layout);
    if (key === this.gatesShown) return;
    this.drawMega(layout);
    this.gates.forEach((gate, i) => {
      gsap.fromTo(gate.view, { alpha: 0 }, { alpha: 1, duration: 0.2, delay: i * 0.05 });
      gsap.fromTo(gate.view.scale, { x: 0.2, y: 2 }, { x: 1, y: 1, duration: 0.35, delay: i * 0.05, ease: 'back.out(2)' });
    });
  }

  /** The layout drawn now (to redraw only when it changes). */
  private gatesShown = '';

  private drawMega(layout: number[][] | null = null): void {
    // the bars' shimmer and glow run forever: stopped before the bars go
    this.gateLayer.removeChildren().forEach(view => {
      gsap.killTweensOf([view, view.scale, ...view.children]);
      view.destroy({ children: true });
    });
    this.gates = [];
    const spin = this.currentSpinSlots();
    // a theme with its own board: the SPIN slots say SPIN (no mark over them); others: a turning wheel mark over the slot
    this.slots.forEach((slot, i) => {
      const isSpin = spin.includes(i);
      slot.spinMark.visible = isSpin && !this.style.fit;
      if (this.style.fit && slot.spinLabel !== isSpin) {
        slot.spinLabel = isSpin;
        slot.setValue(this.multiplier(prizeIndexOfSlot(this.rows, i)));
      }
    });
    if (this.gateBoost <= 0) return;
    const bars = layout ?? this.megaGates[String(this.rows)] ?? [];
    this.gatesShown = JSON.stringify(bars);
    for (const [step, from, to] of bars) {
      const view = this.gateView(from, to, step);
      view.position.set(PEG_SPACING / 2 * (from + to - step), ROW * (step + 1) - BALL_REST);
      this.gateLayer.addChild(view);
      this.gates.push({ step, from, to, view });
    }
  }

  /**
   * A gate: a glowing bar across the pegs a ball must reach to cross it (centred on the bar), its boost in a badge at
   * the end nearest the board's edge. It shimmers all the time, so the board never stands still.
   */
  private gateView(from: number, to: number, step: number): Container {
    const view = new Container();
    // a theme with its own drawn board: no bar across it, the gate's pegs glow instead (the view sits where a ball
    // rests on them, a ball's rest above the pegs)
    if (this.style.fit) {
      const halos = new Graphics();
      for (let k = from; k <= to; k++) {
        const x = (k - (from + to) / 2) * PEG_SPACING;
        halos.circle(x, BALL_REST, 11).fill({ color: megaLook.accent, alpha: 0.28 })
          .circle(x, BALL_REST, 7.5).stroke({ color: megaLook.accent, width: 2, alpha: 0.95 });
      }
      halos.blendMode = 'add';
      view.addChild(halos);
      gsap.fromTo(halos, { alpha: 1 }, { alpha: 0.45, duration: 0.6 + Math.random() * 0.3, yoyo: true, repeat: -1, ease: 'sine.inOut' });
      const outward = from + to < step ? -1 : 1;
      const badge = new Text({ text: '×' + multiplierText(this.gateBoost), anchor: 0.5, resolution: textQuality(),
        style: { fontFamily: typeface.family, fontWeight: typeface.weight as 'normal', fontSize: 13, fill: megaLook.accent,
          stroke: { color: megaLook.ink, width: 3 } } });
      badge.position.set(outward * (PEG_SPACING / 2 * (to - from) + PEG_SPACING * 0.75), BALL_REST);
      view.addChild(badge);
      return view;
    }
    const half = PEG_SPACING / 2 * (to - from) + PEG_SPACING * 0.42;
    const bar = new Graphics()
      .roundRect(-half - 3, -6, 2 * half + 6, 12, 6).fill({ color: megaLook.ink, alpha: 0.85 })
      .roundRect(-half, -3.5, 2 * half, 7, 3.5).fill(megaLook.accent)
      .roundRect(-half + 4, -2.5, 2 * half - 8, 2, 1).fill({ color: '#ffffff', alpha: 0.7 });
    const glow = new Graphics().roundRect(-half - 8, -11, 2 * half + 16, 22, 11).fill({ color: megaLook.accent, alpha: 0.35 });
    glow.blendMode = 'add';
    const shine = new Graphics().rect(-8, -4, 16, 8).fill({ color: '#ffffff', alpha: 0.85 });
    shine.blendMode = 'add';
    const mask = new Graphics().roundRect(-half, -3.5, 2 * half, 7, 3.5).fill('#ffffff');
    shine.mask = mask;
    view.addChild(glow, bar, shine, mask);
    gsap.fromTo(glow, { alpha: 1 }, { alpha: 0.35, duration: 0.7 + Math.random() * 0.3, yoyo: true, repeat: -1, ease: 'sine.inOut' });
    gsap.fromTo(shine, { x: -half - 10 }, { x: half + 10, duration: 1.4, repeat: -1, repeatDelay: 0.6 + Math.random(), ease: 'power1.inOut' });
    // the badge sits on the outer end (the side of the board the bar stands on)
    const outward = from + to < step ? -1 : 1;
    const key = new Container();
    key.addChild(new Graphics().poly([0, -13, 13, 0, 0, 13, -13, 0]).fill(megaLook.ink).stroke({ color: megaLook.accent, width: 2.5 }));
    key.addChild(new Text({ text: '×' + multiplierText(this.gateBoost), anchor: 0.5, resolution: textQuality(),
      style: { fontFamily: typeface.family, fontWeight: typeface.weight as 'normal', fontSize: 11, fill: megaLook.accent } }));
    key.x = outward * (half + 15);
    view.addChild(key);
    return view;
  }

  /** A ball went through a gate: the arch flashes and kicks, and the boost pops out of it. */
  private gatePass(gate: { view: Container }, boostSoFar: number): void {
    const v = gate.view;
    // the gates moved for the next round while this ball was still falling: its gate is gone, the boost still counts
    if (v.destroyed) {
      this.onGate?.(boostSoFar);
      return;
    }
    // a quick bounce; a gate still bouncing is not restarted (balls cross the top gates every fraction of a second,
    // and restarting each time kept them stretched)
    if (!gsap.isTweening(v.scale)) gsap.fromTo(v.scale, { x: 1.06, y: 1.3 }, { x: 1, y: 1, duration: 0.25, ease: 'back.out(3)' });
    this.particles.burst(v.x, v.y - 8, [megaLook.accent, '#ffffff', '#ff8a1a'], 22, 320);
    this.particles.ring(v.x, v.y, megaLook.accent, 34);
    this.particles.ring(v.x, v.y, '#ffffff', 60);
    const pop = new Text({ text: '×' + multiplierText(boostSoFar), anchor: 0.5, resolution: textQuality(),
      style: { fontFamily: typeface.family, fontWeight: typeface.weight as 'normal', fontSize: 24, fill: megaLook.accent,
        stroke: { color: megaLook.ink, width: 5 } } });
    pop.position.set(v.x, v.y - 30);
    this.textLayer.addChild(pop);
    gsap.timeline({ onComplete: () => pop.destroy() })
      .from(pop.scale, { x: 0.3, y: 0.3, duration: 0.2, ease: 'back.out(3)' })
      .to(pop, { y: pop.y - 26, alpha: 0, duration: 0.7, ease: 'power1.in' }, 0.35);
    this.onGate?.(boostSoFar);
  }

  private time = 0;
  private twinkle = 0;

  /**
   * The board never stands still: a wave of light runs along the prize row, the top prizes throb, and pegs twinkle
   * here and there.
   */
  private idle(dt: number): void {
    this.time += dt;
    const top = Math.max(...this.slots.map(slot => slot.tier));
    this.slots.forEach((slot, i) => {
      slot.glint.alpha = 0.4 * Math.max(0, Math.sin(this.time * 2.6 - i * 0.6)) ** 8;
      if (slot.tier === top && !gsap.isTweening(slot.scale)) slot.scale.set(1 + 0.06 * Math.max(0, Math.sin(this.time * 5)) ** 2);
    });
    this.twinkle -= dt;
    if (this.twinkle <= 0) {
      this.twinkle = 0.08;
      const row = Math.floor(Math.random() * this.rows);
      const flash = this.pegFlashes[row]?.[Math.floor(Math.random() * (row + 3))];
      if (flash && !gsap.isTweening(flash)) gsap.fromTo(flash, { alpha: 0.7 }, { alpha: 0, duration: 0.6, ease: 'power1.out' });
    }
  }

  /** How much faster balls fall after a bump (until the board is empty again). */
  private hurry = 1;
  /** Turbo: every ball falls this much faster. */
  speed = 1;

  /** How many balls are in the air. */
  inFlight(): number {
    return this.flights.size;
  }

  /** The balls in the air hurry to their slots (PLAY's skip): no jolt, nothing about where they land changes. */
  hurryAll(): void {
    this.hurry = 3;
    this.flights.forEach(f => f.timeline?.timeScale(this.hurry));
  }

  /**
   * Plinko Mega's bump: the board jolts and the balls in the air hurry on. Looks only: their paths, and so where they
   * land, were decided by the server before they fell.
   */
  bump(): void {
    if (this.flights.size === 0) return;
    this.hurry = Math.min(2.6, Math.max(this.hurry, this.speed) * 1.5);
    this.flights.forEach(f => f.timeline?.timeScale(this.hurry));
    shake(this.field, 7);
    this.flights.forEach(f => { f.vx += random(-160, 160); f.vy -= random(60, 140); });
  }

  /** Called when a ball goes through a gate, with what its gates multiply it by so far (for the sound). */
  onGate: ((boost: number) => void) | null = null;

  /** A ball landed in a SPIN slot: the slot's little wheel whirls and the slot glows. */
  spinLanding(slot: number): void {
    const s = this.slots[slot];
    if (!s) return;
    gsap.fromTo(s.spinMark.scale, { x: 2, y: 2 }, { x: 1, y: 1, duration: 0.6, ease: 'back.out(2)' });
    this.particles.burst(this.slotX(slot), this.slotY() - 20, [megaLook.accent, '#ffffff'], 22, 340);
  }

  /** Which prize positions charge the bonus meter on the current board (none: meter off). */
  setMeterPositions(positions: number[]): void {
    this.slots.forEach(slot => { slot.meterMark.visible = positions.includes(slot.prizeIndex); });
  }

  setRisk(risk: number): void {
    this.risk = risk;
    this.slots.forEach(slot => slot.setValue(this.prizes[slot.prizeIndex]?.[risk] ?? 0));
  }

  /** Review N15.4: no prize labels (a replayed round whose own prizes are unknown: the current ones could be wrong). */
  hideValues(): void {
    this.slots.forEach(slot => { slot.text.visible = false; });
  }

  multiplier(prizeIndex: number): number {
    return this.prizes[prizeIndex]?.[this.risk] ?? 0;
  }

  slotX(slot: number): number {
    return PEG_SPACING / 2 * (2 * slot - this.rows);
  }

  slotY(): number {
    return ROW * (this.rows + 1) + 12 * ROW / PEG_SPACING;
  }

  /** Width and height of the board's content, used to scale it into the available space. */
  contentSize(): { w: number; h: number } {
    return { w: PEG_SPACING * (this.rows + 2), h: ROW * (this.rows + 1) };
  }

  /** Places the frame and the board for the current orientation (same rules as the original game). */
  layout(view: Viewport, place: { x: number; y: number; fit: number; room?: { w: number; h: number } } | null = null): void {
    const { w, h } = this.contentSize();
    // on wide phones the top and bottom of the stage are cut off: keep the pegs, the slots and the status line inside
    const visibleH = view.stageH - 2 * view.offsetY;
    const max = view.landscape ? { w: 1000, h: Math.min(510, visibleH - 110) } : { w: 570, h: 440 };
    // Plinko Mega places the board itself (room for the wheel and the control bar) and shrinks it, frame and all
    const fitTo = place?.fit ?? 1;
    this.lastPlace = { view, place };
    const fit = this.fitNow;
    if (fit) {
      // a frame drawn for this board: the whole frame fits the room, and the pegs sit where the frame shows them
      const picture = view.landscape ? this.frame : this.framePortrait;
      const unitsW = picture.texture.width / (fit.spacing / PEG_SPACING), unitsH = picture.texture.height / (fit.spacing / PEG_SPACING);
      const room = place?.room ?? (view.landscape ? { w: view.stageW * 0.54, h: visibleH * 0.84 } : { w: view.stageW - 12, h: visibleH * 0.5 });
      const fieldScale = Math.min(room.w / unitsW, room.h / unitsH) * fitTo;
      const frameScale = fieldScale * PEG_SPACING / fit.spacing;
      const fx = place?.x ?? view.stageW / 2;
      const fy = place?.y ?? view.stageH / 2;
      for (const frame of [this.frame, this.framePortrait]) {
        frame.scale.set(frameScale);
        frame.position.set(fx, fy);
      }
      this.frame.visible = view.landscape;
      this.framePortrait.visible = !view.landscape;
      // the picture's point (fit.x, fit.y) is the pegs' top centre
      const ox = fx + (fit.x - picture.texture.width / 2) * frameScale;
      const oy = fy + (fit.y - picture.texture.height / 2) * frameScale;
      gsap.to(this.field, { x: ox, y: oy, duration: 0.2, overwrite: true });
      this.field.scale.set(fieldScale);
      return;
    }
    const scale = Math.min(1, max.w / w, max.h / h) * fitTo;
    const x = place?.x ?? (view.landscape ? view.stageW * 0.57 : view.stageW / 2);
    const centreY = place?.y ?? (view.landscape ? view.stageH / 2 : view.stageH * 0.37);
    this.frame.scale.set(fitTo);
    this.framePortrait.scale.set(fitTo);
    this.frame.visible = view.landscape;
    this.framePortrait.visible = !view.landscape;
    this.frame.position.set(x, centreY);
    this.framePortrait.position.set(x, centreY);
    gsap.to(this.field, { x, y: centreY - h * scale / 2, duration: 0.2, overwrite: true });
    this.field.scale.set(scale);
  }

  /**
   * Drops one ball into `slot` (the server's result). The path is built to end there: of the `rows` bounces,
   * exactly `slot` go right, in random order, so every ball looks different but always lands where it must.
   */
  dropBall(slot: number, events: { onPeg?: (peg: PegHit) => void; onLand?: (landing: Landing) => void } = {},
           path: number | null = null, mega = 1,
           pegs: { holes?: Set<number>; smash?: Set<number>; decoy?: boolean; quiet?: boolean } = {}): Promise<void> {
    const holes = pegs.holes ?? new Set<number>();
    const smash = pegs.smash ?? new Set<number>();
    const rows = this.rows;
    // Plinko Mega: the server decides the path (it decides the gates); a round without one gets a lively made-up route
    const steps = path != null && Number.isInteger(path) && path >= 0 && path < 2 ** rows ? pathSteps(rows, path) : wanderingSteps(rows, slot);
    // the gates on this route: after `step` bounces, `rights` of them right
    const passes = new Map<number, { view: Container }>();
    let rights = 0;
    steps.forEach((step, i) => {
      if (step > 0) rights++;
      const gate = this.gates.find(g => g.step === i + 1 && rights >= g.from && rights <= g.to);
      if (gate) passes.set(i + 1, gate);
    });
    let gateBoost = 1;
    // rights after each number of steps: a peg is known by its row and that (see smashPeg)
    const rightsAt = [0];
    steps.forEach(step => rightsAt.push(rightsAt[rightsAt.length - 1] + (step > 0 ? 1 : 0)));
    const points: { x: number; y: number }[] = [{ x: 0, y: ROW - BALL_REST }];
    let x = 0;
    for (let r = 1; r < rows; r++) {
      x += steps[r - 1] * PEG_SPACING / 2;
      points.push({ x, y: ROW * (r + 1) - BALL_REST });
    }
    const landX = x + steps[rows - 1] * PEG_SPACING / 2;

    const ball = new Sprite({ texture: this.ballTexture, anchor: 0.5 });
    ball.position.set(0, 0);
    if (pegs.decoy) { ball.alpha = 0.55; ball.tint = '#9aa0a6'; }
    this.ballLayer.addChild(ball);
    // a MEGA BALL: gold, bigger, on fire from the start, its multiplier riding with it
    // a theme with its own board: the ball sized to its pegs' gap (a ball fills about 62% of the gap between two pegs)
    const fitted = this.fitNow ? 0.78 : 1;
    const size = (mega > 1 ? 1.35 : 1) * fitted;
    const flight: Flight = { sprite: ball, x: 0, y: 0, ox: 0, oy: 0, vx: 0, vy: 0, free: true, cooldown: 0, heat: mega > 1 ? 2 : 0, size };
    this.flights.add(flight);
    // quiet: one of many MEGA BALLs in a round (heavy shells): the gold ball and its tag, no stamp of its own
    const stopFollowing = mega > 1 ? this.megaBall(ball, mega, !pegs.quiet) : () => undefined;

    const radius = ball.texture.width / 2;
    // every ball has its own pace, but within a ball the hops change smoothly (no random jerks from peg to peg)
    const pace = random(0.17, 0.19);
    const slotTop = this.slotY() - SLOT_H / 2;

    return new Promise(resolve => {
      const timeline = gsap.timeline({ onComplete: resolve });
      flight.timeline = timeline;
      if (Math.max(this.hurry, this.speed) > 1) timeline.timeScale(Math.max(this.hurry, this.speed));
      /** One arc from a to b: steady sideways speed, and up-then-down like under gravity; the ball rolls as it moves. */
      const arc = (from: { x: number; y: number }, to: { x: number; y: number }, height: number, duration: number) => {
        const hop = { t: 0 };
        timeline.to(hop, {
          t: 1, duration, ease: 'none',
          onUpdate: () => {
            flight.x = from.x + (to.x - from.x) * hop.t;
            flight.y = from.y + (to.y - from.y) * hop.t - height * 4 * hop.t * (1 - hop.t);
            ball.rotation = this.spin(flight.x, radius);
            this.streak(ball, radius, flight.heat);
          },
        });
      };

      // bursts in at the top through a ring of light, and falls onto the first peg, speeding up
      ball.alpha = 0;
      timeline.call(() => this.launch(points[0].y - PEG_SPACING * 0.9));
      timeline.to(ball, { alpha: pegs.decoy ? 0.55 : 1, duration: 0.12 });
      timeline.fromTo(ball.scale, { x: 0.2, y: 0.2 }, { x: size, y: size, duration: 0.25, ease: 'back.out(3)' }, 0);
      timeline.to(flight, { y: points[0].y, duration: 0.34, ease: 'power2.in' }, 0);
      timeline.call(() => this.touchPeg(ball, 0, 0, events.onPeg, flight.size));
      const gateAt = (i: number) => {
        const gate = passes.get(i);
        if (gate) timeline.call(() => {
          gateBoost *= this.gateBoost;
          flight.heat++;
          this.gatePass(gate, gateBoost);
          flight.size = size * (1 + 0.15 * Math.max(0, flight.heat - (mega > 1 ? 2 : 0)));
          gsap.to(ball.scale, { x: flight.size, y: flight.size, duration: 0.2, ease: 'back.out(2)', overwrite: true });
        });
      };
      // peg to peg: hops get a little quicker and flatter as the ball picks up speed. Where a MEGA BALL smashed the
      // peg, the ball falls straight down through the hole, past the next row, onto the peg two rows down.
      let prev = points[0];
      for (let i = 1; i < points.length; ) {
        const progress = i / points.length;
        const at = points[i];
        arc(prev, at, PEG_SPACING * (0.34 - 0.14 * progress) * random(0.92, 1.08), pace * (1 - 0.28 * progress) * random(0.97, 1.03));
        const column = this.pegColumn(i, at.x);
        const key = pegKey(i, rightsAt[i]);
        if (holes.has(key) && i + 2 <= rows && steps[i] !== steps[i + 1]) {
          // a hole (if this ball got here first, the peg blows now)
          timeline.call(() => this.smashPeg(i, column));
          gateAt(i);
          const below = i + 2 < points.length ? points[i + 2] : null;
          if (!below) { prev = at; break; }   // the slot is straight below
          const drop = { t: 0 };
          timeline.to(drop, { t: 1, duration: pace * 0.9, ease: 'power1.in', onUpdate: () => {
            flight.x = at.x;
            flight.y = at.y + (below.y - at.y) * drop.t;
            this.streak(ball, radius, flight.heat);
          } });
          gateAt(i + 1);
          const belowColumn = this.pegColumn(i + 2, below.x);
          timeline.call(() => this.touchPeg(ball, i + 2, belowColumn, events.onPeg, flight.size));
          gateAt(i + 2);
          prev = below;
          i += 3;
          continue;
        }
        // a hole this ball can't fall through (the server let it bounce here): the peg is back just in time
        if (holes.has(key)) timeline.call(() => this.restorePeg(i, column));
        timeline.call(() => this.touchPeg(ball, i, column, events.onPeg, flight.size));
        // a MEGA BALL blows up the pegs it hits on the smash rows
        if (smash.has(key)) timeline.call(() => this.smashPeg(i, column));
        gateAt(i);
        // now and then the ball bounces up off a peg and comes down on it again before going on
        if (i < points.length - 2 && !smash.has(key) && Math.random() < 0.07) {
          arc(at, at, PEG_SPACING * random(0.18, 0.26) * (1 - 0.4 * progress), pace * 0.55);
          timeline.call(() => this.touchPeg(ball, i, column, events.onPeg, flight.size));
        }
        prev = at;
        i++;
      }
      // into the hole: the last fall keeps its speed and goes behind the slot, which swallows the ball
      const last = prev;
      const end = { x: landX, y: slotTop + radius * 2.4 };
      const height = PEG_SPACING * 0.16;
      let entered = false;
      timeline.call(() => { flight.free = false; this.holeLayer.addChild(ball); });
      const fall = { t: 0 };
      timeline.to(fall, {
        t: 1, duration: pace * 0.95, ease: 'none',
        onUpdate: () => {
          flight.x = last.x + (end.x - last.x) * fall.t;
          flight.y = last.y + (end.y - last.y) * fall.t - height * 4 * fall.t * (1 - fall.t);
          ball.rotation = this.spin(flight.x, radius);
          if (!entered && flight.y > slotTop - radius * 0.3) {
            entered = true;
            if (pegs.decoy) {
              // a cluster decoy: it fizzles in its slot, it pays nothing
              this.particles.burst(this.slotX(slot), slotTop, ['#8b939b', '#ff8a1a'], 6, 160);
              return;
            }
            this.slots[slot]?.hit();
            this.landingEffect(slot, prizeIndexOfSlot(rows, slot), gateBoost * mega);
            if (mega > 1) this.megaLanding(this.slotX(slot), slotTop, mega, !pegs.quiet);
            if (this.currentSpinSlots().includes(slot)) this.spinLanding(slot);
            events.onLand?.({ slot, prizeIndex: prizeIndexOfSlot(rows, slot), x: this.slotX(slot), y: this.slotY(), pan: this.pan(this.slotX(slot)) });
          }
        },
      });
      timeline.call(() => {
        this.flights.delete(flight);
        stopFollowing();
        ball.destroy();
        if (this.flights.size === 0) this.hurry = 1;
      });
    });
  }

  /**
   * A MEGA BALL on its way: the ball turns gold in a pulsing halo, with its multiplier riding above it, and MEGA BALL
   * xN is stamped across the top of the board as it drops. Returns what stops the halo and label following it.
   */
  private megaBall(ball: Sprite, times: number, announce = true): () => void {
    ball.tint = '#ffd76a';
    const halo = new Graphics().circle(0, 0, 22).fill({ color: '#ffb000', alpha: 0.35 }).circle(0, 0, 14).fill({ color: '#fff3b0', alpha: 0.5 });
    halo.blendMode = 'add';
    const tag = goldText('x' + times, 22);
    this.ballLayer.addChildAt(halo, 0);
    this.textLayer.addChild(tag);
    const pulse = gsap.to(halo.scale, { x: 1.5, y: 1.5, duration: 0.25, yoyo: true, repeat: -1, ease: 'sine.inOut' });
    const follow = () => {
      halo.position.copyFrom(ball.position);
      tag.position.set(ball.x, ball.y - 30);
    };
    follow();
    this.followers.add(follow);
    // the stamp across the top of the board
    if (announce) this.stamp('MEGA BALL x' + times);
    return () => {
      this.followers.delete(follow);
      pulse.kill();
      halo.destroy();
      tag.destroy();
    };
  }

  /** A big word across the top of the board: it slams in, the board jolts, it fades. */
  stamp(text: string): void {
    const stamp = goldText(text, 40);
    stamp.position.set(0, PEG_SPACING * 0.6);
    if (stamp.width > PEG_SPACING * (this.rows + 1)) stamp.scale.set(PEG_SPACING * (this.rows + 1) / stamp.width);
    const size = stamp.scale.x;
    this.textLayer.addChild(stamp);
    gsap.timeline({ onComplete: () => stamp.destroy() })
      .fromTo(stamp.scale, { x: size * 3, y: size * 3 }, { x: size, y: size, duration: 0.3, ease: 'power3.in' })
      .call(() => { shake(this, 6); this.particles.burst(0, PEG_SPACING * 0.6, ['#ffd76a', '#fff3b0', '#ff8a1a'], 24, 320); })
      .to(stamp, { alpha: 0, y: stamp.y - 20, duration: 0.5 }, 1.3);
  }

  /** A MEGA BALL in its slot: a gold shockwave, a fountain of gold and xN MEGA! stamped over the slot. */
  private megaLanding(x: number, y: number, times: number, loud = true): void {
    if (loud) shake(this, 10);
    kindBlast(this.textLayer, 'napalm', x, y + 18, loud ? 210 : 150);
    this.particles.burst(x, y, ['#ffd76a', '#fff3b0', '#e9a91a', '#ffffff'], loud ? 48 : 16, loud ? 520 : 360);
    if (loud) this.particles.ring(x, y, '#ffd76a', 90);
    const word = goldText('x' + times + (loud ? ' MEGA!' : ''), loud ? 44 : 28);
    word.position.set(x, y - 70);
    this.textLayer.addChild(word);
    gsap.timeline({ onComplete: () => word.destroy() })
      .fromTo(word.scale, { x: 3, y: 3 }, { x: 1, y: 1, duration: 0.25, ease: 'power3.in' })
      .to(word.scale, { x: 1.15, y: 1.15, duration: 0.15, yoyo: true, repeat: 3 })
      .to(word, { alpha: 0, y: word.y - 40, duration: 0.6 }, 1.4);
  }

  /**
   * Where a point of the frame picture (its own pixels at 1x) is, on the screen: the console's parts are found this way.
   * Null when the frame of this shape isn't on screen.
   */
  framePoint(x: number, y: number, landscape: boolean): { x: number; y: number } | null {
    const picture = landscape ? this.frame : this.framePortrait;
    if (!picture.visible) return null;
    return picture.toGlobal({ x: x - picture.texture.width / 2, y: y - picture.texture.height / 2 });
  }

  /** Where a slot is, on the screen. */
  slotPoint(slot: number): { x: number; y: number } {
    return this.field.toGlobal({ x: this.slotX(slot), y: this.slotY() });
  }

  /**
   * Shows what a landed ball paid, floating up from its slot (green when it pays more than the bet). A slot shows
   * one amount at a time, and neighbouring slots alternate heights so amounts never sit on top of each other.
   */
  showAmount(slot: number, text: string, win: boolean): void {
    const previous = this.amounts.get(slot);
    if (previous) this.removeAmount(previous);
    const amount = new Text({
      text, anchor: { x: 0.5, y: 1 }, resolution: textQuality(),
      style: { fontFamily: typeface.family, fontWeight: typeface.weight as 'normal', fontSize: 20, fill: win ? '#3BF07A' : '#FF5A5A',
        dropShadow: { color: '#000000', alpha: 0.85, blur: 4, distance: 2, angle: Math.PI / 2 } },
    });
    amount.position.set(this.slotX(slot), this.slotY() - SLOT_H / 2 - 6 - (slot % 2) * 24);
    amount.scale.set(Math.min(1, (PEG_SPACING * 2 - 6) / amount.width)); // never wider than two slots
    const size = amount.scale.x;
    this.textLayer.addChild(amount);
    this.amounts.set(slot, amount);
    gsap.timeline({ onComplete: () => { if (this.amounts.get(slot) === amount) this.amounts.delete(slot); amount.destroy(); } })
      .from(amount.scale, { x: size * 0.6, y: size * 0.6, duration: 0.15, ease: 'back.out(2)' })
      .to(amount, { y: amount.y - 30, alpha: 0, duration: 0.8, ease: 'circ.in' }, 0.35);
  }

  clearAmounts(): void {
    this.amounts.forEach(amount => this.removeAmount(amount));
    this.amounts.clear();
  }

  private removeAmount(amount: Text): void {
    gsap.killTweensOf(amount);
    gsap.killTweensOf(amount.scale);
    amount.destroy();
  }

  /** The line above the pegs: what a play costs, what is happening, what was won. */
  setStatus(text: string): void {
    this.status.text = text;
    // never wider than the top of the board (long amounts shrink rather than run past the frame)
    this.status.scale.set(1);
    if (this.status.width > STATUS_MAX_WIDTH) this.status.scale.set(STATUS_MAX_WIDTH / this.status.width);
  }

  /**
   * Every frame, after the routes have moved: balls that touch knock each other aside (springy, settling back onto
   * their route), then every ball is drawn at route + knock.
   */
  update(deltaMs: number): void {
    const dt = Math.min(deltaMs, 50) / 1000;
    this.idle(dt);
    const flights = [...this.flights];
    for (const f of flights) {
      // spring back to the route; balls going into a slot settle faster
      const k = f.free ? 260 : 900;
      f.vx += (-k * f.ox - 22 * f.vx) * dt;
      f.vy += (-k * f.oy - 22 * f.vy) * dt;
      f.ox += f.vx * dt;
      f.oy += f.vy * dt;
      f.cooldown -= dt;
    }
    for (let i = 0; i < flights.length; i++) {
      const a = flights[i];
      if (!a.free) continue;
      for (let j = i + 1; j < flights.length; j++) {
        const b = flights[j];
        if (!b.free) continue;
        const dx = a.x + a.ox - (b.x + b.ox);
        const dy = a.y + a.oy - (b.y + b.oy);
        const d2 = dx * dx + dy * dy;
        if (d2 > (2 * BALL_RADIUS) ** 2 || d2 < 0.01 || (a.cooldown > 0 && b.cooldown > 0)) continue;
        const d = Math.sqrt(d2);
        const nx = dx / d, ny = dy / d;
        // a gentle nudge, not a kick: a crowd of balls slides past each other instead of jittering
        const push = 70;
        a.vx += nx * push; a.vy += ny * push;
        b.vx -= nx * push; b.vy -= ny * push;
        a.cooldown = b.cooldown = 0.25;
        this.particles.sparks((a.x + a.ox + b.x + b.ox) / 2, (a.y + a.oy + b.y + b.oy) / 2, '#ffffff', 3, 10);
        this.onClack?.();
      }
    }
    for (const f of flights) {
      // a hot ball sheds sparks as it falls
      if (f.heat > 0 && f.free && Math.random() < 0.25 * f.heat) {
        this.particles.sparks(f.sprite.x, f.sprite.y, ['#ffd76a', '#ff8a1a', '#ff3b7a'][Math.min(f.heat, 3) - 1], 1, 18);
      }
      const limit = BALL_RADIUS * 1.5;
      f.ox = Math.max(-limit, Math.min(limit, f.ox));
      f.oy = Math.max(-limit, Math.min(limit, f.oy));
      f.sprite.position.set(f.x + f.ox, f.y + f.oy);
    }
    this.followers.forEach(follow => follow());
  }

  /** A ball goes into its slot: a puff for small prizes, sparks, a fountain with a ring, and a shake for the top ones. */
  private landingEffect(slot: number, prizeIndex: number, gates = 1): void {
    const multiplier = this.multiplier(prizeIndex) * gates;
    const face = slotLooks[slotTier(this.rows, prizeIndex)].face;
    const x = this.slotX(slot);
    const y = this.slotY() - SLOT_H / 2;
    if (this.fx === 'war') this.explode(x, y, multiplier);
    if (multiplier < 1) {
      this.particles.sparks(x, y, '#8a9097', 4, 12);
    } else if (multiplier < 2) {
      this.particles.burst(x, y, [face, '#ffffff'], 8, 160);
    } else if (multiplier < 10) {
      this.particles.burst(x, y, [face, '#ffffff'], 16, 220);
      this.particles.ring(x, this.slotY(), face, 36);
    } else {
      this.particles.burst(x, y, [face, '#ffffff', this.pegFlashColour], 30, 300);
      this.particles.ring(x, this.slotY(), face, 60);
      gsap.delayedCall(0.12, () => this.particles.ring(x, this.slotY(), '#ffffff', 90));
      shake(this, 7);
    }
    if (multiplier >= 2) this.particles.burst(x, y, ['#ff3b7a', '#ffd76a', '#2ec4ff', '#7dffa0', '#ffffff'], multiplier >= 5 ? 26 : 14, 360);
    if (multiplier >= 5) this.beam(x, face, multiplier >= 10);
    if (multiplier >= 10) {
      // a coin fountain and a double shockwave
      this.particles.burst(x, y, ['#ffd76a', '#fff3b0', '#e9a91a'], 40, 620);
      gsap.delayedCall(0.2, () => this.particles.ring(x, this.slotY(), '#ffd76a', 150));
      gsap.delayedCall(0.35, () => this.particles.ring(x, this.slotY(), '#ffffff', 210));
    }
  }

  private comboText: Text | null = null;

  /** COMBO ×n over the board: winning balls in a row; each new one punches it bigger and hotter. */
  combo(count: number): void {
    if (count < 3) {
      if (this.comboText) {
        const old = this.comboText;
        this.comboText = null;
        gsap.killTweensOf([old, old.scale]);
        gsap.to(old, { alpha: 0, duration: 0.3, onComplete: () => old.destroy() });
      }
      return;
    }
    if (!this.comboText) {
      this.comboText = goldText('', 34);
      this.comboText.position.set(0, PEG_SPACING * 0.2);
      this.textLayer.addChild(this.comboText);
    }
    const text = this.comboText;
    text.text = `COMBO ×${count}`;
    text.alpha = 1;
    const size = Math.min(1.6, 0.9 + count * 0.07);
    gsap.killTweensOf(text.scale);
    gsap.fromTo(text.scale, { x: size * 1.5, y: size * 1.5 }, { x: size, y: size, duration: 0.35, ease: 'back.out(3)' });
    text.rotation = (Math.random() - 0.5) * 0.12;
    this.particles.sparks(0, PEG_SPACING * 0.2, '#ffd76a', 8, 50);
  }

  /** A ball comes in: a ring of light opens at the top of the board and throws a few sparks. */
  private launch(y: number): void {
    const many = this.flights.size > 12;
    this.particles.ring(0, y, this.pegFlashColour, many ? 16 : 28);
    if (!many) this.particles.sparks(0, y, '#ffffff', 6, 26);
  }

  /** A column of light shooting up out of a slot that paid big, in the slot's colour with a white core. */
  private beam(x: number, colour: string, huge: boolean): void {
    const height = this.slotY() - PEG_SPACING * (huge ? 0.5 : 2);
    const w = SLOT_W * (huge ? 0.9 : 0.6);
    const column = new Container();
    const outer = new Graphics().rect(-w, -height, 2 * w, height).fill({ color: colour, alpha: 0.45 });
    const core = new Graphics().rect(-w * 0.35, -height, w * 0.7, height).fill({ color: '#ffffff', alpha: 0.8 });
    outer.blendMode = 'add';
    core.blendMode = 'add';
    column.addChild(outer, core);
    column.position.set(x, this.slotY() - SLOT_H / 2);
    this.trailLayer.addChild(column);
    gsap.timeline({ onComplete: () => column.destroy({ children: true }) })
      .fromTo(column.scale, { x: 0.1, y: 0 }, { x: 1, y: 1, duration: 0.18, ease: 'power3.out' })
      .to(column.scale, { x: 0, duration: huge ? 0.9 : 0.5, ease: 'power2.in' }, huge ? 0.5 : 0.25)
      .to(column, { alpha: 0, duration: 0.3 }, huge ? 1.1 : 0.55);
  }

  /**
   * Bombs Away: every ball that lands blows up. A fireball and smoke from every slot, debris flying; a good landing
   * also stamps BOOM!, KABOOM! or DIRECT HIT! over it in stencil, and the board jolts.
   */
  private explode(x: number, y: number, multiplier: number): void {
    const size = multiplier >= 10 ? 3 : multiplier >= 2 ? 2 : multiplier >= 1 ? 1 : 0;
    this.particles.burst(x, y, ['#fff3b0', '#ffd76a', '#ff8a1a', '#c1121f'], [6, 12, 22, 36][size], [150, 220, 320, 440][size]);
    // smoke rising out of the hole
    this.particles.burst(x, y - 6, ['#3a3d42', '#55595f', '#24272b'], [3, 6, 10, 16][size], [60, 90, 120, 160][size], -260);
    // debris: dark chunks thrown out and falling back
    if (size >= 1) this.particles.burst(x, y, ['#1a1d21', '#4b5320', '#2b3036'], [0, 6, 10, 16][size], [0, 260, 360, 480][size], 1400);
    // the theme's explosions: a small hit for small wins, a fireball for good ones, a shell impact for big ones; else
    // the photo blast; else a flash
    const kind = (['small', 'small', 'fireball', 'impact'] as const)[size];
    const shown = kindBlast(this.textLayer, kind, x, size === 3 ? y + 14 : y, [40, 58, 92, 130][size])
      || photoBlast(this.textLayer, x, y, [26, 38, 56, 84][size], size >= 1);
    if (!shown) {
      const flash = new Graphics().circle(0, 0, [10, 16, 24, 36][size]).fill('#fff3b0');
      flash.blendMode = 'add';
      flash.position.set(x, y);
      this.textLayer.addChild(flash);
      gsap.timeline({ onComplete: () => flash.destroy() })
        .fromTo(flash.scale, { x: 0.3, y: 0.3 }, { x: 1.4, y: 1.4, duration: 0.18, ease: 'power2.out' })
        .to(flash, { alpha: 0, duration: 0.25 }, 0.05);
    }
    if (size < 2) return;
    shake(this, size === 3 ? 9 : 4);
    const words = size === 3 ? ['DIRECT HIT!', 'KABOOM!', 'NUKED!'] : ['BOOM!', 'BLAM!', 'HIT!'];
    const word = label(words[Math.floor(Math.random() * words.length)], size === 3 ? 34 : 24, 'center',
      { fill: size === 3 ? '#f2b705' : '#e9e4d4', stroke: { color: '#0b0d10', width: 6, join: 'miter' } });
    word.anchor.set(0.5, 0.5);
    word.position.set(x, y - 40);
    word.rotation = (Math.random() - 0.5) * 0.3;
    this.textLayer.addChild(word);
    gsap.timeline({ onComplete: () => word.destroy() })
      .fromTo(word.scale, { x: 2.4, y: 2.4 }, { x: 1, y: 1, duration: 0.2, ease: 'power3.in' })
      .to(word, { y: word.y - 30, alpha: 0, duration: 0.6, ease: 'power1.in' }, 0.55);
  }

  /**
   * The ball's comet tail: glowing dots that shrink and fade behind it. A ball through gate bars burns hotter: gold,
   * then orange, then hot pink, with a longer, wider tail.
   */
  private streak(ball: Sprite, radius: number, heat: number): void {
    if (this.fx === 'war') {
      // a grenade: its fuse, and after a gate it burns: soft flames, red to yellow the hotter it is (never a white stripe)
      this.fuse(ball, radius);
      if (heat > 0 && Math.random() < 0.5) this.particles.flame(ball.x, ball.y, WAR_FIRE[Math.min(heat, WAR_FIRE.length) - 1], radius * (0.3 + 0.08 * heat), 0.16 + 0.04 * heat);
      return;
    }
    const context = heat > 0 ? HEAT[Math.min(heat, HEAT.length) - 1] : this.trailDot;
    if (!context) return;
    const dot = new Graphics(context);
    dot.blendMode = 'add';
    dot.position.copyFrom(ball.position);
    const size = radius * (0.7 + 0.25 * heat);
    const life = 0.32 + 0.12 * heat;
    dot.scale.set(size);
    dot.alpha = 0.85;
    this.trailLayer.addChild(dot);
    gsap.to(dot, { alpha: 0, duration: life, ease: 'power1.in', onComplete: () => dot.destroy() });
    gsap.to(dot.scale, { x: size * 0.15, y: size * 0.15, duration: life });
  }

  /**
   * Bombs Away: the grenade's lit fuse: a few sparks fizzing on the fuse head on top (it turns as the grenade spins).
   * The whole board shares a budget a frame (about 6 sparks), so one grenade fizzes a lot and twenty a little each.
   */
  private fuse(ball: Sprite, radius: number): void {
    this.fuseTick = (this.fuseTick + 1) % 1000;
    const balls = Math.max(1, this.flights.size);
    const top = radius * 0.85 * ball.scale.y;
    const x = ball.x + Math.sin(ball.rotation) * top;
    const y = ball.y - Math.cos(ball.rotation) * top;
    const out = ball.rotation - Math.PI / 2;   // the way the fuse points
    // a very short fuse: a few sparks fizzing right on the fuse head, no smoke trail
    if (Math.random() < Math.min(0.5, 6 / balls)) this.particles.spit(x, y, FUSE[this.fuseTick % FUSE.length], out, radius * 0.15);
  }

  /**
   * How a falling ball is turned: rolling with its sideways travel, or (a war theme's bomb, lit from above) only
   * swaying a little, so its shine stays where the light is.
   */
  private spin(x: number, radius: number): number {
    return this.fx === 'war' ? Math.sin(x / (radius * 3)) * 0.35 : x / radius;
  }

  /** Pegs blown away this round, as "row:column", to put back at the next round. */
  private readonly smashedNow = new Set<string>();
  /** A peg smashed (for its sound). */
  onSmash: (() => void) | null = null;

  /** A MEGA BALL blows a peg away: a blast, debris, and the peg is gone until the next round. */
  private smashPeg(row: number, column: number): void {
    const id = row + ':' + column;
    const peg = this.pegs[row]?.[column];
    if (!peg || this.smashedNow.has(id)) return;
    this.smashedNow.add(id);
    gsap.killTweensOf(peg.scale);
    peg.visible = false;
    const flash = this.pegFlashes[row]?.[column];
    if (flash) { gsap.killTweensOf(flash); flash.alpha = 0; }
    this.particles.burst(peg.x, peg.y, ['#fff3b0', '#ffd76a', '#ff8a1a'], 10, 260);
    this.particles.burst(peg.x, peg.y, ['#8b939b', '#4c535a', '#2a2f35'], 8, 300, 1400);
    this.particles.ring(peg.x, peg.y, '#ff8a1a', 26);
    this.onSmash?.();
  }

  /** The smashed pegs come back (at the start of a round). */
  restorePegs(): void {
    for (const id of [...this.smashedNow]) {
      const [row, column] = id.split(':').map(Number);
      this.restorePeg(row, column);
    }
  }

  private restorePeg(row: number, column: number): void {
    const id = row + ':' + column;
    if (!this.smashedNow.delete(id)) return;
    const peg = this.pegs[row]?.[column];
    if (!peg) return;
    peg.visible = true;
    gsap.fromTo(peg.scale, { x: 0, y: 0 }, { x: 1, y: 1, duration: 0.25, ease: 'back.out(3)' });
  }

  private pegColumn(row: number, x: number): number {
    const count = 3 + row;
    return Math.round((x + PEG_SPACING * (count - 1) / 2) / PEG_SPACING);
  }

  /** The ball touches a peg: the ball squashes a little and the peg flashes. */
  /** Where x is across the board, -1 (left edge) .. 1 (right edge), for stereo sound. */
  private pan(x: number): number {
    return Math.max(-1, Math.min(1, x / (PEG_SPACING * (this.rows + 2) / 2)));
  }

  private touchPeg(ball: Sprite, row: number, column: number, onPeg?: (peg: PegHit) => void, size = 1): void {
    // a small squash around the ball's own size (never back to 1: a MEGA or hot ball keeps its size)
    gsap.fromTo(ball.scale, { x: size * 1.06, y: size * 0.94 }, { x: size, y: size, duration: 0.12, ease: 'power1.out', overwrite: true });
    const flash = this.pegFlashes[row]?.[column];
    const peg = this.pegs[row]?.[column];
    if (peg) {
      const many = this.flights.size > 15;
      this.particles.sparks(peg.x, peg.y, this.pegFlashColour, many ? 2 : 4);
      if (!many) this.particles.ring(peg.x, peg.y, this.pegFlashColour, 11);
      gsap.fromTo(peg.scale, { x: 1.35, y: 1.35 }, { x: 1, y: 1, duration: 0.25, ease: 'back.out(3)', overwrite: true });
    }
    if (flash) {
      gsap.killTweensOf(flash);
      gsap.killTweensOf(flash.scale);
      gsap.timeline()
        .to(flash, { alpha: 0.5, duration: 0.3 }, 0)
        .to(flash.scale, { x: 2, y: 2, duration: 0.3 }, 0)
        .to(flash, { alpha: 0, duration: 0.3 })
        .to(flash.scale, { x: 1, y: 1, duration: 0.3 }, '<');
    }
    onPeg?.({ row, rows: this.rows, pan: this.pan(peg?.x ?? ball.x) });
  }
}
