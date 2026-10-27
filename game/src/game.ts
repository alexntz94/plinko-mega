import { Container, Graphics, Rectangle, Sprite, type Texture } from 'pixi.js';
import { gsap } from 'gsap';
import { errorText, formatAmount, playRound, resumeSession, type ApiError, type HistoryEntry, type MeterInfo, type Round, type Session } from './api';
import { HistoryPanel } from './historyPanel';
import { ResponsiblePlay } from './responsible';
import { Board, smashedPegs, type Landing, type PegHit } from './board';
import { BigWin, BlastShower, CoinShower, splatter, winLevel, type Shower } from './effects';
import { FortuneWheel, type ChestPick, type GachaPick, type PhotoRevolver, type TeslaRing } from './fortuneWheel';
import { Character } from './character';
import { WashingLines } from './washingLines';
import { Props } from './props';
import { MegaControls } from './megaControls';
import { ConsoleControls } from './consoleControls';
import { ArsenalPanel, ARSENALS } from './arsenal';
import { CoreMeltdown } from './meltdown';
import { TankAssault } from './tank';
import { WinTicker } from './ticker';
import { Ambient, Screen, WinBanner } from './juice';
import { Meter } from './meter';
import { ballSteps, betLadder, nextBet, RISKS, round2 } from './rules';
import { ExitDialog, Menu, Options, ResultScreen, type MotionSwitch } from './screens';
import { isMuted, play as sound, setMuted } from './sound';
import { neonLayers, type Theme } from './theme';
import type { Viewport } from './viewport';

const random = (min: number, max: number) => min + Math.random() * (max - min);

/** A peg's tick: a little higher on every row down (the board plays a scale as a ball falls), never twice the same,
 * heard from where the peg is. */
const pegSound = (peg: PegHit) => sound('hit', {
  volume: 0.6, rate: 0.9 + 0.32 * peg.row / Math.max(1, peg.rows - 1) + random(-0.025, 0.025), pan: peg.pan * 0.7 });

/** A landing, by what it paid: nothing, a little, 2x and up, 10x and up. */
function landSound(multiplier: number, landing: Landing): void {
  const name = multiplier >= 10 ? 'huge' : multiplier >= 2 ? 'big' : multiplier > 1 ? 'score' : 'noScore';
  sound(name, { pan: landing.pan * 0.6 });
}
/** A win the way the server pays it: whole cents, rounded down. */
function cents(value: number): number {
  return Math.floor(value * 100 + 1e-9) / 100;
}

/** The theme's photo revolver's textures, or null. */
function photoRevolver(theme: Theme, tex: (file: string) => Texture): PhotoRevolver | null {
  const art = theme.wheel.revolverArt;
  if (!art) return null;
  const c = art.cartridges;
  return { cylinder: tex(art.cylinder), dial: tex(art.dial), flash: tex(art.flash), spec: art,
    cartridges: { plain: tex(c.plain), MINI: tex(c.MINI), MAJOR: tex(c.MAJOR), MEGA: tex(c.MEGA) } };
}

/** The theme's tesla ring's textures, or null. */
function teslaRing(theme: Theme, tex: (file: string) => Texture): TeslaRing | null {
  const art = theme.wheel.tesla;
  if (!art) return null;
  const c = art.cells;
  return { motion: art.motion, orb: tex(art.orb), flash: tex(art.flash), cells: { plain: tex(c.plain), MINI: tex(c.MINI), MAJOR: tex(c.MAJOR), MEGA: tex(c.MEGA) },
    plate: art.plate ? { plate: tex(art.plate.plate), coil: tex(art.plate.coil), spec: art.plate } : null };
}

/** The theme's gacha machine's textures, or null. */
function gachaPick(theme: Theme, tex: (file: string) => Texture): GachaPick | null {
  const g = theme.wheel.gacha;
  if (!g) return null;
  const kinds = (k: GachaPick['spec']['capsules']) => ({ plain: tex(k.plain), MINI: tex(k.MINI), MAJOR: tex(k.MAJOR), MEGA: tex(k.MEGA) });
  return { machine: tex(g.machine), crank: tex(g.crank), open: tex(g.open), burst: tex(g.burst), capsules: kinds(g.capsules),
    tokens: kinds(g.tokens), spec: g };
}

/** The theme's chest pick's textures, or null. */
function chestPick(theme: Theme, tex: (file: string) => Texture): ChestPick | null {
  const art = theme.wheel.chests;
  if (!art) return null;
  const t = art.tokens;
  return { closed: tex(art.closed), open: tex(art.open), goldClosed: tex(art.goldClosed), goldOpen: tex(art.goldOpen),
    shelf: tex(art.shelf), glow: tex(art.glow), burst: tex(art.burst), plank: art.plank, span: art.span,
    doors: art.doors ? { ...art.doors, phone: art.doors.phone?.map(p => ({ wall: tex(p.wall), arches: p.arches })) ?? null } : null,
    jars: art.jars ? { top: art.jars.top, kinds: { plain: tex(art.jars.kinds.plain), MINI: tex(art.jars.kinds.MINI), MAJOR: tex(art.jars.kinds.MAJOR),
      MEGA: tex(art.jars.kinds.MEGA) } } : null,
    socks: art.socks ? { peg: tex(art.socks.peg), sag: art.socks.sag, kinds: { plain: tex(art.socks.kinds.plain), MINI: tex(art.socks.kinds.MINI),
      MAJOR: tex(art.socks.kinds.MAJOR), MEGA: tex(art.socks.kinds.MEGA) } } : null,
    tokens: { plain: tex(t.plain), MINI: tex(t.MINI), MAJOR: tex(t.MAJOR), MEGA: tex(t.MEGA) }, pick: art.pick };
}

/** Tomb Raid's payouts (x the round's bet): the server's meltdown table, a door each, the first on the left. */
const TOMB_RAID = [0.5, 1, 2, 3, 5, 10, 20];

/** The power-ups' short names, for the status line and the ARSENAL key. */
const POWER_NAMES: Record<string, string> = { heavy: 'HEAVY', cluster: 'CLUSTER', 'strike-left': 'STRIKE L', 'strike-centre': 'STRIKE C',
  'strike-right': 'STRIKE R', crates: 'CRATES', missile: 'MISSILE', night: 'NIGHT', tank: 'TANK',
  chain: 'CHAIN', 'magnet-left': 'MAGNET L', 'magnet-right': 'MAGNET R', meltdown: 'MELTDOWN', pair: 'PEARL PAIR' };
/** Lagoon Drop's names for the power-ups it sells (Sunburst Surge is heavy, Crosscurrent the magnet, Volcano Vault the
 *  meltdown). */
