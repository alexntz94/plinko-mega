// The ARSENAL: the power-ups a player can buy for the next round, as cards on a steel panel over the game. Each card
// says what it does and what the round will cost with it (the server's price for this board and risk); a tap arms it
// for the next drop (another tap disarms it). Power-ups still to come are shown, locked.

import { Container, Graphics, Rectangle, Sprite, Text, type Texture } from 'pixi.js';
import { gsap } from 'gsap';
import type { Viewport } from './viewport';
import { label, fit } from './ui';

export interface ArsenalCard { id: string; name: string; text: string; ready: boolean }

/** Every power-up of the arsenal, the ones on sale first. */
export const ARSENAL: ArsenalCard[] = [
  { id: 'heavy', name: 'HEAVY SHELLS', text: 'Every ball is a MEGA BALL (x2 to x10)', ready: true },
  { id: 'cluster', name: 'CLUSTER SHELLS', text: 'Each ball splits in 3 - the best landing pays', ready: true },
  { id: 'airstrike', name: 'PRECISION AIRSTRIKE', text: 'Pick a zone: its slots pay x2', ready: false },
  { id: 'crates', name: 'CHAIN REACTION', text: 'Ammo crates on the board multiply wins', ready: false },
  { id: 'missile', name: 'MISSILE STRIKE', text: 'The board reshuffles with boosted prizes', ready: false },
  { id: 'tank', name: 'TANK ASSAULT', text: 'Buy the bonus game', ready: false },
  { id: 'night', name: 'NIGHT RAID', text: 'High-risk night board: bigger edges', ready: false },
];

interface CardView { card: ArsenalCard; root: Container; art: Sprite; lit: Graphics; price: Text }

export class ArsenalPanel extends Container {
  private readonly dim = new Graphics();
  private readonly title: Text;
  private readonly hint: Text;
  private readonly cards: CardView[] = [];
  private armed: string | null = null;

  /** prices: what a ball costs with each power-up (x the bet) on the board in play, or null when not on sale. */
  constructor(panel: Texture, private readonly look: { gold: string; text: string },
              private readonly onPick: (id: string | null) => void) {
    super();
    this.title = label('ARSENAL', 34, 'center', { fill: look.gold, stroke: { color: '#000000', width: 5, join: 'round' } });
    this.title.anchor.set(0.5, 0.5);
    this.hint = label('Pick a power-up for your next drop', 15, 'center', { fill: look.text });
    this.hint.anchor.set(0.5, 0.5);
    this.addChild(this.dim, this.title, this.hint);
    for (const card of ARSENAL) {
      const root = new Container();
      const art = new Sprite({ texture: panel, anchor: 0.5 });
      const lit = new Graphics();
      const name = label(card.name, 17, 'center', { fill: card.ready ? look.gold : '#8a8f86', stroke: { color: '#000000', width: 3 } });
      name.anchor.set(0.5, 0.5);
      name.y = -24;
      const text = label(card.text, 12, 'center', { fill: card.ready ? look.text : '#8a8f86' });
      text.anchor.set(0.5, 0.5);
      const price = label(card.ready ? '' : 'COMING SOON', 15, 'center', { fill: card.ready ? '#ffffff' : '#6f746c' });
      price.anchor.set(0.5, 0.5);
      price.y = 24;
      root.addChild(art, lit, name, text, price);
      root.alpha = card.ready ? 1 : 0.6;
      if (card.ready) {
        root.eventMode = 'static';
        root.cursor = 'pointer';
        root.on('pointertap', () => this.pick(card.id));
      }
      this.cards.push({ card, root, art, lit, price });
      this.addChild(root);
    }
    // a tap outside the cards closes it
    this.dim.eventMode = 'static';
    this.dim.on('pointertap', () => this.close());
    this.visible = false;
  }

  get open(): boolean { return this.visible; }

  /** The prices on the board in play: "x2.69 . $26.90 a round" (null: not on sale here). */
  setPrices(prices: Record<string, { times: number; cost: string } | null>): void {
    for (const view of this.cards) {
      if (!view.card.ready) continue;
      const p = prices[view.card.id];
      view.price.text = p ? `x${p.times.toFixed(2)}  ·  ${p.cost} A ROUND` : 'NOT ON SALE';
      view.root.eventMode = p ? 'static' : 'none';
      view.root.alpha = p ? 1 : 0.5;
    }
  }

  setArmed(id: string | null): void {
    this.armed = id;
    for (const view of this.cards) {
      const on = view.card.id === id;
      view.lit.visible = on;
      view.art.tint = on ? '#ffcf6a' : '#ffffff';
    }
  }

  layout(view: Viewport): void {
    this.dim.clear().rect(0, 0, view.stageW, view.stageH).fill({ color: '#000000', alpha: 0.72 });
    this.hitArea = new Rectangle(0, 0, view.stageW, view.stageH);
    const cols = view.landscape ? 4 : 2;
    const w = Math.min(view.landscape ? 300 : (view.stageW - 36) / 2, 300), h = w * 0.42;
    const rows = Math.ceil(this.cards.length / cols);
    const top = view.stageH / 2 - (rows * (h + 12)) / 2 + h / 2 + 20;
    this.title.position.set(view.stageW / 2, top - h / 2 - 62);
    this.hint.position.set(view.stageW / 2, top - h / 2 - 30);
    this.cards.forEach((v, i) => {
      const r = Math.floor(i / cols), c = i % cols;
      const inRow = Math.min(cols, this.cards.length - r * cols);
      v.root.position.set(view.stageW / 2 + (c - (inRow - 1) / 2) * (w + 12), top + r * (h + 12));
      v.art.width = w;
      v.art.height = h;
      v.lit.clear().roundRect(-w / 2 - 3, -h / 2 - 3, w + 6, h + 6, 8).stroke({ color: this.look.gold, width: 4 });
      v.lit.visible = v.card.id === this.armed;
      for (const t of v.root.children) if (t instanceof Text) fit(t, w * 0.9);
    });
  }

  show(): void {
    this.visible = true;
    gsap.fromTo(this, { alpha: 0 }, { alpha: 1, duration: 0.18 });
  }

  close(): void {
    if (!this.visible) return;
    gsap.to(this, { alpha: 0, duration: 0.15, onComplete: () => { this.visible = false; } });
  }

  private pick(id: string): void {
    const next = this.armed === id ? null : id;
    this.onPick(next);
    if (next) this.close();
  }
}
