import { Color, Container, FillGradient, Graphics, Rectangle, Sprite, type ColorSource, type Text, type Texture } from 'pixi.js';
import { gsap } from 'gsap';
import type { Viewport } from './viewport';
import { fit, label } from './ui';

/**
 * The control center: one panel with the big drop button (one ball per drop; hold for a stream), the bet, risk and
 * rows, and the balance and last win. Drawn in code in the theme's colours, so every theme gets it without new artwork.
 * Phones (portrait): the risk switch on top, then the bet | the drop button | the rows, then balance and win.
 * Landscape: the same pieces in one column left of the board.
 */

export interface ControlColours { accent: string; text: string; background: string; second: string }

/**
 * 'glass' themes: shaded panels and glossy buttons; 'ink' themes (comic): flat panels with ink outlines and an offset
 * ink shadow. Either may bring its own drawing of the drop button. Neither: the flat look.
 */
export interface ControlLook { glass: boolean; drop: Texture | null; ink?: { ink: string; panel: string } | null }

export interface ControlActions {
  dropDown(): void;            // the drop button is pressed (a tap plays; holding keeps playing)
  dropUp(): void;              // ...and let go
  bet(up: boolean): void;
  betPick(value: number): void;
  risk(index: number): void;
  rows(step: number): void;
  info(): void;                // the i button: explain the control center
}

const RISK_NAMES = ['LOW', 'MEDIUM', 'HIGH'];

/** A mix of two #rrggbb colours (t = 0: a, t = 1: b), as a number for Pixi. */
function mix(a: string, b: string, t: number): number {
  const pa = parseInt(a.slice(1), 16);
  const pb = parseInt(b.slice(1), 16);
  const channel = (shift: number) => Math.round(((pa >> shift) & 255) * (1 - t) + ((pb >> shift) & 255) * t);
  return (channel(16) << 16) | (channel(8) << 8) | channel(0);
}

/** Whether a #rrggbb colour is light (dark text reads better on it). */
function light(hex: string): boolean {
  const n = parseInt(hex.slice(1), 16);
  return 0.299 * (n >> 16) + 0.587 * ((n >> 8) & 255) + 0.114 * (n & 255) > 160;
}

/** A top-to-bottom shade, sized to whatever shape it fills. */
export function shade(top: ColorSource, bottom: ColorSource): FillGradient {
  return new FillGradient({ type: 'linear', start: { x: 0, y: 0 }, end: { x: 0, y: 1 }, textureSpace: 'local',
    colorStops: [{ offset: 0, color: top }, { offset: 1, color: bottom }] });
}

/** A glass panel: a drop shadow, a shaded body, a thin edge and a highlight along the top. */
export function glassPanel(g: Graphics, x: number, y: number, w: number, h: number, r: number, background: string): Graphics {
  return g.roundRect(x, y + 4, w, h, r).fill({ color: '#000000', alpha: 0.45 })
    .roundRect(x, y, w, h, r).fill({ fill: shade(mix(background, '#ffffff', 0.07), mix(background, '#000000', 0.45)), alpha: 0.94 })
    .stroke({ width: 1.5, color: mix(background, '#ffffff', 0.16) })
    .moveTo(x + r, y + 1.5).lineTo(x + w - r, y + 1.5).stroke({ width: 1, color: '#ffffff', alpha: 0.14 });
}

/** A comic panel: an offset ink shadow, a flat body and a thick ink outline. */
export function inkPanel(g: Graphics, x: number, y: number, w: number, h: number, r: number, ink: string, panel: string): Graphics {
  return g.roundRect(x + 4, y + 4, w, h, r).fill(ink)
    .roundRect(x, y, w, h, r).fill(panel).stroke({ width: 3.5, color: ink })
    .roundRect(x + 8, y + 5, w - 16, 3, 1.5).fill({ color: '#ffffff', alpha: 0.18 });
}

/** A round button drawn in code: a disc with a ring and a label or a drawn sign; presses in when touched. */
class RoundButton extends Container {
  private on_ = true;
  readonly disc = new Graphics();
  private text: Text | null = null;