const LAGOON_NAMES: Record<string, string> = { heavy: 'SUNBURST', 'magnet-left': 'CURRENT L', 'magnet-right': 'CURRENT R', meltdown: 'VOLCANO' };
const LAGOON_STAMPS: Record<string, string> = { 'magnet-left': 'CROSSCURRENT', 'magnet-right': 'CROSSCURRENT', meltdown: 'VOLCANO VAULT', pair: 'PEARL PAIR' };
/** Pharaoh's Drop's: Scarab Fortune is heavy, Twin Serpents the pair, Sirocco Draft the magnet, Tomb Raid the meltdown. */
const PHARAOH_NAMES: Record<string, string> = { heavy: 'SCARAB', pair: 'SERPENTS', 'magnet-left': 'SIROCCO L', 'magnet-right': 'SIROCCO R', meltdown: 'TOMB RAID' };
const PHARAOH_STAMPS: Record<string, string> = { pair: 'TWIN SERPENTS', 'magnet-left': 'SIROCCO DRAFT', 'magnet-right': 'SIROCCO DRAFT', meltdown: 'TOMB RAID' };
/** Neon Nights': Golden Gacha is heavy, Twin Pop the pair, Side Magnet the magnet, Lucky Draw the meltdown. */
const NEON_NAMES: Record<string, string> = { heavy: 'GOLDEN', pair: 'TWIN POP', 'magnet-left': 'MAGNET L', 'magnet-right': 'MAGNET R', meltdown: 'LUCKY DRAW' };
const NEON_STAMPS: Record<string, string> = { pair: 'TWIN POP', 'magnet-left': 'SIDE MAGNET', 'magnet-right': 'SIDE MAGNET', meltdown: 'LUCKY DRAW' };
/** Candy Factory's: Golden Glaze is heavy, Double Dip the pair, Candy Current the magnet, Sugar Rush the meltdown. */
const CANDY_NAMES: Record<string, string> = { heavy: 'GLAZE', pair: 'DOUBLE DIP', 'magnet-left': 'CURRENT L', 'magnet-right': 'CURRENT R', meltdown: 'SUGAR RUSH' };
const CANDY_STAMPS: Record<string, string> = { pair: 'DOUBLE DIP', 'magnet-left': 'CANDY CURRENT', 'magnet-right': 'CANDY CURRENT', meltdown: 'SUGAR RUSH' };
/** Shurda Plinko's: Golden Sock is heavy, Pair of Socks the pair, Drawer Breeze the magnet, Laundry Day the meltdown. */
const SHURDA_NAMES: Record<string, string> = { heavy: 'GOLD SOCK', pair: 'PAIR', 'magnet-left': 'BREEZE L', 'magnet-right': 'BREEZE R', meltdown: 'LAUNDRY' };
const SHURDA_STAMPS: Record<string, string> = { pair: 'PAIR OF SOCKS', 'magnet-left': 'DRAWER BREEZE', 'magnet-right': 'DRAWER BREEZE', meltdown: 'LAUNDRY DAY' };
/** Hell of a Deal's: Hellfire Mark is heavy, Double Dealing the pair, Sulphur Draft the magnet, Closing Sale the meltdown. */
const HELL_NAMES: Record<string, string> = { heavy: 'HELLFIRE', pair: 'DOUBLE', 'magnet-left': 'SULPHUR L', 'magnet-right': 'SULPHUR R', meltdown: 'SALE' };
const HELL_STAMPS: Record<string, string> = { pair: 'DOUBLE DEALING', 'magnet-left': 'SULPHUR DRAFT', 'magnet-right': 'SULPHUR DRAFT', meltdown: 'CLOSING SALE' };
/** Zombie War's: Mega Charge is heavy, Split Fuse the pair, Horde Rush the magnet, Locker Raid the meltdown. */
const ZOMBIE_NAMES: Record<string, string> = { heavy: 'MEGA CHARGE', pair: 'SPLIT FUSE', 'magnet-left': 'HORDE L', 'magnet-right': 'HORDE R', meltdown: 'RAID' };
const ZOMBIE_STAMPS: Record<string, string> = { pair: 'SPLIT FUSE', 'magnet-left': 'HORDE RUSH', 'magnet-right': 'HORDE RUSH', meltdown: 'LOCKER RAID' };
/** Every power-up the arsenal can sell. */
const POWER_IDS = ['heavy', 'cluster', 'strike-left', 'strike-centre', 'strike-right', 'crates', 'missile', 'night', 'tank',
  'chain', 'magnet-left', 'magnet-right', 'meltdown', 'pair'];
/** What a bought round is announced as. */
const POWER_STAMPS: Record<string, string> = { cluster: 'CLUSTER SHELLS', 'strike-left': 'AIRSTRIKE x2', 'strike-centre': 'AIRSTRIKE x2',
  'strike-right': 'AIRSTRIKE x2', crates: 'CHAIN REACTION', missile: 'MISSILE STRIKE x3', night: 'NIGHT RAID', tank: 'TANK ASSAULT',
  chain: 'CHAIN LIGHTNING', 'magnet-left': 'MAGNETIC FIELD', 'magnet-right': 'MAGNETIC FIELD', meltdown: 'CORE MELTDOWN' };

