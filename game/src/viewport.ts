// The designers' artwork is drawn for a fixed stage: 1280x768 in landscape, 768x1024 in portrait. The stage is
// scaled to cover the window while its "content" area (the part that must stay visible) always fits; offsetX/offsetY
// are the stage pixels cut off on each side, so edge buttons can stay on screen.

export interface Viewport {
  landscape: boolean;
  stageW: number;
  stageH: number;
  contentW: number;
  contentH: number;
  scale: number;
  x: number;        // where the stage's top-left lands on the screen
  y: number;
  offsetX: number;  // stage pixels hidden on the left (and right)
  offsetY: number;  // stage pixels hidden at the top (and bottom)
}

const LANDSCAPE = { stageW: 1280, stageH: 768, contentW: 1024, contentH: 576 };
const PORTRAIT = { stageW: 768, stageH: 1024, contentW: 576, contentH: 900 };

export function computeViewport(windowW: number, windowH: number): Viewport {
  const landscape = windowW > windowH;
  const size = landscape ? LANDSCAPE : PORTRAIT;
  // the content area fits on screen; the rest of the stage fills the remaining space and is cropped
  let scale = Math.min(windowW / size.contentW, windowH / size.contentH);
  scale = Math.min(scale, Math.max(windowW / size.stageW, windowH / size.stageH));
  const width = size.stageW * scale;
  const height = size.stageH * scale;
  const x = (windowW - width) / 2;
  const y = (windowH - height) / 2;
  return {
    landscape, ...size, scale, x, y,
    offsetX: Math.max(0, -x / scale),
    offsetY: Math.max(0, -y / scale),
  };
}
