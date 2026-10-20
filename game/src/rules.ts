// Bet sizes and labels. The server checks every limit again; these only keep the controls sensible.

/** Round bet amounts between the limits: the limits themselves and 1-2-5 steps (0.10, 0.20, 0.50, 1, 2, 5, 10...). */
export function betLadder(min: number, max: number, extra?: number): number[] {
  const steps = [min, max];
  if (extra !== undefined && extra > min && extra < max) steps.push(extra);
  for (let e = -2; e <= 6; e++) {
    for (const unit of [1, 2, 5]) {
      const step = Math.round(unit * 10 ** e * 100) / 100;
      if (step > min && step < max) steps.push(step);
    }
  }
  return [...new Set(steps)].sort((a, b) => a - b);
}

export function nextBet(ladder: number[], current: number, up: boolean): number {
  if (up) return ladder.find(step => step > current + 0.001) ?? current;
  return [...ladder].reverse().find(step => step < current - 0.001) ?? current;
}

export const round2 = (value: number) => Math.round(value * 100) / 100;

export const RISKS = ['LOW', 'MID', 'HIGH'];

/** Slot p of a board with `rows` rows pays prize position floor(|p - rows/2|): the centre is 0, the edges the highest. */
export function prizeIndexOfSlot(rows: number, slot: number): number {
  return Math.floor(Math.abs(slot - rows / 2));
}

/** Colour tier (0 = centre colour .. 4 = edge colour) of a prize position on a board with `rows` rows. */
export function slotTier(rows: number, prizeIndex: number): number {
  return Math.round(prizeIndex * 4 / Math.max(1, Math.floor(rows / 2)));
}
