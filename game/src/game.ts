import { Container, type Texture } from 'pixi.js';
import { gsap } from 'gsap';
import { errorText, formatAmount, playRound, resumeSession, type ApiError, type HistoryEntry, type MeterInfo, type Round, type Session } from './api';
import { HistoryPanel } from './historyPanel';
import { ResponsiblePlay } from './responsible';
import { Board, slotTier, type Landing, type PegHit } from './board';
import { BigWin, winLevel } from './effects';
import { Wheel } from './wheel';
import { Meter } from './meter';
import { History } from './history';
import { ControlCenter } from './control';
import { Guide, guideSeen, type GuideStep } from './guide';
import { Panel } from './panel';
import { betLadder, nextBet, RISKS, round2 } from './rules';
import { ExitDialog, Menu, Options, ResultScreen, type MotionSwitch } from './screens';
import { isMuted, play as sound, setMuted } from './sound';
import { neonLayers, type Theme } from './theme';
import type { Viewport } from './viewport';

const random = (min: number, max: number) => min + Math.random() * (max - min);

/** A peg's tick: a little higher on every row down (the board plays a scale as a ball falls), never twice the same,
 * heard from where the peg is. */
const pegSound = (peg: PegHit) => sound('hit', {
  volume: 0.6, rate: 0.9 + 0.32 * peg.row / Math.max(1, peg.rows - 1) + random(-0.025, 0.025), pan: peg.pan * 0.7 });

/** A landing, by what it paid: nothing, a little, 2x and up, 10x and up (a golden ball has its own). */
function landSound(multiplier: number, boost: number, landing: Landing): void {
  const name = boost > 1 ? 'golden' : multiplier >= 10 ? 'huge' : multiplier >= 2 ? 'big' : multiplier > 1 ? 'score' : 'noScore';
  sound(name, { pan: landing.pan * 0.6 });
}
/** A win the way the server pays it: whole cents, rounded down. */
function cents(value: number): number {
  return Math.floor(value * 100 + 1e-9) / 100;
}

/** A pause that stops while the exit dialog is open (it runs on GSAP's clock). */
const wait = (seconds: number) => new Promise<void>(resolve => { gsap.delayedCall(seconds, resolve); });

type Page = 'menu' | 'game' | 'result';
/** The player's controls: the control center (one ball per drop, Hold & Drop) or the classic panel (balls slider). */
type Controls = 'center' | 'classic';
const CONTROLS_KEY = 'plinko.controls';

/** The controls this player picked last time on this device; the classic panel until they pick (or if storage is blocked). */
function savedControls(): Controls {
  try { return window.localStorage.getItem(CONTROLS_KEY) === 'center' ? 'center' : 'classic'; } catch { return 'classic'; }
}

/**
 * The game. Outcomes come only from the server: the client sends rows, risk, bet and balls (the control center
 * always sends 1: a tap drops one ball, holding drops a stream; the classic panel sends its BALLS setting), and shows
 * the slots and balance it gets back. Nothing here can change what a round pays.
 */
export class Game extends Container {
  private readonly menu: Menu;
  private readonly table = new Container();
  private readonly board: Board;
  private readonly panel: ControlCenter;
  private readonly classic: Panel;
  private controls: Controls = savedControls();
  private readonly history = new History();
  private readonly exitDialog: ExitDialog;
  private readonly result: ResultScreen;
  private readonly options: Options;
  private readonly bigWin: BigWin;
  private readonly wheel: Wheel;
  private readonly meter: Meter;
  private readonly guide: Guide;
  private readonly accent: string;

  private readonly rowOptions: number[];
  private readonly ladder: number[];
  private page: Page = 'menu';
  private view!: Viewport;
  private balance: number;
  private bet: number;
  private risk = 0;
  private rows: number;
  private balls = 1;              // 1 with the control center; the classic panel's BALLS setting otherwise
  private requesting = false;     // a round request is on its way (only one at a time)
  private inAir = 0;              // rounds whose balls are still falling
  private replaying = false;      // an earlier round is being shown again (review 7.5): no bet, nothing paid
  private readonly historyPanel: HistoryPanel;
  private readonly responsible: ResponsiblePlay;
  private lastLaunch = 0;          // when the last round was asked for (the market's minimum time per round)
  private holding = false;        // Hold & Drop is running
  private pressed = false;
  private queuedTaps = 0;         // taps that came while a request was on its way: played right after it
  private holdTimer: gsap.core.Tween | null = null;
  private spins = 0;
  private pendingWins = 0;        // wins of balls still in the air: on screen only once they land
  private lastWin = 0;
  private statusTimer: gsap.core.Tween | null = null;
  private gameOverTimer: gsap.core.Tween | null = null;

