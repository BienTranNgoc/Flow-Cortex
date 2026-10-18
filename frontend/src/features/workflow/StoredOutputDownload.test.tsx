import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { stubApi } from '../../test/fakeApi';
import { openInCurrentTab } from './browser';
import { StoredOutputDownload } from './StoredOutputDownload';

vi.mock('./browser', async (importOriginal) => ({
  ...(await importOriginal<object>()),
  openInCurrentTab: vi.fn(),
}));

const VIDEO = { name: 'final.mp4', mime: 'video/mp4', bytes: 2_000_000 };
const URL_PATH = '/api/v1/runs/r1/steps/5/outputs/final.mp4/url';

/** Answers every URL request with a new signature, as the server does with a fresh presign. */
function serveFreshUrls() {
  let signed = 0;
  return stubApi({
    [`GET ${URL_PATH}`]: () => {
      signed += 1;
      return {
        status: 200,
        body: {
          url: `http://localhost:9000/flowcortex-media/final.mp4?sig=${signed}`,
          expiresAt: '2026-10-01T10:15:00Z',
        },
      };
    },
  });
}

function renderVideo() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <StoredOutputDownload runId="r1" stepOrder={5} output={VIDEO} />
    </QueryClientProvider>,
  );
}

function urlRequests(calls: { path: string }[]): number {
  return calls.filter((call) => call.path === URL_PATH).length;
}

function player(): HTMLVideoElement {
  const video = document.querySelector('video');
  if (!video) {
    throw new Error('no video player');
  }
  return video;
}

describe('StoredOutputDownload for a video', () => {
  it('asks for a fresh link on every download instead of reusing one that may have expired', async () => {
    const user = userEvent.setup();
    const calls = serveFreshUrls();
    renderVideo();

    await user.click(screen.getByRole('button', { name: 'Download final.mp4' }));
    await waitFor(() =>
      expect(openInCurrentTab).toHaveBeenLastCalledWith(
        'http://localhost:9000/flowcortex-media/final.mp4?sig=1',
      ),
    );
    await user.click(await screen.findByRole('button', { name: 'Download final.mp4' }));
    await waitFor(() =>
      expect(openInCurrentTab).toHaveBeenLastCalledWith(
        'http://localhost:9000/flowcortex-media/final.mp4?sig=2',
      ),
    );

    expect(urlRequests(calls)).toBe(2);
  });

  it('asks for a fresh link each time the player is opened', async () => {
    const user = userEvent.setup();
    const calls = serveFreshUrls();
    renderVideo();

    await user.click(screen.getByRole('button', { name: 'Watch video' }));
    await waitFor(() => expect(player()).toHaveAttribute('src', expect.stringContaining('sig=1')));
    await user.click(screen.getByRole('button', { name: 'Hide video' }));
    expect(document.querySelector('video')).toBeNull();
    await user.click(screen.getByRole('button', { name: 'Watch video' }));
    await waitFor(() => expect(player()).toHaveAttribute('src', expect.stringContaining('sig=2')));

    expect(urlRequests(calls)).toBe(2);
  });

  it('re-requests the link once when the player cannot load it, and not again', async () => {
    const user = userEvent.setup();
    const calls = serveFreshUrls();
    renderVideo();

    await user.click(screen.getByRole('button', { name: 'Watch video' }));
    await waitFor(() => expect(player()).toHaveAttribute('src', expect.stringContaining('sig=1')));

    fireEvent.error(player());
    await waitFor(() => expect(player()).toHaveAttribute('src', expect.stringContaining('sig=2')));
    fireEvent.error(player());
    await screen.findByRole('button', { name: 'Hide video' });

    expect(urlRequests(calls)).toBe(2);
    expect(screen.getByRole('alert')).toHaveTextContent('Could not play the video. Download it instead.');
  });

  it('keeps the player closed when the member hides it while a fresh link is on its way', async () => {
    const user = userEvent.setup();
    let release: () => void = () => {};
    let signed = 0;
    const calls = stubApi({
      [`GET ${URL_PATH}`]: async () => {
        signed += 1;
        if (signed === 2) {
          await new Promise<void>((resolve) => {
            release = resolve;
          });
        }
        return {
          status: 200,
          body: {
            url: `http://localhost:9000/flowcortex-media/final.mp4?sig=${signed}`,
            expiresAt: '2026-10-01T10:15:00Z',
          },
        };
      },
    });
    renderVideo();

    await user.click(screen.getByRole('button', { name: 'Watch video' }));
    await waitFor(() => expect(player()).toHaveAttribute('src', expect.stringContaining('sig=1')));
    fireEvent.error(player());
    await waitFor(() => expect(urlRequests(calls)).toBe(2));
    await user.click(screen.getByRole('button', { name: 'Hide video' }));
    release();

    await waitFor(() => expect(screen.getByRole('button', { name: 'Watch video' })).toBeInTheDocument());
    expect(document.querySelector('video')).toBeNull();
  });

  it('labels the card and its controls in English', async () => {
    serveFreshUrls();
    renderVideo();

    expect(screen.getByText('Ad video (final.mp4)')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Watch video' })).toBeInTheDocument();
  });
});
