// The controls of a theme whose board has a console (Bombs Away): set into the steel console under the board, as on
// an arcade cabinet. BET on the left (− value +, and quick bets), the big DROP plate in the middle (it shows the balls
// still falling, and STOP during autoplay), the balls, the risk and the small keys (autoplay, turbo, bump) on the right;
// credits and the round's win on a plate of their own. On a tall screen the console's parts sit under the board.

import { Container, Graphics, NineSliceSprite, Rectangle, Sprite, Text, type Texture } from 'pixi.js';
import { gsap } from 'gsap';
import type { Viewport } from './viewport';
import { label, fit } from './ui';
import type { MegaActions } from './megaControls';

/** panelInner: the plain area inside the readout panel's frame (shares of the picture), when its frame is thick;
 *  ledger: the controls are written on a ledger's page (Death & Taxes) in ink, the lit ones circled in red, with no
 *  panels or keys drawn round them (only DROP keeps its art: a wax seal) */
export interface ConsoleArt { drop: Texture; panel: Texture; key: Texture; back?: Texture | null; panelInner?: [number, number, number, number] | null;
  ledger?: Ledger | null }
/** a ledger's ink and its red */
export interface Ledger { ink: string; red: string }

export type PanelArea = { x0: number; x1: number; y0: number; y1: number };

/**
 * A panel picture and how to size it to w by h: stretched whole, or, with its frame's inner area known, only its middle
 * stretched (the frame's ends scaled with the height, so they keep their shape). size() gives where the plain inner
 * area is then, centred on the panel.
 */
export function framedPanel(texture: Texture, inner: [number, number, number, number] | null | undefined): {
  art: Sprite | NineSliceSprite; size: (w: number, h: number) => PanelArea } {
  if (!inner) {
    const art = new Sprite({ texture, anchor: 0.5 });
    return { art, size: (w, h) => {
      art.width = w;
      art.height = h;
      return { x0: -w / 2, x1: w / 2, y0: -h / 2, y1: h / 2 };
    } };
  }
  const [ix0, iy0, ix1, iy1] = inner;
  const left = texture.width * ix0, right = texture.width * (1 - ix1);
  const art = new NineSliceSprite({ texture, leftWidth: left, rightWidth: right, topHeight: 0, bottomHeight: 0, anchor: 0.5 });
  return { art, size: (w, h) => {
    // drawn at the picture's own height and scaled: the ends keep their shape (narrower, if the panel is too short)
    const s = Math.min(h / texture.height, w / (left + right + 1));
    art.scale.set(1);
    art.width = w / s;
    art.height = h / s;
    art.scale.set(s);
    return { x0: -w / 2 + left * s, x1: w / 2 - right * s, y0: -h / 2 + h * iy0, y1: -h / 2 + h * iy1 };
  } };
}
export interface ConsoleLook { gold: string; text: string; ink: string }
export interface ConsoleActions extends MegaActions {
  /** a quick bet: this bet per ball */
  pickBet(value: number): void;
  /** opens the ARSENAL (the power-ups) */
  arsenal(): void;
}
/** Where the console's three areas are on the screen, in the controls' parent's coordinates (wide screens). */
export interface ConsoleRects { left: Rectangle; centre: Rectangle; right: Rectangle; credits: Rectangle }

/** A pressable piece of console art with a stencilled label on it; dims when it can't be used. */
class Key extends Container {
  readonly art: Sprite;
  readonly text: Text;
  enabled = true;

  /** a ledger's key: the word alone, in ink, circled in red when lit */
  private readonly ring: Graphics | null;

  constructor(private readonly square: Texture, text: string, size: number, colour: string, private readonly onTap: () => void,
              private readonly wide: Texture | null = null, private readonly inner: [number, number, number, number] | null = null,
              private readonly ledger: Ledger | null = null) {
    super();
    this.art = new Sprite({ texture: square, anchor: 0.5 });
    this.text = label(text, size, 'center', ledger ? { fill: ledger.ink }
      : { fill: colour, stroke: { color: '#000000', width: Math.max(2, size * 0.14), join: 'round' } });
    this.text.anchor.set(0.5, 0.5);
    this.ring = ledger ? new Graphics() : null;
    this.art.visible = !ledger;
    if (this.ring) this.ring.visible = false;
    this.addChild(this.art, ...(this.ring ? [this.ring] : []), this.text);
    this.eventMode = 'static';
    this.cursor = 'pointer';
    this.on('pointertap', () => {
      if (!this.enabled) return;
      gsap.fromTo(this.scale, { x: this.scale.x * 0.92, y: this.scale.y * 0.92 }, { x: this.base, y: this.base, duration: 0.2, ease: 'back.out(3)', overwrite: true });
      this.onTap();
    });
  }