  constructor(private readonly session: Session, theme: Theme, tex: (file: string) => Texture,
              private readonly fatal: (message: string) => void, motion: MotionSwitch | null = null,
              private readonly roundPlayed: () => void = () => undefined) {
    super();
    const config = session.config;
    this.rowOptions = config.rows.map(r => r.rows).sort((a, b) => a - b);
    this.rows = this.rowOptions[0] ?? 8;
    const defaultBet = config.defaultBet || config.minBet;
    this.ladder = betLadder(config.minBet, config.maxBet, defaultBet);
    this.bet = defaultBet;
    this.balance = session.balance;

    this.menu = new Menu(tex('logo.png'), tex('logo_p.png'), tex('button_start.png'), () => this.start(),
      neonLayers(theme).map(([wide, tall]) => [tex(wide), tex(tall)]), motion);
    this.board = new Board(tex('item_plinko.png'), tex('item_plinko_p.png'), tex('item_ball.png'), theme.board,
      theme.board.peg ? tex(theme.board.peg) : null);
    const inkLook = theme.ui.style === 'ink' ? { ink: theme.ui.ink, panel: theme.ui.panel } : null;
    const colours = { accent: theme.accent, text: theme.text, background: theme.background,
      second: theme.board.slots?.[4]?.face ?? '#3d6fd6' };
    this.panel = new ControlCenter(colours, {
      dropDown: () => this.dropDown(),
      dropUp: () => this.dropUp(),
      bet: up => this.changeBet(up),
      betPick: value => this.pickBet(value),
      risk: index => this.changeRisk(index - this.risk),
      rows: step => this.changeRows(this.rowOptions.indexOf(this.rows) + step),
      info: () => this.openGuide(),
    }, this.ladder, config.holdToDrop !== false, { glass: theme.ui.style === 'glass', drop: theme.ui.drop ? tex(theme.ui.drop) : null, ink: inkLook });
    this.classic = new Panel({
      stat: tex('item_stat_display.png'), half: tex('button_bet_half.png'), double: tex('button_bet_multiply.png'),
      left: tex('button_arrow_left.png'), right: tex('button_arrow_right.png'), bar: tex('item_drag_bar.png'),
      knob: tex('item_drag.png'), play: tex('button_blank.png'),
    }, theme.layout, {
      bet: up => this.changeBet(up),
      risk: step => this.changeRisk(step),
      rows: index => this.changeRows(index),
      balls: index => this.changeBalls(index + 1),
      play: () => this.play(),
    }, this.rowOptions.length, config.maxBalls, theme.playText);
    this.accent = theme.accent;
    this.meter = new Meter(theme.accent, theme.ui.style === 'glass' ? theme.background : null, inkLook);
    this.meter.set(session.meter?.enabled ?? false, session.meter?.charges ?? 0, session.meter?.of ?? 0);
    this.table.addChild(this.board, this.history, this.panel, this.classic, this.meter);

    this.exitDialog = new ExitDialog({ exit: tex('item_exit.png'), exitPortrait: tex('item_exit_p.png'),
      ok: tex('button_confirm.png'), no: tex('button_cancel.png') }, theme.landscapeExitFromPortrait,
    () => { this.closeExit(); this.go('menu'); }, () => this.closeExit());
    this.bigWin = new BigWin([theme.accent, ...(theme.board.slots?.map(s => s.face) ?? ['#FFBF00', '#EF521E', '#ED0000']), theme.text], theme.board.fx);
    this.board.onClack = () => sound('clack', { volume: 0.5 });
    const mega = config.mega;
    if (mega) this.board.setMega(mega.gates ?? {}, mega.gateBoost ?? null, mega.wheel ? mega.spinSlots ?? {} : {});
    this.board.onGate = () => sound('golden', { volume: 0.7 });
    this.board.bumpButton.on('pointertap', () => { sound('clack'); this.board.bump(); });
    this.wheel = new Wheel(theme.wheel, theme.wheel.hub ? tex(theme.wheel.hub) : null, theme.wheel.pointer ? tex(theme.wheel.pointer) : null);
    this.wheel.onTick = () => sound('hit', { volume: 0.35 });

    this.result = new ResultScreen(tex('item_result.png'), tex('item_result_p.png'), tex('button_continue.png'), () => this.go('menu'));
    this.options = new Options({ settings: tex('button_settings.png'), soundOn: tex('button_sound_on.png'),
      soundOff: tex('button_sound_off.png'), fullscreen: tex('button_fullscreen.png'), exit: tex('button_exit.png') },
    isMuted, () => setMuted(!isMuted()), fullscreenToggle(), () => this.openExit(),
    { texture: theme.ui.controls ? tex(theme.ui.controls) : null, colours: { accent: theme.accent, text: theme.text, background: theme.background,
        ...(theme.ui.style === 'ink' ? { ring: theme.ui.ink, disc: theme.ui.panel } : {}) },
      onTap: () => this.switchControls() }, motion,
    () => { if (!this.busy()) this.historyPanel.show(); });
    this.options.on('pointertap', () => sound('click'));
    this.guide = new Guide({ accent: theme.accent, text: theme.text, background: theme.background });
    this.showControls();

    this.addChild(this.menu, this.table, this.wheel, this.bigWin, this.result, this.exitDialog, this.options, this.guide);
    this.historyPanel = new HistoryPanel(value => this.money(value), entry => void this.replay(entry));
    this.responsible = new ResponsiblePlay(config, session.sessionId, value => this.money(value), () => { this.stopHolding(); this.go('menu'); });
    this.go('menu');
    window.addEventListener('keydown', event => {
      if ((event.code === 'Space' || event.code === 'Enter') && this.page === 'game' && !this.exitDialog.visible && !this.guide.showing
        && !this.historyPanel.open && !this.responsible.pausing && !(event.target instanceof HTMLButtonElement)) {
        event.preventDefault();
        this.play();
      }
      if (event.code === 'KeyB' && this.page === 'game') this.board.bump();
    });
  }