  constructor(radius: number, private readonly onTap: () => void, fill: ColorSource, ring: ColorSource, glass = false, ink: string | null = null) {
    super();
    if (ink) {
      // comic: an offset ink shadow, a flat face, a thick ink outline
      this.disc.circle(3, 3, radius).fill(ink).circle(0, 0, radius).fill(fill).stroke({ width: 3, color: ink });
    } else if (glass) {
      // glossy: a shadow, a shaded face, a thin ring and a shine across the top
      const face = new Color(fill).toHex();
      this.disc.circle(0, 2.5, radius).fill({ color: '#000000', alpha: 0.5 })
        .circle(0, 0, radius).fill(shade(mix(face, '#ffffff', 0.18), face)).stroke({ width: 1.5, color: ring })
        .ellipse(0, -radius * 0.5, radius * 0.62, radius * 0.3).fill({ color: '#ffffff', alpha: 0.1 });
    } else {
      this.disc.circle(0, 0, radius).fill(fill).stroke({ width: 3, color: ring });
    }
    this.addChild(this.disc);
    this.eventMode = 'static';
    this.cursor = 'pointer';
    this.hitArea = new Rectangle(-radius - 6, -radius - 6, radius * 2 + 12, radius * 2 + 12);
    const press = (down: boolean) => gsap.to(this.scale, { x: down ? 0.9 : 1, y: down ? 0.9 : 1, duration: 0.1, overwrite: true });
    this.on('pointerdown', () => press(true));
    this.on('pointerup', () => press(false));
    this.on('pointerupoutside', () => press(false));
    this.on('pointertap', () => { if (this.on_) this.onTap(); });
  }

  setLabel(value: string, size: number, colour: string): void {
    if (!this.text) {
      this.text = label(value, size, 'center', { fill: colour });
      this.text.anchor.set(0.5, 0.5);
      this.addChild(this.text);
    }
    this.text.text = value;
  }

  get enabled(): boolean { return this.on_; }

  set enabled(value: boolean) {
    this.on_ = value;
    this.alpha = value ? 1 : 0.4;
    this.eventMode = value ? 'static' : 'none';
  }
}

/** A labelled pill (balance, win). */
class Pill extends Container {
  private readonly bg = new Graphics();
  private readonly title: Text;
  readonly value: Text;

  constructor(private readonly c: ControlColours, title: string, valueColour: string, private readonly glass = false,
              private readonly ink: { ink: string; panel: string } | null = null) {
    super();
    this.title = label(title, 15, 'center', { fill: mix(c.text, c.background, 0.35) });
    this.title.anchor.set(0.5, 0.5);
    this.value = label('', 24, 'center', { fill: valueColour });
    this.value.anchor.set(0.5, 0.5);
    this.addChild(this.bg, this.title, this.value);
  }

  size(width: number, height: number, stacked = true): void {
    if (this.glass || this.ink) {
      // the title small on the left, the amount large on the right
      if (this.ink) inkPanel(this.bg.clear(), 0, 0, width, height, 14, this.ink.ink, this.ink.panel);
      else glassPanel(this.bg.clear(), 0, 0, width, height, 14, this.c.background);
      this.title.anchor.x = 0;
      this.title.style.fontSize = 13;
      this.title.style.letterSpacing = 2;
      this.title.position.set(18, height / 2);
      this.value.anchor.x = 1;
      this.value.position.set(width - 16, height / 2);
      this.fitWidth = width - 110;
      fit(this.value, this.fitWidth);
      return;
    }
    this.bg.clear().roundRect(0, 0, width, height, height / 2).fill({ color: mix(this.c.background, '#000000', 0.35), alpha: 0.85 })
      .stroke({ width: 2, color: this.c.accent, alpha: 0.45 });
    if (stacked) {
      this.title.visible = true;
      this.title.position.set(width / 2, height * 0.3);
      this.value.position.set(width / 2, height * 0.66);
    } else {
      this.title.visible = false;
      this.value.position.set(width / 2, height / 2);
    }
    this.fitWidth = width - 24;
    fit(this.value, this.fitWidth);
  }

  private fitWidth = 200;

  set(text: string): void {
    this.value.text = text;
    fit(this.value, this.fitWidth);
  }
}

