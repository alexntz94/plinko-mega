// The controls of a theme whose board has a console (Bombs Away): set into the steel console under the board, as on
// an arcade cabinet. BET on the left (− value +, and quick bets), the big DROP plate in the middle (it shows the balls
// still falling, and STOP during autoplay), the balls, the risk and the small keys (autoplay, turbo, bump) on the right;
// credits and the round's win on a plate of their own. On a tall screen the console's parts sit under the board.

import { Container, Rectangle, Sprite, Text, type Texture } from 'pixi.js';
import { gsap } from 'gsap';
import type { Viewport } from './viewport';
import { label, fit } from './ui';
import type { MegaActions } from './megaControls';

export interface ConsoleArt { drop: Texture; panel: Texture; key: Texture; back?: Texture | null }
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
  readonly art: Sprite;
  readonly title: Text;
  readonly value: Text;

  constructor(texture: Texture, title: string, look: ConsoleLook) {
    super();
    this.art = new Sprite({ texture, anchor: 0.5 });
    this.title = label(title, 12, 'center', { fill: look.text });
    this.title.anchor.set(0.5, 0.5);
    this.value = label('', 24, 'center', { fill: look.gold, stroke: { color: '#000000', width: 3, join: 'round' } });
    this.value.anchor.set(0.5, 0.5);
    this.addChild(this.art, this.title, this.value);
  }

  size(w: number, h: number): void {
    this.art.width = w;
    this.art.height = h;
    this.title.style.fontSize = Math.round(h * 0.2);
    this.title.y = -h * 0.2;
    this.value.style.fontSize = Math.round(h * 0.36);
    this.value.y = h * 0.14;
    this.set(this.value.text);
  }

  set(text: string): void {
    this.value.text = text;
    fit(this.value, this.art.width * 0.82);
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
    this.back = art.back ? new Sprite({ texture: art.back, anchor: 0.5 }) : null;
    if (this.back) this.addChild(this.back);
    const key = (text: string, onTap: () => void) => new Key(art.key, text, 18, look.text, onTap, art.panel);
    this.drop = new Key(art.drop, 'DROP', 44, '#f3ecd6', () => actions.play());
    this.betMinus = key('−', () => actions.bet(false));
    this.betPlus = key('+', () => actions.bet(true));
    this.bet = new Readout(art.panel, 'BET PER BALL', look);
    this.quickValues = quickBets;
    for (const value of quickBets) this.quick.push(key(format(value).replace(/\.00$/, ''), () => actions.pickBet(value)));
    this.ballsMinus = key('−', () => actions.balls(-1));
    this.ballsPlus = key('+', () => actions.balls(1));
    this.balls = new Readout(art.panel, 'BALLS', look);
    this.risks = ['LOW', 'MED', 'HIGH'].map((name, i) => key(name, () => actions.risk(i - this.riskIndex)));
    this.auto = key('AUTO', () => actions.auto());
    this.turboKey = key('TURBO', () => { this.turbo = !this.turbo; this.turboKey.setLit(this.turbo, look); actions.turbo(this.turbo); });
    this.bumpKey = key('⚡', () => actions.bump());
    // the board's rows: a tap goes to the next size (the game wraps from the largest back to the smallest)
    this.rowsKey = key('ROWS 8', () => actions.rows(1));
    this.arsenalKey = key('ARSENAL', () => actions.arsenal());
    // wide screens: ROWS ◀ 8 ▶ under the bet
    this.rowsDown = key('◀', () => actions.rows(-1));
    this.rowsUp = key('▶', () => actions.rows(1));
    this.rowsShown = new Readout(art.panel, 'ROWS', look);
    this.credits = new Readout(art.panel, 'CREDITS', look);
    this.win = new Readout(art.panel, 'WIN', look);
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
    // tall, top to bottom, filling the screen: credits | win; bet | balls; risk; the keys; DROP (no quick bets: the
    // board gets the room)
    const w = Math.min(view.stageW - 20, 560);
    const x0 = (view.stageW - w) / 2;
    const bottom = view.stageH - view.offsetY - 12;
    const unit = (bottom - top) / 6.9;
    for (const k of this.quick) k.visible = false;
    for (const k of [this.rowsDown, this.rowsShown, this.rowsUp]) k.visible = false;
    this.rowsKey.visible = true;
    const rowH = Math.min(84, unit * 0.8);
    if (this.back) {
      // one steel console behind everything, as on the board
      this.back.position.set(view.stageW / 2, (top + bottom) / 2);
      this.back.width = view.stageW + 8;
      this.back.height = bottom - top + 20;
    }
    let y = top + unit * 0.55;
    this.credits.position.set(x0 + w * 0.25, y); this.credits.size(w * 0.48, rowH);
    this.win.position.set(x0 + w * 0.75, y); this.win.size(w * 0.48, rowH);
    y += unit * 1.1;
    this.row(this.betMinus, this.bet, this.betPlus, x0, w * 0.49, y, rowH);
    this.row(this.ballsMinus, this.balls, this.ballsPlus, x0 + w * 0.51, w * 0.49, y, rowH);
    y += unit * 1.1;
    this.spread(this.risks, x0, w, y, rowH * 0.85);
    y += unit;
    this.spread([this.auto, this.turboKey, this.rowsKey, this.arsenalKey], x0, w, y, rowH * 0.8);
    this.bumpKey.visible = false;
    y += unit * 1.55;
    // a wide plate fills the row; a round key (Reactor Drop's RELEASE) keeps its shape, a little taller
    const dropH = Math.min(unit * 1.9, w * 0.3);
    const ratio = this.drop.art.texture.width / this.drop.art.texture.height;
    this.drop.position.set(view.stageW / 2, y);
    if (ratio < 1.6) this.drop.size(dropH * 1.3 * ratio, dropH * 1.3);
    else this.drop.size(w * 0.8, dropH);
    this.drop.text.style.fontSize = Math.round(Math.min(unit * 0.8, 52));
    this.drop.base = 1;
  }

  /** Wide: BET and quick bets on the left panel, DROP on the centre plate, balls, risk and keys on the right panel. */
  private place(r: ConsoleRects): void {
    const { left, centre, right, credits } = r;
    for (const k of this.quick) k.visible = false;
    for (const k of [this.rowsDown, this.rowsShown, this.rowsUp]) k.visible = true;
    this.rowsKey.visible = false;
    const rowH = left.height * 0.42;
    this.row(this.betMinus, this.bet, this.betPlus, left.x, left.width, left.y + left.height * 0.27, rowH);
    this.row(this.rowsDown, this.rowsShown, this.rowsUp, left.x, left.width, left.y + left.height * 0.76, rowH * 0.85);
    this.row(this.ballsMinus, this.balls, this.ballsPlus, right.x, right.width * 0.62, right.y + right.height * 0.27, rowH);
    this.spread(this.risks, right.x, right.width * 0.62, right.y + right.height * 0.76, rowH * 0.85);
    this.bumpKey.visible = true;
    this.spreadKeys([this.auto, this.turboKey, this.arsenalKey, this.bumpKey], right.x + right.width * 0.64, right.width * 0.36,
      right.y + right.height * 0.5, rowH * 0.5, true);
    this.drop.position.set(centre.x + centre.width / 2, centre.y + centre.height / 2);
    this.drop.size(centre.width * 1.04, centre.height * 1.1);
    this.drop.text.style.fontSize = Math.round(centre.height * 0.5);
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
  /** The armed power-up (its short name), lit on the ARSENAL key; null: none. */
  setArmed(name: string | null): void {
    this.arsenalKey.text.text = name ?? 'ARSENAL';
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
    fit(this.drop.text, this.drop.art.width * 0.7);
    this.auto.setLit(auto, this.look);
    this.bumpKey.setEnabled(left > 0);
    for (const k of [this.betMinus, this.betPlus, this.ballsMinus, this.ballsPlus, this.rowsKey, this.rowsDown, this.rowsUp, this.arsenalKey, ...this.quick, ...this.risks]) k.setEnabled(!busy);
  }
}
