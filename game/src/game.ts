import { Container, type Texture } from 'pixi.js';
import { gsap } from 'gsap';
import { errorText, formatAmount, playRound, resumeSession, type ApiError, type HistoryEntry, type MeterInfo, type Round, type Session } from './api';
import { HistoryPanel } from './historyPanel';
import { ResponsiblePlay } from './responsible';
import { Board, type Landing, type PegHit } from './board';
import { BigWin, winLevel } from './effects';
import { FortuneWheel } from './fortuneWheel';
import { MegaControls } from './megaControls';
import { WinTicker } from './ticker';
import { Meter } from './meter';
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

/**
 * Plinko Mega. Outcomes come only from the server: the client sends rows, risk, bet and balls and shows the paths,
 * gates, wheel spins and balance it gets back. Nothing here can change what a round pays.
 */
export class Game extends Container {
  private readonly menu: Menu;
  private readonly table = new Container();
  private readonly board: Board;
  private readonly exitDialog: ExitDialog;
  private readonly result: ResultScreen;
  private readonly options: Options;
  private readonly bigWin: BigWin;
  /** The fortune wheel under the board, and the control bar. */
  private readonly wheel: FortuneWheel;
  private readonly bar: MegaControls;
  private readonly ticker: WinTicker;
  private ballsLeft = 0;          // balls dropped and not landed yet (PLAY shows them)
  private readonly meter: Meter;

  private readonly rowOptions: number[];
  private readonly ladder: number[];
  private page: Page = 'menu';
  private view!: Viewport;
  private balance: number;
  private bet: number;
  private risk = 0;
  private rows: number;
  private balls = 1;              // balls per round (the drawer's BALLS)
  private requesting = false;     // a round request is on its way (only one at a time)
  private inAir = 0;              // rounds whose balls are still falling
  private replaying = false;      // an earlier round is being shown again (review 7.5): no bet, nothing paid
  private readonly historyPanel: HistoryPanel;
  private readonly responsible: ResponsiblePlay;
  private lastLaunch = 0;          // when the last round was asked for (the market's minimum time per round)
  private holding = false;        // autoplay is running
  private queuedTaps = 0;         // taps that came while a request was on its way: played right after it
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
    this.meter = new Meter(theme.accent, theme.ui.style === 'glass' ? theme.background : null, inkLook);
    this.meter.set(session.meter?.enabled ?? false, session.meter?.charges ?? 0, session.meter?.of ?? 0);

    this.exitDialog = new ExitDialog({ exit: tex('item_exit.png'), exitPortrait: tex('item_exit_p.png'),
      ok: tex('button_confirm.png'), no: tex('button_cancel.png') }, theme.landscapeExitFromPortrait,
    () => { this.closeExit(); this.go('menu'); }, () => this.closeExit());
    this.bigWin = new BigWin([theme.accent, ...(theme.board.slots?.map(s => s.face) ?? ['#FFBF00', '#EF521E', '#ED0000']), theme.text]);
    this.board.onClack = () => sound('clack', { volume: 0.5 });
    const mega = config.mega;
    this.board.setMega(mega?.gates ?? {}, mega?.gateBoost ?? null, mega?.wheel ? mega.spinSlots ?? {} : {});
    this.board.onGate = () => sound('golden', { volume: 0.7 });
    this.wheel = new FortuneWheel(theme.wheel, theme.wheel.hub ? tex(theme.wheel.hub) : null, theme.wheel.pointer ? tex(theme.wheel.pointer) : null,
      value => this.money(value));
    this.wheel.onTick = () => sound('hit', { volume: 0.35 });
    this.wheel.visible = !!mega?.wheel;
    this.wheel.setWheel(mega?.wheel ?? [], this.bet);
    this.bar = new MegaControls({ gold: theme.wheel.title, ink: theme.wheel.ink, panel: theme.ui.panel, text: theme.text }, {
      play: () => this.play(),
      auto: () => { if (this.holding) this.stopHolding(); else if (this.page === 'game') void this.autoplay(); },
      turbo: on => { this.board.speed = on ? 2 : 1; this.wheel.setHurry(on); },
      bump: () => { sound('clack'); this.board.bump(); },
      bet: up => this.changeBet(up),
      risk: step => this.changeRisk(step),
      balls: step => this.changeBalls(Math.min(config.maxBalls, Math.max(1, this.balls + step))),
      rows: step => this.changeRows(this.rowOptions.indexOf(this.rows) + step),
    });
    this.ticker = new WinTicker(theme.wheel.title);
    this.table.addChild(this.board, this.ticker, this.wheel, this.meter, this.bar);

