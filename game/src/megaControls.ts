// Plinko Mega's controls, as on the big plinko games: a round PLAY button with the bet's − and + beside it, autoplay,
// turbo, the bump and a menu that opens the settings drawer (rows); the balls (− 10 +) and the risk switch (LOW,
// MEDIUM, HIGH) on top of the bar; balance, total bet and last win underneath. While balls fall PLAY shows how many are left and a tap hurries them; during autoplay it stops it.

import { Container, Graphics, Rectangle, Text } from 'pixi.js';
import { gsap } from 'gsap';
import type { Viewport } from './viewport';
import { label, fit } from './ui';
import { mix, shade } from './paint';

export interface MegaActions {
  play(): void;           // PLAY: a round; while balls fall, hurry them; during autoplay, stop
  auto(): void;           // autoplay on / off
  turbo(on: boolean): void;
  bump(): void;
  bet(up: boolean): void;
  risk(step: number): void;
  balls(step: number): void;
  rows(step: number): void;
}

export interface MegaLook { gold: string; ink: string; panel: string; text: string }

type Icon = 'play' | 'skip' | 'stop' | 'minus' | 'plus' | 'auto' | 'turbo' | 'bolt' | 'menu' | 'close';

/** A round button: a dark disc in a gold ring with a white icon; dims when it can't be used. */
class RoundButton extends Container {
  private readonly disc = new Graphics();
  private readonly icon = new Graphics();
  private readonly badge: Text;
  private lit = false;

  constructor(private readonly r: number, private readonly look: MegaLook, private kind: Icon, onTap: () => void) {
    super();
    this.badge = label('', Math.round(r * 0.42), 'center', { fill: '#ffffff', stroke: { color: '#000000', width: 3 } });
    this.badge.y = r * 0.95;
    this.addChild(this.disc, this.icon, this.badge);
    this.eventMode = 'static';
    this.cursor = 'pointer';
    this.hitArea = new Rectangle(-r * 1.15, -r * 1.15, r * 2.3, r * 2.3);
    this.on('pointertap', () => {
      if (!this.enabled) return;
      gsap.fromTo(this.scale, { x: 0.88, y: 0.88 }, { x: 1, y: 1, duration: 0.25, ease: 'back.out(3)' });
      onTap();
    });
    this.draw();
  }

  enabled = true;

  set(kind: Icon, badge = ''): void {
    if (kind !== this.kind) { this.kind = kind; this.draw(); }
    this.badge.text = badge;
  }

  setOn(on: boolean): void {
    if (on !== this.lit) { this.lit = on; this.draw(); }
  }

  setEnabled(enabled: boolean): void {
    this.enabled = enabled;
    this.alpha = enabled ? 1 : 0.4;
  }

  private draw(): void {
    const r = this.r, gold = this.look.gold, lit = this.lit;
    this.disc.clear()
      .circle(0, r * 0.08, r).fill({ color: '#000000', alpha: 0.5 })
      .circle(0, 0, r).fill(lit ? shade('#ffe9a0', gold) : shade(mix(this.look.panel, '#ffffff', 0.12), mix(this.look.panel, '#000000', 0.55))).stroke({ color: gold, width: Math.max(2, r * 0.1) })
      .circle(0, 0, r * 0.82).stroke({ color: '#ffffff', width: 1, alpha: 0.18 });
    const c = lit ? this.look.ink : '#ffffff';
    const g = this.icon.clear();
    const s = r * 0.42;
    switch (this.kind) {
      case 'play': g.poly([-s * 0.6, -s, s * 1.0, 0, -s * 0.6, s]).fill(c); break;
      case 'skip': g.poly([-s * 0.8, -s * 0.8, s * 0.5, 0, -s * 0.8, s * 0.8]).fill(c).rect(s * 0.55, -s * 0.8, s * 0.3, s * 1.6).fill(c); break;
      case 'stop': g.roundRect(-s * 0.7, -s * 0.7, s * 1.4, s * 1.4, s * 0.15).fill(c); break;
      case 'minus': g.rect(-s, -s * 0.18, s * 2, s * 0.36).fill(c); break;
      case 'plus': g.rect(-s, -s * 0.18, s * 2, s * 0.36).fill(c).rect(-s * 0.18, -s, s * 0.36, s * 2).fill(c); break;
      case 'auto':
        g.arc(0, 0, s, -Math.PI * 0.35, Math.PI * 1.35).stroke({ color: c, width: s * 0.28, cap: 'round' })
          .poly([s * 0.35, -s * 1.15, s * 1.05, -s * 0.65, s * 0.25, -s * 0.35]).fill(c)
          .poly([-s * 0.3, -s * 0.45, s * 0.5, 0, -s * 0.3, s * 0.45]).fill(c);
        break;
      case 'turbo':
        for (const dx of [-s * 0.55, s * 0.25]) g.poly([dx - s * 0.35, -s * 0.7, dx + s * 0.35, 0, dx - s * 0.35, s * 0.7]).stroke({ color: c, width: s * 0.28, join: 'round' });
        break;
      case 'bolt': g.poly([s * 0.2, -s * 1.1, -s * 0.6, s * 0.15, -s * 0.05, s * 0.15, -s * 0.25, s * 1.1, s * 0.6, -s * 0.2, s * 0.05, -s * 0.2]).fill(c); break;
      case 'menu': for (const dy of [-s * 0.6, 0, s * 0.6]) g.roundRect(-s * 0.85, dy - s * 0.13, s * 1.7, s * 0.26, s * 0.13).fill(c); break;
      case 'close': g.moveTo(-s * 0.7, -s * 0.7).lineTo(s * 0.7, s * 0.7).moveTo(s * 0.7, -s * 0.7).lineTo(-s * 0.7, s * 0.7).stroke({ color: c, width: s * 0.3, cap: 'round' }); break;
    }
  }
}

