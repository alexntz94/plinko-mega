// The ARSENAL: the power-ups a player can buy for the next round, as cards on a steel panel over the game. Each card
// says what it does and what the round will cost with it (the server's price for this board and risk); a tap arms it
// for the next drop (another tap disarms it).

import { Container, Graphics, NineSliceSprite, Rectangle, Sprite, Text, type Texture } from 'pixi.js';
import { gsap } from 'gsap';
import type { Viewport } from './viewport';
import { label, fit } from './ui';
import { framedPanel, type PanelArea } from './consoleControls';

/** zones: a card sold per zone (the airstrike): the ids of its zones and their names */
export interface ArsenalCard { id: string; name: string; text: string; ready: boolean; zones?: [string, string][] }

/** Bombs Away's arsenal. */
const WAR_CARDS: ArsenalCard[] = [
  { id: 'heavy', name: 'HEAVY SHELLS', text: 'Every ball is a MEGA BALL (x2 to x10)', ready: true },
  { id: 'cluster', name: 'CLUSTER SHELLS', text: 'Each ball splits in 3 - the best landing pays', ready: true },
  { id: 'airstrike', name: 'PRECISION AIRSTRIKE', text: 'Pick a zone: its slots pay x2', ready: true,
    zones: [['strike-left', 'LEFT'], ['strike-centre', 'CENTRE'], ['strike-right', 'RIGHT']] },
  { id: 'crates', name: 'CHAIN REACTION', text: '1 ball in 5 hits a crate: x2, x3 or x5', ready: true },
  { id: 'missile', name: 'MISSILE STRIKE', text: 'The outer slots pay x3', ready: true },
  { id: 'night', name: 'NIGHT RAID', text: 'Big prizes x3, small ones less', ready: true },
  { id: 'tank', name: 'TANK ASSAULT', text: 'A tank fires at 5 targets: x0.1 to x10 each', ready: true },
];

/** Reactor Drop's boosters (Overcharge is the server's heavy). */
const REACTOR_CARDS: ArsenalCard[] = [
  { id: 'heavy', name: 'OVERCHARGE', text: 'Every capsule is supercharged: x2 to x10', ready: true },
  { id: 'chain', name: 'CHAIN LIGHTNING', text: 'A capsule also wins half of each neighbouring bay', ready: true },
  { id: 'magnet', name: 'MAGNETIC FIELD', text: '70% of capsules pulled to your side: its bays pay x1.5', ready: true,
    zones: [['magnet-left', 'LEFT'], ['magnet-right', 'RIGHT']] },
  { id: 'meltdown', name: 'CORE MELTDOWN', text: 'The reactor overloads after the drop: x0.5 to x20', ready: true },
];

/** Lagoon Drop's boosters (Sunburst Surge is the server's heavy, Crosscurrent the magnet, Volcano Vault the meltdown). */
const LAGOON_CARDS: ArsenalCard[] = [
  { id: 'heavy', name: 'SUNBURST SURGE', text: 'Every pearl is sun-charged: x2 to x10', ready: true },
  { id: 'pair', name: 'PEARL PAIR', text: 'Each pearl splits in 2 - the better landing pays', ready: true },
  { id: 'magnet', name: 'CROSSCURRENT', text: '70% of pearls drift to your side: its bays pay x1.5', ready: true,
    zones: [['magnet-left', 'LEFT'], ['magnet-right', 'RIGHT']] },
  { id: 'meltdown', name: 'VOLCANO VAULT', text: 'The volcano erupts after the drop: x0.5 to x20', ready: true },
];

/** Pharaoh's Drop's relics (Scarab Fortune is the server's heavy, Twin Serpents the pair, Sirocco Draft the magnet,
 *  Tomb Raid the meltdown). */