  base = 1;

  /** Sized to w by h (the art stretches), its label kept inside. */
  size(w: number, h: number): void {
    if (this.ledger) {
      this.hitArea = new Rectangle(-w / 2, -h / 2, w, h);
      this.room = w * 0.92;
      this.text.style.fontSize = Math.round(Math.min(h * 0.62, 30));
      fit(this.text, this.room);
      this.drawRing();
      return;
    }
    // a wide key is a panel; a square art stretched wide looks wrong
    this.art.texture = this.wide && w > h * 1.5 ? this.wide : this.square;
    this.art.width = w;
    this.art.height = h;
    this.hitArea = new Rectangle(-w / 2, -h / 2, w, h);
    // a wide key with a thick frame (its ends carved): the label stays in the plain middle
    const framed = this.inner && this.art.texture === this.wide;
    this.room = framed ? (this.inner![2] - this.inner![0]) * w * 0.86 : w * 0.8;
    this.text.style.fontSize = Math.round(Math.min(h * (framed ? 0.4 : 0.42), 26));
    fit(this.text, this.room);
  }

  /** How wide the label may be (set by size). */
  private room = 100;

  /** A new label, kept inside the key as size placed it. */
  relabel(text: string): void {
    this.text.text = text;
    fit(this.text, this.room);
    if (this.ledger) this.drawRing();
  }

  /** A ledger's lit key: a quick hand-drawn red ellipse round its word (a little open, a little crooked). */
  private drawRing(): void {
    const ring = this.ring!;
    const rx = this.text.width / 2 + 9, ry = this.text.height * 0.5 + 4;
    ring.clear();
    const steps = 28;
    for (let i = 0; i <= steps; i++) {
      const t = -0.35 + (i / steps) * (Math.PI * 2 + 0.25);
      const wobble = 1 + 0.05 * Math.sin(t * 3);
      const x = Math.cos(t) * rx * wobble, y = Math.sin(t) * ry * wobble + (t > Math.PI ? 1.5 : 0);
      if (i === 0) ring.moveTo(x, y); else ring.lineTo(x, y);
    }
    ring.stroke({ color: this.ledger!.red, width: 2.2, cap: 'round', join: 'round' });
    ring.rotation = -0.06;
  }

  setEnabled(enabled: boolean): void {
    this.enabled = enabled;
    this.alpha = enabled ? 1 : this.ledger ? 0.4 : 0.5;
  }

  private lit = false;

  setLit(lit: boolean, look: ConsoleLook): void {
    if (this.ledger) {
      if (lit && !this.lit && this.ring) gsap.fromTo(this.ring, { alpha: 0 }, { alpha: 1, duration: 0.25 });
      this.lit = lit;
      this.text.style.fill = lit ? this.ledger.red : this.ledger.ink;
      if (this.ring) this.ring.visible = lit;
      return;
    }
    this.art.tint = lit ? '#ffcf6a' : '#ffffff';
    this.text.style.fill = lit ? look.gold : look.text;
  }
}

/**
 * A panel with a title and a value on it (not pressable). On a ledger: no panel, an entry written on the page, its
 * title and its value on one line over a ruled line (inline), or its title over its value (stacked).
 */
class Readout extends Container {
  readonly art: Sprite | NineSliceSprite;
  readonly title: Text;
  readonly value: Text;
  private readonly sizer: (w: number, h: number) => PanelArea;
  private readonly framed: boolean;
  private room = 100;
  private readonly rule: Graphics | null;
  /** a ledger entry on one line: its value's middle this far across (shares of its width), or null: at its right end */
  inline: number | null | false = false;

