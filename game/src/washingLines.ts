// Washing lines over the wall (Shurda Plinko's sock drawer): a rope sagging between two points and socks pegged along
// it, each swaying at its own pace from its peg. Now and then a breeze runs along a line, sock after sock; a big win
// sets them all flapping. A handful of sprites turned each frame: nothing redrawn.

import { Container, MeshRope, Sprite, type Texture } from 'pixi.js';
import { calm } from './effects';
import type { Viewport } from './viewport';
import type { WashingLine, WashingLines as LinesArt } from './theme';

/** A sock on a line: its own sway, and a springy swing on top of it (a breeze, a big win) that dies away. */
interface Sock { sprite: Sprite; phase: number; speed: number; swing: number; swingV: number; line: number }

const RANDOM = (min: number, max: number) => min + Math.random() * (max - min);

export class WashingLines extends Container {
  private socks: Sock[] = [];
  /** pushes waiting to happen: a breeze reaching a sock a moment after the one before it */
  private pushes: { at: number; sock: Sock; force: number }[] = [];
  private time = 0;
  private breezeIn = RANDOM(3, 6);
  private lineCount = 0;

  constructor(private readonly art: LinesArt, private readonly rope: Texture, private readonly sockTextures: Texture[]) {
    super();
  }

  /** Hangs the lines for this screen (the wide ones or the tall ones), beside the board whose middle is at boardX. */
  layout(view: Viewport, boardX: number): void {
    this.removeChildren().forEach(child => child.destroy());
    this.socks = [];
    this.pushes = [];
    const visibleW = view.stageW - 2 * view.offsetX, visibleH = view.stageH - 2 * view.offsetY;
    const lines = view.landscape ? this.art.wide : this.art.tall;
    this.lineCount = lines.length;
    // every sock to the same scale (the first one the size the theme gives), so the knee sock stays the longest
    const sockH = visibleH * (view.landscape ? this.art.size[0] : this.art.size[1]);
    const scale = sockH / (this.sockTextures[0]?.height || 1);
    // (on a screen narrower than the theme was laid out for, a line stops short of the screen's edges)
    const left = view.offsetX + visibleW * 0.02, right = view.offsetX + visibleW * 0.98;
    lines.forEach((line, n) => this.hang(line, n, boardX, view.offsetY, visibleH, left, right, scale));
  }

  private hang(line: WashingLine, n: number, x0: number, y0: number, h: number, left: number, right: number, scale: number): void {
    const clamp = (x: number) => Math.min(right, Math.max(left, x));
    const from = clamp(x0 + h * line.from[0]), to = clamp(x0 + h * line.to[0]);
    const at = (u: number) => ({
      x: from + (to - from) * u,
      y: y0 + h * (line.from[1] + (line.to[1] - line.from[1]) * u + line.sag * 4 * u * (1 - u)),
    });
    // the rope: a gentle curve through a dozen points, as thick as the socks are big
    const points = Array.from({ length: 13 }, (_, i) => at(i / 12));
    const rope = new MeshRope({ texture: this.rope, points: points.map(p => ({ x: p.x / scale, y: p.y / scale })) });
    rope.scale.set(scale);
    this.addChild(rope);
    const count = line.socks.length;
    line.socks.forEach((id, j) => {
      const texture = this.sockTextures[id];
      const hang = this.art.socks[id].hang;
      const sprite = new Sprite({ texture, anchor: { x: hang[0], y: hang[1] } });
      const p = at(line.span[0] + (line.span[1] - line.span[0]) * (j + 0.5) / count);
      sprite.position.set(p.x, p.y);
      sprite.scale.set(scale);
      this.addChild(sprite);
      this.socks.push({ sprite, phase: RANDOM(0, Math.PI * 2), speed: RANDOM(0.9, 1.5), swing: 0, swingV: 0, line: n });
    });
  }

  /** A breeze along one line (or a random one): sock after sock swings, left to right. */
  breeze(line = Math.floor(Math.random() * Math.max(1, this.lineCount)), force = RANDOM(1.4, 2.2)): void {
    if (calm) return;
    this.socks.filter(s => s.line === line).forEach((sock, j) => this.pushes.push({ at: this.time + j * 0.11, sock, force }));
  }

  /** A big win: every line flaps, twice. */
  flap(): void {
    if (calm) return;
    for (let n = 0; n < this.lineCount; n++) {
      this.breeze(n, 4);
      this.socks.filter(s => s.line === n).forEach((sock, j) => this.pushes.push({ at: this.time + 0.5 + j * 0.08, sock, force: -3.5 }));
    }
  }

  /** Every frame: the socks sway; the swings spring back and die away. */
  update(deltaMs: number): void {
    if (calm || !this.visible || !this.socks.length) return;
    const dt = Math.min(deltaMs, 50) / 1000;
    this.time += dt;
    this.breezeIn -= dt;
    if (this.breezeIn <= 0) {
      this.breezeIn = RANDOM(5, 9);
      this.breeze();
    }
    if (this.pushes.length) {
      this.pushes = this.pushes.filter(push => {
        if (push.at > this.time) return true;
        push.sock.swingV += push.force;
        return false;
      });
    }
    for (const sock of this.socks) {
      // a damped spring: about one swing a second, gone in two
      sock.swingV += (-28 * sock.swing - 2.6 * sock.swingV) * dt;
      sock.swing += sock.swingV * dt;
      sock.sprite.rotation = 0.035 * Math.sin(this.time * sock.speed + sock.phase) + 0.35 * Math.tanh(sock.swing);
    }
  }
}
