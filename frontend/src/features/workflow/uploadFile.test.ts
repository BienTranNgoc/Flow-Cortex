import { FakeXhr } from '../../test/fakeXhr';
import { UploadFailedError, uploadFile } from './uploadFile';

const URL_ = 'http://localhost:9000/flowcortex-uploads/u/1?X-Amz-Signature=abc';

function clip() {
  return new File(['0123456789'], 'holiday clip.mp4', { type: 'video/mp4' });
}

describe('uploadFile', () => {
  beforeEach(() => {
    FakeXhr.instances = [];
    vi.stubGlobal('XMLHttpRequest', FakeXhr);
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('PUTs the file to the given URL with exactly the returned headers and no Authorization', async () => {
    const file = clip();
    const done = uploadFile(URL_, file, { 'Content-Type': 'video/mp4' }, () => {});
    const xhr = FakeXhr.instances[0]!;

    expect(xhr.method).toBe('PUT');
    expect(xhr.url).toBe(URL_);
    expect(xhr.headers).toEqual({ 'Content-Type': 'video/mp4' });
    expect(Object.keys(xhr.headers).map((name) => name.toLowerCase())).not.toContain('authorization');
    expect(xhr.body).toBe(file);

    xhr.respond(200);
    await expect(done).resolves.toBeUndefined();
  });

  it('reports progress from 0 to 100 from upload.onprogress', async () => {
    const seen: number[] = [];
    const done = uploadFile(URL_, clip(), {}, (percent) => seen.push(percent));
    const xhr = FakeXhr.instances[0]!;

    xhr.progress(0, 10);
    xhr.progress(4, 10);
    xhr.progress(10, 10);
    xhr.respond(204);
    await done;

    expect(seen).toEqual([0, 40, 100]);
  });

  it('rejects when the store refuses the PUT', async () => {
    const done = uploadFile(URL_, clip(), {}, () => {});
    FakeXhr.instances[0]!.respond(403);

    await expect(done).rejects.toEqual(new UploadFailedError(403));
  });

  it('rejects on a network error', async () => {
    const done = uploadFile(URL_, clip(), {}, () => {});
    FakeXhr.instances[0]!.failNetwork();

    await expect(done).rejects.toBeInstanceOf(UploadFailedError);
  });

  it('rejects when the upload is aborted', async () => {
    const done = uploadFile(URL_, clip(), {}, () => {});
    FakeXhr.instances[0]!.abort();

    await expect(done).rejects.toBeInstanceOf(UploadFailedError);
  });
});
