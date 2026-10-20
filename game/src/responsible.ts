import { reportShown, type GameConfig, type ShownEvent } from './api';

const START_KEY = 'plinkoSessionStart';

/**
 * The market's responsible gambling options (review 7.4), all set by the casino in the admin: a clock with the time
 * played and the net result of this session, and a reality check every so many minutes that pauses the game until
 * the player chooses to continue or to stop. Plain HTML over the game, readable by screen readers.
 * <p>
 * The server is told when each is shown and when the player continues (kept as proof where a market requires it; there
 * the server refuses rounds while a reality check is due, and the game shows it: {@link checkNow}).
 */
export class ResponsiblePlay {
  private readonly started: number;
  private net = 0;
  private readonly clock: HTMLDivElement | null = null;
  private dialog: HTMLDivElement | null = null;
  private nextCheck: number;
  /** Everyone waiting for the player's answer to the reality check on screen. */
  private waiting: ((go: boolean) => void)[] = [];
  /** The report that the check on screen was shown: the "continued" report is sent after it. */
  private shownReported: Promise<void> = Promise.resolve();
  /** The player answered and the answer is being reported: still no rounds. */
  private answering = false;

  constructor(private readonly config: GameConfig, sessionId: string, private readonly money: (value: number) => string,
              private readonly exit: () => void, private readonly report: (type: ShownEvent) => Promise<void> = reportShown) {
    // the session's start survives a reload of the page (this tab only)
    let started = Date.now();
    try {
      const saved = JSON.parse(window.sessionStorage.getItem(START_KEY) ?? 'null') as { id: string; at: number; net: number } | null;
      if (saved && saved.id === sessionId) { started = saved.at; this.net = saved.net; }
    } catch { /* a new start */ }
    this.started = started;
    this.save(sessionId);
    this.sessionId = sessionId;
    const every = this.every();
    this.nextCheck = every > 0 ? started + every * (Math.floor((Date.now() - started) / every) + 1) : Infinity;
    if (config.sessionClock) {
      this.clock = document.createElement('div');
      this.clock.className = 'session-clock';
      this.clock.setAttribute('role', 'status');
      document.body.append(this.clock);
      this.tick();
      window.setInterval(() => this.tick(), 1000);
      void this.send('SESSION_CLOCK_SHOWN');
    }
    if (every > 0) this.dialog = this.makeDialog();
  }

  private readonly sessionId: string;

  private every(): number {
    return (this.config.realityCheckMinutes ?? 0) * 60_000;
  }

  /** A report to the server; the game goes on whatever its answer (the server asks again when it needs one). */
  private send(type: ShownEvent): Promise<void> {
    return this.report(type).catch(() => undefined);
  }

  private makeDialog(): HTMLDivElement {
    const dialog = document.createElement('div');
    dialog.className = 'reality-check';
    dialog.hidden = true;
    dialog.setAttribute('role', 'alertdialog');
    dialog.setAttribute('aria-modal', 'true');
    document.body.append(dialog);
    return dialog;
  }

  private save(sessionId: string): void {
    try { window.sessionStorage.setItem(START_KEY, JSON.stringify({ id: sessionId, at: this.started, net: this.net })); } catch { /* kept in memory */ }
  }

  /** A round the server played: its cost and what it paid. */
  addRound(bet: number, win: number): void {
    this.net = Math.round((this.net + win - bet) * 100) / 100;
    this.save(this.sessionId);
    this.tick();
  }

  /** Waits while a reality check is due or showing; true if the player chose to go on. */
  async allowed(): Promise<boolean> {
    if (this.pausing) return new Promise<boolean>(resolve => this.waiting.push(resolve));
    if (!this.dialog || Date.now() < this.nextCheck) return true;
    return new Promise<boolean>(resolve => this.show(resolve));
  }

  /** The server says a reality check is due (REALITY_CHECK_DUE): it is shown now, unless it already is. */
  checkNow(): void {
    this.dialog ??= this.makeDialog();
    if (!this.pausing) this.show(() => undefined);
  }

  /** True while the reality check is on screen (the game takes no rounds). */
  get pausing(): boolean {
    return (!!this.dialog && !this.dialog.hidden) || this.answering;
  }

  setVisible(visible: boolean): void {
    if (this.clock) this.clock.hidden = !visible;
  }

  private played(): string {
    const minutes = Math.floor((Date.now() - this.started) / 60_000);
    const h = Math.floor(minutes / 60);
    return h > 0 ? `${h} h ${minutes % 60} min` : `${minutes} min`;
  }

  private result(): string {
    return (this.net < 0 ? '−' : this.net > 0 ? '+' : '') + this.money(Math.abs(this.net));
  }

  private tick(): void {
    if (this.clock) this.clock.textContent = `Played ${this.played()} · Net ${this.result()}`;
  }

  private show(resolve: (go: boolean) => void): void {
    const dialog = this.dialog!;
    this.waiting.push(resolve);
    dialog.replaceChildren();
    const box = document.createElement('div');
    const title = document.createElement('h2');
    title.textContent = 'Reality check';
    const text = document.createElement('p');
    text.textContent = `You have played for ${this.played()}. Your net result this session: ${this.result()}.`;
    const go = document.createElement('button');
    go.type = 'button';
    go.textContent = 'Continue playing';
    const stop = document.createElement('button');
    stop.type = 'button';
    stop.className = 'secondary';
    stop.textContent = 'Stop playing';
    let answered = false;
    const done = async (keepPlaying: boolean) => {
      if (answered) return;
      answered = true;
      this.answering = true;
      dialog.hidden = true;
      const every = this.every();
      this.nextCheck = every > 0 ? Date.now() + every : Infinity;
      // reported in order, before the next round is asked for: the server then knows the player continued
      if (keepPlaying) {
        await this.shownReported;
        await this.send('REALITY_CHECK_CONTINUED');
      } else {
        this.exit();
      }
      this.answering = false;
      const waiting = this.waiting;
      this.waiting = [];
      waiting.forEach(answer => answer(keepPlaying));
    };
    go.addEventListener('click', () => void done(true));
    stop.addEventListener('click', () => void done(false));
    box.append(title, text, go, stop);
    dialog.append(box);
    dialog.hidden = false;
    this.shownReported = this.send('REALITY_CHECK_SHOWN');
    go.focus();
  }
}
