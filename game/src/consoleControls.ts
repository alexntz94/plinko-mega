// The controls of a theme whose board has a console (Bombs Away): set into the steel console under the board, as on
// an arcade cabinet. BET on the left (− value +, and quick bets), the big DROP plate in the middle (it shows the balls
// still falling, and STOP during autoplay), the balls, the risk and the small keys (autoplay, turbo, bump) on the right;
// credits and the round's win on a plate of their own. On a tall screen the console's parts sit under the board.

import { Container, NineSliceSprite, Rectangle, Sprite, Text, type Texture } from 'pixi.js';
import { gsap } from 'gsap';
import type { Viewport } from './viewport';
import { label, fit } from './ui';
import type { MegaActions } from './megaControls';

/** panelInner: the plain area inside the readout panel's frame (shares of the picture), when its frame is thick */
export interface ConsoleArt { drop: Texture; panel: Texture; key: Texture; back?: Texture | null; panelInner?: [number, number, number, number] | null }

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

  constructor(private readonly square: Texture, text: string, size: number, colour: string, private readonly onTap: () => void,
              private readonly wide: Texture | null = null) {
    super();
    this.art = new Sprite({ texture: square, anchor: 0.5 });
    this.text = label(text, size, 'center', { fill: colour, stroke: { color: '#000000', width: Math.max(2, size * 0.14), join: 'round' } });
    this.text.anchor.set(0.5, 0.5);
    this.addChild(this.art, this.text);
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
    // a wide key is a panel; a square art stretched wide looks wrong
    this.art.texture = this.wide && w > h * 1.5 ? this.wide : this.square;
    this.art.width = w;
    this.art.height = h;
    this.hitArea = new Rectangle(-w / 2, -h / 2, w, h);
    this.text.style.fontSize = Math.round(Math.min(h * 0.42, 26));
    fit(this.text, w * 0.8);
  }

  setEnabled(enabled: boolean): void {
    this.enabled = enabled;
    this.alpha = enabled ? 1 : 0.5;
  }

  setLit(lit: boolean, look: ConsoleLook): void {
    this.art.tint = lit ? '#ffcf6a' : '#ffffff';
    this.text.style.fill = lit ? look.gold : look.text;
  }
}

/** A panel with a title and a value on it (not pressable). */
class Readout extends Container {
  readonly art: Sprite | NineSliceSprite;
  readonly title: Text;
  readonly value: Text;
  private readonly sizer: (w: number, h: number) => PanelArea;
  private readonly framed: boolean;
  private room = 100;

  constructor(texture: Texture, title: string, look: ConsoleLook, inner?: [number, number, number, number] | null) {
    super();
    ({ art: this.art, size: this.sizer } = framedPanel(texture, inner));
    this.framed = !!inner;
    this.title = label(title, 12, 'center', { fill: look.text });
    this.title.anchor.set(0.5, 0.5);
    this.value = label('', 24, 'center', { fill: look.gold, stroke: { color: '#000000', width: 3, join: 'round' } });
    this.value.anchor.set(0.5, 0.5);
    this.addChild(this.art, this.title, this.value);
  }

  size(w: number, h: number): void {
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

  constructor(art: ConsoleArt, private readonly look: ConsoleLook, actions: ConsoleActions, quickBets: number[],
              format: (value: number) => string) {
    super();
    this.panelInner = art.panelInner ?? null;
    this.back = art.back ? new Sprite({ texture: art.back, anchor: 0.5 }) : null;
    if (this.back) this.addChild(this.back);
    const key = (text: string, onTap: () => void) => new Key(art.key, text, 18, look.text, onTap, art.panel);
    this.drop = new Key(art.drop, 'DROP', 44, '#f3ecd6', () => actions.play());
    this.betMinus = key('−', () => actions.bet(false));
    this.betPlus = key('+', () => actions.bet(true));
    this.bet = new Readout(art.panel, 'BET PER BALL', look, art.panelInner);
    this.quickValues = quickBets;
    for (const value of quickBets) this.quick.push(key(format(value).replace(/\.00$/, ''), () => actions.pickBet(value)));
    this.ballsMinus = key('−', () => actions.balls(-1));
    this.ballsPlus = key('+', () => actions.balls(1));
    this.balls = new Readout(art.panel, 'BALLS', look, art.panelInner);
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
    this.rowsShown = new Readout(art.panel, 'ROWS', look, art.panelInner);
    this.credits = new Readout(art.panel, 'CREDITS', look, art.panelInner);
    this.win = new Readout(art.panel, 'WIN', look, art.panelInner);
    this.addChild(this.credits, this.win, this.betMinus, this.bet, this.betPlus, ...this.quick, this.ballsMinus, this.balls, this.ballsPlus,
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
   * then the risks | rows | the small keys.
   */
  layoutBar(view: Viewport, box: Rectangle): void {
    for (const k of this.quick) k.visible = false;
    for (const k of [this.rowsDown, this.rowsShown, this.rowsUp]) k.visible = false;
    this.rowsKey.visible = true;
    this.bumpKey.visible = false;
    if (this.back) {
      this.back.visible = true;
      this.back.position.set(box.x + box.width / 2, box.y + box.height / 2);
      this.back.width = box.width + 24;
      this.back.height = box.height + 16;
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
    this.arsenalKey.text.text = name;
  }

  /** The armed power-up (its short name), lit on the ARSENAL key; null: none. */
  setArmed(name: string | null): void {
    this.arsenalKey.text.text = name ?? this.arsenalName;
    this.arsenalKey.setLit(name !== null, this.look);
    fit(this.arsenalKey.text, this.arsenalKey.art.width * 0.8);
  }

  setRows(rows: number): void {
    this.rowsShown.set(String(rows));
    this.rowsKey.text.text = `ROWS ${rows}`;
    fit(this.rowsKey.text, this.rowsKey.art.width * 0.8);
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
