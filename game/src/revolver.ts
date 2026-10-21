// The photo revolver's geometry (no drawing here, so it can be tested on its own).

import type { RevolverArt } from './theme';

/**
 * Where the cartridges go on a photo revolver of radius r (screen units, round the cylinder's centre): each at its
 * chamber's centre, as wide as the chamber's bore (a little inside its rim), turned so its label reads outward; and
 * the turn that brings chamber i under the hammer (straight up).
 */
export function chamberLayout(spec: RevolverArt, r: number): { x: number; y: number; size: number; rotation: number; stop: number }[] {
  const s = r / spec.radius;                       // picture shares to screen
  const [cx, cy] = spec.centre;
  return spec.chambers.map(([x, y]) => {
    const angle = Math.atan2(y - cy, x - cx);
    return { x: (x - cx) * s, y: (y - cy) * s, size: spec.chamberRadius * s * 2 * 0.9, rotation: angle + Math.PI / 2, stop: -(angle + Math.PI / 2) };
  });
}


/**
 * Where a spin that starts at `start` (wherever the last one left the cylinder) must end so that it rests at `stop`
 * (the angle that puts the winning chamber under the hammer, any multiple of a full turn away): always backwards
 * (the way it turns), `turns` full turns plus whatever is left to reach the stop.
 */
export function spinTarget(start: number, stop: number, turns: number): number {
  const full = Math.PI * 2;
  const rest = (((start - stop) % full) + full) % full;   // 0 .. one turn
  return start - turns * full - rest;
}