  constructor(texture: Texture, title: string, look: ConsoleLook, inner?: [number, number, number, number] | null,
              private readonly ledger: Ledger | null = null, red = false) {
    super();
    ({ art: this.art, size: this.sizer } = framedPanel(texture, inner));
    this.framed = !!inner;
    this.title = label(title, 12, 'center', { fill: ledger ? ledger.ink : look.text });
    this.title.anchor.set(0.5, 0.5);
    this.value = label('', 24, 'center', ledger ? { fill: red ? ledger.red : ledger.ink }
      : { fill: look.gold, stroke: { color: '#000000', width: 3, join: 'round' } });
    this.value.anchor.set(0.5, 0.5);
    this.rule = ledger ? new Graphics() : null;
    this.art.visible = !ledger;
    this.addChild(this.art, ...(this.rule ? [this.rule] : []), this.title, this.value);
  }

  size(w: number, h: number): void {
    if (this.ledger) {
      this.sizeLedger(w, h);
      return;
    }
    const area = this.sizer(w, h);
    if (this.framed) {
      // a thick frame: the title and the value both inside its plain middle
      const { x0, x1, y0, y1 } = area, ih = y1 - y0;
      this.room = (x1 - x0) * 0.92;
      this.title.style.fontSize = Math.round(ih * 0.42);
      this.title.y = y0 + ih * 0.22;
      this.value.style.fontSize = Math.round(ih * 0.72);
      this.value.y = y0 + ih * 0.68;
      this.title.x = this.value.x = (x0 + x1) / 2;
      fit(this.title, this.room);
    } else {
      this.room = w * 0.82;
      this.title.style.fontSize = Math.round(h * 0.2);
      this.title.y = -h * 0.2;
      this.value.style.fontSize = Math.round(h * 0.36);
      this.value.y = h * 0.14;
    }
    this.set(this.value.text);
  }

  private sizeLedger(w: number, h: number): void {
    const rule = this.rule!, ink = this.ledger!.ink;
    rule.clear();
    if (this.inline !== false) {
      // TITLE .......... value   (or TITLE   − value +, the value's middle at `inline`)
      this.title.anchor.set(0, 0.5);
      this.title.style.fontSize = Math.round(h * 0.4);
      this.title.position.set(-w / 2, 0);
      this.value.style.fontSize = Math.round(h * 0.56);
      const at = this.inline;
      this.value.anchor.set(at === null ? 1 : 0.5, 0.5);
      this.value.position.set(at === null ? w / 2 : -w / 2 + w * at, -h * 0.02);
      this.room = at === null ? w * 0.55 : w * 2 * Math.min(at, 1 - at) * 0.9;
      fit(this.title, w * (at === null ? 0.42 : Math.max(0.2, at - 0.25)));
      rule.moveTo(-w / 2, h * 0.38).lineTo(w / 2, h * 0.38).stroke({ color: ink, width: 1, alpha: 0.35 });
    } else {
      this.title.anchor.set(0.5, 0.5);
      this.value.anchor.set(0.5, 0.5);
      this.room = w * 0.92;
      this.title.style.fontSize = Math.round(h * 0.28);
      this.title.position.set(0, -h * 0.24);
      this.value.style.fontSize = Math.round(h * 0.46);
      this.value.position.set(0, h * 0.12);
      fit(this.title, this.room);
      rule.moveTo(-w / 2, h * 0.42).lineTo(w / 2, h * 0.42).stroke({ color: ink, width: 1, alpha: 0.35 });
    }
    this.set(this.value.text);
  }

  set(text: string): void {
    this.value.text = text;
    fit(this.value, this.room);
  }
}

export class ConsoleControls extends Container {
  private readonly drop: Key;
  private readonly betMinus: Key;
  private readonly betPlus: Key;
  private readonly bet: Readout;
  private readonly quick: Key[] = [];
  private quickValues: number[] = [];
  private readonly ballsMinus: Key;
  private readonly ballsPlus: Key;
  private readonly balls: Readout;
  private readonly risks: Key[];
  private readonly auto: Key;
  private readonly turboKey: Key;
  private readonly bumpKey: Key;
  private readonly rowsKey: Key;
  private readonly arsenalKey: Key;
  private readonly rowsDown: Key;
  private readonly rowsUp: Key;
  private readonly rowsShown: Readout;
  private readonly credits: Readout;
  private readonly win: Readout;
  private turbo = false;
  private riskIndex = 0;
  private pulse: gsap.core.Tween | null = null;
  /** the phone's console plate, behind all the controls */
  private readonly back: Sprite | null;
  /** a ledger's RISK, in front of its three keys */
  private readonly riskTitle: Text;