  layout(view: Viewport): void {
    this.view = view;
    this.menu.layout(view);
    this.board.layout(view);
    this.panel.layout(view);
    this.classic.layout(view);
    this.history.layout(view);
    this.exitDialog.layout(view);
    this.result.layout(view);
    this.bigWin.layout(view);
    this.wheel.layout(view);
    this.meter.layout(view);
    this.options.layout(view);
    this.guide.layout(view);
  }

  private go(page: Page): void {
    this.gameOverTimer?.kill();
    this.gameOverTimer = null;
    this.page = page;
    this.menu.visible = page === 'menu';
    this.table.visible = page === 'game';
    this.result.visible = page === 'result';
    this.options.setExit(page === 'game');
    this.responsible?.setVisible(page === 'game');
    if (page !== 'game') { this.guide.close(); this.historyPanel.hide(); }
  }

  /** From the start screen: a fresh table with the latest balance from the casino (no jingle: the theme's music plays). */
  private async start(): Promise<void> {
    this.go('game');
    this.history.clear();
    this.board.clearAmounts();
    this.risk = 0;
    this.rows = this.rowOptions[0] ?? 8;
    this.bet = this.ladder.includes(this.session.config.defaultBet) ? this.session.config.defaultBet : this.ladder[0];
    this.lastWin = 0;
    this.pendingWins = 0;
    this.applyRows();
    this.refreshPanel();
    this.panel.setPlaying(false);
    this.classic.setPlaying(false);
    this.idleStatus();
    this.firstGuide();
    try {
      const fresh = await resumeSession();
      if (!this.busy()) { this.balance = fresh.balance; this.refreshPanel(); }
      if (fresh.meter && !this.busy()) this.applyMeter(fresh.meter);
    } catch { /* keep the balance we have */ }
  }

  private changeBet(up: boolean): void {
    if (this.busy()) return;
    const next = nextBet(this.ladder, this.bet, up);
    if (next !== this.bet) sound('click');
    this.bet = next;
    this.refreshPanel();
    this.idleStatus();
  }

  private changeRisk(step: number): void {
    if (this.busy()) return;
    const risk = Math.min(RISKS.length - 1, Math.max(0, this.risk + step));
    if (risk === this.risk) return;
    sound('click');
    this.risk = risk;
    this.board.setRisk(risk);
    this.markMeterSlots();
    this.refreshPanel();
  }

  /** Which prize positions charge the bonus meter on this board and risk. */
  private meterPositions(): number[] {
    const meter = this.session.meter;
    return meter?.enabled ? meter.positions[`${this.rows}:${this.risk}`] ?? [] : [];
  }

  private markMeterSlots(): void {
    this.board.setMeterPositions(this.meterPositions());
  }