/** One line of the drawer: − value +, with a title above the value. */
class Stepper extends Container {
  readonly value: Text;
  private readonly title: Text;
  readonly minus: RoundButton;
  readonly plus: RoundButton;

  constructor(title: string, private readonly w: number, look: MegaLook, step: (by: number) => void) {
    super();
    const h = 58;
    this.addChild(new Graphics()
      .roundRect(-w / 2, -h / 2 + 3, w, h, 14).fill({ color: '#000000', alpha: 0.5 })
      .roundRect(-w / 2, -h / 2, w, h, 14).fill(shade(mix(look.panel, '#000000', 0.3), mix(look.panel, '#000000', 0.8))).stroke({ color: look.gold, width: 3 }));
    this.title = label(title, 15, 'center', { fill: look.gold });
    this.title.y = -10;
    this.value = label('', 24, 'center', { fill: '#ffffff' });
    this.value.y = 18;
    this.minus = new RoundButton(18, look, 'minus', () => step(-1));
    this.plus = new RoundButton(18, look, 'plus', () => step(1));
    this.minus.x = -w / 2 + 28;
    this.plus.x = w / 2 - 28;
    this.addChild(this.title, this.value, this.minus, this.plus);
  }

  set(text: string): void {
    this.value.text = text;
    fit(this.value, this.w - 120);
  }
}

/** The balls a round drops: − and + round the count, the price of one ball under it. */
class BallsPill extends Container {
  private readonly count: Text;
  private readonly each: Text;
  readonly minus: RoundButton;
  readonly plus: RoundButton;

  constructor(private readonly w: number, h: number, look: MegaLook, step: (by: number) => void) {
    super();
    this.addChild(new Graphics()
      .roundRect(-w / 2, -h / 2 + 3, w, h, h / 2).fill({ color: '#000000', alpha: 0.5 })
      .roundRect(-w / 2, -h / 2, w, h, h / 2).fill(shade(mix(look.panel, '#000000', 0.3), mix(look.panel, '#000000', 0.8))).stroke({ color: look.gold, width: 2.5 }));
    this.count = label('', Math.round(h * 0.42), 'center', { fill: '#ffffff' });
    this.count.anchor.set(0.5, 0.5);
    this.count.y = -h * 0.12;
    this.each = label('', Math.round(h * 0.26), 'center', { fill: look.gold });
    this.each.anchor.set(0.5, 0.5);
    this.each.y = h * 0.27;
    this.minus = new RoundButton(h * 0.4, look, 'minus', () => step(-1));
    this.plus = new RoundButton(h * 0.4, look, 'plus', () => step(1));
    this.minus.x = -w / 2 + h * 0.5;
    this.plus.x = w / 2 - h * 0.5;
    this.addChild(this.count, this.each, this.minus, this.plus);
  }

  set(balls: number, perBall: string): void {
    this.count.text = balls === 1 ? '1 BALL' : `${balls} BALLS`;
    this.each.text = `${perBall} EACH`;
    fit(this.count, this.w - 90);
    fit(this.each, this.w - 90);
  }
}

