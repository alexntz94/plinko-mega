// Drawing helpers shared by the parts the game draws in code (the control bar, the wheel, the meter).
import { FillGradient, type ColorSource, type Graphics } from 'pixi.js';

/** A mix of two #rrggbb colours (t = 0: a, t = 1: b), as a number for Pixi. */
export function mix(a: string, b: string, t: number): number {
  const pa = parseInt(a.slice(1), 16);
  const pb = parseInt(b.slice(1), 16);
  const channel = (shift: number) => Math.round(((pa >> shift) & 255) * (1 - t) + ((pb >> shift) & 255) * t);
  return (channel(16) << 16) | (channel(8) << 8) | channel(0);
}

/** A top-to-bottom shade, sized to whatever shape it fills. */
export function shade(top: ColorSource, bottom: ColorSource): FillGradient {
  return new FillGradient({ type: 'linear', start: { x: 0, y: 0 }, end: { x: 0, y: 1 }, textureSpace: 'local',
    colorStops: [{ offset: 0, color: top }, { offset: 1, color: bottom }] });
}

/** A glass panel: a drop shadow, a shaded body, a thin edge and a highlight along the top. */
export function glassPanel(g: Graphics, x: number, y: number, w: number, h: number, r: number, background: string): Graphics {
  return g.roundRect(x, y + 4, w, h, r).fill({ color: '#000000', alpha: 0.45 })
    .roundRect(x, y, w, h, r).fill({ fill: shade(mix(background, '#ffffff', 0.07), mix(background, '#000000', 0.45)), alpha: 0.94 })
    .stroke({ width: 1.5, color: mix(background, '#ffffff', 0.16) })
    .moveTo(x + r, y + 1.5).lineTo(x + w - r, y + 1.5).stroke({ width: 1, color: '#ffffff', alpha: 0.14 });
}

/** A comic panel: an offset ink shadow, a flat body and a thick ink outline. */
export function inkPanel(g: Graphics, x: number, y: number, w: number, h: number, r: number, ink: string, panel: string): Graphics {
  return g.roundRect(x + 4, y + 4, w, h, r).fill(ink)
    .roundRect(x, y, w, h, r).fill(panel).stroke({ width: 3.5, color: ink })
    .roundRect(x + 8, y + 5, w - 16, 3, 1.5).fill({ color: '#ffffff', alpha: 0.18 });
}
