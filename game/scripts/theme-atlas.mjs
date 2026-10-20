// Review 4.4: the small pictures of each theme (buttons, ball, pegs, slots, sliders: about 25 files) packed into one
// picture, a texture atlas, so a first visit asks for 2 files (the picture and its description) instead of 25. Run by
// the image build (deploy/nginx/Dockerfile) before the WebP step and the build, so the atlas gets a WebP copy and is
// part of each theme's fingerprint.
//
//   node scripts/theme-atlas.mjs [folder with themes/]   (default: public)
//
// Writes, per theme: atlas@2x.png + atlas@2x.json from the @2x files (a theme without @2x files: atlas.png + atlas.json
// from its plain files). No half-size atlas: as lossless WebP the @2x one is smaller than one made from the half-size
// copies (measured on every theme, e.g. 82 KB against 90 KB: the designers' @2x files have few colours, the scaled-down
// copies many), so every screen gets the @2x atlas.
// The JSON is a PixiJS spritesheet; each frame is named after the file the game asks for (e.g. "button_start.png").
// The single files stay in the build: the game falls back to them if the atlas can't be loaded.
// Pure JavaScript (pngjs), so it runs in the Alpine build stage as on any machine.
import { existsSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import pngjs from 'pngjs';

const { PNG } = pngjs;

/** The small pictures: buttons, the ball, the slider, the stat plate, pegs and slot art. Bigger ones stay single files. */
const SMALL = /^(button_[a-z0-9_]+|item_ball|item_drag|item_drag_bar|item_stat_display|item_peg|item_slot_[0-4])$/;
const MAX_SIDE = 640;     // a picture larger than this on either side stays a single file
const MAX_ATLAS = 2048;   // every WebGL device takes textures this size
const GAP = 2;            // transparent pixels between pictures, so smooth scaling never picks up a neighbour's edge

/** Packs boxes in rows (tallest first) at a given width; returns the positions and the height used, or null. */
function shelfPack(boxes, width) {
  const placed = [];
  let x = 0;
  let y = 0;
  let row = 0;
  for (const box of [...boxes].sort((a, b) => b.h - a.h || b.w - a.w || a.name.localeCompare(b.name))) {
    if (box.w > width) return null;
    if (x + box.w > width) { y += row + GAP; x = 0; row = 0; }
    placed.push({ ...box, x, y });
    x += box.w + GAP;
    row = Math.max(row, box.h);
  }
  return { placed, width, height: y + row };
}

/** The smallest layout (by area) among a few widths. */
function layout(boxes) {
  let best = null;
  for (const width of [256, 384, 512, 640, 768, 896, 1024, 1280, 1536, 1792, 2048]) {
    const packed = shelfPack(boxes, width);
    if (!packed || packed.height > MAX_ATLAS) continue;
    const used = Math.max(...packed.placed.map(p => p.x + p.w));
    const area = used * packed.height;
    if (!best || area < best.area) best = { ...packed, width: used, area };
  }
  return best;
}

/** Packs the given files ({name: frame name, file: path}) into <out>.png and <out>.json; returns the frame count. */
function pack(dir, entries, out, scale) {
  const boxes = [];
  for (const { name, file } of entries) {
    const png = PNG.sync.read(readFileSync(join(dir, file)));
    if (png.width > MAX_SIDE || png.height > MAX_SIDE) continue;
    boxes.push({ name, png, w: png.width, h: png.height });
  }
  if (boxes.length < 2) return 0;
  const packed = layout(boxes);
  if (!packed) throw new Error(`${dir}: the small pictures don't fit in ${MAX_ATLAS}x${MAX_ATLAS}`);
  const atlas = new PNG({ width: packed.width, height: packed.height });   // starts fully transparent
  const frames = {};
  for (const p of packed.placed.sort((a, b) => a.name.localeCompare(b.name))) {
    PNG.bitblt(p.png, atlas, 0, 0, p.w, p.h, p.x, p.y);
    frames[p.name] = { frame: { x: p.x, y: p.y, w: p.w, h: p.h }, rotated: false, trimmed: false,
      spriteSourceSize: { x: 0, y: 0, w: p.w, h: p.h }, sourceSize: { w: p.w, h: p.h } };
  }
  writeFileSync(join(dir, out + '.png'), PNG.sync.write(atlas, { colorType: 6, deflateLevel: 9 }));
  writeFileSync(join(dir, out + '.json'), JSON.stringify({ frames,
    meta: { image: out + '.png', format: 'RGBA8888', size: { w: packed.width, h: packed.height }, scale: String(scale) } }));
  return boxes.length;
}

const root = resolve(process.argv[2] ?? 'public');
const themes = join(root, 'themes');
for (const id of readdirSync(themes).sort()) {
  const dir = join(themes, id);
  if (!existsSync(join(dir, 'theme.json'))) continue;
  const files = readdirSync(dir);
  const base = name => name.replace(/(@2x)?\.png$/, '');
  const small = [...new Set(files.filter(f => f.endsWith('.png') && SMALL.test(base(f))).map(base))].sort();
  const sharp = small.filter(name => files.includes(name + '@2x.png')).map(name => ({ name: name + '.png', file: name + '@2x.png' }));
  const plain = small.filter(name => files.includes(name + '.png')).map(name => ({ name: name + '.png', file: name + '.png' }));
  const count = sharp.length ? pack(dir, sharp, 'atlas@2x', 2) : pack(dir, plain, 'atlas', 1);
  console.log(`${id}: ${count} pictures in the atlas${sharp.length ? ' (@2x)' : ''}`);
}