  constructor(art: ConsoleArt, private readonly look: ConsoleLook, actions: ConsoleActions, quickBets: number[],
              format: (value: number) => string) {
    super();
    this.panelInner = art.panelInner ?? null;
    this.back = art.back ? new Sprite({ texture: art.back, anchor: 0.5 }) : null;
    if (this.back) this.addChild(this.back);
    const ledger = art.ledger ?? null;
    const key = (text: string, onTap: () => void) => new Key(art.key, text, 18, look.text, onTap, art.panel, art.panelInner, ledger);
    this.drop = new Key(art.drop, 'DROP', 44, '#f3ecd6', () => actions.play());
    this.betMinus = key('−', () => actions.bet(false));
    this.betPlus = key('+', () => actions.bet(true));
    this.bet = new Readout(art.panel, 'BET PER BALL', look, art.panelInner, ledger);
    this.quickValues = quickBets;
    for (const value of quickBets) this.quick.push(key(format(value).replace(/\.00$/, ''), () => actions.pickBet(value)));
    this.ballsMinus = key('−', () => actions.balls(-1));
    this.ballsPlus = key('+', () => actions.balls(1));
    this.balls = new Readout(art.panel, 'BALLS', look, art.panelInner, ledger);
    this.risks = ['LOW', 'MED', 'HIGH'].map((name, i) => key(name, () => actions.risk(i - this.riskIndex)));
    this.auto = key('AUTO', () => actions.auto());
    this.turboKey = key('TURBO', () => { this.turbo = !this.turbo; this.turboKey.setLit(this.turbo, look); actions.turbo(this.turbo); });
    this.bumpKey = key('BUMP', () => actions.bump());
    // the board's rows: a tap goes to the next size (the game wraps from the largest back to the smallest)
    this.rowsKey = key('ROWS 8', () => actions.rows(1));
    this.arsenalKey = key('ARSENAL', () => actions.arsenal());
    // wide screens: ROWS ◀ 8 ▶ under the bet
    this.rowsDown = key('◀', () => actions.rows(-1));
    this.rowsUp = key('▶', () => actions.rows(1));
    this.rowsShown = new Readout(art.panel, 'ROWS', look, art.panelInner, ledger);
    this.credits = new Readout(art.panel, 'CREDITS', look, art.panelInner, ledger);
    this.win = new Readout(art.panel, 'WIN', look, art.panelInner, ledger, true);
    this.riskTitle = label('RISK', 18, 'left', { fill: ledger?.ink ?? look.text });
    this.riskTitle.anchor.set(0, 0.5);
    this.riskTitle.visible = false;
    this.addChild(this.riskTitle, this.credits, this.win, this.betMinus, this.bet, this.betPlus, ...this.quick, this.ballsMinus, this.balls, this.ballsPlus,
      ...this.risks, this.auto, this.turboKey, this.rowsKey, this.bumpKey, this.arsenalKey, this.rowsDown, this.rowsShown, this.rowsUp, this.drop);
    this.bumpKey.setEnabled(false);
  }

  closeDrawer(): void { /* no drawer: everything is on the console */ }

