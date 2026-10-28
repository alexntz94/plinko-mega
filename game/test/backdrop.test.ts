import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { BackdropVideo, VIDEO_WAITS_FOR_FIRST_ROUND } from '../src/backdrop';
import type { Theme } from '../src/theme';

// Review 4.x / N15.3: the moving background moves from the start (the owner's choice): it loads once the game has
// loaded, only the orientation on screen, and never for a player who stopped it (nor where it never plays).
vi.mock('pixi.js', () => ({ Texture: class {}, VideoSource: class {} }));
vi.mock('../src/effects', () => ({ calm: false }));
vi.mock('../src/version', () => ({ assetUrl: (path: string) => path }));

type FakeVideo = { src: string; style: object; paused: boolean; play: () => Promise<void>; pause: () => void;
  setAttribute: () => void; addEventListener: () => void; canPlayType: () => string };
const theme = { path: 'themes/bombs-away/', video: { wide: 'background.mp4', tall: 'background_p.mp4' } } as unknown as Theme;

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

  it('does not wait for the first round (the budget script counts it in the first visit)', () => {
    expect(VIDEO_WAITS_FOR_FIRST_ROUND).toBe(false);
  });

  it('loads the orientation on screen once the game has loaded, before any round', () => {
    const backdrop = new BackdropVideo(theme, () => undefined);
    vi.runAllTimers();
    expect(backdrop.texture(true)).toBeNull(); // the still picture until it plays
    expect(videos.map(video => video.src)).toEqual(['themes/bombs-away/background.mp4']);
  });

  it('starts loading the orientation on screen after the first round', () => {
    const redraw = vi.fn();
    const backdrop = new BackdropVideo(theme, redraw);
    backdrop.start();
    expect(redraw).toHaveBeenCalled();
    expect(backdrop.texture(true)).toBeNull(); // the still picture until it plays
    expect(videos.map(video => video.src)).toEqual(['themes/bombs-away/background.mp4']);
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
    expect(videos.map(video => video.src)).toEqual(['themes/bombs-away/background_p.mp4']);
  });
});
