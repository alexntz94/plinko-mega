import { roundHistory, type ApiError, type HistoryEntry } from './api';

const RISKS = ['Low', 'Medium', 'High'];

/**
 * The player's round history (review 7.5): their own rounds of the last 90 days, newest first, with each round's id for
 * support and a Replay button that plays its balls again on the board (no bet, nothing paid). Plain HTML over the game,
 * in the theme's colours, so it reads well and works with a keyboard and a screen reader.
 */
export class HistoryPanel {
  private readonly root: HTMLDivElement;
  private readonly list: HTMLOListElement;
  private readonly more: HTMLButtonElement;
  private readonly note: HTMLParagraphElement;
  private next: string | null = null;
  private loading = false;
  private static readonly INTRO = 'The last 90 days. Replay plays a round again on the board: nothing is bet or paid.';

  constructor(private readonly money: (value: number) => string, private readonly replay: (entry: HistoryEntry) => void) {
    this.root = document.createElement('div');
    this.root.className = 'history-panel';
    this.root.hidden = true;
    this.root.setAttribute('role', 'dialog');
    this.root.setAttribute('aria-modal', 'true');
    this.root.setAttribute('aria-label', 'Your rounds');
    const box = document.createElement('div');
    box.className = 'history-box';
    const head = document.createElement('div');
    head.className = 'history-head';
    const title = document.createElement('h2');
    title.textContent = 'Your rounds';
    const close = document.createElement('button');
    close.type = 'button';
    close.className = 'history-close';
    close.textContent = 'Close';
    close.addEventListener('click', () => this.hide());
    head.append(title, close);
    this.note = document.createElement('p');
    this.note.className = 'history-note';
    this.list = document.createElement('ol');
    this.list.className = 'history-list';
    this.more = document.createElement('button');
    this.more.type = 'button';
    this.more.className = 'history-more';
    this.more.textContent = 'Show older rounds';
    this.more.addEventListener('click', () => void this.load());
    box.append(head, this.note, this.list, this.more);
    this.root.append(box);
    this.root.addEventListener('click', event => { if (event.target === this.root) this.hide(); });
    this.root.addEventListener('keydown', event => { if (event.key === 'Escape') this.hide(); });
    document.body.append(this.root);
  }

  get open(): boolean {
    return !this.root.hidden;
  }

  show(): void {
    this.list.replaceChildren();
    this.next = null;
    // review N15.4: a "no rounds" or error note from an earlier opening doesn't stay
    this.note.textContent = HistoryPanel.INTRO;
    this.root.hidden = false;
    void this.load(true);
  }

  hide(): void {
    this.root.hidden = true;
  }

  private async load(first = false): Promise<void> {
    if (this.loading) return;
    this.loading = true;
    this.more.hidden = true;
    try {
      const page = await roundHistory(first ? null : this.next);
      this.next = page.next;
      page.rounds.forEach(entry => this.list.append(this.row(entry)));
      if (first && page.rounds.length === 0) this.note.textContent = 'No rounds in the last 90 days yet.';
      this.more.hidden = !this.next;
      if (first) (this.root.querySelector('.history-close') as HTMLButtonElement | null)?.focus();
    } catch (error) {
      const failure = error as ApiError;
      this.note.textContent = failure.code === 'SESSION_EXPIRED'
        ? 'Your game session has ended. Please relaunch the game from your casino account.'
        : 'The history could not be loaded. Please try again.';
    } finally {
      this.loading = false;
    }
  }

  private row(entry: HistoryEntry): HTMLLIElement {
    const item = document.createElement('li');
    const when = new Date(entry.time);
    const line = document.createElement('div');
    line.className = 'history-line';
    const what = document.createElement('span');
    what.textContent = `${when.toLocaleDateString()} ${when.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })} · `
      + `${entry.rows} rows · ${RISKS[entry.risk] ?? ''} · ${entry.balls} × ${this.money(entry.bet)}`;
    const won = document.createElement('strong');
    const win = entry.round?.totalWin ?? null;
    won.textContent = entry.status === 'TAMPERED' || entry.status === 'UNDER_REVIEW' ? 'Under review' : win === null ? '' : 'Won ' + this.money(win);
    line.append(what, won);
    const id = document.createElement('div');
    id.className = 'history-id';
    // every round's id, also for one that can't be replayed (review N15.4)
    id.textContent = 'Round ' + (entry.roundId ?? entry.round?.roundId ?? '');
    item.append(line, id);
    if (entry.round && entry.status !== 'TAMPERED' && entry.status !== 'UNDER_REVIEW') {
      const replay = document.createElement('button');
      replay.type = 'button';
      replay.textContent = 'Replay';
      replay.setAttribute('aria-label', `Replay the round of ${when.toLocaleString()}`);
      replay.addEventListener('click', () => { this.hide(); this.replay(entry); });
      item.append(replay);
    }
    return item;
  }
}
