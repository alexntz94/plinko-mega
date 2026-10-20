import { CanvasSource, Container, Graphics, Sprite, Texture, type Text } from 'pixi.js';
import { gsap } from 'gsap';
import type { Viewport } from './viewport';
import { Button, label } from './ui';

/** Full-stage artwork that switches between the landscape and portrait versions. */
class Artwork extends Container {
  private readonly wide: Sprite;
  private readonly tall: Sprite;

  constructor(wide: Texture, tall: Texture) {
    super();
    this.wide = new Sprite(wide);
    this.tall = new Sprite(tall);
    this.addChild(this.wide, this.tall);
  }

  layout(view: Viewport): void {
    this.wide.visible = view.landscape;
    this.tall.visible = !view.landscape;
  }
}

/** The start screen: the theme's logo and its play button. A neon logo's lit tubes lie over it in layers that buzz
 *  and flicker on their own like a real sign; steady for players who asked for less motion or stopped it. */
export class Menu extends Container {
  private readonly art: Artwork;
  private readonly start: Button;

  constructor(logo: Texture, logoPortrait: Texture, start: Texture, onStart: () => void,
              neon: [Texture, Texture][] = [], private readonly motion: MotionSwitch | null = null) {
    super();
    this.art = new Artwork(logo, logoPortrait);
    this.start = new Button(start, onStart);
    const layers = neon.map(([wide, tall]) => new Artwork(wide, tall));
    this.addChild(this.art, ...layers, this.start);
    // the play button breathes, except for players who asked their device for less motion
    if (!window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) {
      gsap.to(this.start.scale, { x: 1.05, y: 1.05, duration: 0.8, repeat: -1, yoyo: true, ease: 'sine.inOut' });
    }
    // the last layer is the weak tube: it sputters more often and sometimes dies for a moment
    layers.forEach((layer, i) => this.flicker(layer, i === layers.length - 1 && i > 0 ? 3 : 1));
  }

  private flicker(layer: Artwork, weakness: number): void {
    const still = window.matchMedia('(prefers-reduced-motion: reduce)');
    let trouble = 0;   // ticks left of a sputter
    let dead = 0;      // ticks left of being out
    const tick = () => {
      if (this.destroyed) return;
      if (!this.visible || still.matches || (this.motion && !this.motion.moving())) {
        layer.alpha = 1;
      } else if (dead > 0) {
        dead--;
        layer.alpha = 0.04;
      } else if (trouble > 0) {
        trouble--;
        layer.alpha = Math.random() < 0.5 ? 0.06 : 0.9 + Math.random() * 0.1;
      } else {
        layer.alpha = 0.88 + Math.random() * 0.12;   // the hum of a lit tube
        const roll = Math.random();
        if (roll < 0.004 * weakness) dead = 10 + Math.floor(Math.random() * 14);
        else if (roll < 0.016 * weakness) trouble = 4 + Math.floor(Math.random() * 10);
      }
      gsap.delayedCall(0.05 + Math.random() * 0.04, tick);
    };
    tick();
  }

  layout(view: Viewport): void {
    this.art.layout(view);
    for (const child of this.children) if (child instanceof Artwork) child.layout(view);
    this.start.position.set(view.stageW / 2, view.stageH * (view.landscape ? 0.77 : 0.7));
  }
}

export interface DialogTextures { exit: Texture; exitPortrait: Texture; ok: Texture; no: Texture }

/** "Exit game?" with OK / NO. Themes without a landscape version show the portrait one on a dark backdrop. */
export class ExitDialog extends Container {
  private readonly wide = new Container();
  private readonly tall: Sprite;
  private readonly content = new Container();   // title, message and buttons
  private readonly title: Text;
  private readonly message: Text;
  private readonly ok: Button;
  private readonly no: Button;
  private readonly artScale: number;

  constructor(t: DialogTextures, private readonly portraitOnly: boolean, onOk: () => void, onNo: () => void) {
    super();
    this.artScale = 768 / t.exitPortrait.height;
    if (portraitOnly) {
      const art = new Sprite(t.exitPortrait);
      art.scale.set(this.artScale);
      art.x = (1280 - t.exitPortrait.width * this.artScale) / 2;
      this.wide.addChild(new Graphics().rect(0, 0, 1280, 768).fill({ color: '#000000', alpha: 0.85 }), art);
    } else {
      this.wide.addChild(new Sprite(t.exit));
    }
    this.tall = new Sprite(t.exitPortrait);
    this.title = label('EXIT GAME', 50, 'center');
    this.message = label('ARE YOU SURE YOU WANT\nTO QUIT GAME?', 30, 'center', { lineHeight: 35 });
    this.ok = new Button(t.ok, onOk);
    this.no = new Button(t.no, onNo);
    this.content.addChild(this.title, this.message, this.ok, this.no);
    // blocks taps on the game underneath
    this.eventMode = 'static';
    this.addChild(this.wide, this.tall, this.content);
    this.visible = false;
  }

