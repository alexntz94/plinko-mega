import { CanvasTextMetrics, Container, Rectangle, Sprite, Text, TextStyle, type FederatedPointerEvent, type Texture } from 'pixi.js';
import { gsap } from 'gsap';

/** The theme's font and text colour, set once at start-up before anything is drawn. */
export const typeface = { family: 'Azonix', weight: 'normal' as string, colour: '#ffffff' };

export function setTypeface(family: string, weight: string, colour: string): void {
  typeface.family = family;
  typeface.weight = weight;
  typeface.colour = colour;
}

/**
 * Text is rendered at the pixel density it is shown at (screen density x how much the stage is scaled), so it is
 * sharp on every screen instead of being drawn at one size and smeared when scaled. Updated on every resize.
 */
let textResolution = 2;

export function textQuality(): number {
  return textResolution;
}

/** Sets the text density and re-renders every text already on screen with it. */
export function setTextQuality(resolution: number, root: Container): void {
  textResolution = resolution;
  const visit = (node: Container) => {
    if (node instanceof Text) node.resolution = resolution;
    node.children.forEach(child => visit(child as Container));
  };
  visit(root);
}
type Align = 'left' | 'center' | 'right';

/**
 * Text whose y is its baseline (like the original game's canvas text), so values line up on the designers'
 * stat boxes whatever the font's ascent.
 */
export function label(value: string, size: number, align: Align = 'left', extra: Partial<TextStyle> = {}): Text {
  const style = new TextStyle({ fontFamily: typeface.family, fontWeight: typeface.weight as TextStyle['fontWeight'],
    fontSize: size, fill: typeface.colour, align, ...extra });
  const text = new Text({ text: value, style, resolution: textResolution });
  const ascent = CanvasTextMetrics.measureText('A', style).fontProperties.ascent;
  text.anchor.set(align === 'left' ? 0 : align === 'center' ? 0.5 : 1, ascent / text.height);
  return text;
}

/** Shrinks a text so it is at most `max` wide (long amounts on small boxes). */
export function fit(text: Text, max: number): void {
  text.scale.set(1);
  if (text.width > max) text.scale.set(max / text.width);
}

/** An image button: presses in when touched, and can be disabled (dimmed, ignores taps). */
export class Button extends Sprite {
  private on_ = true;

  constructor(texture: Texture, onTap: () => void, centred = true) {
    super({ texture, anchor: centred ? 0.5 : 0 });
    this.eventMode = 'static';
    this.cursor = 'pointer';
    const press = (down: boolean) => gsap.to(this.scale, { x: down ? 0.92 : 1, y: down ? 0.92 : 1, duration: 0.1, overwrite: true });
    this.on('pointerdown', () => press(true));
    this.on('pointerup', () => press(false));
    this.on('pointerupoutside', () => press(false));
    this.on('pointertap', () => { if (this.on_) onTap(); });
  }

  get enabled(): boolean { return this.on_; }

  set enabled(value: boolean) {
    this.on_ = value;
    this.alpha = value ? 1 : 0.4;
    this.eventMode = value ? 'static' : 'none';
    this.cursor = value ? 'pointer' : 'default';
  }
}

/** The rows/balls slider: a bar and a knob that snaps to `steps` positions. Drag the knob or tap the bar. */
export class Slider extends Container {
  private readonly knob: Sprite;
  private readonly start: number;
  private readonly width_: number;
  private index = 0;
  private dragging = false;
  private on_ = true;

  constructor(bar: Texture, knob: Texture, private steps: number, private readonly onChange: (index: number) => void,
              centreX: number, barY: number, knobY: number) {
    super();
    const barSprite = new Sprite({ texture: bar, anchor: 0.5 });
    barSprite.position.set(centreX, barY);
    this.knob = new Sprite({ texture: knob, anchor: 0.5 });
    this.knob.y = knobY;
    this.width_ = bar.width;
    this.start = centreX - bar.width / 2;
    this.addChild(barSprite, this.knob);
    // a generous touch area around the bar, so it is easy to grab on a phone
    this.hitArea = new Rectangle(this.start - 20, Math.min(barY, knobY) - 28, this.width_ + 40, 56);
    this.eventMode = 'static';
    this.cursor = 'pointer';
    this.on('pointerdown', event => { this.dragging = true; this.follow(event); });
    this.on('globalpointermove', event => { if (this.dragging) this.follow(event); });
    this.on('pointerup', () => { this.dragging = false; });
    this.on('pointerupoutside', () => { this.dragging = false; });
  }

  setSteps(steps: number): void {
    this.steps = Math.max(1, steps);
    this.set(Math.min(this.index, this.steps - 1), false);
  }

  /** Moves the knob to a position (without telling the game: used when the game sets the value). */
  set(index: number, animate = true): void {
    this.index = index;
    const x = this.start + (this.steps > 1 ? this.width_ * index / (this.steps - 1) : 0);
    if (animate) gsap.to(this.knob, { x, duration: 0.12, overwrite: true }); else this.knob.x = x;
  }

  get enabled(): boolean { return this.on_; }

  set enabled(value: boolean) {
    this.on_ = value;
    this.dragging = false;
    this.alpha = value ? 1 : 0.4;
    this.eventMode = value ? 'static' : 'none';
  }

  private follow(event: FederatedPointerEvent): void {
    const x = this.toLocal(event.global).x;
    const fraction = Math.min(1, Math.max(0, (x - this.start) / this.width_));
    const index = Math.round(fraction * (this.steps - 1));
    if (index !== this.index) {
      this.set(index);
      this.onChange(index);
    }
  }
}
