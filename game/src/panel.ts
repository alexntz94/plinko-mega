import { Container, Sprite, type Text, type Texture } from 'pixi.js';
import type { ThemeLayout } from './theme';
import type { Viewport } from './viewport';
import { Button, Slider, fit, label } from './ui';

export interface PanelTextures {
  stat: Texture; half: Texture; double: Texture; left: Texture; right: Texture; bar: Texture; knob: Texture; play: Texture;
}

export interface PanelActions {
  bet(up: boolean): void;
  risk(step: number): void;
  rows(index: number): void;
  balls(index: number): void;
  play(): void;
}

/** One of the designers' stat boxes: a label above it and a value inside, at the theme's offsets. */
class StatBox extends Container {
  readonly value: Text;

  constructor(texture: Texture, title: string, layout: ThemeLayout, valueAlign: 'left' | 'center' = 'left', valueX = 20) {
    super();
    const title_ = label(title, 20);
    title_.position.set(20, layout.labelY);
    this.value = label('', 30, valueAlign);
    this.value.position.set(valueX, layout.valueY);
    this.addChild(new Sprite(texture), title_, this.value);
  }
}

/** Credit, bet, risk, rows, balls and the play button, laid out like the original game. */
export class Panel extends Container {
  private readonly credit: StatBox;
  private readonly bet: StatBox;
  private readonly risk: StatBox;
  private readonly rows: StatBox;
  private readonly balls: StatBox;
  private readonly playBox = new Container();
  private readonly playText: Text;
  private readonly controls: (Button | Slider)[];
  private readonly playButton: Button;
  readonly rowsSlider: Slider;
  readonly ballsSlider: Slider;
  private readonly creditMax: number;
  private readonly betMax: number;
  private readonly boxW: number;
  private readonly boxH: number;

  constructor(t: PanelTextures, layout: ThemeLayout, actions: PanelActions, rowSteps: number, ballSteps: number, playColour?: string) {
    super();
    this.credit = new StatBox(t.stat, 'CREDIT', layout);
    this.bet = new StatBox(t.stat, 'BET AMOUNT', layout);
    this.risk = new StatBox(t.stat, 'RISK LEVEL', layout, 'center', 120);
    this.rows = new StatBox(t.stat, 'ROWS', layout);
    this.balls = new StatBox(t.stat, 'BALLS', layout);

    const half = new Button(t.half, () => actions.bet(false));
    half.position.set(layout.halfX, layout.controlY);
    const double = new Button(t.double, () => actions.bet(true));
    double.position.set(layout.doubleX, layout.controlY);
    this.bet.addChild(half, double);

    const left = new Button(t.left, () => actions.risk(-1));
    left.position.set(layout.riskLeftX, layout.controlY);
    const right = new Button(t.right, () => actions.risk(1));
    right.position.set(layout.riskRightX, layout.controlY);
    this.risk.addChild(left, right);

    this.rowsSlider = new Slider(t.bar, t.knob, rowSteps, actions.rows, 150, layout.dragBarY, layout.controlY);
    this.rows.addChild(this.rowsSlider);
    this.ballsSlider = new Slider(t.bar, t.knob, ballSteps, actions.balls, 150, layout.dragBarY, layout.controlY);
    this.balls.addChild(this.ballsSlider);

    this.playButton = new Button(t.play, () => actions.play(), false);
    this.playText = label('PLAY', 30, 'center', playColour ? { fill: playColour } : {});
    this.playText.position.set(120, layout.valueY);
    this.playBox.addChild(this.playButton, this.playText);

    this.controls = [half, double, left, right, this.rowsSlider, this.ballsSlider];
    this.creditMax = t.stat.width - 40;
    this.betMax = layout.halfX - t.half.width / 2 - 6 - 20;
    this.boxW = t.stat.width;
    this.boxH = t.stat.height;
    this.addChild(this.credit, this.bet, this.risk, this.rows, this.balls, this.playBox);
  }

  setCredit(text: string): void { this.credit.value.text = text; fit(this.credit.value, this.creditMax); }
  setBet(text: string): void { this.bet.value.text = text; fit(this.bet.value, this.betMax); }
  setRisk(text: string): void { this.risk.value.text = text; }
  setRows(value: number): void { this.rows.value.text = String(value); }
  setBalls(value: number): void { this.balls.value.text = String(value); }

  /** While balls are dropping the settings are locked and the button says so. */
  setPlaying(playing: boolean): void {
    this.controls.forEach(control => { control.enabled = !playing; });
    this.playButton.enabled = !playing;
    this.playButton.alpha = 1;
    this.playText.text = playing ? 'PLAYING...' : 'PLAY';
    this.playText.alpha = playing ? 0.6 : 1;
  }

  layout(view: Viewport): void {
    const spaceX = 250;
    if (view.landscape) {
      const spaceY = 90;
      const x = view.stageW * 0.11;
      const y = view.stageH * 0.2;
      this.scale.set(1);
      this.position.set(0, 0);
      [this.credit, this.bet, this.risk, this.rows, this.balls].forEach((box, i) => box.position.set(x, y + spaceY * i));
      this.playBox.position.set(x, y + spaceY * 4 + 75);
    } else {
      // Phones in portrait show the stage at about half size, so the panel grows into the width the phone really
      // shows (up to 20%), as far as it fits below the board; the rows get more room for their labels.
      const spaceY = 98;
      const width = spaceX + this.boxW;
      const height = spaceY * 2 + this.boxH;
      const top = view.stageH * 0.68;
      const visibleW = view.stageW - 2 * view.offsetX;
      const bottom = view.stageH - view.offsetY - 12;
      const scale = Math.max(1, Math.min(1.2, (visibleW - 24) / width, (bottom - top) / height));
      this.scale.set(scale);
      this.position.set((view.stageW - width * scale) / 2, top);
      this.bet.position.set(0, 0);
      this.risk.position.set(spaceX, 0);
      this.rows.position.set(0, spaceY);
      this.balls.position.set(spaceX, spaceY);
      this.credit.position.set(0, spaceY * 2);
      this.playBox.position.set(spaceX, spaceY * 2);
    }
  }
}
