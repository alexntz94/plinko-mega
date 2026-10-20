import { Container, Graphics, GraphicsContext, Sprite, Text, Texture } from 'pixi.js';
import { gsap } from 'gsap';
import type { Viewport } from './viewport';
import { label, textQuality, typeface } from './ui';
import { Particles, shake } from './effects';
import type { BoardStyle, ThemeFx } from './theme';
import { prizeIndexOfSlot, slotTier } from './rules';

export { prizeIndexOfSlot, slotTier };

// Board geometry (same as the original game): pegs 45 apart, row r has 3 + r pegs, slots under the last row.
export const PEG_SPACING = 45;
/** The status line's widest: the top of the board, between the frame's edges. */
const STATUS_MAX_WIDTH = 250;
const PEG_RADIUS = 5;
const BALL_REST = 14.5;            // how far above a peg's centre the ball sits when it touches it
const BALL_RADIUS = BALL_REST - PEG_RADIUS;   // the ball's size for knocking into other balls
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
function spinBadge(): Container {
  const badge = new Container();
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

  setValue(value: number): void {
    this.text.text = multiplierText(value);
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
    // always dips from and back to its resting place, however many balls land at once
    gsap.killTweensOf(this);
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

  constructor(frame: Texture, framePortrait: Texture, private readonly ballTexture: Texture, style: BoardStyle,
              pegTexture: Texture | null) {
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
        const y = PEG_SPACING * (r + 1);
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

  private drawMega(): void {
    // the bars' shimmer and glow run forever: stopped before the bars go
    this.gateLayer.removeChildren().forEach(view => {
      gsap.killTweensOf([view, view.scale, ...view.children]);
      view.destroy({ children: true });
    });
    this.gates = [];
    const spin = this.currentSpinSlots();
    this.slots.forEach((slot, i) => { slot.spinMark.visible = spin.includes(i); });
    if (this.gateBoost <= 0) return;
    for (const [step, from, to] of this.megaGates[String(this.rows)] ?? []) {
      const view = this.gateView(from, to, step);
      view.position.set(PEG_SPACING / 2 * (from + to - step), PEG_SPACING * (step + 1) - BALL_REST);
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
    gsap.killTweensOf(v.scale);
    gsap.fromTo(v.scale, { x: 1.12, y: 1.8 }, { x: 1, y: 1, duration: 0.45, ease: 'elastic.out(1.2, 0.4)' });
    this.particles.burst(v.x, v.y - 8, [megaLook.accent, '#ffffff'], 14, 260);
    this.particles.ring(v.x, v.y - 8, megaLook.accent, 26);
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
      if (slot.tier === top) slot.scale.set(1 + 0.06 * Math.max(0, Math.sin(this.time * 5)) ** 2);
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
    return PEG_SPACING * (this.rows + 1) + 12;
  }

  /** Width and height of the board's content, used to scale it into the available space. */
  contentSize(): { w: number; h: number } {
    return { w: PEG_SPACING * (this.rows + 2), h: PEG_SPACING * (this.rows + 1) };
  }

  /** Places the frame and the board for the current orientation (same rules as the original game). */
  layout(view: Viewport, place: { x: number; y: number; fit: number } | null = null): void {
    const { w, h } = this.contentSize();
    // on wide phones the top and bottom of the stage are cut off: keep the pegs, the slots and the status line inside
    const visibleH = view.stageH - 2 * view.offsetY;
    const max = view.landscape ? { w: 1000, h: Math.min(510, visibleH - 110) } : { w: 570, h: 440 };
    // Plinko Mega places the board itself (room for the wheel and the control bar) and shrinks it, frame and all
    const fitTo = place?.fit ?? 1;
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
  dropBall(slot: number, events: { onPeg?: (peg: PegHit) => void; onLand?: (landing: Landing) => void; onDrama?: () => void } = {},
           path: number | null = null): Promise<void> {
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
    const points: { x: number; y: number }[] = [{ x: 0, y: PEG_SPACING - BALL_REST }];
    let x = 0;
    for (let r = 1; r < rows; r++) {
      x += steps[r - 1] * PEG_SPACING / 2;
      points.push({ x, y: PEG_SPACING * (r + 1) - BALL_REST });
    }
    const landX = x + steps[rows - 1] * PEG_SPACING / 2;

    const ball = new Sprite({ texture: this.ballTexture, anchor: 0.5 });
    ball.position.set(0, 0);
    this.ballLayer.addChild(ball);
    const flight: Flight = { sprite: ball, x: 0, y: 0, ox: 0, oy: 0, vx: 0, vy: 0, free: true, cooldown: 0 };
    this.flights.add(flight);

    const radius = ball.texture.width / 2;
    // every ball has its own pace, but within a ball the hops change smoothly (no random jerks from peg to peg)
    const pace = random(0.27, 0.31);
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
            ball.rotation = flight.x / radius;
            this.streak(ball, radius);
          },
        });
      };

      // appears and falls onto the first peg, speeding up
      ball.alpha = 0;
      timeline.to(ball, { alpha: 1, duration: 0.12 });
      timeline.to(flight, { y: points[0].y, duration: 0.34, ease: 'power2.in' }, 0);
      timeline.call(() => this.touchPeg(ball, 0, 0, events.onPeg));
      // peg to peg: hops get a little quicker and flatter as the ball picks up speed
      for (let i = 1; i < points.length; i++) {
        const progress = i / points.length;
        arc(points[i - 1], points[i], PEG_SPACING * (0.34 - 0.14 * progress) * random(0.92, 1.08),
          pace * (1 - 0.28 * progress) * random(0.97, 1.03));
        const column = this.pegColumn(i, points[i].x);
        timeline.call(() => this.touchPeg(ball, i, column, events.onPeg));
        // a ball on its way to something big: slow motion for its last rows (the game decides which)
        if (events.onDrama && i === Math.max(1, points.length - 3)) timeline.call(() => events.onDrama?.());
        const gate = passes.get(i);
        if (gate) timeline.call(() => { gateBoost *= this.gateBoost; this.gatePass(gate, gateBoost); ball.scale.set(1 + 0.12 * Math.log2(gateBoost)); });
        // now and then the ball bounces up off a peg and comes down on it again before going on
        if (i < points.length - 2 && Math.random() < 0.18) {
          arc(points[i], points[i], PEG_SPACING * random(0.22, 0.32) * (1 - 0.4 * progress), pace * 0.62);
          timeline.call(() => this.touchPeg(ball, i, column, events.onPeg));
        }
      }
      // into the hole: the last fall keeps its speed and goes behind the slot, which swallows the ball
      const last = points[points.length - 1];
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
          ball.rotation = flight.x / radius;
          if (!entered && flight.y > slotTop - radius * 0.3) {
            entered = true;
            this.slots[slot]?.hit();
            this.landingEffect(slot, prizeIndexOfSlot(rows, slot));
            if (this.currentSpinSlots().includes(slot)) this.spinLanding(slot);
            events.onLand?.({ slot, prizeIndex: prizeIndexOfSlot(rows, slot), x: this.slotX(slot), y: this.slotY(), pan: this.pan(this.slotX(slot)) });
          }
        },
      });
      timeline.call(() => {
        this.flights.delete(flight);
        ball.destroy();
        if (this.flights.size === 0) this.hurry = 1;
      });
    });
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
        const push = 150;
        a.vx += nx * push; a.vy += ny * push;
        b.vx -= nx * push; b.vy -= ny * push;
        a.cooldown = b.cooldown = 0.15;
        this.particles.sparks((a.x + a.ox + b.x + b.ox) / 2, (a.y + a.oy + b.y + b.oy) / 2, '#ffffff', 3, 10);
        this.onClack?.();
      }
    }
    for (const f of flights) {
      const limit = BALL_RADIUS * 1.5;
      f.ox = Math.max(-limit, Math.min(limit, f.ox));
      f.oy = Math.max(-limit, Math.min(limit, f.oy));
      f.sprite.position.set(f.x + f.ox, f.y + f.oy);
    }
  }

  /** A ball goes into its slot: a puff for small prizes, sparks, a fountain with a ring, and a shake for the top ones. */
  private landingEffect(slot: number, prizeIndex: number): void {
    const multiplier = this.multiplier(prizeIndex);
    const face = slotLooks[slotTier(this.rows, prizeIndex)].face;
    const x = this.slotX(slot);
    const y = this.slotY() - SLOT_H / 2;
    if (this.fx === 'loteria') {
      // marigold petals out of every landing; a card is called on a good one
      this.particles.burst(x, y, ['#f59a1b', '#e3197c', '#f7f1e3'], multiplier < 1 ? 4 : multiplier < 2 ? 8 : 14, multiplier < 2 ? 140 : 220);
      if (multiplier >= 2) this.callCard(x, y, multiplier);
    }
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
    if (multiplier >= 5) this.beam(x, face, multiplier >= 10);
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
   * Lotería Muerta: a good landing calls a card, as the caller does in Lotería. A little paper card flips up out of
   * the slot with its number and name (a big prize calls La Muerte or La Calavera), wobbles and drifts away.
   */
  private callCard(x: number, y: number, multiplier: number): void {
    const SMALL: [number, string][] = [[2, 'EL DIABLITO'], [4, 'EL CATRÍN'], [9, 'EL ARBOLITO'], [12, 'EL BORRACHO'], [24, 'LA ROSA'],
      [27, 'EL CORAZÓN'], [35, 'LA ESTRELLA'], [43, 'LA LUNA'], [50, 'EL VALIENTE'], [2, 'LA CATRINA']];
    const BIG: [number, string][] = [[13, 'LA MUERTE'], [1, 'LA CALAVERA'], [54, '¡LOTERÍA!']];
    const pool = multiplier >= 10 ? BIG : SMALL;
    const [number, name] = pool[Math.floor(Math.random() * pool.length)];
    const w = multiplier >= 10 ? 74 : 58;
    const h = w * 1.4;
    const cardView = new Container({ label: 'called-card' });
    const paper = new Graphics().roundRect(-w / 2 + 2, -h / 2 + 4, w, h, 4).fill({ color: '#000000', alpha: 0.4 })
      .roundRect(-w / 2, -h / 2, w, h, 4).fill('#efe0bf').stroke({ color: '#1f1512', width: 2.5 })
      .rect(-w / 2 + 4, -h / 2 + 4, w - 8, h - 8).stroke({ color: '#1f1512', width: 1 });
    const num = label(String(number), w * 0.24, 'left', { fill: '#1f1512' });
    num.position.set(-w / 2 + 7, -h / 2 + 5);
    const title = label(name, w * 0.16, 'center', { fill: multiplier >= 10 ? '#c41a1f' : '#1f1512' });
    title.anchor.set(0.5, 0.5);
    title.position.set(0, h / 2 - w * 0.17);
    if (title.width > w - 8) title.scale.set((w - 8) / title.width);
    const times = label('×' + multiplierText(multiplier), w * 0.32, 'center', { fill: multiplier >= 10 ? '#c41a1f' : '#e3197c',
      stroke: { color: '#1f1512', width: 3, join: 'round' } });
    times.anchor.set(0.5, 0.5);
    times.y = -w * 0.08;
    cardView.addChild(paper, num, title, times);
    cardView.position.set(x, y);
    this.textLayer.addChild(cardView);
    const tilt = (Math.random() - 0.5) * 0.5;
    gsap.timeline({ onComplete: () => cardView.destroy({ children: true }) })
      .fromTo(cardView.scale, { x: 0, y: 0.4 }, { x: 1, y: 1, duration: 0.35, ease: 'back.out(2.2)' })
      .to(cardView, { y: y - h * 0.9, rotation: tilt, duration: 0.35, ease: 'power2.out' }, 0)
      .to(cardView, { rotation: -tilt, duration: 0.18, yoyo: true, repeat: 2, ease: 'sine.inOut' })
      .to(cardView, { y: y - h * 1.5, alpha: 0, duration: 0.6, ease: 'power1.in' }, '+=0.25');
    if (multiplier >= 10) shake(this, 6);
  }

  /** The theme's trail: small dots left behind the ball that shrink and fade, so a falling ball draws a streak. */
  private streak(ball: Sprite, radius: number): void {
    if (!this.trailDot) return;
    const dot = new Graphics(this.trailDot);
    dot.position.copyFrom(ball.position);
    dot.scale.set(radius * 0.6);
    dot.alpha = 0.7;
    this.trailLayer.addChild(dot);
    gsap.to(dot, { alpha: 0, duration: 0.28, ease: 'power1.in', onComplete: () => dot.destroy() });
    gsap.to(dot.scale, { x: radius * 0.12, y: radius * 0.12, duration: 0.28 });
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

  private touchPeg(ball: Sprite, row: number, column: number, onPeg?: (peg: PegHit) => void): void {
    gsap.fromTo(ball.scale, { x: 1.07, y: 0.93 }, { x: 1, y: 1, duration: 0.1, ease: 'power1.out', overwrite: true });
    const flash = this.pegFlashes[row]?.[column];
    const peg = this.pegs[row]?.[column];
    if (peg) {
      const many = this.flights.size > 15;
      this.particles.sparks(peg.x, peg.y, this.pegFlashColour, many ? 2 : 4);
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