const PHARAOH_CARDS: ArsenalCard[] = [
  { id: 'heavy', name: 'SCARAB FORTUNE', text: 'Every scarab is blessed: x2 to x10', ready: true },
  { id: 'pair', name: 'TWIN SERPENTS', text: 'Each scarab splits in 2 - the better landing pays', ready: true },
  { id: 'magnet', name: 'SIROCCO DRAFT', text: '70% of scarabs blown to your side: its bays pay x1.5', ready: true,
    zones: [['magnet-left', 'LEFT'], ['magnet-right', 'RIGHT']] },
  { id: 'meltdown', name: 'TOMB RAID', text: 'Raid the tomb after the drop: x0.5 to x20', ready: true },
];

/** Zombie War's loadout (Mega Charge is the server's heavy, Split Fuse the pair, Horde Rush the magnet, Locker Raid the
 *  meltdown). */
const ZOMBIE_CARDS: ArsenalCard[] = [
  { id: 'heavy', name: 'MEGA CHARGE', text: 'Every grenade is a mega bomb: x2 to x10', ready: true },
  { id: 'pair', name: 'SPLIT FUSE', text: 'Each grenade splits in 2 - the better landing pays', ready: true },
  { id: 'magnet', name: 'HORDE RUSH', text: '70% of grenades dragged to your side: its bays pay x1.5', ready: true,
    zones: [['magnet-left', 'LEFT'], ['magnet-right', 'RIGHT']] },
  { id: 'meltdown', name: 'LOCKER RAID', text: 'Crack an ammo locker after the drop: x0.5 to x20', ready: true },
];

/** Neon Nights' power-ups (Golden Gacha is the server's heavy, Twin Pop the pair, Side Magnet the magnet, Lucky Draw the
 *  meltdown). */
const NEON_CARDS: ArsenalCard[] = [
  { id: 'heavy', name: 'GOLDEN GACHA', text: 'Every ball turns gold: x2 to x10', ready: true },
  { id: 'pair', name: 'TWIN POP', text: 'Each ball splits in 2 - the better landing pays', ready: true },
  { id: 'magnet', name: 'SIDE MAGNET', text: '70% of balls pulled to your side: its bays pay x1.5', ready: true,
    zones: [['magnet-left', 'LEFT'], ['magnet-right', 'RIGHT']] },
  { id: 'meltdown', name: 'LUCKY DRAW', text: 'The gacha turns after the drop: x0.5 to x20', ready: true },
];

/** Candy Factory's power-ups (Golden Glaze is the server's heavy, Double Dip the pair, Candy Current the magnet, Sugar
 *  Rush the meltdown). */
const CANDY_CARDS: ArsenalCard[] = [
  { id: 'heavy', name: 'GOLDEN GLAZE', text: 'Every gumball turns gold: x2 to x10', ready: true },
  { id: 'pair', name: 'DOUBLE DIP', text: 'Each gumball splits in 2 - the better landing pays', ready: true },
  { id: 'magnet', name: 'CANDY CURRENT', text: '70% of gumballs pulled to your side: its bays pay x1.5', ready: true,
    zones: [['magnet-left', 'LEFT'], ['magnet-right', 'RIGHT']] },
  { id: 'meltdown', name: 'SUGAR RUSH', text: 'Pick a candy jar after the drop: x0.5 to x20', ready: true },
];

/** Shurda Plinko's power-ups (Golden Sock is the server's heavy, Pair of Socks the pair, Drawer Breeze the magnet,
 *  Laundry Day the meltdown). */
const SHURDA_CARDS: ArsenalCard[] = [
  { id: 'heavy', name: 'GOLDEN SOCK', text: 'Every sock ball turns gold: x2 to x10', ready: true },
  { id: 'pair', name: 'PAIR OF SOCKS', text: 'Each sock ball splits in 2 - the better landing pays', ready: true },
  { id: 'magnet', name: 'DRAWER BREEZE', text: '70% of balls blown to your side: its bays pay x1.5', ready: true,
    zones: [['magnet-left', 'LEFT'], ['magnet-right', 'RIGHT']] },
  { id: 'meltdown', name: 'LAUNDRY DAY', text: 'Pick a sock ball from the drawer after the drop: x0.5 to x20', ready: true },
];

/** Hell of a Deal's power-ups (Hellfire Mark is the server's heavy, Double Dealing the pair, Sulphur Draft the magnet,
 *  Closing Sale the meltdown). */