  /** The server's meter: its count, and which slots charge it (an admin may have changed it since the game opened). */
  private applyMeter(meter: MeterInfo): void {
    this.session.meter = meter;
    this.meter.set(meter.enabled, meter.charges, meter.of);
    this.markMeterSlots();
  }

  private changeRows(index: number): void {
    if (this.busy()) return;
    const rows = this.rowOptions[index];
    if (rows === undefined || rows === this.rows) return;
    sound('change');
    this.rows = rows;
    this.applyRows();
    this.refreshPanel();
  }

  private changeBalls(balls: number): void {
    if (this.busy() || balls === this.balls) return;
    sound('ball');
    this.balls = balls;
    this.refreshPanel();
    this.idleStatus();
  }

  /** The player flips between the control center and the classic panel (not while balls are dropping). */
  private switchControls(): void {
    if (this.busy()) return;
    this.controls = this.controls === 'center' ? 'classic' : 'center';
    try { window.localStorage.setItem(CONTROLS_KEY, this.controls); } catch { /* remembered for this visit only */ }
    this.showControls();
    this.refreshPanel();
    if (this.page === 'game') this.idleStatus();
    if (this.controls === 'center') this.firstGuide();
    else this.guide.close();
  }

  /** The first time this device uses the control center, explain it. */
  private firstGuide(): void {
    if (this.controls === 'center' && this.page === 'game' && !guideSeen()) this.openGuide();
  }

  /** Walks through the control center: drop, bet, risk, rows (each bubble points at the real part). */
  private openGuide(): void {
    if (this.busy() || this.controls !== 'center') return;
    const at = (part: 'drop' | 'bet' | 'risk' | 'rows') => () => {
      const spot = this.panel.spots()[part];
      const a = this.toLocal(this.panel.toGlobal({ x: spot.x, y: spot.y }));
      const b = this.toLocal(this.panel.toGlobal({ x: spot.x + spot.w, y: spot.y + spot.h }));
      return { x: a.x, y: a.y, w: b.x - a.x, h: b.y - a.y, round: spot.round };
    };
    const steps: GuideStep[] = [
      { spot: at('drop'), text: this.session.config.holdToDrop !== false
        ? 'Tap DROP to drop one ball. Hold it to keep dropping: let go or tap STOP to stop.'
        : 'Tap DROP to drop one ball. Every tap plays one round.' },
      { spot: at('bet'), text: 'Your bet for each ball. Tap the amount for a quick list, or use − and +.' },
      { spot: at('risk'), text: 'Higher risk: bigger top prizes, but more balls land on small ones.' },
      { spot: at('rows'), text: 'More rows: more bounces and a wider spread of prizes.' },
    ];
    this.guide.show(steps);
  }

  private showControls(): void {
    const classic = this.controls === 'classic';
    this.panel.visible = !classic;
    this.classic.visible = classic;
    if (!classic) this.balls = 1;   // the control center drops one ball at a time
  }

  private applyRows(): void {
    this.board.setRows(this.rows, this.session.config.rows.find(r => r.rows === this.rows)?.prizes ?? []);
    this.board.setRisk(this.risk);
    this.markMeterSlots();
    if (this.view) this.board.layout(this.view);
  }

  private money(value: number): string {
    return formatAmount(this.session.currencySymbol, value);
  }

  private refreshPanel(): void {
    this.panel.setBalance(this.money(Math.max(0, round2(this.balance - this.pendingWins))));
    this.panel.setBet(this.money(this.bet));
    this.panel.setRisk(this.risk);
    this.panel.setRows(this.rows);
    this.panel.setWin(this.money(this.lastWin));
    this.classic.setCredit(this.money(Math.max(0, round2(this.balance - this.pendingWins))));
    this.classic.setBet(this.money(this.bet));
    this.classic.setRisk(RISKS[this.risk]);
    this.classic.setRows(this.rows);
    this.classic.setBalls(this.balls);
    this.classic.rowsSlider.set(Math.max(0, this.rowOptions.indexOf(this.rows)), false);
    this.classic.ballsSlider.set(this.balls - 1, false);
  }

  /** A bet chosen in the quick picker (only values from the ladder). */
  private pickBet(value: number): void {
    if (this.busy() || !this.ladder.includes(value) || value === this.bet) return;
    sound('click');
    this.bet = value;
    this.refreshPanel();
    this.idleStatus();
  }

  private idleStatus(): void {
    this.statusTimer?.kill();
    if (!this.busy()) this.board.setStatus('PLAY ' + this.money(round2(this.bet * this.balls)));
  }

