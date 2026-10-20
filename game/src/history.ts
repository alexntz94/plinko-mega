import { Container } from 'pixi.js';
import { gsap } from 'gsap';
import { PEG_SPACING, Slot } from './board';
import type { Viewport } from './viewport';

const MAX = 10;          // results kept
const PORTRAIT_SHOWN = 6; // what fits across a phone in portrait

/** The latest results: a column beside the board in landscape, a row above it in portrait. */
export class History extends Container {
  private items: Slot[] = [];
  private landscape = true;
  private landscapeShown = MAX; // what fits in the visible height (short on wide phones)

  add(prizeIndex: number, tier: number, multiplier: number): void {
    const item = new Slot(prizeIndex, tier);
    item.setValue(multiplier);
    item.alpha = 0;
    this.addChild(item);
    this.items.push(item);
    if (this.items.length > MAX) {
      const old = this.items.shift()!;
      gsap.to(old, { alpha: 0, duration: 0.2, onComplete: () => old.destroy({ children: true }) });
    }
    this.arrange();
    gsap.timeline()
      .to(item, { alpha: 1, duration: 0.2, ease: 'sine.in' }, 0)
      .to(item.scale, { x: 1.2, y: 1.2, duration: 0.2, ease: 'sine.in' }, 0)
      .to(item.scale, { x: 1, y: 1, duration: 0.2, ease: 'sine.out' });
  }

  clear(): void {
    this.items.forEach(item => item.destroy({ children: true }));
    this.items = [];
  }

  layout(view: Viewport): void {
    this.landscape = view.landscape;
    const visibleH = view.stageH - 2 * view.offsetY;
    this.landscapeShown = Math.max(3, Math.min(MAX, Math.floor((visibleH - 80 - PEG_SPACING) / (PEG_SPACING + 13)) + 1));
    this.position.set(view.landscape ? view.stageW * 0.86 : view.stageW / 2, view.landscape ? view.stageH / 2 : view.stageH * 0.08);
    this.arrange();
  }

  private arrange(): void {
    const first = Math.max(0, this.items.length - (this.landscape ? this.landscapeShown : PORTRAIT_SHOWN));
    const shown = this.items.length - first;
    const pitch = PEG_SPACING + (this.landscape ? 13 : 3);
    let offset = -((shown - 1) * pitch) / 2;
    this.items.forEach((item, i) => {
      item.visible = i >= first;
      if (i < first) return;
      const x = this.landscape ? 0 : offset;
      const y = this.landscape ? offset : 0;
      if (item.alpha === 0) item.position.set(x, y); else gsap.to(item, { x, y, duration: 0.2, overwrite: 'auto' }); // only x/y: keeps its fade-in running
      offset += pitch;
    });
  }
}