export class ControlCenter extends Container {
  private readonly c: ControlColours;
  private readonly card = new Graphics();
  // the drop button
  private readonly drop = new Container();
  private readonly dropDisc = new Graphics();
  private readonly dropArt: Sprite | null;
  private readonly ballIcon = new Graphics();
  private readonly dropText: Text;
  private readonly holdHint: Text;
  private readonly stopIcon = new Graphics();
  private readonly glow = new Graphics();
  private readonly spinsText: Text;
  private glowTween: gsap.core.Tween | null = null;
  // bet
  private readonly betTitle: Text;
  private readonly betValue: Text;
  private readonly betCaret: Text;
  private readonly betTap = new Container();
  private readonly minus: RoundButton;
  private readonly plus: RoundButton;
  private readonly picker = new Container();
  // risk: one switch of three
  private readonly riskBg = new Graphics();
  private readonly riskKnob = new Graphics();
  private readonly riskParts: { hit: Container; text: Text }[] = [];
  private riskWidth = 520;
  private riskHeight = 48;
  // rows
  private readonly rowsTitle: Text;
  private readonly rowsValue: Text;
  private readonly rowsDown: RoundButton;
  private readonly rowsUp: RoundButton;
  // balance and win
  private readonly balance: Pill;
  private readonly win: Pill;
  private readonly info: RoundButton;
  private dropRadius = 72;
  private risk = 0;
  private playing = false;
  private holding = false;
  private landscape = false;

  constructor(colours: ControlColours, private readonly actions: ControlActions, private readonly ladder: number[],
              private readonly holdToDrop: boolean, private readonly look: ControlLook = { glass: false, drop: null }) {
    super();
    this.c = colours;
    const ink = look.ink ?? null;
    const glass = look.glass || ink !== null;   // the ink look shares the glass layout (pills, labels, the switch)
    const dark = ink ? ink.panel : mix(colours.background, glass ? '#ffffff' : '#000000', glass ? 0.05 : 0.25);
    const ring = ink ? ink.ink : glass ? mix(colours.background, '#ffffff', 0.22) : colours.accent;
    const inkLine = ink?.ink ?? null;
    this.dropArt = look.drop ? new Sprite({ texture: look.drop, anchor: 0.5 }) : null;

    // the big drop button: a tap drops one ball, holding keeps dropping
    this.dropText = label('DROP', 30, 'center', { fill: colours.text, letterSpacing: 2 });
    this.dropText.anchor.set(0.5, 0.5);
    this.holdHint = label('HOLD FOR AUTO', 11, 'center', { fill: mix(colours.text, colours.accent, 0.35), letterSpacing: 1 });
    this.holdHint.anchor.set(0.5, 0.5);
    this.holdHint.visible = holdToDrop;
    this.stopIcon.visible = false;
    this.glow.visible = false;
    this.drop.addChild(this.glow, this.dropDisc, ...(this.dropArt ? [this.dropArt] : []), this.ballIcon, this.dropText, this.holdHint, this.stopIcon);
    this.drop.eventMode = 'static';
    this.drop.cursor = 'pointer';
    this.drop.on('pointerdown', () => {
      gsap.to(this.drop.scale, { x: 0.94, y: 0.94, duration: 0.1, overwrite: true });
      actions.dropDown();
    });
    const release = () => {
      gsap.to(this.drop.scale, { x: 1, y: 1, duration: 0.15, overwrite: true });
      actions.dropUp();
    };
    this.drop.on('pointerup', release);
    this.drop.on('pointerupoutside', release);
    this.spinsText = label('', 18, 'center', { fill: colours.accent, letterSpacing: 1 });
    this.spinsText.anchor.set(0.5, 0.5);
    this.spinsText.visible = false;

    // bet: the amount (tap it for the quick picker), with - and +
    this.betTitle = this.heading('BET');
    this.betValue = label('', 26, 'center', { fill: colours.text });
    this.betValue.anchor.set(0.5, 0.5);
    this.betCaret = label('▾', 16, 'center', { fill: colours.accent });
    this.betCaret.anchor.set(0.5, 0.5);
    this.betTap.addChild(this.betValue, this.betCaret);
    this.betTap.eventMode = 'static';
    this.betTap.cursor = 'pointer';
    this.betTap.hitArea = new Rectangle(-70, -22, 140, 44);
    this.betTap.on('pointertap', () => this.togglePicker());
    this.minus = new RoundButton(24, () => actions.bet(false), dark, ring, glass, inkLine);
    this.plus = new RoundButton(24, () => actions.bet(true), ink ? colours.accent : glass ? mix(colours.accent, '#000000', 0.15) : mix(colours.accent, colours.background, 0.35),
      ink ? ink.ink : glass ? mix(colours.accent, '#ffffff', 0.35) : colours.accent, glass, inkLine);
    this.drawSign(this.minus, false);
    this.drawSign(this.plus, true);
    this.buildPicker(dark);

    // risk: LOW | MEDIUM | HIGH
    RISK_NAMES.forEach((name, index) => {
      const hit = new Container();
      const text = label(name, 16, 'center', { fill: colours.text, letterSpacing: 1 });
      text.anchor.set(0.5, 0.5);
      hit.addChild(text);
      hit.eventMode = 'static';
      hit.cursor = 'pointer';
      hit.on('pointertap', () => { if (!this.playing) actions.risk(index); });
      this.riskParts.push({ hit, text });
    });

    // rows: the number, with ‹ and ›
    this.rowsTitle = this.heading('ROWS');
    this.rowsValue = label('', 26, 'center', { fill: colours.text });
    this.rowsValue.anchor.set(0.5, 0.5);
    this.rowsDown = new RoundButton(24, () => actions.rows(-1), dark, ring, glass, inkLine);
    this.rowsUp = new RoundButton(24, () => actions.rows(1), dark, ring, glass, inkLine);
    this.rowsDown.disc.poly([4, -8, -5, 0, 4, 8]).fill(colours.text);
    this.rowsUp.disc.poly([-4, -8, 5, 0, -4, 8]).fill(colours.text);

    // i: a short guide to all of this
    this.info = new RoundButton(17, () => actions.info(), dark, ring, glass, inkLine);
    this.info.setLabel('i', 20, colours.text);

    this.balance = new Pill(colours, 'BALANCE', colours.text, glass, ink);
    this.win = new Pill(colours, 'WIN', colours.accent, glass, ink);

    this.addChild(this.card, this.riskBg, this.riskKnob, ...this.riskParts.map(p => p.hit), this.betTitle, this.betTap,
      this.minus, this.plus, this.rowsTitle, this.rowsValue, this.rowsDown, this.rowsUp, this.drop, this.spinsText,
      this.balance, this.win, this.info, this.picker);
  }

