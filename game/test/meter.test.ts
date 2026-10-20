import { beforeEach, describe, expect, it, vi } from 'vitest';
import { made, reset, type Stub } from './stubs';

// Review 5.7 #4: the bonus meter shows the server's count. Pixi and GSAP are stood in for (test/stubs.ts): only what
// the meter says is checked, not how it is drawn.
vi.mock('pixi.js', async () => (await import('./stubs')).pixi());
vi.mock('gsap', async () => (await import('./stubs')).gsapStub());
vi.mock('../src/ui', async () => {
  const { pixi } = await import('./stubs');
  const { Text } = pixi();
  return { label: (value: string) => Object.assign(new Text(), { text: value }) };
});
vi.mock('../src/control', () => ({ glassPanel: (g: unknown) => g, inkPanel: (g: unknown) => g }));

import { Meter } from '../src/meter';

/** The meter's "charges/of" text (its second label; the first is the title). */
const shown = () => (made.Text[1] as Stub & { text: string }).text;

describe('bonus meter (review 5.7 #4)', () => {
  beforeEach(() => reset());

  for (const look of ['flat', 'glass', 'ink'] as const) {
    it(`shows the server's count and how many fill it (${look})`, () => {
      const meter = new Meter('#ff0', look === 'glass' ? '#000' : null, look === 'ink' ? { ink: '#000', panel: '#fff' } : null);
      meter.set(true, 3, 5);
      expect(shown()).toBe('3/5');
      expect(meter.visible).toBe(true);
    });
  }

  it('is hidden while the meter is off, or has no size', () => {
    const meter = new Meter('#ff0');
    expect(meter.visible).toBe(false);
    meter.set(false, 2, 5);
    expect(meter.visible).toBe(false);
    meter.set(true, 0, 0);
    expect(meter.visible).toBe(false);
  });

  it('moves up one step per charging ball, and starts again once full', () => {
    const meter = new Meter('#ff0');
    meter.set(true, 3, 5);
    meter.charge();
    expect(shown()).toBe('4/5');
    meter.charge();
    // full: it flashes (finished at once here) and starts again from 0, as the server's meter does after paying free balls
    expect(shown()).toBe('0/5');
  });

  it('the server\'s count after the round replaces what the landings moved it to', () => {
    const meter = new Meter('#ff0');
    meter.set(true, 1, 5);
    meter.charge();
    meter.charge();
    meter.set(true, 2, 5);   // e.g. a ball the animation counted, but the server did not
    expect(shown()).toBe('2/5');
    meter.set(true, 2, 8);   // an admin changed the meter's size since the game opened
    expect(shown()).toBe('2/8');
  });
});