  /**
   * Wide screens: into the console's areas (rects, from the board). Tall: the same parts in rows under the board,
   * from y down to the bottom of the screen.
   */
  layout(view: Viewport, rects: ConsoleRects | null, top = 0): void {
    if (this.back) this.back.visible = !rects;
    if (rects) {
      this.place(rects);
      return;
    }
    // tall, top to bottom: credits | win; bet | balls; then the keys in three rows on the left (risk; auto, turbo;
    // rows, the power-ups) and DROP big beside them on the right, under the thumb (no quick bets: the board gets the room)
    const w = Math.min(view.stageW - 2 * view.offsetX - 16, 560);
    const x0 = (view.stageW - w) / 2;
    const bottom = view.stageH - view.offsetY - 10;
    for (const k of this.quick) k.visible = false;
    for (const k of [this.rowsDown, this.rowsShown, this.rowsUp]) k.visible = false;
    this.rowsKey.visible = true;
    this.bumpKey.visible = false;
    // five rows: the readouts a little taller than the keys; whatever height is left goes between them
    const rowH = Math.min(64, w * 0.15, (bottom - top) / 6);
    const gap = Math.max(4, Math.min(rowH * 0.45, (bottom - top - rowH * 5) / 6));
    if (this.back) {
      // one plate behind everything, as on the board
      this.back.position.set(view.stageW / 2, (top + bottom) / 2 + 2);
      this.back.width = view.stageW + 8;
      this.back.height = bottom - top + 24;
    }
    let y = top + gap + rowH / 2;
    this.credits.position.set(x0 + w * 0.25, y); this.credits.size(w * 0.49, rowH);
    this.win.position.set(x0 + w * 0.75, y); this.win.size(w * 0.49, rowH);
    y += rowH + gap;
    this.row(this.betMinus, this.bet, this.betPlus, x0, w * 0.49, y, rowH);
    this.row(this.ballsMinus, this.balls, this.ballsPlus, x0 + w * 0.51, w * 0.49, y, rowH);
    y += rowH + gap;
    const left = w * 0.64, keyH = rowH * 0.82;
    this.spread(this.risks, x0, left, y, keyH);
    this.spread([this.auto, this.turboKey], x0, left, y + keyH + gap, keyH);
    this.spread([this.rowsKey, this.arsenalKey], x0, left, y + 2 * (keyH + gap), keyH);
    // DROP beside the three rows of keys
    const span = 3 * keyH + 2 * gap;
    const cx = x0 + left + (w - left) / 2, cy = y + span / 2 - keyH / 2;
    const dropH = Math.min(span * 1.05, (w - left) * 1.05);
    this.drop.position.set(cx, cy);
    if (this.roundDrop()) {
      const ratio = this.drop.art.texture.width / this.drop.art.texture.height;
      this.drop.size(dropH * ratio, dropH);
      this.dropText = 0.44;
      this.drop.text.style.fontSize = Math.round(dropH * 0.2);
    } else {
      this.drop.size(w - left - 6, Math.min(span, (w - left) * 0.7));
      this.dropText = 0.7;
      this.drop.text.style.fontSize = Math.round(Math.min(span * 0.3, 44));
    }
    fit(this.drop.text, this.drop.art.width * this.dropText);
    this.drop.base = 1;
  }

  /**
   * Wide, in a bar of its own (box: where it goes): on a plate, two rows left of a big DROP: credits | win | bet | balls,
   * then the risks | rows | the small keys. right: its strip runs on to there (under a character leaning on it).
   */
  layoutBar(view: Viewport, box: Rectangle, right: number | null = null): void {
    for (const k of this.quick) k.visible = false;
    for (const k of [this.rowsDown, this.rowsShown, this.rowsUp]) k.visible = false;
    this.rowsKey.visible = true;
    this.bumpKey.visible = false;
    if (this.back) {
      this.back.visible = true;
      this.back.position.set(box.x + box.width / 2, box.y + box.height / 2);
      this.back.width = box.width + 24;
      this.back.height = box.height + 16;
      // the strip runs on to `right` (a counter for a character to lean on), the controls staying in the box
      if (right !== null && right > box.x + box.width) {
        this.back.width = right - box.x + 12;
        this.back.x = (box.x - 12 + right) / 2;
      }
    }
    const pad = box.height * 0.08;
    const dropW = box.height * 1.05;
    const x0 = box.x + pad, w = box.width - dropW - pad * 3;
    const rowH = (box.height - pad * 3) / 2;
    const y1 = box.y + pad + rowH / 2, y2 = y1 + rowH + pad;
    // credits and win a little narrower than bet and balls, whose − and + keys share their room
    const part = w / 4, cw = w * 0.22, bw = w * 0.28;
    this.credits.position.set(x0 + cw * 0.5, y1); this.credits.size(cw * 0.96, rowH);
    this.win.position.set(x0 + cw * 1.5, y1); this.win.size(cw * 0.96, rowH);
    this.row(this.betMinus, this.bet, this.betPlus, x0 + cw * 2, bw * 0.98, y1, rowH);
    this.row(this.ballsMinus, this.balls, this.ballsPlus, x0 + cw * 2 + bw, bw * 0.98, y1, rowH);
    this.spread(this.risks, x0, part * 1.5, y2, rowH * 0.86);
    this.spread([this.rowsKey, this.auto, this.turboKey, this.arsenalKey], x0 + part * 1.55, w - part * 1.55, y2, rowH * 0.86);
    const cx = box.x + box.width - pad - dropW / 2;
    this.drop.position.set(cx, box.y + box.height / 2);
    const ratio = this.drop.art.texture.width / this.drop.art.texture.height;
    if (this.roundDrop()) {
      this.drop.size(dropW * ratio, dropW);
      this.dropText = 0.44;
      this.drop.text.style.fontSize = Math.round(dropW * 0.2);
    } else {
      this.drop.size(dropW * 1.6, dropW * 0.7);
      this.dropText = 0.7;
      this.drop.text.style.fontSize = Math.round(dropW * 0.3);
    }
    fit(this.drop.text, this.drop.art.width * this.dropText);
    this.drop.base = 1;
    void view;
  }

