// The win ticker: the latest balls' wins in a short column beside the board, newest on top, older ones fading out.

import { Container, Graphics } from 'pixi.js';
import { gsap } from 'gsap';
import type { Viewport } from './viewport';
import { label, fit } from './ui';

const SHOWN = 8;
const PITCH = 30;

export class WinTicker extends Container {
  private readonly rows = new Container();

  constructor(private readonly gold: string) {
    super();
    this.addChild(this.rows);
  }

  layout(view: Viewport): void {
    if (view.landscape) this.position.set(view.offsetX + 80, view.stageH * 0.2);
    else this.position.set(view.offsetX + 62, view.stageH * 0.1);
    this.scale.set(view.landscape ? 1 : 0.9);
  }

  /** A ball's win: green when it paid more than its bet, gold for 10x and up, grey otherwise. */
  add(text: string, multiplier: number): void {
    const colour = multiplier >= 10 ? this.gold : multiplier > 1 ? '#7dffa0' : '#b9b2c8';
    const row = new Container({ label: 'ticker-row' });
    const words = label(text, 17, 'center', { fill: colour, stroke: { color: '#000000', width: 3 } });
    words.y = 6;
    fit(words, 104);
    row.addChild(new Graphics().roundRect(-56, -12, 112, 26, 13).fill({ color: '#000000', alpha: 0.55 })
      .stroke({ color: colour, width: multiplier >= 10 ? 2 : 1, alpha: 0.8 }), words);
    this.rows.addChildAt(row, 0);
    // the oldest goes first, so no tween is ever started on a row that is gone
    while (this.rows.children.length > SHOWN) this.drop(this.rows.removeChildAt(SHOWN));
    gsap.from(row.scale, { x: 0.3, y: 0.3, duration: 0.25, ease: 'back.out(2.5)' });
    this.rows.children.forEach((child, i) => {
      gsap.to(child, { y: i * PITCH, alpha: 1 - i / (SHOWN + 1), duration: 0.2, overwrite: true });
    });
  }

  clear(): void {
    this.rows.removeChildren().forEach(child => this.drop(child));
  }

  private drop(row: Container): void {
    gsap.killTweensOf([row, row.scale]);
    row.destroy({ children: true });
  }
}
