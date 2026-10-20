import { Container, Graphics, Rectangle, type Text } from 'pixi.js';
import { gsap } from 'gsap';
import { label } from './ui';
import type { Viewport } from './viewport';

/** A part of the control center the guide points at: its box in stage pixels (round: drawn as a circle). */
export interface Spot { x: number; y: number; w: number; h: number; round?: boolean }

export interface GuideStep { text: string; spot: () => Spot }

const SEEN_KEY = 'plinko.guide';

/** Whether this device has already seen the control center guide (browser storage may be blocked: then not). */
export function guideSeen(): boolean {
  try { return window.localStorage.getItem(SEEN_KEY) === 'seen'; } catch { return false; }
}

function markSeen(): void {
  try { window.localStorage.setItem(SEEN_KEY, 'seen'); } catch { /* shown again next visit */ }
}

/**
 * A short walk through the control center: the screen dims, a ring marks one part and a bubble explains it, with
 * Skip and Next. Shown once per device the first time the control center is used, and again from its i button.
 * Only explains; it never plays or changes anything.
 */
export class Guide extends Container {
  private readonly dim = new Graphics();
  private readonly ring = new Graphics();
  private readonly bubble = new Container();
  private readonly box = new Graphics();
  private readonly text: Text;
  private readonly count: Text;
  private readonly next: Text;
  private readonly skip: Text;
  private steps: GuideStep[] = [];
  private index = 0;
  private view: Viewport | null = null;

  constructor(private readonly c: { accent: string; text: string; background: string }) {
    super();
    this.dim.eventMode = 'static';
    this.dim.on('pointertap', () => this.advance());
    this.text = label('', 20, 'left', { fill: c.text, wordWrap: true, wordWrapWidth: 320, lineHeight: 27 });
    this.text.anchor.set(0, 0);
    this.count = label('', 15, 'left', { fill: c.text });
    this.count.anchor.set(0, 0.5);
    this.count.alpha = 0.6;
    this.next = label('', 18, 'right', { fill: c.accent, letterSpacing: 1 });
    this.next.anchor.set(1, 0.5);
    this.skip = label('SKIP', 15, 'right', { fill: c.text, letterSpacing: 1 });
    this.skip.anchor.set(1, 0.5);
    this.skip.alpha = 0.6;
    for (const [button, tap] of [[this.next, () => this.advance()], [this.skip, () => this.close()]] as const) {
      button.eventMode = 'static';
      button.cursor = 'pointer';
      button.on('pointertap', event => { event.stopPropagation(); tap(); });
    }
    this.box.eventMode = 'static';   // a tap on the bubble itself does nothing
    this.bubble.addChild(this.box, this.text, this.count, this.skip, this.next);
    this.addChild(this.dim, this.ring, this.bubble);
    this.visible = false;
  }

  get showing(): boolean {
    return this.visible;
  }

  show(steps: GuideStep[]): void {
    this.steps = steps;
    this.index = 0;
    this.visible = true;
    this.alpha = 0;
    gsap.to(this, { alpha: 1, duration: 0.2, overwrite: true });
    this.draw();
  }

  close(): void {
    if (!this.visible) return;
    markSeen();
    gsap.to(this, { alpha: 0, duration: 0.15, overwrite: true, onComplete: () => { this.visible = false; } });
  }

  layout(view: Viewport): void {
    this.view = view;
    if (this.visible) this.draw();
  }

  private advance(): void {
    if (this.index + 1 >= this.steps.length) {
      this.close();
      return;
    }
    this.index++;
    this.draw();
  }

  private draw(): void {
    const view = this.view;
    const step = this.steps[this.index];
    if (!view || !step) return;
    // the screen dims except the part being explained, which gets a ring
    const spot = step.spot();
    const box = (g: Graphics, grow: number) => spot.round
      ? g.circle(spot.x + spot.w / 2, spot.y + spot.h / 2, spot.w / 2 + grow)
      : g.roundRect(spot.x - grow, spot.y - grow, spot.w + 2 * grow, spot.h + 2 * grow, 16 + grow);
    box(this.dim.clear().rect(0, 0, view.stageW, view.stageH).fill({ color: '#000000', alpha: 0.6 }), 6).cut();
    this.dim.hitArea = new Rectangle(0, 0, view.stageW, view.stageH);
    box(this.ring.clear(), 6).stroke({ width: 4, color: this.c.accent });
    box(this.ring, 13).stroke({ width: 2, color: this.c.accent, alpha: 0.4 });

    // the bubble: the text, then "2/4", SKIP and NEXT on one line
    const width = 360;
    const pad = 20;
    this.text.text = step.text;
    const last = this.index === this.steps.length - 1;
    this.next.text = last ? 'GOT IT' : 'NEXT ›';
    this.count.text = `${this.index + 1}/${this.steps.length}`;
    this.skip.visible = !last;
    const footer = pad + this.text.height + 26;
    const height = footer + 30;
    this.count.position.set(pad, footer);
    this.next.position.set(width - pad, footer);
    this.skip.position.set(width - pad - this.next.width - 26, footer);
    this.text.position.set(pad, pad);
    this.next.hitArea = new Rectangle(-this.next.width - 14, -22, this.next.width + 28, 44);
    this.skip.hitArea = new Rectangle(-this.skip.width - 14, -22, this.skip.width + 28, 44);

    // above the spot if it fits, otherwise below; kept inside the visible part of the stage
    const gap = 30;
    const centre = spot.x + spot.w / 2;
    const above = spot.y - gap - height > view.offsetY + 10;
    const top = above ? spot.y - gap - height : spot.y + spot.h + gap;
    const minX = view.offsetX + 12;
    const maxX = view.stageW - view.offsetX - 12 - width;
    const left = Math.min(maxX, Math.max(minX, centre - width / 2));
    this.bubble.position.set(left, top);
    const tip = Math.min(width - 30, Math.max(30, centre - left));
    const tail = above ? [tip - 12, height, tip, height + 14, tip + 12, height] : [tip - 12, 0, tip, -14, tip + 12, 0];
    this.box.clear().roundRect(0, 0, width, height, 18).fill({ color: this.c.background, alpha: 0.96 })
      .stroke({ width: 2, color: this.c.accent }).poly(tail).fill({ color: this.c.background, alpha: 0.96 })
      .stroke({ width: 2, color: this.c.accent });
  }
}
