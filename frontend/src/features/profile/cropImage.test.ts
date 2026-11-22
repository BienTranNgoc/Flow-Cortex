import { cropOutputSize } from './cropImage';

describe('cropOutputSize', () => {
  it('keeps an area that already fits', () => {
    expect(cropOutputSize({ width: 300, height: 300 }, 512, 512)).toEqual({ width: 300, height: 300 });
  });

  it('scales a larger square down to the avatar bound', () => {
    expect(cropOutputSize({ width: 2000, height: 2000 }, 512, 512)).toEqual({
      width: 512,
      height: 512,
    });
  });

  it('scales a 3:1 area down to the cover bound keeping the ratio', () => {
    expect(cropOutputSize({ width: 3000, height: 1000 }, 1500, 500)).toEqual({
      width: 1500,
      height: 500,
    });
  });

  it('never rounds a side down to zero', () => {
    const size = cropOutputSize({ width: 100000, height: 1 }, 1500, 500);
    expect(size.width).toBeGreaterThanOrEqual(1);
    expect(size.height).toBeGreaterThanOrEqual(1);
  });
});
