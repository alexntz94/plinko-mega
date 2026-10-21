import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { chamberLayout, spinTarget } from '../src/revolver';
import type { RevolverArt } from '../src/theme';

// The Bombs Away revolver as the theme describes it (its chambers were measured on the picture, design/.../chambers.py)
const theme = JSON.parse(readFileSync(new URL('../public/themes/bombs-away/theme.json', import.meta.url), 'utf8'));
const spec = theme.wheel.revolverArt as RevolverArt;
const R = 100;   // any radius: the layout scales with it

describe('photo revolver: every cartridge centred in its chamber', () => {
  const places = chamberLayout(spec, R);
  const s = R / spec.radius;

  it('has one chamber per prize on the wheel', () => {
    expect(spec.chambers).toHaveLength(theme.wheel.revolverArt.chambers.length);
    expect(places).toHaveLength(7);
  });

  it('puts each cartridge exactly on the centre of its chamber', () => {
    places.forEach((p, i) => {
      const [x, y] = spec.chambers[i];
      expect(p.x).toBeCloseTo((x - spec.centre[0]) * s, 6);
      expect(p.y).toBeCloseTo((y - spec.centre[1]) * s, 6);
    });
  });

  it('makes each cartridge fill its chamber, inside the rim', () => {
    const bore = spec.chamberRadius * s * 2;
    for (const p of places) {
      expect(p.size).toBeLessThan(bore);
      expect(p.size).toBeGreaterThan(bore * 0.8);
    }
  });

  it('has the chambers on one circle round the centre of the cylinder, inside it, not overlapping', () => {
    const dist = places.map(p => Math.hypot(p.x, p.y));
    const mean = dist.reduce((a, b) => a + b, 0) / dist.length;
    for (const d of dist) {
      expect(Math.abs(d - mean) / mean).toBeLessThan(0.05);
      expect(d + places[0].size / 2).toBeLessThan(R);
    }
    for (let i = 0; i < places.length; i++) {
      for (let j = i + 1; j < places.length; j++) {
        expect(Math.hypot(places[i].x - places[j].x, places[i].y - places[j].y)).toBeGreaterThan(places[i].size);
      }
    }
  });

  it('lists the chambers from the top, clockwise', () => {
    // clockwise from straight up (a chamber a hair left of the top counts as the top)
    const angles = places.map(p => ((Math.atan2(p.y, p.x) + Math.PI / 2 + 0.3 + 4 * Math.PI) % (2 * Math.PI)) - 0.3);
    expect(Math.abs(angles[0])).toBeLessThan(0.15);   // the first is straight up (under the hammer at rest)
    for (let i = 1; i < angles.length; i++) expect(angles[i]).toBeGreaterThan(angles[i - 1]);
  });

  it('brings each chamber exactly under the hammer when it stops', () => {
    places.forEach(p => {
      const turned = Math.atan2(p.y, p.x) + p.stop;   // the chamber's angle after the turn
      const off = Math.atan2(Math.sin(turned + Math.PI / 2), Math.cos(turned + Math.PI / 2));
      expect(Math.abs(off)).toBeLessThan(1e-9);
      // and its label reads upright there
      expect(Math.abs(Math.atan2(Math.sin(p.rotation + p.stop), Math.cos(p.rotation + p.stop)))).toBeLessThan(1e-9);
    });
  });
});

describe('the revolver stops on the server\'s chamber, spin after spin', () => {
  const places = chamberLayout(spec, R);
  const underHammer = (rotation: number) => {
    // which chamber is straight up after the cylinder is turned by `rotation`
    let best = 0, bestOff = Infinity;
    places.forEach((p, i) => {
      const a = Math.atan2(p.y, p.x) + rotation + Math.PI / 2;
      const off = Math.abs(Math.atan2(Math.sin(a), Math.cos(a)));
      if (off < bestOff) { bestOff = off; best = i; }
    });
    return { chamber: best, off: bestOff };
  };

  it('lands on the drawn segment every time, whatever the last spin left', () => {
    let rotation = 0;
    const random = (() => { let s = 7; return () => (s = (s * 16807) % 2147483647) / 2147483647; })();
    for (let spin = 0; spin < 500; spin++) {
      const index = Math.floor(random() * places.length);
      const target = spinTarget(rotation % (Math.PI * 2), places[index].stop, 2 + Math.floor(random() * 3));
      expect(target).toBeLessThan(rotation % (Math.PI * 2));   // it always turns the same way
      rotation = target;
      const at = underHammer(rotation);
      expect(at.chamber).toBe(index);
      expect(at.off).toBeLessThan(1e-9);
    }
  });
});
