import { setToken, type WorkflowResponse } from '@flow-cortex/api-client';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import { Route } from 'react-router-dom';
import { renderRoute, settled, stubApi } from '../test/fakeApi';
import { HomePage } from './HomePage';

const ME = { id: 'u1', email: 'member1@flowcortex.dev', name: 'Member One', role: 'MEMBER' };
const LINE = 'Have a video or audio file? Check it →';

function workflow(id: string, name: string, types: string[]): WorkflowResponse {
  return {
    id,
    name,
    isShared: true,
    steps: types.map((type, index) => ({
      id: `${id}-s${index + 1}`,
      stepOrder: index + 1,
      name: `Step ${index + 1}`,
      type,
      mode: type === 'probe' || type === 'extract_audio' ? 'remote' : 'inline',
    })),
  };
}

function renderHome() {
  return renderRoute(
    '/',
    '/',
    <HomePage />,
    <Route path="/workflows/:id" element={<p>Start form</p>} />,
  );
}

describe('HomePage', () => {
  beforeEach(() => setToken('t'));
  afterEach(() => {
    setToken(null);
    vi.unstubAllGlobals();
  });

  it('links to the first listed upload workflow, whatever it is called', async () => {
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'GET /api/v1/workflows': {
        status: 200,
        body: [
          workflow('echo', 'Echo Demo', ['echo']),
          workflow('mixed', 'Probe then echo', ['probe', 'echo']),
          workflow('check', 'Renamed by its owner', ['probe', 'extract_audio']),
          workflow('check-2', 'Another check', ['probe', 'extract_audio']),
        ],
      },
    });

    renderHome();

    const link = await screen.findByRole('link', { name: LINE });
    expect(link).toHaveAttribute('href', '/workflows/check');
  });

  it('omits the line when no upload workflow is available', async () => {
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'GET /api/v1/workflows': {
        status: 200,
        body: [
          workflow('echo', 'Echo Demo', ['echo']),
          workflow('mixed', 'Mixed', ['probe', 'echo']),
        ],
      },
    });

    const { queryClient } = renderHome();

    await screen.findByRole('heading', { name: 'Welcome, Member One' });
    await settled(queryClient);
    expect(screen.queryByText(LINE)).not.toBeInTheDocument();
  });

  it('shows no link while the list is still loading', async () => {
    let release!: () => void;
    const gate = new Promise<void>((resolve) => (release = resolve));
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'GET /api/v1/workflows': async () => {
        await gate;
        return { status: 200, body: [workflow('check', 'Check a video file', ['probe', 'extract_audio'])] };
      },
    });

    renderHome();

    await screen.findByRole('heading', { name: 'Welcome, Member One' });
    expect(screen.queryByRole('link', { name: LINE })).not.toBeInTheDocument();
    release();
    expect(await screen.findByRole('link', { name: LINE })).toHaveAttribute(
      'href',
      '/workflows/check',
    );
  });

  it('starts the first URL-capable workflow instead of an upload workflow', async () => {
    const calls = stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'GET /api/v1/workflows': {
        status: 200,
        body: [
          workflow('upload', 'Check a video file', ['probe', 'extract_audio']),
          workflow('url', 'TikTok URL Pipeline', ['echo']),
        ],
      },
      'POST /api/v1/workflows/url/runs': {
        status: 200,
        body: { id: 'run-url', status: 'running' },
      },
    });

    renderHome();

    const input = await screen.findByLabelText('Source URL');
    fireEvent.change(input, { target: { value: 'https://www.tiktok.com/@creator/video/123' } });
    fireEvent.click(screen.getByRole('button', { name: /Generate pipeline/ }));

    await waitFor(() =>
      expect(calls).toContainEqual({
        method: 'POST',
        path: '/api/v1/workflows/url/runs',
        body: { input: 'https://www.tiktok.com/@creator/video/123' },
      }),
    );
  });
});
