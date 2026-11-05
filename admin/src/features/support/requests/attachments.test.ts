import { addAttachments, uploadAttachments } from './attachments';

function image(name: string, type: string, size = 1024): File {
  const file = new File(['x'], name, { type });
  Object.defineProperty(file, 'size', { value: size });
  return file;
}

describe('reply attachments', () => {
  it('keeps up to three PNG, JPG or WebP images of at most 5 MB, naming each refusal once', () => {
    const picked = [
      image('a.png', 'image/png'),
      image('b.jpg', 'image/jpeg'),
      image('c.gif', 'image/gif'),
      image('d.webp', 'image/webp', 6 * 1024 * 1024),
      image('e.webp', 'image/webp'),
      image('f.png', 'image/png'),
    ];

    const { files, refused } = addAttachments([], picked);

    expect(files.map((file) => file.name)).toEqual(['a.png', 'b.jpg', 'e.webp']);
    expect(refused).toEqual([
      'Chỉ nhận ảnh PNG, JPG hoặc WebP',
      'Ảnh tối đa 5 MB',
      'Tối đa 3 ảnh mỗi tin nhắn',
    ]);
  });

  it('refuses an empty image and a file name the upload grant would refuse', () => {
    const longName = `${'a'.repeat(252)}.png`;

    const { files, refused } = addAttachments(
      [],
      [
        image('empty.png', 'image/png', 0),
        image(longName, 'image/png'),
        image(`${'b'.repeat(251)}.png`, 'image/png'),
      ],
    );

    expect(files.map((file) => file.name.length)).toEqual([255]);
    expect(refused).toEqual(['Ảnh trống — hãy chọn ảnh khác', 'Tên tệp tối đa 255 ký tự']);
  });

  it('asks a grant and PUTs each file in order, returning the upload ids', async () => {
    const steps: string[] = [];
    const ids = await uploadAttachments([image('a.png', 'image/png'), image('b.jpg', 'image/jpeg')], {
      requestGrant: async ({ fileName }) => {
        steps.push(`grant ${fileName}`);
        return {
          uploadId: `id-${fileName}`,
          url: 'https://store.example/put',
          method: 'PUT',
          headers: {},
          expiresAt: '2026-11-05T03:00:00Z',
          maxBytes: 5 * 1024 * 1024,
        };
      },
      put: async (grant) => {
        steps.push(`put ${grant.uploadId}`);
      },
    });

    expect(ids).toEqual(['id-a.png', 'id-b.jpg']);
    expect(steps).toEqual(['grant a.png', 'put id-a.png', 'grant b.jpg', 'put id-b.jpg']);
  });
});