  /** Glass or ink: the newer layout (labels in the text colour, light-or-dark text on the accent). */
  private get shaded(): boolean {
    return this.look.glass || !!this.look.ink;
  }

  private heading(text: string): Text {
    const fill = this.shaded ? mix(this.c.text, this.c.background, 0.45) : mix(this.c.text, this.c.accent, 0.45);
    const title = label(text, 14, 'center', { fill, letterSpacing: 2 });
    title.anchor.set(0.5, 0.5);
    return title;
  }

  private drawSign(button: RoundButton, plus: boolean): void {
    const colour = plus && this.shaded && light(this.c.accent) ? this.c.background : this.c.text;
    button.disc.roundRect(-10, -3, 20, 6, 3).fill(colour);
    if (plus) button.disc.roundRect(-3, -10, 6, 20, 3).fill(colour);
  }

  /** The quick bet picker: every bet on the ladder, opened by tapping the amount. */
  private buildPicker(dark: ColorSource): void {
    const columns = 4;
    const cellW = 74;
    const cellH = 36;
    const rows = Math.ceil(this.ladder.length / columns);
    const bg = new Graphics().roundRect(-10, -10, columns * cellW + 20, rows * cellH + 20, 14)
      .fill({ color: this.shaded ? mix(this.c.background, '#000000', 0.2) : dark, alpha: 0.97 }).stroke({ width: 2, color: this.c.accent });
    this.picker.addChild(bg);
    this.ladder.forEach((value, i) => {
      const cell = new Container();
      const box = new Graphics().roundRect(2, 2, cellW - 4, cellH - 4, 10).fill({ color: mix(this.c.background, this.c.accent, 0.15) });
      const text = label(value.toFixed(2).replace(/\.00$/, ''), 16, 'center', { fill: this.c.text });
      text.anchor.set(0.5, 0.5);
      text.position.set(cellW / 2, cellH / 2);
      cell.addChild(box, text);
      cell.position.set((i % columns) * cellW, Math.floor(i / columns) * cellH);
      cell.eventMode = 'static';
      cell.cursor = 'pointer';
      cell.on('pointertap', () => { this.picker.visible = false; this.actions.betPick(value); });
      this.picker.addChild(cell);
    });
    this.picker.visible = false;
  }

