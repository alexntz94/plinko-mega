// Stand-ins for the Pixi, GSAP and drawing modules, so the game's own logic (what it asks the server, what it shows)
// runs in Node without a browser. Each stand-in records every call made to it; nothing is drawn.
import { vi } from 'vitest';

export type Call = [string, ...unknown[]];

/** Every stand-in made, by class name, in the order they were made. */
export const made: Record<string, Stub[]> = {};

/** A class that accepts any method call and records it (calls), plus the arguments it was made with (args). */
export class Stub {
  args: unknown[];
  calls: Call[] = [];
  visible = true;
  alpha = 1;
  position = { set: () => undefined, x: 0, y: 0 };
  scale = { set: () => undefined, x: 1, y: 1 };
  /** Pixi's point conversion, for a container at the origin. */
  toLocal(point: { x: number; y: number }) { return point; }

  constructor(...args: unknown[]) {
    this.args = args;
    const name = new.target.name;
    const chain = (new.target as unknown as { chain?: boolean }).chain === true;
    const proxy: Stub = new Proxy(this, {
      get(target, key, receiver) {
        if (key in target || typeof key === 'symbol') return Reflect.get(target, key, receiver);
        const record = (...a: unknown[]) => { target.calls.push([key, ...a]); return chain ? proxy : undefined; };
        (target as unknown as Record<string, unknown>)[key] = record;
        return record;
      },
    });
    (made[name] ??= []).push(proxy);
    return proxy;
  }

  /** The arguments of every call to one method. */
  calledWith(method: string): unknown[][] {
    return this.calls.filter(c => c[0] === method).map(c => c.slice(1));
  }

  /** The first argument of the last call to one method. */
  last(method: string): unknown {
    const all = this.calledWith(method);
    return all.length ? all[all.length - 1][0] : undefined;
  }
}

export function reset(): void {
  for (const key of Object.keys(made)) delete made[key];
}

/** The one stand-in of a class (the game makes one of each). */
export function one<T extends Stub = Stub>(name: string): T {
  const list = made[name] ?? [];
  if (list.length !== 1) throw new Error(`expected one ${name}, found ${list.length}`);
  return list[0] as T;
}

/** pixi.js: the few classes the game's modules extend or make. */
export const pixi = () => ({
  Container: class Container extends Stub {},
  /** drawing calls chain (g.clear().rect(...).fill(...)) */
  Graphics: class Graphics extends Stub { static chain = true; },
  Sprite: class Sprite extends Stub {},
  Text: class Text extends Stub {},
  Texture: { EMPTY: {} },
  Rectangle: class Rectangle {},
  FillGradient: class FillGradient {},
});

/** A GSAP tween: delayed calls run on the (fake) timers; tweens finish at once and run their onComplete. */
export const gsapStub = () => {
  const delayedCall = (seconds: number, done: () => void) => {
    let active = true;
    const timer = setTimeout(() => { active = false; done(); }, seconds * 1000);
    return { kill: () => { active = false; clearTimeout(timer); }, isActive: () => active };
  };
  const tween = (_target: unknown, ...vars: { onComplete?: () => void }[]) => {
    vars[vars.length - 1]?.onComplete?.();
    return { kill: () => undefined, isActive: () => false };
  };
  return { gsap: { delayedCall, to: tween, fromTo: tween, killTweensOf: vi.fn(), timeline: () => ({ to: () => undefined }) } };
};