  /** A message for a few seconds, then back to the price of a play. */
  private flashStatus(text: string, seconds: number): void {
    this.statusTimer?.kill();
    this.board.setStatus(text);
    this.statusTimer = gsap.delayedCall(seconds, () => this.idleStatus());
  }

  /** A tap: one round now. Earlier balls may still be falling; only one request is ever on its way. */
  private play(): void {
    if (this.page !== 'game' || this.holding) return;
    void this.launchRound();
  }

  /**
   * The drop button is pressed: one round at once, and if it is still held a moment later (and Hold & Drop is on),
   * round after round until it is let go, the balance can't pay the next one, a round wins 10x or more, or an error.
   */
  private dropDown(): void {
    if (this.page !== 'game') return;
    if (this.holding) { this.stopHolding(); return; }   // the button shows STOP while holding: a tap stops
    this.pressed = true;
    void this.launchRound();
    if (this.session.config.holdToDrop === false) return;
    this.holdTimer?.kill();
    this.holdTimer = gsap.delayedCall(0.35, () => { if (this.pressed) void this.holdLoop(); });
  }

  private dropUp(): void {
    this.pressed = false;
    this.holdTimer?.kill();
    if (this.holding) this.stopHolding();
  }

  private async holdLoop(): Promise<void> {
    if (this.holding) return;
    this.holding = true;
    this.queuedTaps = 0;
    this.spins = 0;
    this.syncControls();
    while (this.holding && this.page === 'game') {
      // the next drop when the last request is answered, and not faster than the balls can be told apart
      while (this.requesting) await wait(0.05);
      if (!this.holding) break;
      const round = await this.launchRound();
      if (!round) { this.stopHolding(); break; }
      this.spins++;
      this.board.setStatus(`SPINS ${this.spins}`);
      if (winLevel(round.totalWin, round.totalBet)) { this.stopHolding(); break; }
      await wait(Math.max(0.45, 0.06 * round.slots.length));
    }
  }

  private stopHolding(): void {
    if (!this.holding) return;
    this.holding = false;
    this.syncControls();
    if (this.inAir === 0 && !this.requesting) this.settle();
  }

  /** Asks the server for one round and starts its animation; null if it could not be played. */
  private async launchRound(): Promise<Round | null> {
    if (this.requesting) {
      // quick taps are kept (a few) and played one after the other; holding has its own pace
      if (!this.holding && this.queuedTaps < 3) this.queuedTaps++;
      return null;
    }
    const cost = round2(this.bet * this.balls);
    if (this.balance < cost) {
      this.flashStatus('NOT ENOUGH CREDIT', 2);
      return null;
    }
    sound('click');
    this.requesting = true;
    this.statusTimer?.kill();
    this.syncControls();
    // the market's reality check, when one is due: no round until the player chooses to go on (review 7.4)
    if (!(await this.responsible.allowed())) {
      this.requesting = false;
      this.queuedTaps = 0;
      this.syncControls();
      return null;
    }
    // the market's minimum time per round (the server refuses a sooner one)
    const gap = (this.session.config.minRoundSeconds ?? 0) * 1000 - (Date.now() - this.lastLaunch);
    if (gap > 0) await wait(gap / 1000 + 0.05);
    this.lastLaunch = Date.now();
    if (!this.holding) this.board.setStatus('PLAYING...');
    let round: Round;
    try {
      round = await playRound(this.rows, this.risk, this.bet, this.balls);
    } catch (error) {
      this.requesting = false;
      this.queuedTaps = 0;
      this.roundFailed(error as ApiError);
      this.syncControls();
      return null;
    }
    this.requesting = false;
    this.responsible.addRound(round.totalBet, round.totalWin);
    // the server has taken the bet and paid the win: what is on screen is its balance, less the wins of balls
    // still falling (they are added as they land)
    this.balance = round.balance;
    this.pendingWins = round2(this.pendingWins + round.totalWin);
    if (this.inAir === 0) this.board.clearAmounts();
    this.inAir++;
    this.syncControls();
    this.refreshPanel();
    void this.animateRound(round, this.rows, this.bet, this.meterPositions()).then(() => {
      this.inAir--;
      this.syncControls();
      if (this.inAir === 0 && !this.requesting && !this.holding) this.settle();
    });
    if (this.queuedTaps > 0 && !this.holding) {
      this.queuedTaps--;
      void this.launchRound();
    }
    return round;
  }