  private togglePicker(): void {
    if (this.playing) return;
    this.picker.visible = !this.picker.visible;
  }

  setBet(text: string): void {
    this.betValue.text = text;
    fit(this.betValue, 120);
    this.betCaret.position.set(this.betValue.width / 2 + 12, 1);
  }

  setBalance(text: string): void { this.balance.set(text); }
  setWin(text: string): void { this.win.set(text); }
  setRows(rows: number): void { this.rowsValue.text = String(rows); }

  setRisk(index: number): void {
    const moved = index !== this.risk;
    this.risk = index;
    this.drawRisk(moved);
  }

  /** While balls are in the air the settings are locked; the drop button stays live, and is STOP while holding. */
  setPlaying(playing: boolean, holding = false): void {
    this.playing = playing;
    if (playing) this.picker.visible = false;
    [this.minus, this.plus, this.rowsDown, this.rowsUp, this.info].forEach(b => { b.enabled = !playing; });
    const dim = playing ? 0.45 : 1;
    this.betTap.alpha = dim;
    this.betTap.eventMode = playing ? 'none' : 'static';
    this.riskParts.forEach(p => { p.hit.alpha = dim; });
    this.riskKnob.alpha = dim;
    if (holding !== this.holding) {
      this.holding = holding;
      this.ballIcon.visible = !holding && !this.dropArt;
      this.dropText.visible = !holding;
      this.holdHint.visible = !holding && this.holdToDrop;
      this.stopIcon.visible = this.glow.visible = holding;
      this.glowTween?.kill();
      this.glowTween = holding ? gsap.fromTo(this.glow, { alpha: 0.35 }, { alpha: 1, duration: 0.5, yoyo: true, repeat: -1 }) : null;
    }
  }

  /** "SPINS 12" above the button while Hold & Drop runs (null hides it). */
  setSpins(spins: number | null): void {
    this.spinsText.visible = spins !== null;
    if (spins !== null) this.spinsText.text = `SPINS ${spins}`;
  }

  /** The risk switch: a track with a knob behind the chosen level. */
  private drawRisk(animate = false): void {
    const w = this.riskWidth;
    const h = this.riskHeight;
    const part = w / 3;
    const ink = this.look.ink;
    if (ink) {
      // comic: a dark track and a flat knob, both inked
      this.riskBg.clear().roundRect(3, 3, w, h, h / 2).fill(ink.ink).roundRect(0, 0, w, h, h / 2)
        .fill(mix(ink.panel, '#000000', 0.35)).stroke({ width: 3, color: ink.ink });
      this.riskKnob.clear().roundRect(4, 4, part - 8, h - 8, (h - 8) / 2).fill(this.c.accent).stroke({ width: 2.5, color: ink.ink });
    } else if (this.look.glass) {
      // a sunken track and a glossy knob
      this.riskBg.clear().roundRect(0, 0, w, h, h / 2).fill({ color: mix(this.c.background, '#000000', 0.55), alpha: 0.95 })
        .stroke({ width: 1.5, color: mix(this.c.background, '#ffffff', 0.14) })
        .moveTo(h / 2, 2).lineTo(w - h / 2, 2).stroke({ width: 2, color: '#000000', alpha: 0.5 });
      this.riskKnob.clear().roundRect(3, 3, part - 6, h - 6, (h - 6) / 2)
        .fill(shade(mix(this.c.accent, '#ffffff', 0.18), mix(this.c.accent, '#000000', 0.22)))
        .stroke({ width: 1, color: mix(this.c.accent, '#ffffff', 0.45) })
        .roundRect(3 + h / 3, 5, part - 6 - h * 2 / 3, 2, 1).fill({ color: '#ffffff', alpha: 0.35 });
    } else {
      this.riskBg.clear().roundRect(0, 0, w, h, h / 2).fill({ color: mix(this.c.background, '#000000', 0.4), alpha: 0.9 })
        .stroke({ width: 2, color: this.c.accent, alpha: 0.4 });
      this.riskKnob.clear().roundRect(3, 3, part - 6, h - 6, (h - 6) / 2).fill(this.c.accent);
    }
    const x = this.riskBg.x + this.risk * part;
    if (animate) gsap.to(this.riskKnob, { x, duration: 0.18, ease: 'power2.out', overwrite: true }); else this.riskKnob.x = x;
    this.riskKnob.y = this.riskBg.y;
    this.riskParts.forEach(({ hit, text }, index) => {
      hit.position.set(this.riskBg.x + part * index + part / 2, this.riskBg.y + h / 2);
      hit.hitArea = new Rectangle(-part / 2, -h / 2, part, h);
      const off = this.shaded ? mix(this.c.text, this.c.background, 0.35) : this.c.text;
      text.style.fill = index === this.risk ? (this.shaded && !light(this.c.accent) ? '#ffffff' : this.c.background) : off;
      text.style.fontSize = this.landscape ? 13 : 16;
    });
  }

