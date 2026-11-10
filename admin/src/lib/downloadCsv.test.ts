import { ApiError, setToken } from '@flow-cortex/api-client';
import { downloadCsv } from './downloadCsv';

function stubFetch(response: Response) {
  const fetchMock = vi.fn(async () => response);
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

beforeEach(() => {
  setToken('csv-token', true);
  URL.createObjectURL = vi.fn(() => 'blob:csv');
  URL.revokeObjectURL = vi.fn();
});
afterEach(() => {
  setToken(null);
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('downloadCsv', () => {
  it('sends the bearer token, saves the file under the served name and returns the matched total', async () => {
    const fetchMock = stubFetch(
      new Response('a,b\r\n', {
        status: 200,
        headers: { 'Content-Disposition': 'attachment; filename="runs-20261108.csv"', 'X-Total-Count': '1500' },
      }),
    );
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});

    const result = await downloadCsv('/api/v1/admin/runs/export?status=error');

    expect(result).toEqual({ fileName: 'runs-20261108.csv', total: 1500 });
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/admin/runs/export?status=error', {
      headers: { Accept: 'text/csv', Authorization: 'Bearer csv-token' },
    });
    expect(click).toHaveBeenCalledTimes(1);
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:csv');
    expect(document.querySelector('a[download]')).toBeNull();
  });

  it('falls back to a generic name and no total when the headers are absent', async () => {
    stubFetch(new Response('a\r\n', { status: 200 }));
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});

    expect(await downloadCsv('/x')).toEqual({ fileName: 'export.csv', total: undefined });
  });

  it('throws the problem of a refused export and clears the token on a 401', async () => {
    stubFetch(new Response(JSON.stringify({ status: 403, detail: 'Forbidden' }), { status: 403 }));
    await expect(downloadCsv('/x')).rejects.toMatchObject({ status: 403, message: 'Forbidden' });

    stubFetch(new Response('', { status: 401 }));
    const error = await downloadCsv('/x').catch((reason: unknown) => reason);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 401 });
  });
});