  /** Wide: BET and quick bets on the left panel, DROP on the centre plate, balls, risk and keys on the right panel. */
  private place(r: ConsoleRects): void {
    const { left, centre, right, credits } = r;
    for (const k of this.quick) k.visible = false;
    for (const k of [this.rowsDown, this.rowsShown, this.rowsUp]) k.visible = true;
    this.rowsKey.visible = false;
    // a thick-framed panel art (its text in a smaller inside) gets the panel's whole height for its two rows
    const framed = !!this.panelInner;
    const rowH = left.height * (framed ? 0.5 : 0.42);
    const top = framed ? 0.25 : 0.27, bottom = framed ? 0.75 : 0.76, second = framed ? 0.96 : 0.85;
    this.row(this.betMinus, this.bet, this.betPlus, left.x, left.width, left.y + left.height * top, rowH);
    this.row(this.rowsDown, this.rowsShown, this.rowsUp, left.x, left.width, left.y + left.height * bottom, rowH * second);
    this.row(this.ballsMinus, this.balls, this.ballsPlus, right.x, right.width * 0.62, right.y + right.height * top, rowH);
    this.spread(this.risks, right.x, right.width * 0.62, right.y + right.height * bottom, rowH * second);
    // the small keys in a column (three bigger ones when the panel art is framed: no BUMP)
    this.bumpKey.visible = !framed;
    const small = framed ? [this.auto, this.turboKey, this.arsenalKey] : [this.auto, this.turboKey, this.arsenalKey, this.bumpKey];
    this.spreadKeys(small, right.x + right.width * 0.64, right.width * 0.36, right.y + right.height * 0.5, rowH * (framed ? 0.62 : 0.5), true);
    this.drop.position.set(centre.x + centre.width / 2, centre.y + centre.height / 2);
    this.drop.size(centre.width * 1.04, centre.height * 1.1);
    // a round key's label stays inside its glass (about half its width); a wide plate's fills it
    this.dropText = this.roundDrop() ? 0.44 : 0.7;
    this.drop.text.style.fontSize = Math.round(centre.height * (this.roundDrop() ? 0.3 : 0.5));
    fit(this.drop.text, this.drop.art.width * this.dropText);
    this.credits.position.set(credits.x + credits.width / 2, credits.y + credits.height * 0.27);
    this.credits.size(credits.width, credits.height * 0.5);
    this.win.position.set(credits.x + credits.width / 2, credits.y + credits.height * 0.77);
    this.win.size(credits.width, credits.height * 0.46);
  }