  /** The drop button: a disc in the theme's colours with a falling ball, DROP, and the hold hint. */
  private drawDrop(radius: number): void {
    this.dropRadius = radius;
    const ring = mix(this.c.accent, '#ffffff', 0.1);
    if (this.dropArt) {
      // the theme's drawing (180 across for a radius of 72, emblem and all): the code adds only the words
      this.dropArt.scale.set(radius * 2.5 / this.dropArt.texture.width);
      this.dropDisc.clear();
      this.ballIcon.visible = false;
    } else this.dropDisc.clear()
      .circle(0, 0, radius + 7).fill({ color: this.c.accent, alpha: 0.16 })
      .circle(0, 0, radius).fill(mix(this.c.background, this.c.accent, 0.3)).stroke({ width: 5, color: ring })
      .circle(0, 0, radius * 0.8).fill({ color: mix(this.c.background, '#000000', 0.25), alpha: 0.75 });
    // a ball falling, with its trail
    const ball = radius * 0.12;
    const by = -radius * 0.3;
    this.ballIcon.clear();
    [0.55, 0.35, 0.18].forEach((alpha, k) => {
      const y = by - ball * (1.6 + k * 1.15);
      this.ballIcon.roundRect(-ball * 0.35, y - ball * 0.45, ball * 0.7, ball * 0.9, ball * 0.35).fill({ color: this.c.accent, alpha });
    });
    this.ballIcon.circle(0, by, ball).fill(this.c.text).circle(-ball * 0.35, by - ball * 0.35, ball * 0.3).fill({ color: '#ffffff', alpha: 0.6 });
    this.dropText.style.fontSize = radius * 0.32;
    this.dropText.position.set(0, radius * 0.1);
    this.holdHint.style.fontSize = radius * 0.13;
    this.holdHint.position.set(0, radius * 0.42);
    if (this.dropArt) {
      // on the theme's (accent-coloured) button the words and the STOP square are light
      this.dropText.style.fill = '#ffffff';
      this.holdHint.style.fill = mix(this.c.text, '#ffffff', 0.5);
      this.holdHint.alpha = 0.85;
      // the art's face is smaller than the plain disc (about 0.64 of the radius): keep both words on it
      this.dropText.style.fontSize = radius * 0.28;
      this.dropText.position.set(0, radius * 0.02);
      this.holdHint.style.fontSize = radius * 0.09;
      this.holdHint.position.set(0, radius * 0.27);
    }
    // never wider than the face, whatever the font
    const face = this.dropArt ? radius * 0.95 : radius * 1.3;
    for (const t of [this.dropText, this.holdHint]) {
      t.scale.set(1);
      if (t.width > face) t.scale.set(face / t.width);
    }
    const side = radius * 0.5;
    this.stopIcon.clear().roundRect(-side / 2, -side / 2, side, side, side * 0.18).fill(this.dropArt ? '#ffffff' : this.c.accent)
      .stroke({ width: 3, color: mix(this.c.accent, '#ffffff', 0.5) });
    this.glow.clear().circle(0, 0, radius + 16).fill({ color: this.c.accent, alpha: 0.3 })
      .circle(0, 0, radius + 8).fill({ color: this.c.accent, alpha: 0.45 });
    this.drop.hitArea = new Rectangle(-radius, -radius, radius * 2, radius * 2);
  }

