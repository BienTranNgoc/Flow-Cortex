import { setToken, type WorkflowResponse } from '@flow-cortex/api-client';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import { Route } from 'react-router-dom';
import { renderRoute, settled, stubApi, type FakeReply } from '../test/fakeApi';
import { HomePage } from './HomePage';

const ME = { id: 'u1', email: 'member1@flowcortex.dev', name: 'Member One', role: 'MEMBER' };
const LINE = 'Have a video or audio file? Check it →';
const SOURCE = 'https://www.tiktok.com/@creator/video/123';

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
  beforeEach(() => {
    setToken('t');
    vi.stubGlobal('scrollTo', vi.fn());
  });
  afterEach(() => {
    setToken(null);
    vi.unstubAllGlobals();
  });

  it('links to the first listed workflow whose first step is remote, whatever it is called', async () => {
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'GET /api/v1/workflows': {
        status: 200,
        body: [
          workflow('echo', 'Echo Demo', ['echo']),
          workflow('mixed', 'Probe then echo', ['probe', 'echo']),
          workflow('check', 'Renamed by its owner', ['probe', 'extract_audio']),
        ],
      },
    });

    renderHome();

    const link = await screen.findByRole('link', { name: LINE });
    expect(link).toHaveAttribute('href', '/workflows/mixed');
  });

  it('omits the line when no workflow starts with a remote step', async () => {
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'GET /api/v1/workflows': {
        status: 200,
        body: [
          workflow('echo', 'Echo Demo', ['echo']),
          workflow('echo-probe', 'Echo then probe', ['echo', 'probe']),
        ],
      },
    });

    const { queryClient } = renderHome();

    await screen.findByText('Welcome, Member One');
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
        return {
          status: 200,
          body: [workflow('check', 'Check a video file', ['probe', 'extract_audio'])],
        };
      },
    });

    renderHome();

    await screen.findByText('Welcome, Member One');
    expect(screen.queryByRole('link', { name: LINE })).not.toBeInTheDocument();
    release();
    expect(await screen.findByRole('link', { name: LINE })).toHaveAttribute(
      'href',
      '/workflows/check',
    );
  });

  it('starts the first workflow whose first step is not remote, never a probe-only one, whatever the names say', async () => {
    const calls = stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'GET /api/v1/workflows': {
        status: 200,
        body: [
          workflow('probe-only', 'TikTok link probe', ['probe']),
          workflow('echo', 'Echo Demo', ['echo']),
          workflow('url', 'TikTok URL Pipeline', ['echo']),
        ],
      },
      'POST /api/v1/workflows/echo/runs': {
        status: 200,
        body: { id: 'run-echo', status: 'running' },
      },
    });

    renderHome();

    expect(await screen.findByRole('link', { name: LINE })).toHaveAttribute(
      'href',
      '/workflows/probe-only',
    );
    fireEvent.change(screen.getByLabelText('Source URL'), { target: { value: SOURCE } });
    fireEvent.click(screen.getByRole('button', { name: /Generate pipeline/ }));

    await waitFor(() =>
      expect(calls).toContainEqual({
        method: 'POST',
        path: '/api/v1/workflows/echo/runs',
        body: { input: SOURCE },
      }),
    );
    expect(calls.filter((call) => call.method === 'POST')).toHaveLength(1);
  });

  it('disables the launch button and marks it busy while the start request is in flight', async () => {
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'GET /api/v1/workflows': { status: 200, body: [workflow('echo', 'Echo Demo', ['echo'])] },
      'POST /api/v1/workflows/echo/runs': () => new Promise<FakeReply>(() => {}),
    });

    renderHome();

    await screen.findByText('Welcome, Member One');
    fireEvent.change(screen.getByLabelText('Source URL'), { target: { value: SOURCE } });
    const button = screen.getByRole('button', { name: /Generate pipeline/ });
    await waitFor(() => expect(button).toBeEnabled());
    expect(button).not.toHaveAttribute('aria-busy', 'true');
    fireEvent.click(button);

    const busy = await screen.findByRole('button', { name: 'Starting pipeline…' });
    expect(busy).toBeDisabled();
    expect(busy).toHaveAttribute('aria-busy', 'true');
  });

  it('disables the launch when every workflow starts with a remote step', async () => {
    const calls = stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'GET /api/v1/workflows': {
        status: 200,
        body: [workflow('probe-only', 'Probe only', ['probe'])],
      },
    });

    const { queryClient } = renderHome();

    expect(
      await screen.findByText('No workflow is available for this account yet.'),
    ).toBeInTheDocument();
    await settled(queryClient);
    const button = screen.getByRole('button', { name: /Generate pipeline/ });
    expect(button).toBeDisabled();
    fireEvent.change(screen.getByLabelText('Source URL'), { target: { value: SOURCE } });
    fireEvent.click(button);
    expect(calls.filter((call) => call.method === 'POST')).toEqual([]);
  });
});