    this.result = new ResultScreen(tex('item_result.png'), tex('item_result_p.png'), tex('button_continue.png'), () => this.go('menu'));
    this.options = new Options({ settings: tex('button_settings.png'), soundOn: tex('button_sound_on.png'),
      soundOff: tex('button_sound_off.png'), fullscreen: tex('button_fullscreen.png'), exit: tex('button_exit.png') },
    isMuted, () => setMuted(!isMuted()), fullscreenToggle(), () => this.openExit(),
    { accent: theme.accent, text: theme.text, background: theme.background,
      ...(theme.ui.style === 'ink' ? { ring: theme.ui.ink, disc: theme.ui.panel } : {}) }, motion,
    () => { if (!this.busy()) this.historyPanel.show(); });
    this.options.on('pointertap', () => sound('click'));

    this.addChild(this.menu, this.table, this.bigWin, this.result, this.exitDialog, this.options);
    this.historyPanel = new HistoryPanel(value => this.money(value), entry => void this.replay(entry));
    this.responsible = new ResponsiblePlay(config, session.sessionId, value => this.money(value), () => { this.stopHolding(); this.go('menu'); });
    this.go('menu');
    window.addEventListener('keydown', event => {
      if ((event.code === 'Space' || event.code === 'Enter') && this.page === 'game' && !this.exitDialog.visible
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
    this.layoutBoard(view);
    this.exitDialog.layout(view);
    this.result.layout(view);
    this.bigWin.layout(view);
    // portrait: board, wheel, bar from top to bottom; landscape: the board in the middle, the wheel on the right
    const visibleH = view.stageH - 2 * view.offsetY;
    if (view.landscape) this.wheel.place(view.stageW * 0.82 - view.offsetX * 0.5, view.stageH * 0.62, Math.min(140, visibleH * 0.23));
    else this.wheel.place(view.stageW / 2, view.stageH * 0.735, Math.min(150, visibleH * 0.16));
    this.bar.layout(view, view.landscape ? view.stageW * 0.45 : view.stageW / 2);
    this.ticker.layout(view);
    this.meter.layout(view);
    this.options.layout(view);
  }

  /** The board's place: room for the wheel and the control bar. */
  private layoutBoard(view: Viewport): void {
    this.board.layout(view, view.landscape ? { x: view.stageW * 0.45, y: view.stageH * 0.43, fit: 0.8 }
      : { x: view.stageW / 2, y: view.stageH * 0.3, fit: 0.84 });
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
    if (page !== 'game') this.historyPanel.hide();
  }

  /** From the start screen: a fresh table with the latest balance from the casino (no jingle: the theme's music plays). */
  private async start(): Promise<void> {
    this.go('game');
    this.board.clearAmounts();
    this.ticker.clear();
    this.risk = 0;
    this.rows = this.rowOptions[0] ?? 8;
    this.bet = this.ladder.includes(this.session.config.defaultBet) ? this.session.config.defaultBet : this.ladder[0];
    this.lastWin = 0;
    this.pendingWins = 0;
    this.applyRows();
    this.refreshPanel();
    this.syncControls();
    this.idleStatus();
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

  private applyRows(): void {
    this.board.setRows(this.rows, this.session.config.rows.find(r => r.rows === this.rows)?.prizes ?? []);
    this.board.setRisk(this.risk);
    this.markMeterSlots();
    if (this.view) this.layoutBoard(this.view);
  }

  private money(value: number): string {
    return formatAmount(this.session.currencySymbol, value);
  }

  private refreshPanel(): void {
    this.bar.setBalance(this.money(Math.max(0, round2(this.balance - this.pendingWins))));
    this.bar.setTotalBet(this.money(round2(this.bet * this.balls)));
    this.bar.setWin(this.money(this.lastWin));
    this.bar.setRisk(RISKS[this.risk]);
    this.bar.setBalls(this.balls, this.money(this.bet));
    this.bar.setRows(this.rows);
    this.wheel.setStake(this.bet);
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

  /** PLAY: stops autoplay; while balls fall, hurries them; otherwise plays a round. */
  private play(): void {
    if (this.page !== 'game') return;
    if (this.holding) { this.stopHolding(); return; }
    if (this.board.inFlight() > 0) { this.board.hurryAll(); return; }
    void this.launchRound();
  }

  /**
   * Autoplay: round after round until it is stopped, the balance can't pay the next one, a round wins 10x or more, or
   * an error.
   */
  private async autoplay(): Promise<void> {
    if (this.holding) return;
    this.holding = true;
    this.queuedTaps = 0;
    this.syncControls();
    while (this.holding && this.page === 'game') {
      // the next drop when the last request is answered, and not faster than the balls can be told apart
      while (this.requesting) await wait(0.05);
      if (!this.holding) break;
      const round = await this.launchRound();
      if (!round) { this.stopHolding(); break; }
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
    this.ballsLeft += round.slots.length;
    this.syncControls();
    this.refreshPanel();
    void this.animateRound(round, this.bet, this.meterPositions()).then(() => {
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

  /** One round's balls, each adding its own win as it lands, then its wheel spins and MEGA SPINs. */
  private async animateRound(round: Round, bet: number, charging: number[]): Promise<void> {
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
          this.ticker.add(this.money(win), multiplier);
          if (charging.includes(landing.prizeIndex)) this.meter.charge();
          landSound(multiplier, boost, landing);
          this.ballsLeft = Math.max(0, this.ballsLeft - 1);
          this.bar.setPlaying(true, this.holding, this.ballsLeft);
        },
      }, boost, null, round.paths?.[i] ?? null));
    }
    await Promise.all(drops);

    // every ball in a SPIN slot turns the wheel under the board, then a full meter's MEGA SPINs turn it gold
    const config = this.session.config.mega;
    const spins = round.spins ?? [];
    spins.forEach((_, i) => this.wheel.setSpins(i + 1));
    for (let i = 0; i < spins.length; i++) {
      const spin = spins[i];
      if (!config?.wheel?.[spin.segment]) { land(spin.win); continue; }
      sound('big');
      await this.wheel.turn(config.wheel, spin.segment, spin.win, { gold: false, title: 'SPIN' });
      this.wheel.setSpins(spins.length - i - 1);
      land(spin.win);
      sound(config.wheel[spin.segment].name ? 'bigWin' : 'score');
    }
    for (const mega of round.megaSpins ?? []) {
      if (!config?.megaWheel?.[mega.segment]) { land(mega.win); continue; }
      this.meter.set(true, 0, this.session.meter?.of ?? 0);
      sound('huge');
      await this.wheel.turn(config.megaWheel, mega.segment, mega.win, { gold: true, title: 'MEGA SPIN ' + this.money(mega.stake) });
      land(mega.win);
      sound(config.megaWheel[mega.segment].name ? 'bigWin' : 'win');
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
      if (this.view) this.layoutBoard(this.view);
      this.board.setStatus('REPLAY · ROUND ' + round.roundId.slice(0, 8).toUpperCase());
      await wait(1.2);
      const all = round.slots.map((slot, i) => ({ slot, boost: round.boosts?.[i] ?? 1, win: round.wins?.[i] ?? 0,
        paid: (round.multipliers[i] ?? 0) > 1, multiplier: round.multipliers[i] ?? 0, path: round.paths?.[i] ?? null }));
      if (round.hotSlot != null && round.hotBoost != null) this.board.setHot(round.hotSlot, round.hotBoost);
      const drops: Promise<void>[] = [];
      for (let i = 0; i < all.length; i++) {
        if (i > 0) await wait(0.4);
        const ball = all[i];
        drops.push(this.board.dropBall(ball.slot, {
          onPeg: pegSound,
          onLand: landing => { this.board.showAmount(landing.slot, this.money(ball.win), ball.paid); landSound(ball.multiplier, ball.boost, landing); },
        }, ball.boost, null, ball.path));
      }
      await Promise.all(drops);
      const config = this.session.config.mega;
      for (const spin of round.spins ?? []) {
        if (config?.wheel?.[spin.segment]) await this.wheel.turn(config.wheel, spin.segment, spin.win, { gold: false, title: 'SPIN' });
      }
      for (const mega of round.megaSpins ?? []) {
        if (config?.megaWheel?.[mega.segment]) await this.wheel.turn(config.megaWheel, mega.segment, mega.win, { gold: true, title: 'MEGA SPIN' });
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

  /** Settings lock while any ball is in the air (the board can't change under them); PLAY stays live. */
  private syncControls(): void {
    const busy = this.busy();
    this.bar.setPlaying(busy, this.holding, this.ballsLeft);
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