  private roundFailed(failure: ApiError): void {
    if (typeof failure.balance === 'number') { this.balance = failure.balance; this.refreshPanel(); }
    this.holding = false;
    if (['SESSION_EXPIRED', 'PLAYER_BLOCKED', 'GAME_DISABLED', 'CURRENCY_NOT_SUPPORTED'].includes(failure.code)) {
      this.fatal(errorText(failure)); // nothing the player can fix from here
      return;
    }
    if (failure.code === 'REALITY_CHECK_DUE') {
      // the market requires proof of the reality check and it is due by the server's clock: shown now (review 7.4)
      this.responsible.checkNow();
      return;
    }
    this.flashStatus(failure.code === 'INSUFFICIENT_FUNDS' ? 'NOT ENOUGH CREDIT'
      : failure.code === 'BET_TOO_HIGH' ? 'BET TOO HIGH FOR THIS BOARD'
      : failure.code === 'ROUND_IN_PROGRESS' || failure.code === 'ROUND_STILL_RUNNING' ? 'ROUND IN PROGRESS, WAIT'
      : failure.code === 'ROUND_UNDER_REVIEW' ? 'ROUND UNDER REVIEW, YOUR WIN IS SAFE'
      : failure.code === 'LIMIT_REACHED' ? 'CASINO LIMIT REACHED'
      : failure.status === 429 ? 'TOO FAST, SLOW DOWN'
      : 'CONNECTION ERROR, TRY AGAIN', 2.5);
    // the round may still have been played: show the casino's balance, never a guess
    if (failure.code === 'NETWORK' || failure.code === 'ROUND_IN_PROGRESS' || failure.code === 'ROUND_STILL_RUNNING' || failure.status >= 500) {
      resumeSession().then(fresh => {
        if (!this.busy()) { this.balance = fresh.balance; this.pendingWins = 0; this.refreshPanel(); }
      }).catch(() => { /* keep the balance we have */ });
    }
  }