/** Four quick bets for the console: the bet ladder's steps nearest 1, 5, 10 and 25 times the default bet. */
function quickBets(ladder: number[], base: number): number[] {
  const picks: number[] = [];
  for (const times of [1, 5, 10, 25]) {
    const best = ladder.reduce((a, b) => (Math.abs(b - base * times) < Math.abs(a - base * times) ? b : a), ladder[0]);
    if (!picks.includes(best)) picks.push(best);
  }
  return picks;
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
  /** A phone's pick bonus (chests, doors, the gacha): the screen behind it darkened, so it stands on its own. */
  private readonly pickShade = new Graphics();
  private readonly board: Board;
  private readonly exitDialog: ExitDialog;
  private readonly result: ResultScreen;
  private readonly options: Options;
  private readonly bigWin: BigWin;
  /** Coins over the table: out of the wheel on its wins, out of a MEGA BALL's slot, with the WIN banner. */
  private readonly shower: Shower;
  /** The fortune wheel under the board, and the control bar. */
  private readonly wheel: FortuneWheel;
  /** the theme's wheel is a tesla ring or a chest pick (shorter than a revolver, so it is drawn bigger) */
  private readonly ring: boolean;
  /** the theme's spinner is a chest pick (on a phone: two shelves in the upper middle of the screen) */
  private readonly chestPick: boolean;
  /** the meltdown is a Tomb Raid on the tomb doors (Pharaoh's Drop), not the pressure gauge */
  private readonly tombRaid: boolean;
  /** the spinner is a gacha machine (on a phone: big, in the upper middle) */
  private readonly gacha: boolean;
  private readonly bar: MegaControls | ConsoleControls;
  /** the power-up armed for the next round (heavy, cluster), and the ARSENAL panel it is picked on */
  private power: string | null = null;
  private readonly arsenal: ArsenalPanel | null = null;
  private readonly tank: TankAssault | null = null;
  private readonly meltdown: CoreMeltdown | null = null;
  /** Steam from the station's vents, behind the board (Reactor Drop). */
  private readonly vents = new Container();
  /** big wins splash blood over the screen (Zombie War) */
  private readonly bloody: boolean;
  /** the controls in a bar of their own under the board on a wide screen too (theme console.bar) */
  private readonly consoleBar: boolean;
  /** on a phone, where the controls start under the board (theme console.phoneTop) */
  private readonly phoneTop: number;
  /** a character beside the board (Pharaoh's Drop's pharaoh), or null */
  private readonly character: Character | null;
  private readonly characterHeight: number;
  /** his spot on a phone (theme character.phone), or null: the top right corner */
  private readonly characterPhone: { x: number; feet: number; height: number } | null;
  /** on a phone his spot is behind the board (theme character.phoneBehind) */
  private readonly characterPhoneBehind: boolean;
  /** on a wide screen he leans on the control bar: its top this far down his frame (theme character.lean), or null */
  private readonly characterLean: number | null;
  /** where his feet are in his frame (shares) */
  private readonly characterFeet: [number, number];
  /** washing lines over the wall (theme lines), or null */
  private readonly lines: WashingLines | null;
  /** the background's moving pieces (theme props), or null; main.ts lays them over the background */
  readonly props: Props | null;
  /** The theme's names: Reactor Drop's heavy is OVERCHARGE. */
  private readonly powerNames: Record<string, string>;
  private readonly heavyName: string;
  private readonly powerStamps: Record<string, string>;
  /** where the controls go on the board's console (the theme's frame picture) */
  private readonly consoleRects: NonNullable<Theme['console']>['rects'] | null;
  private readonly ticker: WinTicker;
  /** The slot-machine feel: petals always drifting, the WIN banner, the screen's flash and slow motion. */
  private readonly ambient: Ambient;
  private readonly banner = new WinBanner();
  private readonly screen = new Screen();
  private turbo = false;
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
  private balls: number;          // balls per round (the BALLS control: 10, 20, 30 …)
  private requesting = false;     // a round request is on its way (only one at a time)
  private inAir = 0;              // rounds whose balls are still falling
  private replaying = false;      // an earlier round is being shown again (review 7.5): no bet, nothing paid
  private readonly historyPanel: HistoryPanel;
  private readonly responsible: ResponsiblePlay;
  private lastLaunch = 0;          // when the last round was asked for (the market's minimum time per round)
  private holding = false;        // autoplay is running
  private queuedTaps = 0;         // taps that came while a request was on its way: played right after it
  private pendingWins = 0;        // wins of balls still in the air: on screen only once they land
  private roundWin = 0;           // the bar's WIN: what the latest round has won so far (from 0 at every round)
  private roundSeq = 0;           // which round is the latest (an earlier one still landing doesn't touch WIN)
  private statusTimer: gsap.core.Tween | null = null;
  private gameOverTimer: gsap.core.Tween | null = null;

  constructor(private readonly session: Session, theme: Theme, tex: (file: string) => Texture,
              private readonly fatal: (message: string) => void, motion: MotionSwitch | null = null,
              private readonly roundPlayed: () => void = () => undefined) {
    super();
    const config = session.config;
    // a frame drawn for one board (theme fit) allows only its number of rows
    const fitted = theme.board.fit;
    const offered = config.rows.map(r => r.rows).sort((a, b) => a - b);
    this.rowOptions = fitted ? offered.filter(rows => fitted[rows]) : offered;
    if (this.rowOptions.length === 0) this.rowOptions = offered;
    this.rows = this.rowOptions[0] ?? 8;
    const defaultBet = config.defaultBet || config.minBet;
    this.ladder = betLadder(config.minBet, config.maxBet, defaultBet);
    this.bet = defaultBet;
    this.balls = ballSteps(config.maxBalls)[0];
    this.balance = session.balance;

    this.menu = new Menu(tex('logo.png'), tex('logo_p.png'), tex('button_start.png'), () => this.start(),
      neonLayers(theme).map(([wide, tall]) => [tex(wide), tex(tall)]), motion);
    this.board = new Board(tex('item_plinko.png'), tex('item_plinko_p.png'), tex('item_ball.png'), theme.board,
      theme.board.peg ? tex(theme.board.peg) : null,
      Object.fromEntries(Object.entries(theme.board.fit ?? {}).map(([rows, f]) => [rows, { wide: tex(f.frame), tall: tex(f.framePortrait) }])),
      theme.board.fire ? { sheet: tex(theme.board.fire.sheet), glow: theme.board.fire.glow ? tex(theme.board.fire.glow) : null } : null);
    const inkLook = theme.ui.style === 'ink' ? { ink: theme.ui.ink, panel: theme.ui.panel } : null;
    this.meter = new Meter(theme.accent, theme.ui.style === 'glass' ? theme.background : null, inkLook);
    this.meter.set(session.meter?.enabled ?? false, session.meter?.charges ?? 0, session.meter?.of ?? 0);

    this.exitDialog = new ExitDialog({ exit: tex('item_exit.png'), exitPortrait: tex('item_exit_p.png'),
      ok: tex('button_confirm.png'), no: tex('button_cancel.png') }, theme.landscapeExitFromPortrait,
    () => { this.closeExit(); this.go('menu'); }, () => this.closeExit());
    this.consoleRects = theme.console?.rects ?? null;
    this.consoleBar = theme.console?.bar === true;
    this.phoneTop = theme.console?.phoneTop ?? 418;
    const reactor = theme.board.fx === 'reactor', lagoon = theme.board.fx === 'lagoon', pharaoh = theme.board.fx === 'pharaoh';
    const zombie = theme.board.fx === 'zombie', neon = theme.board.fx === 'neon', candy = theme.board.fx === 'candy';
    const shurda = theme.board.fx === 'shurda', hell = theme.board.fx === 'hell';
    this.powerNames = { ...POWER_NAMES, ...(reactor ? { heavy: 'OVERCHARGE' } : {}), ...(lagoon ? LAGOON_NAMES : {}), ...(pharaoh ? PHARAOH_NAMES : {}),
      ...(zombie ? ZOMBIE_NAMES : {}), ...(neon ? NEON_NAMES : {}), ...(candy ? CANDY_NAMES : {}), ...(shurda ? SHURDA_NAMES : {}), ...(hell ? HELL_NAMES : {}) };
    this.powerStamps = { ...POWER_STAMPS, ...(lagoon ? LAGOON_STAMPS : {}), ...(pharaoh ? PHARAOH_STAMPS : {}), ...(zombie ? ZOMBIE_STAMPS : {}), ...(neon ? NEON_STAMPS : {}), ...(candy ? CANDY_STAMPS : {}), ...(shurda ? SHURDA_STAMPS : {}), ...(hell ? HELL_STAMPS : {}) };
    this.heavyName = reactor ? 'OVERCHARGE' : lagoon ? 'SUNBURST SURGE' : pharaoh ? 'SCARAB FORTUNE' : zombie ? 'MEGA CHARGE' : neon ? 'GOLDEN GACHA' : candy ? 'GOLDEN GLAZE' : shurda ? 'GOLDEN SOCK' : hell ? 'HELLFIRE MARK' : 'HEAVY SHELLS';
    this.bloody = zombie;
    const blasts = theme.board.fx !== 'standard';   // war and reactor: blasts (explosions, energy) instead of coins
    this.shower = blasts ? new BlastShower() : new CoinShower();
    this.bigWin = new BigWin([theme.accent, ...(theme.board.slots?.map(s => s.face) ?? ['#FFBF00', '#EF521E', '#ED0000']), theme.text],
      blasts);
    this.board.onClack = () => sound('clack', { volume: 0.5 });
    const mega = config.mega;
    this.board.setMega(mega?.gates ?? {}, mega?.gateBoost ?? null, mega?.wheel ? mega.spinSlots ?? {} : {});
    this.board.onGate = () => sound('gate', { volume: 0.8 });
    this.board.onSmash = () => sound('score', { volume: 0.7 });
    this.board.onCrate = () => sound('big', { volume: 0.8 });
    this.wheel = new FortuneWheel(theme.wheel, theme.wheel.hub ? tex(theme.wheel.hub) : null, theme.wheel.pointer ? tex(theme.wheel.pointer) : null,
      value => this.money(value), theme.ui.panel,
      { bezel: theme.wheel.bezel ? tex(theme.wheel.bezel) : null, glass: theme.wheel.glass ? tex(theme.wheel.glass) : null,
        explode: blasts, photo: photoRevolver(theme, tex), tesla: teslaRing(theme, tex), chests: chestPick(theme, tex), gacha: gachaPick(theme, tex),
        words: reactor ? ['SURGE!', 'OVERLOAD!'] : lagoon ? ['SPLASH!', 'TIDAL WAVE!'] : pharaoh ? ['GOLD!', "PHARAOH'S GOLD!"]
          : zombie ? ['SPLAT!', 'BLOODBATH!'] : neon ? ['SUGOI!', 'JACKPOT!'] : candy ? ['SWEET!', 'SUGAR RUSH!'] : shurda ? ['SHURDA!', 'NICE SOCKS!'] : hell ? ['SOLD!', 'DEVIL OF A DEAL!'] : ['BOOM!', 'KABOOM!'] });
    this.ring = !!theme.wheel.tesla || !!theme.wheel.chests || !!theme.wheel.gacha;
    this.chestPick = !!theme.wheel.chests;
    // (the gacha, the jars and the socks play it too: the capsule, the jar, the sock holds the payout)
    this.tombRaid = !!theme.wheel.chests?.doors || !!theme.wheel.chests?.jars || !!theme.wheel.chests?.socks || !!theme.wheel.gacha;
    this.gacha = !!theme.wheel.gacha;
    this.wheel.onTick = () => sound('tick', { volume: 0.5 });
    this.wheel.onWin = big => {
      // coins out of the wheel on every win, a storm for a jackpot or a MEGA SPIN
      const at = this.toLocal(this.wheel.winPoint());
      this.shower.burst(at.x, at.y, big ? 70 : 28, big ? 1100 : 800);
      if (big) this.shower.pour(12, 0, 0, 0, 1.6);
      if (!blasts) return;
      sound(big ? 'huge' : 'big');
      if (big) this.screen.flash(0.5);
    };
    this.wheel.visible = false;   // only at the end of a round with spins
    this.wheel.setWheel(mega?.wheel ?? []);
    const actions = {
      play: () => this.play(),
      auto: () => { if (this.holding) this.stopHolding(); else if (this.page === 'game') void this.autoplay(); },
      turbo: (on: boolean) => { this.turbo = on; this.board.speed = on ? 2 : 1; this.wheel.setHurry(on); },
      bump: () => { sound('clack'); this.board.bump(); },
      bet: (up: boolean) => this.changeBet(up),
      risk: (step: number) => this.changeRisk(step),
      balls: (step: number) => {
        const steps = ballSteps(config.maxBalls);
        const at = Math.max(0, steps.findIndex(balls => balls >= this.balls));
        this.changeBalls(steps[Math.min(steps.length - 1, Math.max(0, at + step))]);
      },
      // the console's ROWS key only goes forward: past the largest board it comes back to the smallest
      rows: (step: number) => {
        const n = this.rowOptions.length;
        const at = this.rowOptions.indexOf(this.rows) + step;
        // the single ROWS key (phones, a control bar) wraps round; the arrows stop at the ends
        this.changeRows(this.bar instanceof ConsoleControls && step > 0 && at >= n && this.bar.rowsKeyShown ? 0 : at);
      },
      pickBet: (value: number) => this.pickBet(value),
      arsenal: () => this.openArsenal(),
    };
    this.bar = theme.console
      ? new ConsoleControls({ drop: tex(theme.console.drop), panel: tex(theme.console.panel), key: tex(theme.console.key),
        back: theme.console.back ? tex(theme.console.back) : null, panelInner: theme.console.inner },
        { gold: theme.wheel.title, text: theme.text, ink: theme.wheel.ink }, actions, quickBets(this.ladder, defaultBet), value => this.money(value))
      : new MegaControls({ gold: theme.wheel.title, ink: theme.wheel.ink, panel: theme.ui.panel, text: theme.text }, actions);
    this.ticker = new WinTicker(theme.wheel.title);
    this.ambient = new Ambient(theme.ambient.colours, theme.ambient.rise);
    this.banner.onTick = () => sound('tick', { volume: 0.6 });
    const c = theme.character;
    this.characterHeight = c?.height ?? 0.76;
    this.characterPhone = c?.phone ?? null;
    this.characterPhoneBehind = c?.phoneBehind ?? false;
    this.characterLean = c?.lean ?? null;
    this.characterFeet = c?.feet ?? [0.5, 1];
    this.character = c ? new Character({ body: tex(c.body), head: tex(c.head), arm: tex(c.arm), eyes: tex(c.eyes),
      cheer: tex(c.cheer), point: tex(c.point), shrug: c.shrug ? tex(c.shrug) : null,
      face: c.face ? { idle: tex(c.face.idle), happy: tex(c.face.happy), meh: tex(c.face.meh), pupil: tex(c.face.pupil) } : null }, c) : null;
    this.props = theme.props ? new Props(theme.props, tex) : null;
    this.lines = theme.lines ? new WashingLines(theme.lines, tex(theme.lines.rope), theme.lines.socks.map(sock => tex(sock.file))) : null;
    this.table.addChild(this.ambient, ...(this.lines ? [this.lines] : []), this.vents, ...(this.character ? [this.character] : []), this.board, this.ticker, this.wheel,
      this.banner, this.meter, this.bar);
    // Reactor Drop: the station breathes: vent steam drifts up at the sides now and then
    if (theme.board.fx === 'reactor' && theme.blast) this.ventSteam(tex(theme.blast.smoke));
    if (theme.console && config.mega?.powers && Object.keys(config.mega.powers).length) {
      const set = ARSENALS[theme.id] ?? ARSENALS['bombs-away'];
      this.arsenal = new ArsenalPanel(tex(theme.console.panel), { gold: theme.wheel.title, text: theme.text }, id => this.arm(id), set,
        theme.console.inner);
      if (this.bar instanceof ConsoleControls) this.bar.setArsenalName(set.title);
      this.table.addChild(this.arsenal);
    }
    if (reactor || lagoon || pharaoh || zombie || neon || candy || shurda || hell) {
      // Lagoon Drop's Volcano Vault is the same payout: the volcano's heat climbs instead of the core's pressure
      this.meltdown = new CoreMeltdown({ gold: theme.wheel.title, text: theme.text, rim: theme.wheel.rim,
        title: lagoon ? 'VOLCANO HEAT' : pharaoh ? 'TOMB RAID' : shurda ? 'LAUNDRY DAY' : hell ? 'CLOSING SALE' : 'CORE PRESSURE',
        ...(hell ? { face: '#2a0a0e', zone: '#f2c14e' } : {}) });
      this.table.addChild(this.meltdown);
    }
    if (theme.blast?.tank) {
      this.tank = new TankAssault({ tank: tex(theme.blast.tank.body), target: tex(theme.blast.tank.target) });
      this.table.addChild(this.tank);
    }

    this.result = new ResultScreen(tex('item_result.png'), tex('item_result_p.png'), tex('button_continue.png'), () => this.go('menu'));
    this.options = new Options({ settings: tex('button_settings.png'), soundOn: tex('button_sound_on.png'),
      soundOff: tex('button_sound_off.png'), fullscreen: tex('button_fullscreen.png'), exit: tex('button_exit.png') },
    isMuted, () => setMuted(!isMuted()), fullscreenToggle(), () => this.openExit(),
    { accent: theme.accent, text: theme.text, background: theme.background,
      ...(theme.ui.style === 'ink' ? { ring: theme.ui.ink, disc: theme.ui.panel } : {}) }, motion,
    () => { if (!this.busy()) this.historyPanel.show(); });
    this.options.on('pointertap', () => sound('click'));

    this.addChild(this.menu, this.table, this.screen, this.shower, this.bigWin, this.result, this.exitDialog, this.options);
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
    this.shower.layout(view);
    // the board with the wheel right under it, as one column; portrait: the bar below them, landscape: beside them
    const visibleH = view.stageH - 2 * view.offsetY;
    const boardX = this.bar instanceof ConsoleControls ? this.boardX(view) : view.stageW * 0.32;
    // a chest pick's shelf may take most of what is on screen across
    this.wheel.setRoom(Math.min(view.stageW - 2 * view.offsetX - 24, view.landscape ? visibleH * 1.25 : Infinity), !view.landscape, visibleH * 0.78);
    if (view.landscape) {
      // over the middle of the board, clear of the console (a photo revolver's dial is about 3.5 radii tall)
      // a tesla ring is only about 2.7 radii tall, so it can be bigger
      const photo = this.bar instanceof ConsoleControls, ring = photo && this.ring;
      this.wheel.place(boardX, view.offsetY + visibleH * (photo ? 0.42 : 0.62),
        Math.min(ring ? 150 : photo ? 112 : 170, visibleH * (ring ? 0.22 : photo ? 0.17 : 0.28)));
    }
    // a chest pick's two shelves stand in the screen's upper middle
    else if (this.bar instanceof ConsoleControls) this.wheel.place(view.stageW / 2,
      view.offsetY + (this.gacha ? visibleH * 0.42 : this.tombRaid ? visibleH * 0.5 : this.chestPick ? visibleH * 0.3 : 150 + visibleH * 0.19),
      this.gacha ? Math.min(140, visibleH * 0.2, (view.stageW - 2 * view.offsetX) * 0.42) : Math.min(108, view.stageW * 0.25));
    else this.wheel.place(view.stageW / 2, view.stageH * 0.36, Math.min(175, visibleH * 0.19));
    if (this.bar instanceof ConsoleControls) {
      this.layoutConsole(view, this.bar);
    } else if (view.landscape) {
      // the column right of the board and wheel (they end at about 0.6 of the width)
      const left = view.stageW * 0.6, right = view.stageW - view.offsetX - 8;
      this.bar.layout(view, (left + right) / 2, right - left);
    } else {
      this.bar.layout(view, view.stageW / 2);
    }
    this.ambient.layout(view);
    this.screen.layout(view);
    this.arsenal?.layout(view);
    // the WIN banner over the top of the board
    if (view.landscape) this.banner.position.set(boardX, view.offsetY + (view.stageH - 2 * view.offsetY) * 0.2);
    else this.banner.position.set(view.stageW / 2, view.stageH * 0.17);
    this.ticker.layout(view);
    this.meter.layout(view);
    this.options.layout(view);
  }

  /**
   * The console's parts: wide, in the board's own console (where the frame picture shows its panels, in its 1x
   * pixels); tall, in rows under the board. Credits and the win: top right.
   */
  private layoutConsole(view: Viewport, bar: ConsoleControls): void {
    const at = (x: number, y: number) => {
      const p = this.board.framePoint(x, y, view.landscape);
      return p ? this.table.toLocal(p) : { x: 0, y: 0 };
    };
    const rect = (x0: number, y0: number, x1: number, y1: number) => {
      const a = at(x0, y0), b = at(x1, y1);
      return new Rectangle(a.x, a.y, b.x - a.x, b.y - a.y);
    };
    if (view.landscape && this.consoleBar) {
      // a character leaning on it: the bar's strip runs on under him, to the screen's edge
      bar.layoutBar(view, this.barBox(view), this.leaning(view) ? view.stageW - view.offsetX + 12 : null);
      return;
    }
    if (view.landscape) {
      const w = Math.min(260, view.stageW * 0.2);
      const r = this.consoleRects;
      if (!r) return;
      const box = (q: [number, number, number, number]) => rect(q[0], q[1], q[2], q[3]);
      bar.layout(view, { left: box(r.left), centre: box(r.centre), right: box(r.right),
        credits: new Rectangle(view.stageW - view.offsetX - w - 120, view.offsetY + 14, w, 116) });
    } else {
      bar.layout(view, null, at(384, this.phoneTop).y + 6);
    }
  }

  /** A puff of vent steam every few seconds at one side of the screen, rising and fading (only while it is seen). */
  private ventSteam(steam: Texture): void {
    const puff = () => {
      gsap.delayedCall(random(2.5, 5), puff);
      const view = this.view;
      if (!view || this.page !== 'game' || this.vents.children.length >= 4) return;
      const visibleW = view.stageW - 2 * view.offsetX, visibleH = view.stageH - 2 * view.offsetY;
      const left = Math.random() < 0.5;
      const cloud = new Sprite({ texture: steam, anchor: { x: 0.5, y: 1 } });
      cloud.position.set(view.offsetX + visibleW * (left ? random(0.04, 0.16) : random(0.84, 0.96)), view.offsetY + visibleH * random(0.7, 0.9));
      cloud.scale.set(random(0.35, 0.55));
      cloud.alpha = 0;
      this.vents.addChild(cloud);
      gsap.timeline({ onComplete: () => cloud.destroy() })
        .to(cloud, { alpha: random(0.35, 0.55), duration: 0.6, ease: 'sine.out' })
        .to(cloud, { y: cloud.y - visibleH * 0.18, duration: 3.2, ease: 'sine.out' }, 0)
        .to(cloud.scale, { x: cloud.scale.x * 1.5, y: cloud.scale.y * 1.5, duration: 3.2 }, 0)
        .to(cloud, { alpha: 0, duration: 1.8, ease: 'sine.in' }, 1.4);
    };
    gsap.delayedCall(1.5, puff);
  }

  /** A wide screen's control bar (a theme with console.bar): along the bottom, under the board, left of the character. */
  private barBox(view: Viewport): Rectangle {
    const visibleW = view.stageW - 2 * view.offsetX, visibleH = view.stageH - 2 * view.offsetY;
    const h = Math.min(150, visibleH * 0.19);
    const w = Math.min(this.character ? visibleW * 0.76 : visibleW * 0.9, h * 9);
    return new Rectangle(this.boardX(view) - w / 2, view.offsetY + visibleH - h - 10, w, h);
  }

  /** A big win splashes blood over the screen: a few big splatters (more for a bigger win) that run down and fade. */
  private bloodScreen(level: string): void {
    const view = this.view;
    if (!view) return;
    const visibleW = view.stageW - 2 * view.offsetX, visibleH = view.stageH - 2 * view.offsetY;
    const count = level === 'EPIC WIN' ? 10 : level === 'MEGA WIN' ? 7 : 4;
    for (let k = 0; k < count; k++) {
      gsap.delayedCall(k * 0.09, () => splatter(this.screen, view.offsetX + visibleW * random(0.05, 0.95), view.offsetY + visibleH * random(0.05, 0.8),
        Math.min(visibleW, visibleH) * random(0.28, 0.5), random(2.8, 4), 0.35));
    }
  }

  /** On this screen the character leans on the control bar (a wide screen, a theme whose character leans). */
  private leaning(view: Viewport): boolean {
    return !!this.character && view.landscape && this.consoleBar && this.characterLean !== null;
  }

  /** Where a console board's middle is across: the middle of the screen, or left of the character beside it. */
  private boardX(view: Viewport): number {
    return this.character && view.landscape ? view.offsetX + (view.stageW - 2 * view.offsetX) * 0.41 : view.stageW / 2;
  }

  /** The board's place: room for the wheel and the control bar. */
  private layoutBoard(view: Viewport): void {
    // the wheel only shows at the end of a round with spins (over the board), so the board has the room
    const visibleH = view.stageH - 2 * view.offsetY;
    if (this.bar instanceof ConsoleControls) {
      // the board and its console, centred and as big as the screen allows (wide; with a character, left of him); the
      // board on top (tall)
      const visibleW = view.stageW - 2 * view.offsetX;
      this.lines?.layout(view, this.boardX(view));
      if (this.character && this.leaning(view)) {
        // leaning on the bar's right end: the underside of his arm on its top, his elbow just inside its end
        const box = this.barBox(view), c = this.character;
        const height = visibleH * this.characterHeight, frameH = height * 0.94, frameW = frameH * c.aspect;
        const left = box.x + box.width - frameW * 0.2;
        const [fx, fy] = this.characterFeet;
        c.place(left + frameW * fx, box.y + (fy - (this.characterLean as number)) * frameH, height);
        this.table.removeChild(c);
        this.table.addChildAt(c, this.table.getChildIndex(this.board));
      } else if (this.character) {
        if (view.landscape) this.character.place(view.offsetX + visibleW * 0.885, view.offsetY + visibleH * 0.95, visibleH * this.characterHeight);
        // a phone: where the theme puts him, in front of the board (or behind it); or in the top right corner, beside
        // the pyramid's slope, his lower half behind the board
        else if (this.characterPhone) this.character.place(view.offsetX + visibleW * this.characterPhone.x, view.offsetY + visibleH * this.characterPhone.feet,
          visibleH * this.characterPhone.height);
        else this.character.place(view.offsetX + visibleW * 0.86, view.offsetY + visibleH * 0.5, visibleH * 0.42);
        // (behind the board, or in front of it)
        const front = !view.landscape && !!this.characterPhone && !this.characterPhoneBehind;
        this.table.removeChild(this.character);
        this.table.addChildAt(this.character, this.table.getChildIndex(this.board) + (front ? 1 : 0));
      }
      // with its own control bar: the board above it, without its console strip
      const barH = this.consoleBar && view.landscape ? this.barBox(view).height + 16 : 0;
      this.board.layout(view, view.landscape
        ? { x: this.boardX(view), y: view.offsetY + (visibleH - barH) * 0.5 + 4, fit: 1, bare: this.consoleBar,
          room: { w: this.character ? visibleW * 0.74 : view.stageW * 0.78, h: (visibleH - barH) * 0.94 } }
        : { x: view.stageW / 2, y: view.offsetY + 100 + visibleH * 0.25, fit: 1, room: { w: view.stageW - 2 * view.offsetX - 8, h: visibleH * 0.5 } });
      return;
    }
    this.board.layout(view, view.landscape ? { x: view.stageW * 0.32, y: view.offsetY + visibleH * 0.5, fit: 1 }
      : { x: view.stageW / 2, y: view.stageH * 0.37, fit: 1 });
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
    this.roundWin = 0;
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

  /** What a ball costs with a power-up on the board in play (x the bet), or null when it is not on sale. */
  private powerPrice(power: string): number | null {
    return this.session.config.mega?.powers?.[power]?.[`${this.rows}:${this.risk}`] ?? null;
  }

  /** What the next round costs: bet x balls, times the armed power-up's price (rounded up to the cent, as the server). */
  private roundCost(): number {
    const price = this.power ? this.powerPrice(this.power) : null;
    const plain = round2(this.bet * this.balls);
    return price ? Math.ceil(plain * price * 100 - 1e-6) / 100 : plain;
  }

  private openArsenal(): void {
    if (!this.arsenal || this.busy()) return;
    sound('click');
    this.arsenal.setPrices(Object.fromEntries(POWER_IDS.map(id => {
      const times = this.powerPrice(id);
      const plain = round2(this.bet * this.balls);
      return [id, times ? { times, cost: this.money(Math.ceil(plain * times * 100 - 1e-6) / 100) } : null];
    })));
    this.arsenal.setArmed(this.power);
    if (this.view) this.arsenal.layout(this.view);
    this.arsenal.show();
  }

  /** Arms a power-up for the next round (null: none). */
  private arm(id: string | null): void {
    this.power = id && this.powerPrice(id) ? id : null;
    if (id) sound('golden');
    this.arsenal?.setArmed(this.power);
    if (this.bar instanceof ConsoleControls) this.bar.setArmed(this.power ? this.powerNames[this.power] : null);
    this.refreshPanel();
    this.idleStatus();
  }

  /** A quick bet on the console: this bet per ball. */
  private pickBet(value: number): void {
    if (this.busy() || !this.ladder.includes(value) || value === this.bet) return;
    sound('click');
    this.bet = value;
    this.refreshPanel();
    this.idleStatus();
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
    if (this.view) {
      this.layoutBoard(this.view);
      // the console's controls sit where the board's frame is: placed again with it
      if (this.bar instanceof ConsoleControls) this.layoutConsole(this.view, this.bar);
    }
  }

  private money(value: number): string {
    return formatAmount(this.session.currencySymbol, value);
  }

  private refreshPanel(): void {
    this.bar.setBalance(this.money(Math.max(0, round2(this.balance - this.pendingWins))));
    this.bar.setTotalBet(this.money(this.roundCost()));
    this.bar.setWin(this.money(this.roundWin));
    this.bar.setRisk(RISKS[this.risk]);
    this.bar.setBalls(this.balls, this.money(this.bet));
    this.bar.setRows(this.rows);
  }

  private idleStatus(): void {
    this.statusTimer?.kill();
    if (!this.busy()) this.board.setStatus((this.power ? this.powerNames[this.power] + ' · ' : '') + 'PLAY ' + this.money(this.roundCost()));
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
      // a win of 10x the round's cost or more stops autoplay (the big-win screen comes from 3x)
      if (round.totalBet > 0 && round.totalWin >= 10 * round.totalBet) { this.stopHolding(); break; }
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
    const cost = this.roundCost();
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
      round = await playRound(this.rows, this.risk, this.bet, this.balls, this.power);
      // a power-up is for one round
      if (this.power) this.arm(null);
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
    // WIN starts from 0 for this round and counts up as its balls and spins land
    const seq = ++this.roundSeq;
    this.roundWin = 0;
    const land = (win: number) => {
      this.pendingWins = Math.max(0, round2(this.pendingWins - win));
      if (seq === this.roundSeq) this.roundWin = round2(this.roundWin + win);
      this.refreshPanel();
    };
    const count = round.slots.length;
    const drops: Promise<void>[] = [];
    let streak = 0;
    let spinsWon = 0;   // the SPINS counter goes up as each ball lands in a SPIN slot
    const spinsText = () => (spinsWon === 0 ? '' : spinsWon === 1 ? ' · 1 SPIN' : ` · ${spinsWon} SPINS`);
    const spinBalls = new Set((round.spins ?? []).map(spin => spin.ball));
    const megaBalls = new Map((round.megaBalls ?? []).map(m => [m.ball, m.multiplier]));
    // this round's gates, and the pegs its MEGA BALLs smash (the balls after one fall through the holes)
    if (round.gateLayout) this.board.showGates(round.gateLayout);
    this.board.restorePegs();
    const smashRows = this.session.config.mega?.smashRows?.[String(this.rows)] ?? [];
    const holes = new Set<number>();
    // a round bought with a power-up is announced once; then its MEGA BALLs are quiet (one of many)
    const heavy = round.power === 'heavy';
    if (heavy) { this.board.stamp(`${this.heavyName} x${count}`); sound('huge'); this.screen.flash(0.35); }
    if (round.power && this.powerStamps[round.power]) { this.board.stamp(this.powerStamps[round.power]); sound('huge'); this.character?.point(); this.lines?.breeze(); }
    // a power-up by slot: the boosted prizes and their targets (the night) for this round
    if (round.slotFactors?.length) this.board.setPower(round.power ?? null, round.slotFactors);
    const crateOf = new Map((round.crates ?? []).map(c => [c.ball, c.multiplier]));
    for (let i = 0; i < count; i++) {
      if (i > 0) await wait((this.holding ? 0.12 : 0.25) * random(0.6, 1.3));
      if (!this.holding) {
        const left = count - i - 1;
        this.board.setStatus((left > 0 ? `PLAYING... (${left} BALLS)` : 'PLAYING...') + spinsText());
      }
      const multiplier = round.multipliers[i] ?? 0;
      const mega = megaBalls.get(i) ?? 1;
      const before = new Set(holes);
      const smash = mega > 1 && round.paths ? smashedPegs(round.paths[i], smashRows) : new Set<number>();
      smash.forEach(key => holes.add(key));
      if (mega > 1 && !heavy) { sound('huge'); this.screen.flash(0.35); } else if (!this.turbo) sound('drop', { volume: 0.5, rate: random(0.9, 1.12) });
      drops.push(this.board.dropBall(round.slots[i], {
        onPeg: pegSound,
        onLand: landing => {
          // coins out of the slot for a ball worth 3x or more (more coins, the more it paid), a fountain for a MEGA BALL
          if (mega > 1 || multiplier >= 3) {
            const at = this.toLocal(this.board.slotPoint(landing.slot));
            this.shower.burst(at.x, at.y, mega > 1 && !heavy ? 40 : Math.min(36, Math.round(6 + multiplier * 2)), mega > 1 && !heavy ? 950 : 650 + multiplier * 15);
          }
          if (mega > 1 && !heavy) {
            this.screen.flash(0.7);
            sound('jackpot');
          } else if (mega > 1) {
            sound('big', { volume: 0.7 });
          } else if (multiplier >= 5) this.screen.flash(multiplier >= 10 ? 0.6 : 0.3);
          const win = round.wins?.[i] ?? cents(bet * multiplier);
          land(win);
          // the character laughs at a ball that paid double or more
          if (multiplier >= 2 || mega > 1) this.character?.react('happy', multiplier >= 5 || mega > 1 ? 1.6 : 0.9);
          this.board.showAmount(landing.slot, this.money(win), multiplier > 1);
          this.ticker.add(this.money(win), multiplier);
          if (charging.includes(landing.prizeIndex)) { this.meter.charge(); sound('golden', { volume: 0.7 }); }
          if (spinBalls.has(i)) {
            this.wheel.setSpins(++spinsWon);
            if (!this.holding) this.board.setStatus(`PLAYING...${spinsText()}`);
          }
          landSound(multiplier, landing);
          // chain lightning: arcs jump from the bay to its neighbours (whose prizes it shares)
          if (round.power === 'chain') this.board.chainArcs(landing.slot);
          // winning balls in a row make a combo
          streak = multiplier > 1 ? streak + 1 : 0;
          this.board.combo(streak);
          this.ballsLeft = Math.max(0, this.ballsLeft - 1);
          this.bar.setPlaying(true, this.holding, this.ballsLeft);
        },
      }, round.paths?.[i] ?? null, mega, { holes: before, smash, quiet: heavy, crate: crateOf.get(i) }));
      // cluster shells (a pearl pair): the two decoys (the one) fall with it and fizzle in their own slots
      for (const decoy of round.decoys?.[i] ?? []) {
        drops.push(this.board.dropBall(decoy, {}, null, 1, { decoy: true }));
      }
    }
    await Promise.all(drops);
    this.board.combo(0);

    // core meltdown: the reactor's pressure gauge climbs to what it pays
    if (round.power === 'meltdown' && round.targets?.length && this.tombRaid) {
      // Pharaoh's Drop's Tomb Raid: each tomb door hides one of the raid's payouts; the player picks one
      await this.revealWheel();
      const pays = round.targets[0];
      const segments = TOMB_RAID.map(m => ({ multiplier: m, name: m === 20 ? 'MEGA' : m === 10 ? 'MAJOR' : null }));
      const win = round.targetWins?.[0] ?? 0;
      // the raid pays x the round's plain bet (the bet a ball times the balls)
      await this.wheel.turn(segments, Math.max(0, TOMB_RAID.indexOf(pays)), win,
        { gold: false, title: this.powerStamps.meltdown ?? 'BONUS', base: cents(bet * round.slots.length) });
      land(win);
      sound(pays >= 5 ? 'jackpot' : 'big');
      if (!(round.spins?.length || round.megaSpins?.length)) await this.hideWheel();
    } else if (round.power === 'meltdown' && round.targets?.length && this.meltdown) {
      if (this.view) this.meltdown.layout(this.view);
      const win = round.targetWins?.[0] ?? 0;
      await this.meltdown.play(round.targets[0], `x${round.targets[0]}  ${this.money(win)}`,
        () => { land(win); sound(round.targets![0] >= 5 ? 'jackpot' : 'big'); this.screen.flash(0.6); this.props?.wipe(); }, () => sound('riser', { volume: 0.7 }));
    } else if (round.targets?.length && this.tank) {
      // tank assault: the tank fires at its targets, each adding its win as it is hit
      if (this.view) this.tank.layout(this.view);
      const wins = round.targetWins ?? [];
      await this.tank.play(round.targets, i => `x${round.targets![i]}  ${this.money(wins[i] ?? 0)}`,
        i => { land(wins[i] ?? 0); sound(round.targets![i] >= 2 ? 'big' : 'score'); }, () => sound('huge', { volume: 0.7 }));
    } else if (round.targetWins?.length) {
      for (const win of round.targetWins) land(win);
    }

    // every ball in a SPIN slot turns the wheel under the board, then a full meter's MEGA SPINs turn it gold
    const config = this.session.config.mega;
    const spins = round.spins ?? [];
    if (spinsWon !== spins.length) this.wheel.setSpins(spins.length);
    const onWheel = spins.length + (round.megaSpins?.length ?? 0) > 0;
    if (onWheel) await this.revealWheel();
    for (let i = 0; i < spins.length; i++) {
      const spin = spins[i];
      if (!config?.wheel?.[spin.segment]) { land(spin.win); continue; }
      sound('spin');
      // the spin multiplies that ball's win: shown as "ball's win × segment"
      const base = round.wins?.[spin.ball] ?? cents(bet * (round.multipliers[spin.ball] ?? 0));
      await this.wheel.turn(config.wheel, spin.segment, spin.win, { gold: false, title: 'BALL WIN', base });
      this.wheel.setSpins(spins.length - i - 1);
      land(spin.win);
      sound(config.wheel[spin.segment].name ? 'jackpot' : 'coins');
    }
    for (const mega of round.megaSpins ?? []) {
      if (!config?.megaWheel?.[mega.segment]) { land(mega.win); continue; }
      this.meter.set(true, 0, this.session.meter?.of ?? 0);
      sound('huge');
      await this.wheel.turn(config.megaWheel, mega.segment, mega.win, { gold: true, title: 'MEGA SPIN POT', base: mega.stake });
      land(mega.win);
      sound(config.megaWheel[mega.segment].name ? 'jackpot' : 'coins');
    }
    if (onWheel) await this.hideWheel();
    if (round.slotFactors?.length) this.board.setPower(null, []);
    // the pegs a MEGA BALL blew away come back once the round is over
    this.board.restorePegs();

    // the server's meter count is the one that counts
    if (round.meter) this.applyMeter(round.meter);
    // WIN ends on the round's total as the server paid it (a capped round pays less than its balls)
    if (seq === this.roundSeq) this.roundWin = round.totalWin;
    this.refreshPanel();
    // every round that pays back its bet or more: the win counted up in gold over the board (10x and more: the big win)
    const lessThanBet = this.session.config.quietSmallWins && round.totalWin < round.totalBet;
    if (round.totalWin >= round.totalBet && round.totalWin > 0 && !lessThanBet && !winLevel(round.totalWin, round.totalBet) && this.inAir <= 1) {
      sound('coins', { volume: 0.7 });
      if (!this.holding && !this.turbo) {
        this.shower.burst(this.banner.x, this.banner.y, 26, 800);
        this.shower.pour(10, 0, 0, 0, 1.4);
      }
      await this.banner.show(round.totalWin, round.totalWin / round.totalBet, value => this.money(value), this.holding || this.turbo);
    }
    if (!this.holding && this.inAir <= 1) {
      // where the market says so, a round that pays back less than its bet is not shown as a win (review 7.4)
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
      // less than the bet back: a shrug; the bet or more (short of a big win): a laugh
      if (round.totalWin < round.totalBet) this.character?.shrug();
      else if (!winLevel(round.totalWin, round.totalBet)) this.character?.react('happy', 1.4);
    }
    // a round that paid 10x its cost or more gets a celebration (the amount is the server's; tap to skip)
    const level = winLevel(round.totalWin, round.totalBet);
    if (level) sound('bigWin');
    if (level) {
      this.character?.cheer();
      this.lines?.flap();
      this.props?.flare();
      this.props?.wipe();
      this.board.flareFire();
      if (this.bloody) this.bloodScreen(level);
      await this.bigWin.show(level, round.totalWin, value => this.money(value), round.totalBet);
    }
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
      const all = round.slots.map((slot, i) => ({ slot, win: round.wins?.[i] ?? 0,
        paid: (round.multipliers[i] ?? 0) > 1, multiplier: round.multipliers[i] ?? 0, path: round.paths?.[i] ?? null }));
      const drops: Promise<void>[] = [];
      for (let i = 0; i < all.length; i++) {
        if (i > 0) await wait(0.4);
        const ball = all[i];
        drops.push(this.board.dropBall(ball.slot, {
          onPeg: pegSound,
          onLand: landing => { this.board.showAmount(landing.slot, this.money(ball.win), ball.paid); landSound(ball.multiplier, landing); },
        }, ball.path));
      }
      await Promise.all(drops);
      const config = this.session.config.mega;
      const onWheel = (round.spins?.length ?? 0) + (round.megaSpins?.length ?? 0) > 0;
      if (onWheel) await this.revealWheel();
      for (const spin of round.spins ?? []) {
        if (config?.wheel?.[spin.segment]) await this.wheel.turn(config.wheel, spin.segment, spin.win, { gold: false, title: 'SPIN' });
      }
      for (const mega of round.megaSpins ?? []) {
        if (config?.megaWheel?.[mega.segment]) await this.wheel.turn(config.megaWheel, mega.segment, mega.win, { gold: true, title: 'MEGA SPIN' });
      }
      if (onWheel) await this.hideWheel();
      this.board.setStatus('REPLAY · WON ' + this.money(round.totalWin));
      await wait(2.5);
    } finally {
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
    this.ambient.update(deltaMs);
    this.wheel.update(deltaMs);
    this.character?.update(deltaMs, this.busy(), this.board.lowestBall());
    this.lines?.update(deltaMs);
    this.props?.update(deltaMs);
  }

  /** The wheel pops up over the board (dimmed) for a round's spins. */
  private async revealWheel(): Promise<void> {
    if (this.wheel.visible) return;
    this.wheel.visible = true;
    // on a phone the bonus fills the top of the screen where he stands: he steps out of sight until it is done
    if (this.view && !this.view.landscape) this.character?.setAway(true);
    this.character?.point();
    this.props?.wipe();
    sound('spin', { volume: 0.6 });
    gsap.to(this.board, { alpha: 0.35, duration: 0.25 });
    gsap.to([this.meter, this.ticker], { alpha: 0, duration: 0.2 });
    // a chest pick on a phone stands over the console too: the console steps back under it
    const over = (this.chestPick || this.gacha) && !!this.view && !this.view.landscape;
    this.table.setChildIndex(this.wheel, this.table.getChildIndex(over ? this.bar : this.board) + (over ? 0 : 1));
    if (over && this.view) {
      // the board and the console fade right out under a dark shade, and the bonus stands over it alone
      // (well past the stage's edges: the background runs on beyond it on a screen of another shape)
      this.pickShade.clear().rect(-this.view.stageW, -this.view.stageH, this.view.stageW * 3, this.view.stageH * 3).fill('#000000');
      this.table.addChildAt(this.pickShade, this.table.getChildIndex(this.wheel));
      gsap.fromTo(this.pickShade, { alpha: 0 }, { alpha: 0.72, duration: 0.25 });
      gsap.to([this.bar, this.board], { alpha: 0, duration: 0.25 });
    }
    gsap.fromTo(this.wheel, { alpha: 0 }, { alpha: 1, duration: 0.2 });
    await new Promise<void>(resolve => gsap.fromTo(this.wheel.scale, { x: 0.4, y: 0.4 }, { x: 1, y: 1, duration: 0.35, ease: 'back.out(1.8)', onComplete: resolve }));
  }

  /** ...and goes away again when they are done. */
  private async hideWheel(): Promise<void> {
    gsap.to([this.board, this.meter, this.ticker, this.bar], { alpha: 1, duration: 0.25 });
    if (this.pickShade.parent) gsap.to(this.pickShade, { alpha: 0, duration: 0.25, onComplete: () => { this.pickShade.removeFromParent(); } });
    this.character?.setAway(false);
    await new Promise<void>(resolve => gsap.to(this.wheel, { alpha: 0, duration: 0.25, onComplete: resolve }));
    this.wheel.visible = false;
    this.wheel.alpha = 1;
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
