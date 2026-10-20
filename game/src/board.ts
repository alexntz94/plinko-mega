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
/** A colour too dark to carry a dark outline (relative luminance under 0.2). */
function isDark(colour: string): boolean {
  const n = parseInt(colour.replace('#', ''), 16);
  const lin = (c: number) => { const v = c / 255; return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4; };
  return 0.2126 * lin((n >> 16) & 255) + 0.7152 * lin((n >> 8) & 255) + 0.0722 * lin(n & 255) < 0.2;
}
/** The status line's widest: the top of the board, between the frame's edges. */
const STATUS_MAX_WIDTH = 250;
const PEG_RADIUS = 5;
const BALL_REST = 14.5;            // how far above a peg's centre the ball sits when it touches it
const BALL_RADIUS = BALL_REST - PEG_RADIUS;   // the ball's size for knocking into other balls
const GOLD = '#ffd54a';
const goldDot = new GraphicsContext().circle(0, 0, 1).fill(GOLD);
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

  constructor(readonly prizeIndex: number, tier: number) {
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
    this.addChild(...body, this.flash, this.text, this.meterMark, this.spinMark);
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
  tracker?: Container; timeline?: gsap.core.Timeline }

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
  private readonly glowColour: string;
  private readonly fx: ThemeFx;
  private subjects = 0;
  private hot: { slot: number; boost: number; glow: Graphics; badge: Text } | null = null;
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
  private gates: { step: number; rights: number; view: Container }[] = [];

  constructor(frame: Texture, framePortrait: Texture, private readonly ballTexture: Texture, style: BoardStyle,
              pegTexture: Texture | null) {
    super();
    this.trailDot = style.trail ? new GraphicsContext().circle(0, 0, 1).fill(style.trail) : null;
    this.pegFlashColour = style.pegFlash;
    this.glowColour = style.trail ?? style.pegFlash;
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
    this.drawBumpButton();
    this.field.addChild(this.status, this.gateLayer, this.holeLayer, this.slotLayer, this.trailLayer, this.ballLayer, this.particles, this.textLayer,
      this.bumpButton);
    this.addChild(this.frame, this.framePortrait, this.field);
  }

  /** Rows (8..14) and the multipliers per prize position ([low, mid, high] each), from the server's config. */
  setRows(rows: number, prizes: number[][]): void {
    this.rows = rows;
    this.prizes = prizes;
    this.pegs.forEach((row, r) => row.forEach(peg => { peg.visible = r < rows; }));
    this.clearHot();
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
    this.bumpButton.position.set(this.slotX(rows) + PEG_SPACING * 0.2, PEG_SPACING * 1.2);
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
    this.gateLayer.removeChildren().forEach(child => child.destroy({ children: true }));
    this.gates = [];
    const spin = this.currentSpinSlots();
    this.slots.forEach((slot, i) => { slot.spinMark.visible = spin.includes(i); });
    if (this.gateBoost <= 0) return;
    for (const [step, rights] of this.megaGates[String(this.rows)] ?? []) {
      const view = this.gateView();
      view.position.set(PEG_SPACING / 2 * (2 * rights - step), PEG_SPACING * (step + 1));
      this.gateLayer.addChild(view);
      this.gates.push({ step, rights, view });
    }
  }

  /** A gate: an arch over the peg a ball must touch to go through it, with the boost on its keystone. */
  private gateView(): Container {
    const view = new Container();
    if (megaLook.art) {
      view.addChild(new Sprite({ texture: megaLook.art, anchor: { x: 0.5, y: 0.62 } }));
    } else {
      const arch = new Graphics()
        .moveTo(-17, 12).lineTo(-17, -6).arc(0, -6, 17, Math.PI, 0).lineTo(17, 12)
        .stroke({ color: megaLook.ink, width: 7, cap: 'round' })
        .moveTo(-17, 12).lineTo(-17, -6).arc(0, -6, 17, Math.PI, 0).lineTo(17, 12)
        .stroke({ color: megaLook.accent, width: 3.5, cap: 'round' });
      view.addChild(arch);
    }
    const key = new Container();
    key.addChild(new Graphics().circle(0, 0, 9).fill(megaLook.ink).stroke({ color: megaLook.accent, width: 2 }));
    key.addChild(new Text({ text: '×' + multiplierText(this.gateBoost), anchor: 0.5, resolution: textQuality(),
      style: { fontFamily: typeface.family, fontWeight: typeface.weight as 'normal', fontSize: 10, fill: megaLook.accent } }));
    key.y = -25;
    view.addChild(key);
    return view;
  }

  /** A ball went through a gate: the arch flashes and kicks, and the boost pops out of it. */
  private gatePass(gate: { view: Container }, boostSoFar: number): void {
    const v = gate.view;
    gsap.killTweensOf(v.scale);
    gsap.fromTo(v.scale, { x: 1.35, y: 1.35 }, { x: 1, y: 1, duration: 0.45, ease: 'elastic.out(1.2, 0.4)' });
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

  /** How much faster balls fall after a bump (until the board is empty again). */
  private hurry = 1;
  readonly bumpButton = new Container();

  /**
   * Plinko Mega's bump: the board jolts and the balls in the air hurry on. Looks only: their paths, and so where they
   * land, were decided by the server before they fell.
   */
  bump(): void {
    if (this.flights.size === 0) return;
    this.hurry = Math.min(2.6, this.hurry * 1.5);
    this.flights.forEach(f => f.timeline?.timeScale(this.hurry));
    shake(this.field, 7);
    this.flights.forEach(f => { f.vx += random(-160, 160); f.vy -= random(60, 140); });
    gsap.fromTo(this.bumpButton.scale, { x: 0.8, y: 0.8 }, { x: 1, y: 1, duration: 0.3, ease: 'back.out(3)' });
  }

  /** The bump button sits at the board's top right while balls are falling. */
  private drawBumpButton(): void {
    const b = this.bumpButton;
    b.removeChildren().forEach(child => child.destroy({ children: true }));
    b.addChild(new Graphics().circle(0, 0, 26).fill(megaLook.ink).stroke({ color: megaLook.accent, width: 3 }));
    b.addChild(new Text({ text: 'BUMP', anchor: 0.5, resolution: textQuality(),
      style: { fontFamily: typeface.family, fontWeight: typeface.weight as 'normal', fontSize: 13, fill: megaLook.accent } }));
    b.eventMode = 'static';
    b.cursor = 'pointer';
    b.visible = false;
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
  layout(view: Viewport): void {
    const { w, h } = this.contentSize();
    // on wide phones the top and bottom of the stage are cut off: keep the pegs, the slots and the status line inside
    const visibleH = view.stageH - 2 * view.offsetY;
    const max = view.landscape ? { w: 1000, h: Math.min(510, visibleH - 110) } : { w: 570, h: 440 };
    const scale = Math.min(1, max.w / w, max.h / h);
    const x = view.landscape ? view.stageW * 0.57 : view.stageW / 2;
    const centreY = view.landscape ? view.stageH / 2 : view.stageH * 0.37;
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
  dropBall(slot: number, events: { onPeg?: (peg: PegHit) => void; onLand?: (landing: Landing) => void } = {}, boost = 1,
           tint: string | null = null, path: number | null = null): Promise<void> {
    const golden = boost > 1;
    const rows = this.rows;
    // Plinko Mega: the server decides the path (it decides the gates); a round without one gets a lively made-up route
    const steps = path != null && Number.isInteger(path) && path >= 0 && path < 2 ** rows ? pathSteps(rows, path) : wanderingSteps(rows, slot);
    // the gates on this route: after `step` bounces, `rights` of them right
    const passes = new Map<number, { view: Container }>();
    let rights = 0;
    steps.forEach((step, i) => {
      if (step > 0) rights++;
      const gate = this.gates.find(g => g.step === i + 1 && g.rights === rights);
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
    if (golden) ball.tint = GOLD;   // a golden ball (decided by the server) looks golden from the start
    else if (tint) ball.tint = tint;   // e.g. a free ball from the bonus meter
    this.ballLayer.addChild(ball);
    const flight: Flight = { sprite: ball, x: 0, y: 0, ox: 0, oy: 0, vx: 0, vy: 0, free: true, cooldown: 0 };
    this.flights.add(flight);
    this.bumpButton.visible = true;
    if (this.fx === 'cctv') this.track(flight);

    const radius = ball.texture.width / 2;
    // every ball has its own pace, but within a ball the hops change smoothly (no random jerks from peg to peg)
    const pace = random(0.27, 0.31);
    const slotTop = this.slotY() - SLOT_H / 2;
    const hoverAt = this.fx === 'gravity' && rows >= 6 && Math.random() < 0.25 ? 2 + Math.floor(Math.random() * (rows - 4)) : -1;

    return new Promise(resolve => {
      const timeline = gsap.timeline({ onComplete: resolve });
      flight.timeline = timeline;
      if (this.hurry > 1) timeline.timeScale(this.hurry);
      /** One arc from a to b: steady sideways speed, and up-then-down like under gravity; the ball rolls as it moves. */
      const arc = (from: { x: number; y: number }, to: { x: number; y: number }, height: number, duration: number) => {
        const hop = { t: 0 };
        timeline.to(hop, {
          t: 1, duration, ease: 'none',
          onUpdate: () => {
            flight.x = from.x + (to.x - from.x) * hop.t;
            flight.y = from.y + (to.y - from.y) * hop.t - height * 4 * hop.t * (1 - hop.t);
            ball.rotation = flight.x / radius;
            this.streak(ball, radius, golden);
            if (golden && Math.random() < 0.4) this.particles.sparks(ball.x, ball.y, GOLD, 1, 12);
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
        const gate = passes.get(i);
        if (gate) timeline.call(() => { gateBoost *= this.gateBoost; this.gatePass(gate, gateBoost); ball.scale.set(1 + 0.12 * Math.log2(gateBoost)); });
        // now and then the ball bounces up off a peg and comes down on it again before going on (Bad Bounce: often,
        // and badly)
        const bad = this.fx === 'bounce';
        if (i < points.length - 2 && Math.random() < (bad ? 0.4 : 0.18)) {
          arc(points[i], points[i], PEG_SPACING * random(0.22, 0.32) * (bad ? 1.6 : 1) * (1 - 0.4 * progress), pace * (bad ? 0.75 : 0.62));
          timeline.call(() => this.touchPeg(ball, i, column, events.onPeg));
        }
        // Gravity Issues: once in a while a ball loses its gravity, floats up glowing, and drops back onto its peg
        if (i === hoverAt) {
          const at = points[i];
          const lift = PEG_SPACING * random(0.9, 1.4);
          const hover = { t: 0 };
          timeline.call(() => this.particles.ring(at.x, at.y, this.glowColour, 22));
          timeline.to(hover, {
            t: 1, duration: random(0.9, 1.2), ease: 'sine.inOut',
            onUpdate: () => {
              flight.x = at.x + Math.sin(hover.t * Math.PI * 2) * 5;
              flight.y = at.y - lift * Math.sin(hover.t * Math.PI);
              ball.rotation += 0.03;
              if (Math.random() < 0.35) this.particles.sparks(ball.x, ball.y, this.glowColour, 1, 14);
            },
          });
          timeline.call(() => this.touchPeg(ball, i, column, events.onPeg));
        }
      }
      // into the hole: the last fall keeps its speed and goes behind the slot, which swallows the ball
      const last = points[points.length - 1];
      const end = { x: landX, y: slotTop + radius * 2.4 };
      const height = PEG_SPACING * 0.16;
      let entered = false;
      timeline.call(() => { flight.free = false; this.holeLayer.addChild(ball); this.untrack(flight); });
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
            if (golden) this.goldenLanding(slot, boost);
            if (this.hot?.slot === slot) this.hotLanding(slot);
            if (this.currentSpinSlots().includes(slot)) this.spinLanding(slot);
            events.onLand?.({ slot, prizeIndex: prizeIndexOfSlot(rows, slot), x: this.slotX(slot), y: this.slotY(), pan: this.pan(this.slotX(slot)) });
          }
        },
      });
      timeline.call(() => {
        this.flights.delete(flight);
        ball.destroy();
        if (this.flights.size === 0) { this.hurry = 1; this.bumpButton.visible = false; }
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
      // the camera's box follows its ball a moment behind, like a real tracker
      if (f.tracker) f.tracker.position.set(f.tracker.x + (f.sprite.x - f.tracker.x) * Math.min(1, dt * 14),
        f.tracker.y + (f.sprite.y - f.tracker.y) * Math.min(1, dt * 14));
    }
  }

  /** Big Brother: a red tracking box locks onto a falling ball (a few at a time, so a big drop stays readable). */
  private track(flight: Flight): void {
    const tracked = [...this.flights].filter(f => f.tracker).length;
    if (tracked >= 5) return;
    this.subjects = (this.subjects % 99) + 1;
    const r = 15;
    const l = 7;
    const box = new Graphics();
    for (const [sx, sy] of [[-1, -1], [1, -1], [-1, 1], [1, 1]]) {
      box.moveTo(sx * r, sy * (r - l)).lineTo(sx * r, sy * r).lineTo(sx * (r - l), sy * r);
    }
    box.stroke({ color: '#ff2a2a', width: 2 });
    box.moveTo(-3, 0).lineTo(3, 0).moveTo(0, -3).lineTo(0, 3).stroke({ color: '#ff2a2a', width: 1, alpha: 0.8 });
    const tag = label(`SUBJ ${String(this.subjects).padStart(2, '0')}`, 11, 'left', { fill: '#ff2a2a' });
    tag.position.set(r + 3, -r - 2);
    const tracker = new Container();
    tracker.addChild(box, tag);
    tracker.position.set(0, 0);
    this.textLayer.addChild(tracker);
    flight.tracker = tracker;
    gsap.fromTo(tracker.scale, { x: 2.2, y: 2.2 }, { x: 1, y: 1, duration: 0.25, ease: 'power2.out' });
    gsap.fromTo(box, { alpha: 1 }, { alpha: 0.45, duration: 0.18, repeat: 3, yoyo: true });
  }

  private untrack(flight: Flight): void {
    const tracker = flight.tracker;
    if (!tracker) return;
    flight.tracker = undefined;
    gsap.killTweensOf(tracker.scale);
    gsap.to(tracker.scale, { x: 0.5, y: 0.5, duration: 0.2, ease: 'power2.in' });
    gsap.to(tracker, { alpha: 0, duration: 0.2, onComplete: () => tracker.destroy({ children: true }) });
  }

  /** A ball goes into its slot: a puff for small prizes, sparks, a fountain with a ring, and a shake for the top ones. */
  private landingEffect(slot: number, prizeIndex: number): void {
    const multiplier = this.multiplier(prizeIndex);
    const face = slotLooks[slotTier(this.rows, prizeIndex)].face;
    const x = this.slotX(slot);
    const y = this.slotY() - SLOT_H / 2;
    if (this.fx === 'gravity') {
      // everything is pulled into the slot instead of thrown out of it
      const strength = multiplier < 1 ? 0 : multiplier < 2 ? 1 : multiplier < 10 ? 2 : 3;
      this.particles.implode(x, y, [face, '#ffffff', this.glowColour], [5, 10, 18, 32][strength], [24, 40, 60, 90][strength]);
      if (strength >= 2) this.particles.ring(x, this.slotY(), this.glowColour, [0, 0, 50, 90][strength], true);
      if (strength === 3) shake(this, 6);
      return;
    }
    if ((this.fx === 'comic' || this.fx === 'cartoon' || this.fx === 'viral' || this.fx === 'nana' || this.fx === 'shurda' || this.fx === 'inked' || this.fx === 'glitch' || this.fx === 'holy' || this.fx === 'toxic') && multiplier >= 2) this.comicWord(x, y, multiplier);
    if (this.fx === 'scam' && multiplier >= 2) this.scamStamp(x, y, multiplier);
    if (this.fx === 'cctv' && multiplier >= 2) this.report(x, y, multiplier);
    if (this.fx === 'noir' && multiplier >= 2) this.gunshots(x, y, multiplier);
    if (this.fx === 'clown' && multiplier >= 2) this.jackInTheBox(x, y, multiplier);
    if (this.fx === 'riot') {
      // spray paint is bright: dark slots spray the theme's red instead
      const n = parseInt(face.slice(1), 16);
      const light = (0.299 * (n >> 16) + 0.587 * ((n >> 8) & 255) + 0.114 * (n & 255)) / 255;
      this.particles.splat(x, y - 4, light < 0.3 ? this.pegFlashColour : face, multiplier < 1 ? 7 : multiplier < 2 ? 10 : multiplier < 10 ? 14 : 22);
    }
    if (this.fx === 'bounce') {
      this.particles.steam(x, y, multiplier < 2 ? 3 : 6);
      if (multiplier >= 1) this.particles.sparks(x, y, this.pegFlashColour, 5, 22);
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
  }

  /** The round's hot slot (decided by the server) lights up before the balls drop: a pulsing glow and its boost. */
  setHot(slot: number, boost: number): void {
    this.clearHot();
    const x = this.slotX(slot);
    const y = this.slotY();
    const glow = new Graphics().roundRect(-SLOT_W / 2 - 7, -SLOT_H / 2 - 7, SLOT_W + 14, SLOT_H + 14, 12).fill(this.pegFlashColour);
    glow.position.set(x, y);
    glow.alpha = 0;
    this.slotLayer.addChildAt(glow, 0);
    gsap.to(glow, { alpha: 0.85, duration: 0.25 });
    gsap.fromTo(glow.scale, { x: 1, y: 1 }, { x: 1.12, y: 1.12, duration: 0.45, yoyo: true, repeat: -1, ease: 'sine.inOut' });
    const badge = label(`×${boost}`, 24, 'center', { fill: this.pegFlashColour, stroke: { color: '#000000', width: 6, join: 'round' } });
    badge.position.set(x, y - SLOT_H / 2 - 16);
    this.textLayer.addChild(badge);
    gsap.from(badge.scale, { x: 0.2, y: 0.2, duration: 0.4, ease: 'back.out(3)' });
    this.particles.ring(x, y, this.pegFlashColour, 50);
    this.particles.sparks(x, y - SLOT_H / 2, this.pegFlashColour, 10, 30);
    this.hot = { slot, boost, glow, badge };
  }

  clearHot(): void {
    if (!this.hot) return;
    const { glow, badge } = this.hot;
    gsap.killTweensOf([glow, glow.scale, badge, badge.scale]);
    gsap.to([glow, badge], { alpha: 0, duration: 0.3, onComplete: () => { glow.destroy(); badge.destroy(); } });
    this.hot = null;
  }

  /** A ball lands in the hot slot: a burst in the theme's colour and the boost floating up. */
  private hotLanding(slot: number): void {
    if (!this.hot) return;
    const x = this.slotX(slot);
    const y = this.slotY() - SLOT_H / 2;
    this.particles.burst(x, y, [this.pegFlashColour, '#ffffff'], 24, 260);
    this.particles.ring(x, this.slotY(), this.pegFlashColour, 60);
    const tag = label(`HOT ×${this.hot.boost}`, 24, 'center', { fill: this.pegFlashColour, stroke: { color: '#000000', width: 6, join: 'round' } });
    tag.position.set(x, y - 90);
    this.textLayer.addChild(tag);
    gsap.timeline({ onComplete: () => tag.destroy() })
      .from(tag.scale, { x: 0.3, y: 0.3, duration: 0.3, ease: 'back.out(2.5)' })
      .to(tag, { y: tag.y - 24, alpha: 0, duration: 0.8, ease: 'power1.in' }, 0.8);
  }

  /** A word pops out of a good landing in an inked starburst: comic sound effects (Rat Race), cartoon yelps (Bad
   * Dentist), social-media alerts (Doomscroll) or the Shurda gym's cheers. Colours: [burst, word] for small and big wins,
   * each pair at least 4.5:1 apart so the word reads at a glance. */
  private comicWord(x: number, y: number, multiplier: number): void {
    const WORDS: Record<string, [string[], string[], [string, string], [string, string]]> = {
      comic: [['POW!', 'BAM!', 'ZING!', 'KER-PLINK!'], ['KA-CHING!', 'JACKPOT!', 'WHAM!'], ['#b8281c', '#ffffff'], ['#f2c230', '#15120e']],
      cartoon: [['OUCH!', 'YANK!', 'CRACK!', 'OOF!'], ['GOLD TOOTH!', 'AAARGH!', 'JACKPOT!'], ['#efe7d6', '#15120e'], ['#b3121b', '#efe7d6']],
      viral: [['+1K LIKES', 'SHARED!', '♥ +99', 'NEW FOLLOWER'], ['VIRAL!', 'TRENDING!', '1M VIEWS'], ['#19d3ff', '#15120e'], ['#ff2e93', '#15120e']],
      nana: [['BINGO!', 'OH MY!', 'NICE, DEAR', 'TEA TIME!'], ["NANA'S CUT!", 'JACKPOT, DEAR!', 'BINGO BINGO!'], ['#e8b13a', '#2a1530'], ['#b01c30', '#f3ead8']],
      shurda: [['SHURDA!', 'NICE SOCKS!', 'SOCK IT!', 'PUMP IT!'], ['SHURDALIFE!', 'SOCK JACKPOT!', 'BIGGER ICE CREAM!'], ['#ffd23f', '#111111'], ['#f0388c', '#15120e']],
      glitch: [['ERROR 404', 'OVERFLOW!', 'BUG FOUND', 'LAG SPIKE'], ['SYSTEM HACKED!', 'JACKPOT.EXE', 'CRITICAL WIN!'], ['#00f0ff', '#05060a'], ['#ff2bd6', '#05060a']],
      holy: [['AMEN!', 'BLESSED', 'GLORY!', 'HEAVENLY'], ['HALLELUJAH!', 'DIVINE WIN!', 'MIRACLE!'], ['#d9a93a', '#15120e'], ['#a8122a', '#f3ead6']],
      toxic: [['MUTATION!', 'IT LIVES!', 'TOXIC!', 'GLOWING!'], ['BREACH!', 'OUTBREAK!', 'SPECIMEN 13!'], ['#39ff7a', '#04140a'], ['#ffd21e', '#04140a']],
      inked: [['HOLD FAST', 'NO REGRETS', 'LUCKY 13', 'BORN WILD'], ['DAMNED LUCKY!', 'BORN LUCKY', 'TRUE LOVE'], ['#efe4cc', '#000000'], ['#c1121f', '#efe4cc']],
    };
    const [small, big, cSmall, cBig] = WORDS[this.fx] ?? WORDS.comic;
    const words = multiplier >= 10 ? big : small;
    const word = words[Math.floor(Math.random() * words.length)];
    const size = multiplier >= 10 ? 30 : 22;
    const group = new Container();
    const [burstColour, wordColour] = multiplier >= 10 ? cBig : cSmall;
    // light letters get a dark outline; dark letters none (an outline the same colour as the letters fills them in)
    const text = label(word, size, 'center', { fill: wordColour, stroke: { color: '#15120e', width: isDark(wordColour) ? 0 : 5, join: 'round' } });
    text.anchor.set(0.5, 0.5);
    // the burst fits the word: even its inner points (0.62 of the radius) clear the letters, so a long word never
    // spills onto the board, where dark letters vanish
    const inner = 0.62;
    const base = size * (multiplier >= 10 ? 2.4 : 2);
    const rx = Math.max(base * 1.3, (text.width / 2 + size * 0.45) / inner);
    const ry = Math.max(base, (text.height / 2 + size * 0.3) / inner);
    const burst = new Graphics();
    const spikes = 14;
    const pts: number[] = [];
    for (let i = 0; i < spikes * 2; i++) {
      const a = (i / (spikes * 2)) * Math.PI * 2;
      const k = i % 2 ? inner : 1;
      pts.push(Math.cos(a) * rx * k, Math.sin(a) * ry * k);
    }
    burst.poly(pts).fill('#15120e');
    burst.poly(pts.map(v => v * 0.9 - 2)).fill(burstColour).stroke({ color: '#15120e', width: 3 });
    group.addChild(burst, text);
    // very long words: the whole burst is scaled down to a sensible size rather than growing past the board
    const fit = Math.min(1, 150 / rx);
    // above the landing's amount (shown just over the slot), never on it
    group.position.set(x + (Math.random() - 0.5) * 30, y - 48 - ry * fit - Math.random() * 14);
    group.rotation = (Math.random() - 0.5) * 0.4;
    this.textLayer.addChild(group);
    gsap.timeline({ onComplete: () => group.destroy({ children: true }) })
      .fromTo(group.scale, { x: 0.1 * fit, y: 0.1 * fit }, { x: fit, y: fit, duration: 0.22, ease: 'back.out(3)' })
      .to(group, { alpha: 0, y: group.y - 20, duration: 0.35, ease: 'power1.in' }, 0.75);
  }

  /** The Scam Squad: a rubber stamp slams down over a good landing, slightly crooked ("TOTALLY LEGIT!"; 10x and up a
   * big gold "100% REAL!"). Only on wins: a loss is never mocked. */
  private scamStamp(x: number, y: number, multiplier: number): void {
    const big = multiplier >= 10;
    const words = big ? ['100% REAL!', 'CERTIFIED!', 'BIG SCORE!'] : ['TOTALLY LEGIT!', 'TRUST ME!', 'GENUINE!', 'NO REFUNDS!'];
    const word = words[Math.floor(Math.random() * words.length)];
    const ink = big ? '#9e1208' : '#0b5c5e';
    const text = label(word, big ? 28 : 20, 'center', { fill: ink, letterSpacing: 1 });
    text.anchor.set(0.5, 0.5);
    const w = text.width + 30;
    const h = text.height + 18;
    const stamp = new Graphics()
      .roundRect(-w / 2, -h / 2, w, h, 8).fill({ color: big ? '#ffc23a' : '#f6e7c4', alpha: 0.96 })
      .roundRect(-w / 2, -h / 2, w, h, 8).stroke({ color: ink, width: 4 })
      .roundRect(-w / 2 + 6, -h / 2 + 6, w - 12, h - 12, 5).stroke({ color: ink, width: 2 });
    const group = new Container();
    group.addChild(stamp, text);
    group.position.set(x + (Math.random() - 0.5) * 24, y - 64 - Math.random() * 16);
    group.rotation = (Math.random() < 0.5 ? -1 : 1) * (0.12 + Math.random() * 0.14);
    this.textLayer.addChild(group);
    if (big) shake(this, 4);
    gsap.timeline({ onComplete: () => group.destroy({ children: true }) })
      .from(group.scale, { x: 1.9, y: 1.9, duration: 0.14, ease: 'power3.in' })
      .from(group, { alpha: 0, duration: 0.08 }, 0)
      .to(group.scale, { x: 1.06, y: 0.94, duration: 0.06, yoyo: true, repeat: 1 }, 0.14)
      .to(group, { alpha: 0, y: group.y - 16, duration: 0.35, ease: 'power1.in' }, 0.95);
  }

  /** Big Brother: a good landing is filed as a classified report: a paper slip with redacted lines, and a red stamp
   * slams onto it ("CLASSIFIED"; 10x and up "TOP SECRET"). Only on wins. */
  private report(x: number, y: number, multiplier: number): void {
    const big = multiplier >= 10;
    const words = big ? ['TOP SECRET', 'EYES ONLY', 'SUBJECT FLAGGED'] : ['CLASSIFIED', 'ON FILE', 'APPROVED', 'MONITORED'];
    const word = words[Math.floor(Math.random() * words.length)];
    const ink = '#b3121b';
    const text = label(word, big ? 26 : 19, 'center', { fill: ink, letterSpacing: 2 });
    text.anchor.set(0.5, 0.5);
    const w = text.width + 26;
    const h = text.height + 12;
    const stamp = new Graphics()
      .rect(-w / 2, -h / 2, w, h).stroke({ color: ink, width: 3 })
      .rect(-w / 2 + 4, -h / 2 + 4, w - 8, h - 8).stroke({ color: ink, width: 1 });
    const stampGroup = new Container();
    stampGroup.addChild(stamp, text);
    stampGroup.rotation = (Math.random() < 0.5 ? -1 : 1) * (0.1 + Math.random() * 0.1);
    // the slip: a case number and blacked-out lines under the stamp
    const pw = w + 22;
    const ph = h + 34;
    const paper = new Graphics().rect(-pw / 2, -ph / 2, pw, ph).fill('#ece6d6').stroke({ color: '#15120e', width: 2 });
    const bars = new Graphics();
    for (let i = 0; i < 3; i++) bars.rect(-pw / 2 + 10, -ph / 2 + 22 + i * 13, (pw - 20) * (0.55 + Math.random() * 0.45), 7);
    bars.fill('#15120e');
    const no = label(`FILE ${1000 + Math.floor(Math.random() * 9000)}`, 11, 'left', { fill: '#15120e' });
    no.position.set(-pw / 2 + 8, -ph / 2 + 4);
    const group = new Container();
    group.addChild(paper, bars, no, stampGroup);
    stampGroup.y = 6;
    group.position.set(x + (Math.random() - 0.5) * 24, y - 52 - ph / 2 - Math.random() * 12);
    group.rotation = (Math.random() - 0.5) * 0.14;
    const fit = Math.min(1, 170 / pw);
    group.scale.set(fit);
    this.textLayer.addChild(group);
    if (big) shake(this, 4);
    gsap.timeline({ onComplete: () => group.destroy({ children: true }) })
      .from(group, { alpha: 0, y: group.y + 14, duration: 0.12 })
      .from(stampGroup.scale, { x: 2, y: 2, duration: 0.14, ease: 'power3.in' }, 0.12)
      .from(stampGroup, { alpha: 0, duration: 0.06 }, 0.12)
      .to(group, { alpha: 0, y: group.y - 16, duration: 0.35, ease: 'power1.in' }, 1.0);
  }

  /** Fatal Attraction: a good landing is a shot fired into the board above the slot: a red-orange muzzle flash, then a
   * bullet hole with cracks running out as through glass, which lingers and fades. 10x and up: three shots. */
  private gunshots(x: number, y: number, multiplier: number): void {
    const shots = multiplier >= 10 ? 3 : 1;
    for (let i = 0; i < shots; i++) {
      const hx = x + (shots > 1 ? (i - 1) * 34 + (Math.random() - 0.5) * 10 : (Math.random() - 0.5) * 16);
      const hy = y - 78 - Math.random() * 18 - (shots > 1 ? Math.abs(i - 1) * -10 : 0);
      gsap.delayedCall(i * 0.16, () => this.bulletHole(hx, hy, multiplier >= 10 ? 8.5 : 7));
    }
    if (shots > 1) gsap.delayedCall(0.05, () => shake(this, 5));
  }

  private bulletHole(x: number, y: number, r: number): void {
    const flash = new Graphics().circle(0, 0, r * 5).fill({ color: '#ff5a2a', alpha: 0.5 }).circle(0, 0, r * 2.6).fill({ color: '#ffb070', alpha: 0.7 });
    flash.position.set(x, y);
    const hole = new Graphics();
    for (let i = 0; i < 9; i++) {
      const a = (i / 9) * Math.PI * 2 + Math.random() * 0.5;
      const len = r * (2.6 + Math.random() * 2.6);
      const bend = a + (Math.random() - 0.5) * 0.6;
      hole.moveTo(Math.cos(a) * r, Math.sin(a) * r)
        .lineTo(Math.cos(a) * len * 0.55, Math.sin(a) * len * 0.55)
        .lineTo(Math.cos(bend) * len, Math.sin(bend) * len);
    }
    hole.stroke({ color: '#ffffff', width: 1, alpha: 0.75 });
    hole.circle(0, 0, r * 1.9).stroke({ color: '#ffffff', width: 0.8, alpha: 0.35 });
    hole.circle(0, 0, r * 1.15).fill({ color: '#ffffff', alpha: 0.35 }).circle(0, 0, r).fill('#000000')
      .circle(r * 0.15, r * 0.2, r * 0.55).fill({ color: '#5c0409', alpha: 0.8 });
    hole.position.set(x, y);
    hole.rotation = Math.random() * Math.PI;
    this.textLayer.addChild(hole, flash);
    gsap.timeline({ onComplete: () => flash.destroy() })
      .from(flash.scale, { x: 0.3, y: 0.3, duration: 0.06 })
      .to(flash, { alpha: 0, duration: 0.18, ease: 'power2.out' });
    gsap.timeline({ onComplete: () => hole.destroy() })
      .from(hole.scale, { x: 0.6, y: 0.6, duration: 0.08, ease: 'power3.out' })
      .to(hole, { alpha: 0, duration: 0.6, ease: 'power1.in' }, 1.4);
  }

  /** Clown Business: a good landing springs a jack-in-the-box over the slot: the lid flips, a clown head boings up
   * on a zigzag spring, wobbles, and sinks back in. 10x and up: a taller spring, a bigger head, a shake. */
  private jackInTheBox(x: number, y: number, multiplier: number): void {
    const big = multiplier >= 10;
    const s = big ? 1.35 : 1;
    const group = new Container();
    group.position.set(x, y - 6);
    group.scale.set(s);
    // the box: red with a cream band, the lid hinged at its back edge
    const box = new Graphics()
      .roundRect(-13, -16, 26, 16, 2).fill('#a3171c').stroke({ color: '#1a0a05', width: 1.6 })
      .rect(-13, -10, 26, 4).fill('#efe2c2');
    const lid = new Graphics().roundRect(0, -3, 26, 4, 1.5).fill('#c9973f').stroke({ color: '#1a0a05', width: 1.2 });
    lid.position.set(-13, -16);
    // the spring and the head on top of it
    const spring = new Graphics();
    const head = new Container();
    const face = new Graphics()
      .circle(-9, -2, 6).fill('#d8322e').circle(9, -2, 6).fill('#d8322e')                // hair tufts
      .circle(0, 0, 10).fill('#f4ecdc').stroke({ color: '#1a0a05', width: 1.4 })          // porcelain face
      .circle(-3.6, -2.5, 1.6).fill('#1a0a05').circle(3.6, -2.5, 1.6).fill('#1a0a05')     // eyes
      .circle(0, 1, 2.6).fill('#d8322e')                                                   // nose
      .moveTo(-5.5, 4).quadraticCurveTo(0, 9, 5.5, 4).stroke({ color: '#a3171c', width: 1.8 })
      .moveTo(-4, -12).lineTo(0, -21).lineTo(4, -12).closePath().fill('#2b6b66');        // hat
    head.addChild(face);
    group.addChild(spring, head, box, lid);
    this.textLayer.addChild(group);
    const reach = big ? 46 : 32;
    const state = { h: 0 };
    const draw = () => {
      spring.clear();
      const turns = 6;
      spring.moveTo(0, -16);
      for (let i = 1; i <= turns; i++) spring.lineTo(i % 2 ? -5 : 5, -16 - state.h * i / turns);
      spring.stroke({ color: '#c9973f', width: 1.8 });
      head.position.set(0, -26 - state.h);
    };
    draw();
    head.alpha = 0;
    if (big) shake(this, 4);
    gsap.timeline({ onComplete: () => group.destroy({ children: true }) })
      .from(group, { alpha: 0, duration: 0.08 })
      .to(lid, { rotation: -2.2, duration: 0.12, ease: 'power2.out' })
      .set(head, { alpha: 1 }, '<')
      .to(state, { h: reach, duration: 0.55, ease: 'elastic.out(1.2, 0.35)', onUpdate: draw }, '<')
      .fromTo(head, { rotation: -0.35 }, { rotation: 0.35, duration: 0.14, repeat: 5, yoyo: true, ease: 'sine.inOut' }, '<0.1')
      .to(state, { h: 0, duration: 0.25, ease: 'power2.in', onUpdate: draw }, '+=0.25')
      .set(head, { alpha: 0 })
      .to(lid, { rotation: 0, duration: 0.08 })
      .to(group, { alpha: 0, duration: 0.25 }, '+=0.1');
  }

  /** A golden ball lands: a gold fountain and ring, a shake, and the boost floating up over the slot. */
  private goldenLanding(slot: number, boost: number): void {
    const x = this.slotX(slot);
    const y = this.slotY() - SLOT_H / 2;
    this.particles.burst(x, y, [GOLD, '#fff3b0', '#ffffff'], 28, 280);
    this.particles.ring(x, this.slotY(), GOLD, 70);
    shake(this, 5);
    const tag = label(`GOLDEN ×${boost}`, 26, 'center', { fill: GOLD, stroke: { color: '#000000', width: 6, join: 'round' } });
    tag.position.set(x, y - 58);
    this.textLayer.addChild(tag);
    gsap.timeline({ onComplete: () => tag.destroy() })
      .from(tag.scale, { x: 0.3, y: 0.3, duration: 0.35, ease: 'back.out(2.5)' })
      .to(tag, { y: tag.y - 26, alpha: 0, duration: 0.9, ease: 'power1.in' }, 0.9);
  }

  /** The theme's trail: small dots left behind the ball that shrink and fade, so a falling ball draws a streak. */
  private streak(ball: Sprite, radius: number, golden = false): void {
    if (!this.trailDot && !golden) return;
    const dot = new Graphics(golden ? goldDot : this.trailDot!);
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
    const squash = this.fx === 'bounce' ? 0.16 : 0.07;
    gsap.fromTo(ball.scale, { x: 1 + squash, y: 1 - squash }, { x: 1, y: 1, duration: this.fx === 'bounce' ? 0.16 : 0.1, ease: 'power1.out', overwrite: true });
    const flash = this.pegFlashes[row]?.[column];
    const peg = this.pegs[row]?.[column];
    if (peg) {
      const many = this.flights.size > 15;
      if (this.fx === 'bounce') this.particles.sparks(peg.x, peg.y + 3, this.pegFlashColour, many ? 3 : 6, 20);
      else this.particles.sparks(peg.x, peg.y, this.pegFlashColour, many ? 2 : 4);
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