  /** One round's balls (and free balls), each adding its own win as it lands. */
  private async animateRound(round: Round, rows: number, bet: number, charging: number[]): Promise<void> {
    const land = (win: number) => {
      this.pendingWins = Math.max(0, round2(this.pendingWins - win));
      this.lastWin = win;
      this.refreshPanel();
    };
    if (round.hotSlot != null && round.hotBoost != null) {
      this.board.setHot(round.hotSlot, round.hotBoost);
      if (!this.holding) this.board.setStatus(`HOT SLOT ×${round.hotBoost}!`);
      sound('change');
      await wait(1);
    }
    const count = round.slots.length;
    const drops: Promise<void>[] = [];
    for (let i = 0; i < count; i++) {
      if (i > 0) await wait((this.holding ? 0.18 : 0.4) * random(0.6, 1.3));
      if (!this.holding) {
        const left = count - i - 1;
        this.board.setStatus(left > 0 ? `PLAYING... (${left} BALLS)` : 'PLAYING...');
      }
      const multiplier = round.multipliers[i] ?? 0;
      const boost = round.boosts?.[i] ?? 1;
      drops.push(this.board.dropBall(round.slots[i], {
        onPeg: pegSound,
        onLand: landing => {
          const win = round.wins?.[i] ?? cents(bet * multiplier);
          land(win);
          this.board.showAmount(landing.slot, this.money(win), multiplier > 1);
          this.history.add(landing.prizeIndex, slotTier(rows, landing.prizeIndex), multiplier);
          if (charging.includes(landing.prizeIndex)) this.meter.charge();
          landSound(multiplier, boost, landing);
        },
      }, boost, null, round.paths?.[i] ?? null));
    }
    await Promise.all(drops);

    // Plinko Mega: every ball in a SPIN slot spins the wheel, then a full meter's MEGA SPINs turn the gold wheel
    const config = this.session.config.mega;
    for (const spin of round.spins ?? []) {
      if (!config?.wheel?.[spin.segment]) { land(spin.win); continue; }
      sound('big');
      await this.wheel.show(config.wheel, spin.segment, { gold: false, title: 'SPIN!', amount: this.money(spin.win), hurry: this.holding });
      land(spin.win);
      if (spin.win > 0) sound(config.wheel[spin.segment].name ? 'bigWin' : 'score');
    }
    for (const mega of round.megaSpins ?? []) {
      if (!config?.megaWheel?.[mega.segment]) { land(mega.win); continue; }
      this.meter.set(true, 0, this.session.meter?.of ?? 0);
      sound('huge');
      await this.wheel.show(config.megaWheel, mega.segment, { gold: true, title: 'MEGA SPIN · ' + this.money(mega.stake),
        amount: this.money(mega.win), hurry: this.holding });
      land(mega.win);
      sound(config.megaWheel[mega.segment].name ? 'bigWin' : 'win');
    }

    // a full bonus meter pays free balls (decided by the server with the round), dropped after the paid ones
    const free = round.freeSlots ?? [];
    if (free.length > 0) {
      // what each free ball is worth: the meter's pot, so a share of the bets that filled it (not the current bet)
      const stakes = round.freeBets ?? [];
      const same = stakes.length === free.length && stakes.every(s => Math.abs(s - stakes[0]) < 0.005);
      this.board.setStatus(same ? `${free.length} FREE × ${this.money(stakes[0])}` : `FREE BALLS ×${free.length}!`);
      sound('win');
      await wait(1.1);
      const freeDrops: Promise<void>[] = [];
      for (let i = 0; i < free.length; i++) {
        if (i > 0) await wait(0.25 * random(0.8, 1.2));
        const multiplier = round.freeMultipliers?.[i] ?? 0;
        const stake = round.freeBets?.[i] ?? 0;
        if (!this.holding) this.board.setStatus(`FREE ${i + 1}/${free.length} · ${this.money(stake)}`);
        freeDrops.push(this.board.dropBall(free[i], {
          onPeg: pegSound,
          onLand: landing => {
            const win = round.freeWins?.[i] ?? cents(stake * multiplier);
            land(win);
            this.board.showAmount(landing.slot, this.money(win), win > stake);
            this.history.add(landing.prizeIndex, slotTier(rows, landing.prizeIndex), multiplier);
            landSound(multiplier, round.freeBoosts?.[i] ?? 1, landing);
          },
        }, round.freeBoosts?.[i] ?? 1, this.accent));
      }
      await Promise.all(freeDrops);
    }
    // the server's meter count is the one that counts
    if (round.meter) this.applyMeter(round.meter);
    if (round.hotSlot != null) this.board.clearHot();
    this.lastWin = round.totalWin;
    this.refreshPanel();
    if (!this.holding && this.inAir <= 1) {
      // where the market says so, a round that pays back less than its bet is not shown as a win (review 7.4)
      const lessThanBet = this.session.config.quietSmallWins && round.totalWin < round.totalBet;
      if (round.totalWin > 0 && !lessThanBet) {
        this.flashStatus('YOU WON ' + this.money(round.totalWin), 3);
        sound('win');
      } else if (round.totalWin > 0) {
        this.flashStatus('PAID BACK ' + this.money(round.totalWin) + ' OF ' + this.money(round.totalBet), 3);
        sound('noScore');
      } else {
        this.flashStatus('BETTER LUCK NEXT TIME', 3);
        sound('lose');
      }
    }
    // a round that paid 10x its cost or more gets a celebration (the amount is the server's; tap to skip)
    const level = winLevel(round.totalWin, round.totalBet);
    if (level) sound('bigWin');
    if (level) await this.bigWin.show(level, round.totalWin, value => this.money(value));
  }

  /** Everything has landed: the screen shows exactly the server's balance; out of credit ends the game. */
  private settle(): void {
    this.pendingWins = 0;
    this.roundPlayed();
    this.refreshPanel();
    this.syncControls();
    if (this.balance < this.session.config.minBet) {
      this.statusTimer?.kill();
      this.board.setStatus('GAME OVER');
      this.gameOverTimer = gsap.delayedCall(3, () => {
        this.gameOverTimer = null;
        if (this.page !== 'game' || this.busy()) return; // the player has moved on (exit, new start)
        if (this.session.demo) {
          this.fatal('You are out of demo credits.');
          return;
        }
        this.go('result');
        sound('result');
        this.result.show(value => this.money(value), this.balance);
      });
    } else if (!this.statusTimer?.isActive()) {
      this.idleStatus();
    }
  }

  private busy(): boolean {
    return this.requesting || this.inAir > 0 || this.holding || this.replaying;
  }