  /** The parts the guide points at, as boxes in this panel's coordinates (the drop button: round). */
  spots(): Record<'drop' | 'bet' | 'risk' | 'rows', { x: number; y: number; w: number; h: number; round?: boolean }> {
    // a title, then a value, then two round buttons (radius 24) left and right
    const group = (left: Container, right: Container, title: Container) =>
      ({ x: left.x - 30, y: title.y - 22, w: right.x - left.x + 60, h: left.y - title.y + 52 });
    const r = this.dropRadius;
    return {
      drop: { x: this.drop.x - r, y: this.drop.y - r, w: 2 * r, h: 2 * r, round: true },
      bet: group(this.minus, this.plus, this.betTitle),
      risk: { x: this.riskBg.x, y: this.riskBg.y, w: this.riskWidth, h: this.riskHeight },
      rows: group(this.rowsDown, this.rowsUp, this.rowsTitle),
    };
  }

  /** Where everything goes: under the board on phones, in one column left of the board otherwise. */
  layout(view: Viewport): void {
    this.landscape = view.landscape;
    const cardFill = { color: mix(this.c.background, '#000000', 0.3), alpha: 0.8 };
    const cardLine = { width: 2, color: this.c.accent, alpha: 0.35 };
    if (view.landscape) {
      const width = 250;
      const height = 524;
      const top = view.offsetY + 96;
      const available = view.stageH - view.offsetY - 12 - top;
      const scale = Math.min(1, available / height);
      this.scale.set(scale);
      this.position.set(Math.max(view.offsetX + 16, view.stageW * 0.11 - 6), top);
      this.balance.size(width, 50); this.balance.position.set(0, 0);
      this.win.size(width, 50); this.win.position.set(0, 58);
      if (this.look.ink) inkPanel(this.card.clear(), -10, 116, width + 20, 408, 22, this.look.ink.ink, this.look.ink.panel);
      else if (this.look.glass) glassPanel(this.card.clear(), -10, 116, width + 20, 408, 22, this.c.background);
      else this.card.clear().roundRect(-10, 116, width + 20, 408, 26).fill(cardFill).stroke(cardLine);
      this.drawDrop(72);
      this.drop.position.set(width / 2, 214);
      this.spinsText.position.set(width / 2, 128);
      this.betTitle.position.set(width / 2, 306);
      this.minus.position.set(34, 338); this.plus.position.set(width - 34, 338);
      this.betTap.position.set(width / 2 - 6, 338);
      this.riskWidth = width; this.riskHeight = 40;
      this.riskBg.position.set(0, 384);
      this.rowsTitle.position.set(width / 2, 452);
      this.rowsDown.position.set(34, 488); this.rowsValue.position.set(width / 2, 488); this.rowsUp.position.set(width - 34, 488);
      this.picker.position.set(width / 2 - 2 * 74, 364);
      this.info.position.set(width - 12, 140);
    } else {
      const width = 560;
      const height = 340;
      const visibleW = view.stageW - 2 * view.offsetX;
      const top = view.stageH * 0.635;   // below the board's frame
      const available = view.stageH - view.offsetY - 8 - top;
      const scale = Math.min(1.15, available / height, (visibleW - 16) / width);
      this.scale.set(scale);
      this.position.set((view.stageW - width * scale) / 2, top);
      if (this.look.ink) inkPanel(this.card.clear(), 0, 0, width, 270, 26, this.look.ink.ink, this.look.ink.panel);
      else if (this.look.glass) glassPanel(this.card.clear(), 0, 0, width, 270, 26, this.c.background);
      else this.card.clear().roundRect(0, 0, width, 270, 30).fill(cardFill).stroke(cardLine);
      this.riskWidth = width - 40; this.riskHeight = 46;
      this.riskBg.position.set(20, 16);
      this.drawDrop(84);
      this.drop.position.set(width / 2, 168);
      this.spinsText.position.set(width / 2, 74);
      // bet on the left, rows on the right: the same shape either side of the button
      this.betTitle.position.set(95, 96);
      this.betTap.position.set(89, 144);
      this.minus.position.set(55, 206); this.plus.position.set(135, 206);
      this.rowsTitle.position.set(width - 95, 96);
      this.rowsValue.position.set(width - 95, 144);
      this.rowsDown.position.set(width - 135, 206); this.rowsUp.position.set(width - 55, 206);
      this.balance.size(270, 54); this.balance.position.set(0, 284);
      this.win.size(270, 54); this.win.position.set(width - 270, 284);
      this.picker.position.set(20, 168 - Math.ceil(this.ladder.length / 4) * 36 / 2);
      this.info.position.set(width - 28, 96);
    }
    this.setBet(this.betValue.text);
    this.drawRisk();
  }
}