  /**
   * Written on a ledger's page (page: its plain area). Wide (the book's right page), one entry a line: credits, the win,
   * the bet and the balls (− value +), the risk, the rows (◀ value ▶), then the small keys; the wax seal (DROP) under
   * them. Tall (a page under the board): credits | win, the bet, the balls, then the keys in three rows left of the seal.
   */
  layoutLedger(view: Viewport, page: Rectangle): void {
    for (const k of this.quick) k.visible = false;
    this.bumpKey.visible = false;
    let { x, y, width: w, height: h } = page;
    const pad = Math.min(w, h) * 0.02;
    if (view.landscape) {
      // clear of the page's edges
      x += w * 0.025;
      w *= 0.95;
      for (const k of [this.rowsDown, this.rowsShown, this.rowsUp]) k.visible = true;
      this.rowsKey.visible = false;
      this.riskTitle.visible = true;
      if (this.back) this.back.visible = false;
      // a page as wide as tall or wider: the seal beside the last two lines; else under them all
      const side = w > h * 0.9;
      const seal = side ? Math.min(w * 0.3, h * 0.34) : Math.min(w * 0.36, h * 0.3);
      const rowH = side ? (h - pad * 2) / 7 : (h - seal - pad * 2) / 7;
      const at = (i: number) => y + pad + rowH * (i + 0.5);
      const line = (r: Readout, i: number, value: number | null, width = w) => {
        r.inline = value; r.position.set(x + width / 2, at(i)); r.size(width, rowH * 0.9);
      };
      line(this.credits, 0, null);
      line(this.win, 1, null);
      // − value + at the right of the line
      const pair = (minus: Key, r: Readout, plus: Key, i: number, width = w) => {
        line(r, i, 0.78, width);
        const k = rowH * 0.78;
        minus.position.set(x + width * 0.6, at(i)); minus.size(k, k);
        plus.position.set(x + width * 0.96, at(i)); plus.size(k, k);
      };
      pair(this.betMinus, this.bet, this.betPlus, 2);
      pair(this.ballsMinus, this.balls, this.ballsPlus, 3);
      this.riskTitle.style.fontSize = Math.round(rowH * 0.9 * 0.4);
      this.riskTitle.position.set(x, at(4));
      this.spread(this.risks, x + w * 0.4, w * 0.6, at(4), rowH * 0.8);
      const rest = side ? w * 0.66 : w;
      pair(this.rowsDown, this.rowsShown, this.rowsUp, 5, rest);
      this.spread([this.auto, this.turboKey, this.arsenalKey], x, rest, at(6), rowH * 0.8);
      if (side) this.sizeSeal(x + rest + (w - rest) / 2, at(5.5), seal);
      else this.sizeSeal(x + w / 2, y + h - pad - seal / 2, seal);
      return;
    }
    for (const k of [this.rowsDown, this.rowsShown, this.rowsUp]) k.visible = false;
    this.rowsKey.visible = true;
    this.riskTitle.visible = false;
    if (this.back) {
      this.back.visible = true;
      this.back.position.set(x + w / 2, y + h / 2);
      this.back.width = w;
      this.back.height = h;
    }
    // inside the page's margins
    const ix = x + w * 0.08, iw = w * 0.84, iy = y + h * 0.1, ih = h * 0.82;
    const rowH = Math.min(58, ih / 6.2);
    const gap = Math.max(2, (ih - rowH * 6) / 6);
    let cy = iy + gap / 2 + rowH / 2;
    for (const r of [this.credits, this.win]) r.inline = false;
    this.credits.position.set(ix + iw * 0.25, cy); this.credits.size(iw * 0.46, rowH);
    this.win.position.set(ix + iw * 0.75, cy); this.win.size(iw * 0.46, rowH);
    cy += rowH + gap;
    for (const [minus, r, plus] of [[this.betMinus, this.bet, this.betPlus], [this.ballsMinus, this.balls, this.ballsPlus]] as const) {
      r.inline = 0.74;
      r.position.set(ix + iw / 2, cy); r.size(iw, rowH * 0.8);
      const k = rowH * 0.7;
      minus.position.set(ix + iw * 0.54, cy); minus.size(k, k);
      plus.position.set(ix + iw * 0.94, cy); plus.size(k, k);
      cy += rowH * 0.8 + gap;
    }
    const left = iw * 0.66, keyH = rowH * 0.72;
    this.spread(this.risks, ix, left, cy, keyH);
    this.spread([this.auto, this.turboKey], ix, left, cy + keyH + gap, keyH);
    this.spread([this.rowsKey, this.arsenalKey], ix, left, cy + 2 * (keyH + gap), keyH);
    const span = 3 * keyH + 2 * gap;
    this.sizeSeal(ix + left + (iw - left) / 2, cy + span / 2 - keyH / 2, Math.min(span * 1.1, (iw - left) * 1.05));
  }

  /** The wax seal (DROP), round, side across, its word inside its middle. */
  private sizeSeal(cx: number, cy: number, side: number): void {
    const ratio = this.drop.art.texture.width / this.drop.art.texture.height;
    this.drop.position.set(cx, cy);
    this.drop.size(side * ratio, side);
    this.dropText = 0.46;
    this.drop.text.style.fontSize = Math.round(side * 0.2);
    fit(this.drop.text, this.drop.art.width * this.dropText);
    this.drop.base = 1;
  }