  /**
   * Review 7.5: plays an earlier round's balls again, on the board and prizes it was played on, from what the server
   * stored. Nothing is bet or paid and the balance doesn't move; the player's own board comes back afterwards.
   */
  private async replay(entry: HistoryEntry): Promise<void> {
    const round = entry.round;
    if (!round || this.busy() || this.page !== 'game') return;
    this.replaying = true;
    this.syncControls();
    // the prizes of the odds it was played on; a round from before they were recorded is shown without labels, as
    // today's prizes may not be the ones it was played on (review N15.4)
    const played = entry.prizes ? entry.prizes.map(value => [0, 0, 0].map((_, risk) => (risk === entry.risk ? value : 0))) : [];
    try {
      this.board.clearAmounts();
      this.board.setRows(entry.rows, played);
      this.board.setRisk(entry.risk);
      if (!entry.prizes) this.board.hideValues();
      this.board.setMeterPositions([]);
      if (this.view) this.board.layout(this.view);
      this.board.setStatus('REPLAY · ROUND ' + round.roundId.slice(0, 8).toUpperCase());
      await wait(1.2);
      const all = [...round.slots.map((slot, i) => ({ slot, boost: round.boosts?.[i] ?? 1, win: round.wins?.[i] ?? 0,
        paid: (round.multipliers[i] ?? 0) > 1, multiplier: round.multipliers[i] ?? 0, accent: undefined as string | undefined,
        path: round.paths?.[i] ?? null })),
      ...(round.freeSlots ?? []).map((slot, i) => ({ slot, boost: round.freeBoosts?.[i] ?? 1, win: round.freeWins?.[i] ?? 0,
        paid: (round.freeWins?.[i] ?? 0) > (round.freeBets?.[i] ?? 0), multiplier: round.freeMultipliers?.[i] ?? 0, accent: this.accent,
        path: null }))];
      if (round.hotSlot != null && round.hotBoost != null) this.board.setHot(round.hotSlot, round.hotBoost);
      const drops: Promise<void>[] = [];
      for (let i = 0; i < all.length; i++) {
        if (i > 0) await wait(0.4);
        const ball = all[i];
        drops.push(this.board.dropBall(ball.slot, {
          onPeg: pegSound,
          onLand: landing => { this.board.showAmount(landing.slot, this.money(ball.win), ball.paid); landSound(ball.multiplier, ball.boost, landing); },
        }, ball.boost, ball.accent, ball.path));
      }
      await Promise.all(drops);
      const config = this.session.config.mega;
      for (const spin of round.spins ?? []) {
        if (config?.wheel?.[spin.segment]) {
          await this.wheel.show(config.wheel, spin.segment, { gold: false, title: 'SPIN!', amount: this.money(spin.win), hurry: true });
        }
      }
      for (const mega of round.megaSpins ?? []) {
        if (config?.megaWheel?.[mega.segment]) {
          await this.wheel.show(config.megaWheel, mega.segment, { gold: true, title: 'MEGA SPIN', amount: this.money(mega.win), hurry: true });
        }
      }
      this.board.setStatus('REPLAY · WON ' + this.money(round.totalWin));
      await wait(2.5);
    } finally {
      this.board.clearHot();
      this.board.clearAmounts();
      this.applyRows();
      this.replaying = false;
      this.syncControls();
      this.idleStatus();
    }
  }

  /** Settings lock while any ball is in the air (the board can't change under them); the drop button stays live. */
  private syncControls(): void {
    const busy = this.busy();
    this.panel.setPlaying(busy, this.holding);
    this.classic.setPlaying(busy);
    this.panel.setSpins(this.holding ? this.spins : null);
    this.options.setExit(this.page === 'game', !busy);
  }

  /** Called every frame: balls knocking into each other. */
  tick(deltaMs: number): void {
    this.board.update(deltaMs);
  }



  private openExit(): void {
    this.exitDialog.visible = true;
    this.exitDialog.alpha = 0;
    gsap.to(this.exitDialog, { alpha: 1, duration: 0.2 });
  }

  private closeExit(): void {
    this.exitDialog.visible = false;
  }
}

/** Fullscreen toggle, or null where the browser has none (iPhone Safari): then the button is left out. */
function fullscreenToggle(): (() => void) | null {
  type Fs = Document & { webkitFullscreenEnabled?: boolean; webkitFullscreenElement?: Element | null; webkitExitFullscreen?: () => void };
  type FsElement = HTMLElement & { webkitRequestFullscreen?: () => void };
  const doc = document as Fs;
  const root = document.documentElement as FsElement;
  if (!document.fullscreenEnabled && !doc.webkitFullscreenEnabled) return null;
  return () => {
    if (document.fullscreenElement || doc.webkitFullscreenElement) {
      if (document.exitFullscreen) void document.exitFullscreen(); else doc.webkitExitFullscreen?.();
    } else if (root.requestFullscreen) {
      void root.requestFullscreen().catch(() => undefined);
    } else {
      root.webkitRequestFullscreen?.();
    }
  };
}