const HELL_CARDS: ArsenalCard[] = [
  { id: 'heavy', name: 'HELLFIRE MARK', text: 'Every ember marble burns gold: x2 to x10', ready: true },
  { id: 'pair', name: 'DOUBLE DEALING', text: 'Each marble splits in 2 - the better landing pays', ready: true },
  { id: 'magnet', name: 'SULPHUR DRAFT', text: '70% of marbles drawn to your side: its tickets pay x1.5', ready: true,
    zones: [['magnet-left', 'LEFT'], ['magnet-right', 'RIGHT']] },
  { id: 'meltdown', name: 'CLOSING SALE', text: 'Pick a sealed contract after the drop: x0.5 to x20', ready: true },
];

/** Disco Is Dead's power-ups (Gold Record is the server's heavy, Duet the pair, Bassline the magnet, Last Dance the
 *  meltdown). */
const DISCO_CARDS: ArsenalCard[] = [
  { id: 'heavy', name: 'GOLD RECORD', text: 'Every mirror ball goes gold: x2 to x10', ready: true },
  { id: 'pair', name: 'DUET', text: 'Each ball splits in 2 - the better landing pays', ready: true },
  { id: 'magnet', name: 'BASSLINE', text: '70% of balls pulled to your side: its tiles pay x1.5', ready: true,
    zones: [['magnet-left', 'LEFT'], ['magnet-right', 'RIGHT']] },
  { id: 'meltdown', name: 'LAST DANCE', text: 'The dance meter climbs after the drop: x0.5 to x20', ready: true },
];

const EXE_CARDS: ArsenalCard[] = [
  { id: 'heavy', name: 'GOLDEN PACKET', text: 'Every data packet goes gold: x2 to x10', ready: true },
  { id: 'pair', name: 'FORK BOMB', text: 'Each packet forks in 2 - the better landing pays', ready: true },
  { id: 'magnet', name: 'BACKDOOR', text: '70% of packets routed to your side: its keys pay x1.5', ready: true,
    zones: [['magnet-left', 'LEFT'], ['magnet-right', 'RIGHT']] },
  { id: 'meltdown', name: 'SYSTEM CRASH', text: 'The crash meter climbs after the drop: x0.5 to x20', ready: true },
];

const LEDGER_CARDS: ArsenalCard[] = [
  { id: 'heavy', name: 'GOLD SOVEREIGN', text: 'Every coin is struck in gold: x2 to x10', ready: true },
  { id: 'pair', name: 'DOUBLE ENTRY', text: 'Each coin is entered twice - the better landing pays', ready: true },
  { id: 'magnet', name: 'LOOPHOLE', text: '70% of coins slip to your side: its bays pay x1.5', ready: true,
    zones: [['magnet-left', 'LEFT'], ['magnet-right', 'RIGHT']] },
  { id: 'meltdown', name: 'DEATH DUTY', text: 'The death duty climbs after the drop: x0.5 to x20', ready: true },
];

/** Each theme's power-ups and what the panel (and its console key) is called. */
export const ARSENALS: Record<string, { title: string; hint: string; cards: ArsenalCard[] }> = {
  'bombs-away': { title: 'ARSENAL', hint: 'Pick a power-up for your next drop', cards: WAR_CARDS },
  'reactor-drop': { title: 'BOOST', hint: 'Charge your next release', cards: REACTOR_CARDS },
  'lagoon-drop': { title: 'BOOSTERS', hint: 'Bless your next pearl', cards: LAGOON_CARDS },
  'pharaoh-drop': { title: 'RELICS', hint: "Call on the Pharaoh's relics", cards: PHARAOH_CARDS },
  'zombie-war': { title: 'LOADOUT', hint: 'Arm up before the horde hits', cards: ZOMBIE_CARDS },
  'neon-nights': { title: 'POWER-UPS', hint: 'Light up your next drop', cards: NEON_CARDS },
  'candy-factory': { title: 'TREATS', hint: 'Sweeten your next drop', cards: CANDY_CARDS },
  'shurda-plinko': { title: 'SOCK BOX', hint: 'Pick a pair for your next drop', cards: SHURDA_CARDS },
  'hell-of-a-deal': { title: 'DEALS', hint: 'Strike a deal for your next drop', cards: HELL_CARDS },
  'disco-is-dead': { title: 'REQUESTS', hint: 'Request a track for your next drop', cards: DISCO_CARDS },
  'plinko-exe': { title: 'EXPLOITS', hint: 'Run an exploit on your next drop', cards: EXE_CARDS },
  'death-and-taxes': { title: 'CLAUSES', hint: 'Add a clause to your next drop', cards: LEDGER_CARDS },
};

