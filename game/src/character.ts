// A character standing beside the board (Pharaoh's Drop's pharaoh): drawn from layers on one frame (the body, the head
// and the staff arm over it, the glow of the eyes) and two whole poses. Idle he breathes, his head turns a little, his
// eyes glow and fade and gold dust drifts off the staff; while the balls fall he watches the board; he points his staff
// when a spell starts (a booster, the bonus) and throws his arms up on a big win.

import { Container, Graphics, Sprite, type Texture } from 'pixi.js';
import { gsap } from 'gsap';
import { calm } from './effects';
import type { CharacterArt } from './theme';

export interface CharacterTextures { body: Texture; head: Texture; arm: Texture; eyes: Texture; cheer: Texture; point: Texture }

export class Character extends Container {
  private readonly idle = new Container();
  private readonly head: Sprite;
  private readonly arm: Sprite;
  private readonly eyes: Sprite;
  private readonly cheerPose: Sprite;
  private readonly pointPose: Sprite;
  private readonly halo = new Graphics();
  private readonly dust = new Container();
  private time = 0;
  private dustIn = 0.5;
  private watching = false;
  private posing: gsap.core.Timeline | null = null;
  /** Shown only for its big moments (a phone has no room beside the board). */
  private onlyMoments = false;

  constructor(tex: CharacterTextures, private readonly spec: CharacterArt) {
    super();
    const w = tex.body.width, h = tex.body.height;
    const layer = (texture: Texture, pivot: [number, number]) => {
      const s = new Sprite(texture);
      s.pivot.set(pivot[0] * w, pivot[1] * h);
      s.position.set(pivot[0] * w, pivot[1] * h);
      return s;
    };
    const body = new Sprite(tex.body);
    this.arm = layer(tex.arm, spec.shoulder);
    this.head = layer(tex.head, spec.neck);
    this.eyes = layer(tex.eyes, spec.neck);
    this.eyes.blendMode = 'add';
    this.idle.addChild(body, this.arm, this.head, this.eyes);
    // the frame stands on its feet: everything is placed relative to them
    const [fx, fy] = spec.feet;
    this.idle.pivot.set(fx * w, fy * h);
    this.cheerPose = new Sprite(tex.cheer);
    this.cheerPose.pivot.set(fx * w, fy * h);
    this.cheerPose.scale.set(spec.cheerScale);
    this.pointPose = new Sprite(tex.point);
    this.pointPose.pivot.set(fx * w, fy * h);
    this.cheerPose.visible = this.pointPose.visible = false;
    // a soft glow behind him for the big moments
    for (let k = 8; k > 0; k--) this.halo.circle(0, -h * 0.5, w * 0.06 * k).fill({ color: '#ffd76a', alpha: 0.05 });
    this.halo.blendMode = 'add';
    this.halo.alpha = 0;
    this.addChild(this.halo, this.idle, this.cheerPose, this.pointPose, this.dust);
  }

  /** Stands with its feet at (x, y), `height` tall (the whole frame); onlyMoments: hidden but for its big moments. */
  place(x: number, y: number, height: number, onlyMoments = false): void {
    this.position.set(x, y);
    this.scale.set(height / (this.idle.height || 1) * 0.94);
    this.onlyMoments = onlyMoments;
    if (!this.posing?.isActive()) this.visible = !onlyMoments;
  }

  /** Every frame: breathing, the head and the staff swaying, the eyes' glow, gold dust off the staff. */
  update(deltaMs: number, watching: boolean): void {
    if (!this.visible || calm) return;
    const dt = Math.min(deltaMs, 50) / 1000;
    this.time += dt;
    const t = this.time;
    this.watching = watching;
    this.idle.scale.y = 1 + 0.006 * Math.sin(t * 1.7);
    // he turns toward the board while the balls fall
    const look = watching ? -0.05 : 0;
    this.head.rotation += (look + 0.025 * Math.sin(t * 0.45) - this.head.rotation) * Math.min(1, dt * 3);
    this.eyes.rotation = this.head.rotation;
    this.arm.rotation = this.spec.swing * Math.sin(t * this.spec.swingSpeed);
    this.eyes.alpha = watching ? 0.85 + 0.15 * Math.sin(t * 6) : 0.45 + 0.4 * Math.max(0, Math.sin(t * 0.9));
    this.dustIn -= dt;
    if (this.spec.dust && this.dustIn <= 0 && this.idle.visible) {
      this.dustIn = 0.25 + Math.random() * 0.5;
      this.mote();
    }
  }

  /** A mote of gold dust drifting up off the top of the staff. */
  private mote(): void {
    const tex = this.arm.texture;
    // the top of the staff: the arm picture's upper right
    const x = (0.82 + Math.random() * 0.08) * tex.width - this.idle.pivot.x, y = 0.05 * tex.height - this.idle.pivot.y;
    const dot = new Graphics().circle(0, 0, 3 + Math.random() * 4).fill({ color: Math.random() < 0.5 ? '#ffd76a' : '#fff3b0', alpha: 0.9 });
    dot.blendMode = 'add';
    dot.position.set(x, y);
    this.dust.addChild(dot);
    gsap.timeline({ onComplete: () => dot.destroy() })
      .to(dot, { x: x + (Math.random() - 0.5) * 60, y: y - 60 - Math.random() * 80, duration: 1.6, ease: 'sine.out' }, 0)
      .to(dot, { alpha: 0, duration: 1.6, ease: 'power1.in' }, 0);
  }

  /** Steps out of sight (on a phone, while the bonus fills the screen) and back. */
  private away = false;
  setAway(away: boolean): void {
    if (away === this.away) return;
    this.away = away;
    if (away) this.posing?.progress(1);
    gsap.to(this, { alpha: away ? 0 : 1, duration: 0.25, overwrite: true });
  }

  /** Points his staff at the board (a spell starts: a booster, the bonus). */
  point(): void {
    this.pose(this.pointPose, 1.6, 0.6);
  }

  /** Throws his arms up and cheers (a big win). */
  cheer(): void {
    this.pose(this.cheerPose, 2.6, 1);
  }

  private pose(sprite: Sprite, hold: number, glow: number): void {
    if (calm || this.away) return;
    this.posing?.kill();
    this.visible = true;
    this.alpha = 1;
    this.cheerPose.visible = this.pointPose.visible = false;
    this.idle.visible = false;
    sprite.visible = true;
    const base = sprite === this.cheerPose ? this.spec.cheerScale : 1;
    this.posing = gsap.timeline({ onComplete: () => {
      sprite.visible = false;
      this.idle.visible = true;
      if (this.onlyMoments) this.visible = false;
    } })
      .fromTo(sprite.scale, { x: base * 0.96, y: base * 0.96 }, { x: base, y: base, duration: 0.25, ease: 'back.out(3)' }, 0)
      .fromTo(this.halo, { alpha: 0 }, { alpha: glow, duration: 0.25 }, 0)
      .to(this.halo, { alpha: 0, duration: 0.6 }, hold - 0.4)
      // on a phone he fades away again; beside the board he goes back to standing
      .to(this, { alpha: this.onlyMoments ? 0 : 1, duration: 0.3 }, hold - 0.3);
    void this.watching;
  }
}