  layout(view: Viewport): void {
    this.wide.visible = view.landscape;
    this.tall.visible = !view.landscape;
    // on the portrait artwork (also when it stands in for a landscape one) everything keeps its portrait place
    const portraitArt = !view.landscape || this.portraitOnly;
    const w = portraitArt ? 768 : view.stageW;
    const h = portraitArt ? 1024 : view.stageH;
    const scale = view.landscape && this.portraitOnly ? this.artScale : 1;
    this.content.scale.set(scale);
    this.content.x = (view.stageW - w * scale) / 2;
    const cx = w / 2;
    const at = (percent: number) => h * percent / 100;
    this.title.position.set(cx, at(portraitArt ? 34 : 29));
    this.message.position.set(cx, at(portraitArt ? 49 : 47));
    this.ok.position.set(cx - 110, at(portraitArt ? 68 : 75));
    this.no.position.set(cx + 110, at(portraitArt ? 68 : 75));
  }
}

/** Shown when the balance drops below the minimum bet. */
export class ResultScreen extends Container {
  private readonly art: Artwork;
  private readonly title = label('GAME OVER', 50, 'center');
  private readonly description = label('YOUR BALANCE', 30, 'center');
  private readonly amount = label('', 80, 'center');
  private readonly button: Button;

  constructor(art: Texture, artPortrait: Texture, button: Texture, onContinue: () => void) {
    super();
    this.art = new Artwork(art, artPortrait);
    this.button = new Button(button, onContinue);
    this.eventMode = 'static';
    this.addChild(this.art, this.title, this.description, this.amount, this.button);
    this.visible = false;
  }

  show(format: (value: number) => string, balance: number): void {
    this.visible = true;
    const counter = { value: 0 };
    gsap.to(counter, { value: balance, duration: 1, onUpdate: () => { this.amount.text = format(counter.value); } });
  }

  layout(view: Viewport): void {
    const cx = view.stageW / 2;
    const at = (percent: number) => view.stageH * percent / 100;
    this.art.layout(view);
    this.title.position.set(cx, at(view.landscape ? 29 : 34));
    this.description.position.set(cx, at(view.landscape ? 38 : 41));
    this.amount.position.set(cx, at(view.landscape ? 47 : 48));
    this.button.position.set(cx, at(view.landscape ? 75 : 70));
  }
}

export interface OptionTextures { settings: Texture; soundOn: Texture; soundOff: Texture; fullscreen: Texture; exit: Texture }

/** The colours of the buttons the game draws itself (ring and disc: their rim and fill when they differ from accent and
 * background). */
export interface DrawnColours { accent: string; text: string; background: string; ring?: string; disc?: string }

/** The moving-background switch, when the theme has one: shows the action, like the sound button. */
export interface MotionSwitch { moving: () => boolean; onTap: () => void }

/** A dark disc with an accent ring, drawn on a canvas at double size like the themes' @2x images (84 across). */
function drawnButton(c: DrawnColours, icon: (g: CanvasRenderingContext2D) => void): Texture {
  const canvas = document.createElement('canvas');
  canvas.width = canvas.height = 168;
  const g = canvas.getContext('2d')!;
  g.scale(2, 2);
  g.fillStyle = 'rgba(0,0,0,0.55)';
  g.beginPath(); g.arc(42, 45, 36, 0, Math.PI * 2); g.fill();
  g.fillStyle = c.disc ?? c.background;
  g.strokeStyle = c.ring ?? c.accent;
  g.lineWidth = c.ring ? 4.5 : 3;
  g.beginPath(); g.arc(42, 42, 35, 0, Math.PI * 2); g.fill(); g.stroke();
  icon(g);
  return new Texture({ source: new CanvasSource({ resource: canvas, resolution: 2 }) });
}

/** Stop (two bars) while the background moves, start (a triangle) while it is still. */
function drawnMotion(c: DrawnColours, moving: boolean): Texture {
  return drawnButton(c, g => {
    g.fillStyle = c.text;
    g.beginPath();
    if (moving) {
      g.roundRect(30, 28, 8, 28, 2);
      g.roundRect(46, 28, 8, 28, 2);
    } else {
      g.moveTo(34, 26); g.lineTo(60, 42); g.lineTo(34, 58); g.closePath();
    }
    g.fill();
  });
}

