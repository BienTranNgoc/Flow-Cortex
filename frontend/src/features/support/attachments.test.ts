import type { SupportUploadGrant } from '@flow-cortex/api-client';
import { addAttachments, uploadAttachments } from './attachments';

function file(name: string, type: string, size = 800 * 1024): File {
  const picked = new File(['x'], name, { type });
  Object.defineProperty(picked, 'size', { value: size });
  return picked;
}

function grant(uploadId: string): SupportUploadGrant {
  return {
    uploadId,
    url: `http://minio.test/${uploadId}`,
    method: 'PUT',
    headers: { 'Content-Type': 'image/png' },
    expiresAt: '2026-10-19T10:00:00Z',
    maxBytes: 5_242_880,
  };
}

describe('addAttachments', () => {
  it('refuses a PDF and a 6 MB JPG before any upload, each with its reason', () => {
    const result = addAttachments(
      [],
      [file('hoa-don.pdf', 'application/pdf'), file('anh.jpg', 'image/jpeg', 6 * 1024 * 1024)],
    );

    expect(result.files).toEqual([]);
    expect(result.refused).toEqual([
      'Only PNG, JPG or WebP images are accepted',
      'Images can be at most 5 MB',
    ]);
  });

  it('refuses an empty image and a file name over 255 characters, each with its reason', () => {
    const result = addAttachments(
      [],
      [file('trong.png', 'image/png', 0), file(`${'a'.repeat(252)}.png`, 'image/png')],
    );

    expect(result.files).toEqual([]);
    expect(result.refused).toEqual([
      'The image is empty — choose another one',
      'File names can be at most 255 characters',
    ]);
  });

  it('accepts a file name of exactly 255 characters', () => {
    expect(addAttachments([], [file(`${'a'.repeat(251)}.png`, 'image/png')]).files).toHaveLength(1);
  });

  it('needs both an image type and an image extension, and accepts exactly 5 MB', () => {
    const exact = file('man-hinh.webp', 'image/webp', 5_242_880);
    const result = addAttachments(
      [],
      [file('anh.png', 'text/plain'), file('anh.txt', 'image/png'), exact],
    );

    expect(result.files).toEqual([exact]);
    expect(result.refused).toEqual(['Only PNG, JPG or WebP images are accepted']);
  });

  it('refuses the fourth image of a message and keeps the first three', () => {
    const three = [
      file('a.png', 'image/png'),
      file('b.jpg', 'image/jpeg'),
      file('c.webp', 'image/webp'),
    ];

    const result = addAttachments(three, [file('d.png', 'image/png')]);

    expect(result.files).toEqual(three);
    expect(result.refused).toEqual(['At most 3 images per message']);
  });
});

describe('uploadAttachments', () => {
  it('asks a grant per file, PUTs the bytes to it and returns the upload ids in order', async () => {
    const first = file('a.png', 'image/png', 1000);
    const second = file('b.jpg', 'image/jpeg', 2000);
    const steps: string[] = [];
    const deps = {
      requestGrant: vi.fn(async (body: { fileName: string; size: number }) => {
        steps.push(`grant ${body.fileName} ${body.size}`);
        return grant(body.fileName === 'a.png' ? 'u1' : 'u2');
      }),
      put: vi.fn(async (target: SupportUploadGrant, picked: File) => {
        steps.push(`put ${picked.name} ${target.url}`);
      }),
    };

    await expect(uploadAttachments([first, second], deps)).resolves.toEqual(['u1', 'u2']);
    expect(steps).toEqual([
      'grant a.png 1000',
      'put a.png http://minio.test/u1',
      'grant b.jpg 2000',
      'put b.jpg http://minio.test/u2',
    ]);
  });
});
