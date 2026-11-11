export interface CropSize {
  readonly width: number;
  readonly height: number;
}

export interface CropArea extends CropSize {
  readonly x: number;
  readonly y: number;
}

const JPEG_QUALITY = 0.92;

/** The crop's size scaled down (never up) to fit `maxWidth` × `maxHeight`, ratio kept, each side at least 1. */
export function cropOutputSize(area: CropSize, maxWidth: number, maxHeight: number): CropSize {
  const scale = Math.min(1, maxWidth / area.width, maxHeight / area.height);
  return {
    width: Math.max(1, Math.round(area.width * scale)),
    height: Math.max(1, Math.round(area.height * scale)),
  };
}

function loadImage(src: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const image = new Image();
    image.onload = () => resolve(image);
    image.onerror = () => reject(new Error('The image could not be read'));
    image.src = src;
  });
}

/** Draws `area` of `src` (an object URL, so the canvas stays untainted) as a JPEG. */
export async function renderCrop(
  src: string,
  area: CropArea,
  maxWidth: number,
  maxHeight: number,
): Promise<Blob> {
  const image = await loadImage(src);
  const output = cropOutputSize(area, maxWidth, maxHeight);
  const canvas = document.createElement('canvas');
  canvas.width = output.width;
  canvas.height = output.height;
  const context = canvas.getContext('2d');
  if (!context) throw new Error('The canvas is not available');
  context.drawImage(image, area.x, area.y, area.width, area.height, 0, 0, output.width, output.height);
  return new Promise((resolve, reject) => {
    canvas.toBlob(
      (blob) => (blob ? resolve(blob) : reject(new Error('The crop could not be encoded'))),
      'image/jpeg',
      JPEG_QUALITY,
    );
  });
}

/** Fetches `url` into an object URL the canvas may read; the caller revokes it. */
export async function toBlobUrl(url: string): Promise<string> {
  const response = await fetch(url);
  if (!response.ok) throw new Error(`The image answered ${response.status}`);
  return URL.createObjectURL(await response.blob());
}
