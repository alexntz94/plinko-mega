// Which address to ask for each theme picture (review 4.4, N6): AVIF, then WebP, then PNG, as far as the browser can
// show them and the build made them; the half-size copy on a screen that can't show the @2x detail; the small
// pictures from the theme's texture atlas.

import { assetUrl } from './version';
import type { Theme } from './theme';

/** What the image build made for one theme besides the PNGs (vite.config.ts, themeVariants). */
export interface ThemeVariants { avif: string[]; webp: string[]; atlas: string[] }
/** The formats this browser can show (found by decoding a tiny picture of each, see main.ts). */
export interface Formats { avif: boolean; webp: boolean }

declare const __THEME_VARIANTS__: Record<string, ThemeVariants>;
const NONE: ThemeVariants = { avif: [], webp: [], atlas: [] };

export const themeVariants = (theme: Theme): ThemeVariants =>
  (typeof __THEME_VARIANTS__ === 'object' ? __THEME_VARIANTS__[theme.id] : undefined) ?? NONE;

/** The small pictures the build packs into the atlas (game/scripts/theme-atlas.mjs, SMALL; keep the two in step). */
export const ATLAS_FILES = /^(button_[a-z0-9_]+|item_ball|item_peg|item_gate|item_wheel_pointer|item_slot_[0-4])\.png$/;
export const inAtlas = (file: string): boolean => ATLAS_FILES.test(file);

/**
 * The names (no extension) one picture may have, best first. Themes without a landscape exit screen reuse the portrait
 * one. Retina themes ship name@2x.png: Pixi reads the "@2x" and draws the image at its normal size with twice the
 * detail. On a screen that can't show the extra detail (sharp false), first the half-size name.png the build makes from
 * it (a quarter of the pixels), then the @2x one (a plain checkout has no half-size copies).
 */
export function imageNames(theme: Theme, file: string, sharp: boolean): string[] {
  if (file === 'item_exit.png' && theme.landscapeExitFromPortrait) file = 'item_exit_p.png';
  const name = file.replace(/\.png$/, '');
  if (!theme.retina || name.startsWith('background')) return [name];
  return sharp ? [name + '@2x'] : [name, name + '@2x'];
}

/** The addresses to try for one picture, in order: per name, its AVIF and WebP (where made and shown), then its PNG. */
export function imageUrls(theme: Theme, file: string, formats: Formats, sharp: boolean,
  have: ThemeVariants = themeVariants(theme)): string[] {
  const urls: string[] = [];
  for (const name of imageNames(theme, file, sharp)) {
    if (formats.avif && have.avif.includes(name)) urls.push(name + '.avif');
    if (formats.webp && have.webp.includes(name)) urls.push(name + '.webp');
    urls.push(name + '.png');
  }
  return [...new Set(urls)].map(name => assetUrl(theme.path + name));
}

/**
 * The atlas to use: its description (.json) and its picture's addresses, AVIF then WebP; null when there is none to
 * use. Retina themes have only the @2x atlas, used on every screen: as lossless WebP it is smaller than one made from
 * the half-size copies (game/scripts/theme-atlas.mjs). No PNG to fall back on (a lossless PNG of it weighs more than the
 * single files): a browser that shows neither format, or an atlas that fails, gets the single files.
 */
export function atlasUrls(theme: Theme, formats: Formats,
  have: ThemeVariants = themeVariants(theme)): { json: string; images: string[] } | null {
  for (const name of ['atlas@2x', 'atlas'].filter(n => have.atlas.includes(n))) {
    const images = [
      ...(formats.avif && have.avif.includes(name) ? [name + '.avif'] : []),
      ...(formats.webp && have.webp.includes(name) ? [name + '.webp'] : []),
    ];
    if (images.length) return { json: assetUrl(theme.path + name + '.json'), images: images.map(n => assetUrl(theme.path + n)) };
  }
  return null;
}
