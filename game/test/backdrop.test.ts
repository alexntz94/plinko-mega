import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { BackdropVideo, VIDEO_WAITS_FOR_FIRST_ROUND } from '../src/backdrop';
import type { Theme } from '../src/theme';

// Review 4.x / N15.3: the moving background is not part of the first visit on any device: nothing is fetched until the
// player's first round (the game calls start()), or until the player starts it in the settings column.
vi.mock('pixi.js', () => ({ Texture: class {}, VideoSource: class {} }));
vi.mock('../src/effects', () => ({ calm: false }));
vi.mock('../src/version', () => ({ assetUrl: (path: string) => path }));

type FakeVideo = { src: string; style: object; paused: boolean; play: () => Promise<void>; pause: () => void;
  setAttribute: () => void; addEventListener: () => void; canPlayType: () => string };
const theme = { path: 'themes/drop-riot/', video: { wide: 'background.mp4', tall: 'background_p.mp4' } } as unknown as Theme;

describe('the moving background (backdrop.ts)', () => {
  let videos: FakeVideo[];
  let stored: string | null;

  beforeEach(() => {
    vi.useFakeTimers();
    videos = [];
    stored = null;
    const listen = () => undefined;
    vi.stubGlobal('document', {
      hidden: false,
      addEventListener: listen,
      body: { appendChild: () => undefined },
      createElement: () => {
        const video: FakeVideo = { src: '', style: {}, paused: true, play: async () => undefined, pause: () => undefined,
          setAttribute: () => undefined, addEventListener: () => undefined, canPlayType: () => 'probably' };
        videos.push(video);
        return video;
      },
    });
    vi.stubGlobal('window', {
      setTimeout, addEventListener: listen,
      localStorage: { getItem: () => stored, setItem: (_: string, value: string) => { stored = value; } },
    });
    vi.stubGlobal('navigator', { userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/141.0', maxTouchPoints: 0 });
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('waits for the first round on every device (the budget script counts on it)', () => {
    expect(VIDEO_WAITS_FOR_FIRST_ROUND).toBe(true);
  });

  it('loads nothing on the first screen, even on a desktop browser with a fast connection', () => {
    const backdrop = new BackdropVideo(theme, () => undefined);
    vi.runAllTimers();
    expect(backdrop.texture(true)).toBeNull();
    expect(backdrop.texture(false)).toBeNull();
    expect(videos).toHaveLength(0);
  });

  it('starts loading the orientation on screen after the first round', () => {
    const redraw = vi.fn();
    const backdrop = new BackdropVideo(theme, redraw);
    backdrop.start();
    expect(redraw).toHaveBeenCalled();
    expect(backdrop.texture(true)).toBeNull(); // the still picture until it plays
    expect(videos.map(video => video.src)).toEqual(['themes/drop-riot/background.mp4']);
  });

  it('never loads for a player who turned it off, even after a round', () => {
    stored = 'still';
    const backdrop = new BackdropVideo(theme, () => undefined);
    backdrop.start();
    expect(backdrop.texture(true)).toBeNull();
    expect(videos).toHaveLength(0);
  });

  it('a player who turns it back on in the settings gets it at once', () => {
    stored = 'still';
    const backdrop = new BackdropVideo(theme, () => undefined);
    backdrop.toggle();
    expect(stored).toBe('moving');
    backdrop.texture(false);
    expect(videos.map(video => video.src)).toEqual(['themes/drop-riot/background_p.mp4']);
  });
});