interface CardView { card: ArsenalCard; root: Container; art: Sprite | NineSliceSprite; lit: Graphics; price: Text;
  size: (w: number, h: number) => PanelArea;
  zones: { id: string; root: Container; back: Graphics; text: Text }[] }

export class ArsenalPanel extends Container {
  private readonly dim = new Graphics();
  private readonly title: Text;
  private readonly hint: Text;
  private readonly cards: CardView[] = [];
  private armed: string | null = null;

  /** prices: what a ball costs with each power-up (x the bet) on the board in play, or null when not on sale. */
  constructor(panel: Texture, private readonly look: { gold: string; text: string },
              private readonly onPick: (id: string | null) => void, set = ARSENALS['bombs-away'],
              private readonly inner: [number, number, number, number] | null = null) {
    super();
    this.title = label(set.title, 34, 'center', { fill: look.gold, stroke: { color: '#000000', width: 5, join: 'round' } });
    this.title.anchor.set(0.5, 0.5);
    this.hint = label(set.hint, 15, 'center', { fill: look.text });
    this.hint.anchor.set(0.5, 0.5);
    this.addChild(this.dim, this.title, this.hint);
    for (const card of set.cards) {
      const root = new Container();
      const { art, size } = framedPanel(panel, inner);
      const lit = new Graphics();
      const name = label(card.name, 17, 'center', { fill: card.ready ? look.gold : '#8a8f86', stroke: { color: '#000000', width: 3 } });
      name.anchor.set(0.5, 0.5);
      name.y = -24;
      const text = label(card.text, 12, 'center', { fill: card.ready ? look.text : '#8a8f86' });
      text.anchor.set(0.5, 0.5);
      const price = label(card.ready ? '' : 'COMING SOON', 15, 'right', { fill: card.ready ? '#ffffff' : '#6f746c' });
      price.anchor.set(0.5, 0.5);
      price.y = 24;
      root.addChild(art, lit, name, text, price);
      root.alpha = card.ready ? 1 : 0.6;
      // a card sold per zone: a button per zone, each with its own price; the card itself is not a button
      const zones = (card.zones ?? []).map(([id, zoneName]) => {
        const zone = new Container();
        const back = new Graphics();
        const zoneText = label(zoneName, 12, 'center', { fill: '#ffffff', stroke: { color: '#000000', width: 2 } });
        zoneText.anchor.set(0.5, 0.5);
        zone.addChild(back, zoneText);
        zone.eventMode = 'static';
        zone.cursor = 'pointer';
        zone.on('pointertap', () => this.pick(id));
        root.addChild(zone);
        return { id, root: zone, back, text: zoneText };
      });
      if (card.ready && !card.zones) {
        root.eventMode = 'static';
        root.cursor = 'pointer';
        root.on('pointertap', () => this.pick(card.id));
      }
      if (card.zones) price.visible = false;
      this.cards.push({ card, root, art, lit, price, zones, size });
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
      if (view.zones.length) {
        let any = false;
        for (const zone of view.zones) {
          const p = prices[zone.id];
          zone.text.text = p ? `${(view.card.zones ?? []).find(z => z[0] === zone.id)?.[1]}\n${p.cost}` : 'OFF';
          zone.root.eventMode = p ? 'static' : 'none';
          zone.root.alpha = p ? 1 : 0.5;
          any ||= !!p;
        }
        view.root.alpha = any ? 1 : 0.5;
        continue;
      }
      const p = prices[view.card.id];
      view.price.text = p ? `${p.cost} A ROUND\nx${p.times.toFixed(2)}` : 'NOT ON SALE';
      view.root.eventMode = p ? 'static' : 'none';
      view.root.alpha = p ? 1 : 0.5;
    }
  }