  /** − readout + across a width. */
  private row(minus: Key, readout: Readout, plus: Key, x: number, w: number, y: number, h: number): void {
    const k = Math.min(h, w * 0.2);
    minus.position.set(x + k * 0.6, y); minus.size(k, k);
    plus.position.set(x + w - k * 0.6, y); plus.size(k, k);
    readout.position.set(x + w / 2, y); readout.size(w - k * 2.5, h);
  }

  /** Keys side by side, filling a width. */
  private spread(keys: Key[], x: number, w: number, y: number, h: number): void {
    const each = w / keys.length;
    keys.forEach((k, i) => { k.position.set(x + each * (i + 0.5), y); k.size(each * 0.92, h); });
  }

  /** Small keys stacked (column) or side by side in a width. */
  private spreadKeys(keys: Key[], x: number, w: number, y: number, h: number, column = false): void {
    if (column) {
      keys.forEach((k, i) => { k.position.set(x + w / 2, y + (i - (keys.length - 1) / 2) * h * 1.08); k.size(w * 0.92, h); });
      return;
    }
    this.spread(keys, x, w, y, h);
  }

  setBalance(text: string): void { this.credits.set(text); }
  setTotalBet(_text: string): void { /* the bet per ball and the balls are shown; their product is on DROP's hint */ }
  setWin(text: string): void { this.win.set(text); }
  setRisk(name: string): void {
    this.riskIndex = name === 'HIGH' ? 2 : name === 'LOW' ? 0 : 1;
    this.risks.forEach((k, i) => k.setLit(i === this.riskIndex, this.look));
  }
  setBalls(balls: number, perBall: string): void {
    this.balls.set(String(balls));
    this.bet.set(perBall);
    this.quick.forEach((k, i) => k.setLit(perBall.replace(/[^0-9.]/g, '') === String(this.quickValues[i].toFixed(2)), this.look));
  }
  /** The readout panel art's plain inside, when its frame is thick (see ConsoleArt). */
  private readonly panelInner: [number, number, number, number] | null;
  /** How much of the DROP key's width its label may take. */
  private dropText = 0.7;
  /** Whether the DROP key is round (Reactor Drop's RELEASE) rather than a wide plate. */
  private roundDrop(): boolean {
    return this.drop.art.texture.width / this.drop.art.texture.height < 1.6;
  }

  /** What the power-up key says when nothing is armed (the theme's name for its panel). */
  private arsenalName = 'ARSENAL';
  setArsenalName(name: string): void {
    this.arsenalName = name;
    this.arsenalKey.relabel(name);
  }

  /** The armed power-up (its short name), lit on the ARSENAL key; null: none. */
  setArmed(name: string | null): void {
    this.arsenalKey.relabel(name ?? this.arsenalName);
    this.arsenalKey.setLit(name !== null, this.look);
  }

  /** Whether the rows are changed with the single ROWS key (it goes round: past the largest board, the smallest). */
  get rowsKeyShown(): boolean {
    return this.rowsKey.visible;
  }

  setRows(rows: number): void {
    this.rowsShown.set(String(rows));
    this.rowsKey.relabel(`ROWS ${rows}`);
  }

  /** busy: balls falling (settings lock); auto: autoplay; left: balls still to land. */
  setPlaying(busy: boolean, auto: boolean, left: number): void {
    if (!busy && !this.pulse) {
      this.pulse = gsap.to(this.drop.art, { alpha: 0.82, duration: 0.6, yoyo: true, repeat: -1, ease: 'sine.inOut' });
    } else if (busy && this.pulse) {
      this.pulse.kill();
      this.pulse = null;
      this.drop.art.alpha = 1;
    }
    this.drop.text.text = auto ? 'STOP' : busy && left > 0 ? `${left} ▸▸` : 'DROP';
    fit(this.drop.text, this.drop.art.width * this.dropText);
    this.auto.setLit(auto, this.look);
    this.bumpKey.setEnabled(left > 0);
    for (const k of [this.betMinus, this.betPlus, this.ballsMinus, this.ballsPlus, this.rowsKey, this.rowsDown, this.rowsUp, this.arsenalKey, ...this.quick, ...this.risks]) k.setEnabled(!busy);
  }
}