/** The player's round history (review 7.5): a clock with an arrow running back round it. */
function drawnHistory(c: DrawnColours): Texture {
  return drawnButton(c, g => {
    g.lineCap = 'round';
    g.lineJoin = 'round';
    g.strokeStyle = c.text;
    g.lineWidth = 3.5;
    g.beginPath(); g.arc(42, 42, 17, -Math.PI * 0.85, Math.PI * 0.95); g.stroke();
    // the arrowhead where the circle stops, pointing back
    const ax = 42 + 17 * Math.cos(-Math.PI * 0.85), ay = 42 + 17 * Math.sin(-Math.PI * 0.85);
    g.fillStyle = c.text;
    g.beginPath(); g.moveTo(ax - 7, ay - 3); g.lineTo(ax + 5, ay - 5); g.lineTo(ax - 1, ay + 7); g.closePath(); g.fill();
    g.strokeStyle = c.accent;
    g.beginPath(); g.moveTo(42, 32); g.lineTo(42, 42); g.lineTo(50, 47); g.stroke();
  });
}

/**
 * The gear in the corner; it opens a column with sound, fullscreen (where the browser allows it), the moving
 * background's stop / start (themes with one), the player's rounds, the controls switch and exit.
 */
export class Options extends Container {
  private readonly gear: Button;
  private readonly column = new Container();
  private readonly sound: Button;
  private readonly fullscreen: Button | null;
  private readonly motion: Button | null;
  private readonly rounds: Button;
  private readonly exit: Button;
  private open = false;
  private view: Viewport | null = null;

  constructor(t: OptionTextures, muted: () => boolean, toggleSound: () => void,
              fullscreen: (() => void) | null, onExit: () => void, colours: DrawnColours, motion: MotionSwitch | null,
              onRounds: () => void) {
    super();
    this.gear = new Button(t.settings, () => this.toggle());
    this.sound = new Button(muted() ? t.soundOn : t.soundOff, () => {
      toggleSound();
      // the designers' icons show the action: "sound on" while muted, "sound off" while playing
      this.sound.texture = muted() ? t.soundOn : t.soundOff;
    });
    this.fullscreen = fullscreen ? new Button(t.fullscreen, fullscreen) : null;
    this.exit = new Button(t.exit, () => { this.toggle(false); onExit(); });
    if (motion) {
      const icons = { moving: drawnMotion(colours, true), still: drawnMotion(colours, false) };
      this.motion = new Button(motion.moving() ? icons.moving : icons.still, () => {
        motion.onTap();
        this.motion!.texture = motion.moving() ? icons.moving : icons.still;
      });
    } else this.motion = null;
    this.rounds = new Button(drawnHistory(colours), () => { this.toggle(false); onRounds(); });
    this.column.addChild(this.sound, ...(this.fullscreen ? [this.fullscreen] : []), ...(this.motion ? [this.motion] : []),
      this.rounds, this.exit);
    this.column.visible = false;
    this.addChild(this.column, this.gear);
  }

  toggle(open = !this.open): void {
    this.open = open;
    gsap.to(this.gear, { rotation: open ? Math.PI / 2 : 0, duration: 0.25, overwrite: true });
    if (open) {
      this.column.visible = true;
      const shown = this.column.children.filter(child => child.visible);
      const spots = this.spots(shown.length);
      shown.forEach((child, i) => {
        const target = (child as Button).enabled ? 1 : 0.4;
        gsap.fromTo(child, { alpha: 0, x: 0, y: 0 }, { alpha: target, x: spots[i].x, y: spots[i].y, duration: 0.2, delay: i * 0.03, ease: 'back.out(1.6)' });
      });
    } else {
      gsap.to(this.column.children, { alpha: 0, x: 0, y: 0, duration: 0.15, onComplete: () => { this.column.visible = this.open; } });
    }
  }

  /** Where the open buttons go under the gear: one column while it fits the visible height (a short landscape window
   * crops the stage), otherwise two side by side, so the last button never ends up off screen. */
  private spots(count: number): { x: number; y: number }[] {
    const STEP = 105;
    const visible = this.view ? this.view.stageH - 2 * this.view.offsetY : Infinity;
    const room = Math.max(1, Math.floor((visible - 60 - 50) / STEP));   // below the gear, half a button clear of the edge
    const perColumn = count <= room ? count : Math.ceil(count / 2);
    return Array.from({ length: count }, (_, i) => ({ x: -STEP * Math.floor(i / perColumn), y: STEP * (1 + (i % perColumn)) }));
  }

  /** The rounds and exit only make sense on the game screen, and not while balls are still dropping. */
  setExit(visible: boolean, enabled = true): void {
    [this.rounds, this.exit].forEach(button => {
      button.visible = visible;
      gsap.killTweensOf(button, 'alpha');
      button.enabled = enabled;
      if (!this.column.visible) button.alpha = 0;
    });
  }

  layout(view: Viewport): void {
    this.view = view;
    this.position.set(view.stageW - view.offsetX - 60, view.offsetY + 60);
    // turned or resized while open: the buttons move to their new places
    if (this.open) {
      const shown = this.column.children.filter(child => child.visible);
      this.spots(shown.length).forEach((spot, i) => { gsap.killTweensOf(shown[i], 'x,y'); shown[i].position.set(spot.x, spot.y); });
    }
  }
}