  setArmed(id: string | null): void {
    this.armed = id;
    for (const view of this.cards) {
      const on = view.card.id === id || view.zones.some(z => z.id === id);
      for (const zone of view.zones) zone.text.style.fill = zone.id === id ? this.look.gold : '#ffffff';
      view.lit.visible = on;
      view.art.tint = on ? '#ffcf6a' : '#ffffff';
    }
  }

  /** The cards one under the other, as a list: the name on the left, what it does under it, the price on the right. */
  layout(view: Viewport): void {
    this.dim.clear().rect(0, 0, view.stageW, view.stageH).fill({ color: '#000000', alpha: 0.75 });
    this.hitArea = new Rectangle(0, 0, view.stageW, view.stageH);
    const visibleH = view.stageH - 2 * view.offsetY;
    const w = Math.min(view.stageW - 2 * view.offsetX - 20, 620);   // what is on screen (a phone hides the stage's sides)
    // a thick frame leaves less of the card for text, so its cards are taller
    const h = Math.min(this.inner ? 100 : 78, (visibleH - 150) / this.cards.length - 8);
    const gap = 8;
    const listH = this.cards.length * (h + gap) - gap;
    const top = view.offsetY + Math.max(120, (visibleH - listH) / 2 + 40);
    this.title.position.set(view.stageW / 2, top - 76);
    this.hint.position.set(view.stageW / 2, top - 42);
    this.cards.forEach((v, i) => {
      v.root.position.set(view.stageW / 2, top + h / 2 + i * (h + gap));
      const area = v.size(w, h);
      v.lit.clear().roundRect(-w / 2 - 3, -h / 2 - 3, w + 6, h + 6, 8).stroke({ color: this.look.gold, width: 4 });
      v.lit.visible = v.card.id === this.armed || v.zones.some(z => z.id === this.armed);
      const [name, text] = v.root.children.filter((c): c is Text => c instanceof Text && c !== v.price);
      // the card's art has a riveted rim: everything stays inside its dark panel, `inset` in from each side (a thick
      // frame: inside its plain middle)
      const box = this.inner ? area : null;
      // (a thick frame's inside narrows where its ends curve: the text keeps clear of them)
      const inset = box ? 14 + (box.x1 - box.x0) * 0.035 : Math.max(26, w * 0.065);
      const x0 = (box?.x0 ?? -w / 2) + inset, x1 = (box?.x1 ?? w / 2) - inset;
      const y0 = box?.y0 ?? -h / 2, ih = (box?.y1 ?? h / 2) - y0, cy = y0 + ih / 2;
      const span = box ? ih / 2 : h / 2;   // half the height there is for text
      for (const [t, y, size] of [[name, cy - span * 0.36, Math.min(18, span * 0.52)], [text, cy + span * 0.36, Math.min(13, span * 0.38)]] as const) {
        t.anchor.set(0, 0.5);
        t.style.fontSize = Math.round(size);
        t.position.set(x0, y);
      }
      // the price, or the zone buttons, on the right
      const right = (x1 - x0) * 0.4;
      v.price.anchor.set(1, 0.5);
      v.price.style.fontSize = Math.round(Math.min(14, span * 0.4));
      v.price.position.set(x1, cy);
      fit(v.price, right - 10);
      fit(name, x1 - x0 - right - 12);
      fit(text, x1 - x0 - right - 12);
      const zw = right / Math.max(1, v.zones.length);
      v.zones.forEach((zone, z) => {
        zone.root.position.set(x1 - right + zw * (z + 0.5), cy);
        zone.back.clear().roundRect(-zw / 2 + 2, -span * 0.5, zw - 4, span, 5).fill({ color: '#000000', alpha: 0.55 })
          .stroke({ color: this.look.gold, width: 1.5 });
        zone.text.style.fontSize = Math.round(Math.min(12, span * 0.34));
        fit(zone.text, zw - 10);
      });
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
