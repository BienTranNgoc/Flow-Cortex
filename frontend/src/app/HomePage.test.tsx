import { setToken, type WorkflowResponse, type WorkflowStepResponse } from '@flow-cortex/api-client';
import { screen } from '@testing-library/react';
import { Route } from 'react-router-dom';
import { renderRoute, stubApi } from '../test/fakeApi';
import { HomePage } from './HomePage';

const ME = { id: 'u1', email: 'member1@flowcortex.dev', name: 'Member One', role: 'MEMBER' };

function workflow(
  id: string,
  name: string,
  steps: WorkflowStepResponse[],
): WorkflowResponse {
  return { id, name, isShared: true, steps };
}

describe('HomePage — launchpad cards', () => {
  beforeEach(() => setToken('t'));
  afterEach(() => {
    setToken(null);
    vi.unstubAllGlobals();
  });

  function renderHome() {
    return renderRoute(
      '/',
      '/',
      <HomePage />,
      <Route path="/workflows/:id" element={<p>Detail page</p>} />,
    );
  }

  it('renders one card per workflow returned by the API', async () => {
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'GET /api/v1/workflows': {
        status: 200,
        body: [
          workflow('wf-a', 'Douyin Video Repurpose', [
            { id: 'a-1', stepOrder: 1, name: 'Scrape source', type: 'echo', mode: 'inline' },
            { id: 'a-2', stepOrder: 2, name: 'Demucs BGM', type: 'echo', mode: 'inline' },
          ]),
          workflow('wf-b', 'Product Video', [
            { id: 'b-1', stepOrder: 1, name: 'Render', type: 'echo', mode: 'remote' },
          ]),
          workflow('wf-c', 'Check a video file', [
            { id: 'c-1', stepOrder: 1, name: 'Inspect', type: 'probe', mode: 'remote' },
            { id: 'c-2', stepOrder: 2, name: 'Extract audio', type: 'extract_audio', mode: 'remote' },
          ]),
        ],
      },
    });

    renderHome();

    expect(
      await screen.findByRole('heading', { name: 'Douyin Video Repurpose' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Product Video' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Check a video file' })).toBeInTheDocument();
    expect(screen.queryAllByText('Coming soon')).toHaveLength(0);
  });

  it('links every card to its API workflow by id', async () => {
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'GET /api/v1/workflows': {
        status: 200,
        body: [workflow('wf-check', 'Check a video file', [])],
      },
    });

    renderHome();

    const link = await screen.findByRole('link', { name: /Launch Workflow/ });
    expect(link).toHaveAttribute('href', '/workflows/wf-check');
  });

  it('renders a stage chip per workflow step, with the step name', async () => {
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'GET /api/v1/workflows': {
        status: 200,
        body: [
          workflow('wf-a', 'Douyin Video Repurpose', [
            { id: 'a-1', stepOrder: 1, name: 'Scrape source', type: 'echo', mode: 'inline' },
            { id: 'a-2', stepOrder: 2, name: 'Demucs BGM', type: 'echo', mode: 'inline' },
          ]),
        ],
      },
    });

    renderHome();

    await screen.findByRole('heading', { name: 'Douyin Video Repurpose' });
    expect(screen.getByText('Scrape source')).toBeInTheDocument();
    expect(screen.getByText('Demucs BGM')).toBeInTheDocument();
  });

  it('does not render ETA, status, priority, or credit fields', async () => {
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'GET /api/v1/workflows': {
        status: 200,
        body: [workflow('wf-a', 'A', [{ id: 'a-1', stepOrder: 1, name: 'Step', type: 'echo', mode: 'inline' }])],
      },
    });

    renderHome();

    await screen.findByRole('heading', { name: 'A' });
    expect(screen.queryByText(/^~\d+s$/)).not.toBeInTheDocument();
    expect(screen.queryByText(/GPU Credit/)).not.toBeInTheDocument();
    expect(screen.queryByText(/P[012]/)).not.toBeInTheDocument();
  });

  it('shows a load error with retry when the workflows API fails', async () => {
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'GET /api/v1/workflows': { status: 500, body: { detail: 'boom' } },
    });

    renderHome();

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load workflows');
  });

  it('shows a retry block (not a redirect) when /me returns a 5xx', async () => {
    stubApi({
      'GET /api/v1/auth/me': { status: 500, body: { detail: 'boom' } },
    });

    renderHome();

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load your profile');
  });
});
