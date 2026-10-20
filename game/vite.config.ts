import { createHash } from 'node:crypto';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { defineConfig } from 'vite';

/**
 * Fingerprints of the files in public/ (themes, sounds, fonts): the game adds them to those files' addresses
 * (?v=...), so nginx and a CDN can keep them for a year, and a changed file gets a new address at once (review 4.2).
 *
 * One fingerprint per folder that is shipped as a unit (review N12): each theme (themes/<id>/, with its fonts) and the
 * shared sounds. A new or changed theme then changes only its own addresses: returning players keep every other
 * theme and sound in their cache, and so does the CDN. The half-size, atlas, WebP and AVIF copies are made in public/
 * before this runs (deploy/nginx/Dockerfile), so their bytes are part of the fingerprint too.
 */
function fingerprint(dir: string): string {
  const hash = createHash('sha256');
  const walk = (folder: string) => {
    for (const name of readdirSync(folder).sort()) {
      const file = join(folder, name);
      if (statSync(file).isDirectory()) walk(file);
      else hash.update(relative(dir, file)).update('\0').update(readFileSync(file));
    }
  };
  walk(dir);
  return hash.digest('hex').slice(0, 12);
}

/** "/themes/<id>/" and "/assets/sounds/" -> fingerprint of that folder. */
function folderFingerprints(): Record<string, string> {
  const versions: Record<string, string> = {};
  for (const id of readdirSync('public/themes').sort()) {
    if (statSync(join('public/themes', id)).isDirectory()) versions[`/themes/${id}/`] = fingerprint(join('public/themes', id));
  }
  versions['/assets/sounds/'] = fingerprint('public/assets/sounds');
  return versions;
}

/**
 * Review 4.4: per theme, what the image build made besides the PNGs (deploy/nginx/Dockerfile): the pictures it also has
 * as AVIF and as WebP (names without the extension, e.g. "logo@2x"; an AVIF is kept only where it is smaller than the
 * WebP) and the texture atlases of the small pictures ("atlas", "atlas@2x"). The game asks only for files that exist,
 * so a plain checkout (no copies) gets the PNGs without a failed request first.
 */
function themeVariants(): Record<string, { avif: string[]; webp: string[]; atlas: string[] }> {
  const variants: Record<string, { avif: string[]; webp: string[]; atlas: string[] }> = {};
  for (const id of readdirSync('public/themes').sort()) {
    const dir = join('public/themes', id);
    if (!statSync(dir).isDirectory()) continue;
    const files = readdirSync(dir).sort();
    const named = (ext: string) => files.filter(f => f.endsWith(ext)).map(f => f.slice(0, -ext.length));
    variants[id] = { avif: named('.avif'), webp: named('.webp'), atlas: named('.json').filter(n => /^atlas(@2x)?$/.test(n)) };
  }
  return variants;
}

// Served by nginx at /. public/ (themes, sounds, fonts, icon) is copied as-is into the build. Everything is bundled into files (no inline scripts or
// styles), so the game keeps the strict Content-Security-Policy.
export default defineConfig({
  base: '/',
  define: {
    // all of public/: only for addresses that still add ASSET_VERSION themselves (see version.ts)
    __ASSET_VERSION__: JSON.stringify(fingerprint('public')),
    __ASSET_VERSIONS__: JSON.stringify(folderFingerprints()),
    __THEME_VARIANTS__: JSON.stringify(themeVariants()),
  },
  build: {
    outDir: 'dist',
    assetsInlineLimit: 0,
    sourcemap: false,
    target: 'es2022',
  },
});