/** The risk switch: LOW, MEDIUM and HIGH side by side, the chosen one lit; a tap picks one. */
class RiskSwitch extends Container {
  private static readonly NAMES = ['LOW', 'MEDIUM', 'HIGH'];
  private readonly back = new Graphics();
  private readonly lit = new Graphics();
  private readonly words: Text[];
  private chosen = 0;
  enabled = true;

  constructor(private readonly w: number, private readonly h: number, private readonly look: MegaLook, pick: (index: number) => void) {
    super();
    const cell = w / 3;
    this.back
      .roundRect(-w / 2, -h / 2 + 3, w, h, h / 2).fill({ color: '#000000', alpha: 0.5 })
      .roundRect(-w / 2, -h / 2, w, h, h / 2).fill(shade(mix(look.panel, '#000000', 0.3), mix(look.panel, '#000000', 0.8))).stroke({ color: look.gold, width: 2.5 });
    this.words = RiskSwitch.NAMES.map((name, i) => {
      const word = label(name, Math.round(h * 0.42), 'center', { fill: look.text });
      word.anchor.set(0.5, 0.5);
      word.x = -w / 2 + cell * (i + 0.5);
      return word;
    });
    this.addChild(this.back, this.lit, ...this.words);
    this.eventMode = 'static';
    this.cursor = 'pointer';
    this.hitArea = new Rectangle(-w / 2, -h / 2 - 6, w, h + 12);
    this.on('pointertap', event => {
      if (!this.enabled) return;
      const index = Math.min(2, Math.max(0, Math.floor((this.toLocal(event.global).x + w / 2) / cell)));
      if (index !== this.chosen) pick(index);
    });
    this.draw();
  }

  /** name: the game's name for the risk (LOW, MID or HIGH). */
  set(name: string): void {
    const index = name === 'HIGH' ? 2 : name === 'LOW' ? 0 : 1;
    if (index === this.chosen) return;
    this.chosen = index;
    this.draw();
    gsap.fromTo(this.lit.scale, { x: 0.8, y: 0.8 }, { x: 1, y: 1, duration: 0.3, ease: 'back.out(3)' });
  }

  setEnabled(enabled: boolean): void {
    this.enabled = enabled;
    this.alpha = enabled ? 1 : 0.55;
  }

  private draw(): void {
    const cell = this.w / 3, pad = 4;
    const x = -this.w / 2 + cell * (this.chosen + 0.5);
    this.lit.clear()
      .roundRect(-cell / 2 + pad, -this.h / 2 + pad, cell - 2 * pad, this.h - 2 * pad, (this.h - 2 * pad) / 2)
      .fill(shade(mix(this.look.gold, '#ffffff', 0.35), mix(this.look.gold, '#000000', 0.3)));
    this.lit.x = x;
    this.words.forEach((word, i) => { word.style.fill = i === this.chosen ? this.look.ink : this.look.text; });
  }
}

export class MegaControls extends Container {
  private readonly bar = new Graphics();
  private readonly playButton: RoundButton;
  private readonly minus: RoundButton;
  private readonly plus: RoundButton;
  private readonly autoButton: RoundButton;
  private readonly turboButton: RoundButton;
  private readonly bumpButton: RoundButton;
  private readonly menuButton: RoundButton;
  private readonly drawer = new Container();
  private readonly risk: RiskSwitch;
  private readonly balls: BallsPill;
  private readonly rows: Stepper;
  private readonly balance: Text;
  private readonly totalBet: Text;
  private readonly win: Text;
  private turbo = false;
  private open = false;

  constructor(private readonly look: MegaLook, actions: MegaActions) {
    super();
    this.playButton = new RoundButton(52, look, 'play', () => actions.play());
    this.minus = new RoundButton(19, look, 'minus', () => actions.bet(false));
    this.plus = new RoundButton(19, look, 'plus', () => actions.bet(true));
    this.autoButton = new RoundButton(21, look, 'auto', () => actions.auto());
    this.turboButton = new RoundButton(21, look, 'turbo', () => { this.turbo = !this.turbo; this.turboButton.setOn(this.turbo); actions.turbo(this.turbo); });
    this.bumpButton = new RoundButton(27, look, 'bolt', () => actions.bump());
    this.menuButton = new RoundButton(21, look, 'menu', () => this.toggleDrawer());
    this.risk = new RiskSwitch(300, 44, look, index => actions.risk(index - this.riskIndex));
    this.balls = new BallsPill(204, 44, look, by => actions.balls(by));
    this.rows = new Stepper('ROWS', 300, look, by => actions.rows(by));
    this.rows.y = -72;
    this.drawer.addChild(this.rows);
    this.drawer.visible = false;
    const info = { fill: look.text };
    this.balance = label('', 17, 'left', info);
    this.totalBet = label('', 17, 'center', info);
    this.win = label('', 17, 'right', { fill: look.gold });
    this.addChild(this.bar, this.balls, this.risk, this.drawer, this.minus, this.plus, this.autoButton, this.turboButton, this.bumpButton, this.menuButton,
      this.playButton, this.balance, this.totalBet, this.win);
    this.bumpButton.setEnabled(false);
  }

