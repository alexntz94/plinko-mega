import { Container, Graphics, type Text } from 'pixi.js';
import { gsap } from 'gsap';
import type { Viewport } from './viewport';
import { label } from './ui';
import { glassPanel, inkPanel } from './paint';

/**
 * The bonus meter: how many charges the player has of how many fill it. The server keeps the real count (signed
 * with every round); this only shows it, and moves it along as balls land so the player sees it fill.
 */
export class Meter extends Container {
  private readonly title: Text;
  private readonly count: Text;
  private readonly backing = new Graphics();
  private readonly bar = new Graphics();
  private readonly fill = new Graphics();
  private charges = 0;
  private of = 10;
  private width_ = 244;

  /** `glass`: the theme's background colour for a glass panel and a meter of lit cells (null: the flat bar). */
  constructor(private readonly colour: string, private readonly glass: string | null = null,
              private readonly ink: { ink: string; panel: string } | null = null) {
    super();
    this.title = label('BONUS METER', 16);
    this.count = label('', 16, 'right', { fill: colour });
    this.addChild(this.backing, this.title, this.count, this.bar, this.fill);
    this.visible = false;
  }

  /** Shows the server's count (on load and after every round). */
  set(enabled: boolean, charges: number, of: number): void {
    gsap.killTweensOf(this.fill); // a fill flash still running must not reset the server's count afterwards
    this.fill.alpha = 1;
    this.visible = enabled && of > 0;
    this.of = Math.max(1, of);
    this.charges = charges;
    this.draw();
  }

  /** A ball charged the meter: one step up, with a pulse; at the top it flashes and starts again. */
  charge(): void {
    this.charges++;
    if (this.charges >= this.of) {
      this.charges = this.of;
      this.draw();
      gsap.fromTo(this.fill, { alpha: 1 }, { alpha: 0.3, duration: 0.12, yoyo: true, repeat: 5, onComplete: () => {
        this.charges = 0;
        this.draw();
      } });
      return;
    }
    this.draw();
    gsap.fromTo(this.count.scale, { x: 1.4, y: 1.4 }, { x: 1, y: 1, duration: 0.3, ease: 'back.out(3)', overwrite: true });
  }

  layout(view: Viewport): void {
    if (view.landscape) {
      // above the panel column
      this.width_ = 244;
      this.position.set(view.stageW * 0.11 + 5, view.offsetY + 44);
    } else {
      // above the last results, centred
      this.width_ = 300;
      this.position.set((view.stageW - 300) / 2, view.offsetY + 30);
    }
    this.title.position.set(0, 0);
    this.count.position.set(this.width_, 0);
    this.draw();
  }

  private draw(): void {
    const w = this.width_;
    this.count.text = `${this.charges}/${this.of}`;
    if (this.glass || this.ink) {
      // one cell per charge (up to 20), the charged ones lit
      if (this.ink) inkPanel(this.backing.clear(), -12, -24, w + 24, 54, 14, this.ink.ink, this.ink.panel);
      else glassPanel(this.backing.clear(), -12, -24, w + 24, 54, 14, this.glass!);
      this.bar.clear();
      this.fill.clear();
      const cells = Math.min(20, this.of);
      const gap = cells > 10 ? 2 : 4;
      const cw = (w - gap * (cells - 1)) / cells;
      const lit = Math.round(cells * this.charges / this.of);
      for (let i = 0; i < cells; i++) {
        const x = i * (cw + gap);
        if (i < lit) {
          this.fill.roundRect(x - 1.5, 7.5, cw + 3, 13, 4).fill({ color: this.colour, alpha: 0.3 })
            .roundRect(x, 9, cw, 10, 3).fill(this.colour).roundRect(x + 1.5, 10, cw - 3, 2, 1).fill({ color: '#ffffff', alpha: 0.4 });
        } else {
          this.bar.roundRect(x, 9, cw, 10, 3).fill({ color: '#000000', alpha: 0.55 }).stroke({ width: 1, color: '#ffffff', alpha: 0.08 });
        }
      }
      return;
    }
    // a dark pill behind it, so it reads on any background art
    this.backing.clear().roundRect(-12, -22, w + 24, 50, 14).fill({ color: '#000000', alpha: 0.6 });
    this.bar.clear().roundRect(0, 8, w, 12, 6).fill({ color: '#000000', alpha: 0.55 }).stroke({ color: this.colour, width: 1.5, alpha: 0.6 });
    const segments = this.of <= 20 ? this.of : 0;
    this.fill.clear();
    const filled = (w - 4) * this.charges / this.of;
    if (filled > 0) this.fill.roundRect(2, 10, filled, 8, 4).fill(this.colour);
    // notches between the steps, for a meter of up to 20 charges
    for (let i = 1; i < segments; i++) {
      this.fill.rect(2 + (w - 4) * i / segments - 0.75, 10, 1.5, 8).fill({ color: '#000000', alpha: 0.6 });
    }
  }
}
