import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { ATLAS_FILES, atlasUrls, imageUrls, inAtlas, type ThemeVariants } from '../src/images';
import type { Theme } from '../src/theme';

// Review 4.4 / N6: which addresses the game asks for, per browser and screen.
const theme = { id: 't', path: '/themes/t/', retina: true, landscapeExitFromPortrait: false } as Theme;
const built: ThemeVariants = {
  avif: ['background', 'logo@2x', 'logo', 'atlas@2x'],
  webp: ['background', 'logo@2x', 'logo', 'item_plinko@2x', 'atlas@2x'],
  atlas: ['atlas@2x'],
};
const names = (urls: string[]) => urls.map(url => url.replace(/^\/themes\/t\//, '').replace(/\?v=.*$/, ''));
const all = { avif: true, webp: true };
const none = { avif: false, webp: false };

describe('picture addresses', () => {
  it('AVIF, then WebP, then PNG, where the build made them', () => {
    expect(names(imageUrls(theme, 'logo.png', all, true, built))).toEqual(['logo@2x.avif', 'logo@2x.webp', 'logo@2x.png']);
    expect(names(imageUrls(theme, 'item_plinko.png', all, true, built))).toEqual(['item_plinko@2x.webp', 'item_plinko@2x.png']);
    expect(names(imageUrls(theme, 'button_start.png', all, true, built))).toEqual(['button_start@2x.png']);
  });

  it('only the formats the browser shows', () => {
    expect(names(imageUrls(theme, 'logo.png', { avif: false, webp: true }, true, built))).toEqual(['logo@2x.webp', 'logo@2x.png']);
    expect(names(imageUrls(theme, 'logo.png', none, true, built))).toEqual(['logo@2x.png']);
  });

  it('a 1x screen: the half-size copy first, then the @2x one; backgrounds have no @2x', () => {
    expect(names(imageUrls(theme, 'logo.png', all, false, built)))
      .toEqual(['logo.avif', 'logo.webp', 'logo.png', 'logo@2x.avif', 'logo@2x.webp', 'logo@2x.png']);
    expect(names(imageUrls(theme, 'background.png', all, true, built))).toEqual(['background.avif', 'background.webp', 'background.png']);
  });

  it('a plain checkout (no copies): the PNG only', () => {
    expect(names(imageUrls(theme, 'logo.png', all, true, { avif: [], webp: [], atlas: [] }))).toEqual(['logo@2x.png']);
  });

  it('the exit screen of a theme without a landscape one is the portrait one', () => {
    expect(names(imageUrls({ ...theme, landscapeExitFromPortrait: true }, 'item_exit.png', none, true, built))).toEqual(['item_exit_p@2x.png']);
  });
});

describe('texture atlas', () => {
  it('the @2x atlas on every screen, AVIF then WebP', () => {
    const atlas = atlasUrls(theme, all, built)!;
    expect(names([atlas.json, ...atlas.images])).toEqual(['atlas@2x.json', 'atlas@2x.avif', 'atlas@2x.webp']);
    const webpOnly = atlasUrls(theme, { avif: false, webp: true }, built)!;
    expect(names([webpOnly.json, ...webpOnly.images])).toEqual(['atlas@2x.json', 'atlas@2x.webp']);
  });

  it('a theme without @2x pictures: its plain atlas', () => {
    const atlas = atlasUrls(theme, all, { avif: [], webp: ['atlas'], atlas: ['atlas'] })!;
    expect(names([atlas.json, ...atlas.images])).toEqual(['atlas.json', 'atlas.webp']);
  });

  it('none without WebP or AVIF (the single PNGs weigh less than a lossless PNG atlas) or without an atlas', () => {
    expect(atlasUrls(theme, none, built)).toBeNull();
    expect(atlasUrls(theme, all, { avif: [], webp: [], atlas: [] })).toBeNull();
  });

  it('holds the small pictures only, the same ones the build packs', () => {
    for (const file of ['button_start.png', 'item_ball.png', 'item_peg.png', 'item_slot_3.png', 'item_drag_bar.png', 'item_stat_display.png']) {
      expect(inAtlas(file), file).toBe(true);
    }
    for (const file of ['background.png', 'logo.png', 'item_plinko.png', 'item_exit_p.png', 'item_result.png', 'logo_neon_1.png']) {
      expect(inAtlas(file), file).toBe(false);
    }
    const script = readFileSync(new URL('../scripts/theme-atlas.mjs', import.meta.url), 'utf8');
    const packed = /const SMALL = \/(.+)\/;/.exec(script)![1];
    expect(packed.replace(/\$$/, '\\.png$')).toBe(ATLAS_FILES.source);
  });
});