  private toggleDrawer(): void {
    this.open = !this.open;
    this.drawer.visible = this.open;
    this.menuButton.set(this.open ? 'close' : 'menu');
    if (this.open) gsap.fromTo(this.drawer, { alpha: 0, y: 20 }, { alpha: 1, y: 0, duration: 0.25, ease: 'power2.out' });
  }

  closeDrawer(): void {
    if (this.open) this.toggleDrawer();
  }

  /** The bar sits along the bottom, centred on x, never wider than room (the free width there). */
  layout(view: Viewport, x: number, room = view.stageW - 2 * view.offsetX - 16): void {
    const width = 520;
    const bottom = view.stageH - view.offsetY - 8;
    const scale = Math.min(view.landscape ? 0.82 : 1.05, room / width);
    this.scale.set(scale);
    this.position.set(x, bottom - 120 * scale);
    this.bar.clear()
      .roundRect(-width / 2, -18, width, 136, 24).fill({ color: '#000000', alpha: 0.45 })
      .roundRect(-width / 2, -22, width, 136, 24).fill({ fill: shade(mix(this.look.panel, '#ffffff', 0.05), mix(this.look.panel, '#000000', 0.7)), alpha: 0.92 }).stroke({ color: this.look.gold, width: 2, alpha: 0.7 });
    this.playButton.position.set(0, 26);
    this.minus.position.set(-82, 0); this.plus.position.set(82, 0);
    this.autoButton.position.set(-86, 56); this.turboButton.position.set(86, 56);
    this.bumpButton.position.set(176, 24); this.menuButton.position.set(-176, 24);
    this.balance.position.set(-width / 2 + 18, 104);
    this.totalBet.position.set(0, 104);
    this.win.position.set(width / 2 - 18, 104);
    this.drawer.position.set(0, -78);
    this.balls.position.set(-width / 2 + 102, -52);
    this.risk.position.set(width / 2 - 150, -52);
  }

  setBalance(text: string): void { this.balance.text = '◆ ' + text; }
  setTotalBet(text: string): void { this.totalBet.text = 'BET ' + text; }
  setWin(text: string): void { this.win.text = 'WIN ' + text; }
  private riskIndex = 0;
  setRisk(name: string): void {
    this.riskIndex = name === 'HIGH' ? 2 : name === 'LOW' ? 0 : 1;
    this.risk.set(name);
  }
  setBalls(balls: number, perBall: string): void { this.balls.set(balls, perBall); }
  setRows(rows: number): void { this.rows.set(String(rows)); }

  /**
   * busy: balls are falling (the settings lock); auto: autoplay is on; left: balls still to land. PLAY shows PLAY,
   * the balls left (a tap hurries them), or STOP during autoplay.
   */
  private pulse: gsap.core.Tween | null = null;

  setPlaying(busy: boolean, auto: boolean, left: number): void {
    // PLAY breathes while it waits for a tap
    if (!busy && !this.pulse) {
      this.pulse = gsap.to(this.playButton.scale, { x: 1.08, y: 1.08, duration: 0.6, yoyo: true, repeat: -1, ease: 'sine.inOut' });
    } else if (busy && this.pulse) {
      this.pulse.kill();
      this.pulse = null;
      this.playButton.scale.set(1);
    }
    this.playButton.set(auto ? 'stop' : busy && left > 0 ? 'skip' : 'play', busy && left > 0 && !auto ? String(left) : '');
    this.autoButton.setOn(auto);
    this.bumpButton.setEnabled(left > 0);
    this.risk.setEnabled(!busy);
    for (const b of [this.minus, this.plus, this.balls.minus, this.balls.plus, this.rows.minus, this.rows.plus]) {
      b.setEnabled(!busy);
    }
    if (busy) this.closeDrawer();
  }
}
