import { setToken, type WorkflowResponse } from '@flow-cortex/api-client';
import { screen } from '@testing-library/react';
import { Route } from 'react-router-dom';
import { renderRoute, stubApi } from '../test/fakeApi';
import { HomePage } from './HomePage';

const ME = { id: 'u1', email: 'member1@flowcortex.dev', name: 'Member One', role: 'MEMBER' };

function echoWorkflow(id: string, name: string): WorkflowResponse {
  return {
    id,
    name,
    isShared: true,
    steps: [
      { id: `${id}-s1`, stepOrder: 1, name: 'Step 1', type: 'echo', mode: 'inline' },
      { id: `${id}-s2`, stepOrder: 2, name: 'Step 2', type: 'echo', mode: 'inline' },
    ],
  };
}

function probeWorkflow(id: string, name: string): WorkflowResponse {
  return {
    id,
    name,
    isShared: true,
    steps: [
      { id: `${id}-s1`, stepOrder: 1, name: 'Inspect the file', type: 'probe', mode: 'remote' },
      { id: `${id}-s2`, stepOrder: 2, name: 'Extract source audio', type: 'extract_audio', mode: 'remote' },
    ],
  };
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

  it('renders all three showcase cards regardless of API', async () => {
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'GET /api/v1/workflows': { status: 200, body: [] },
    });

    renderHome();

    expect(
      await screen.findByRole('heading', { name: 'Dougyin Translation & Dubbing' }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('heading', { name: 'Product Content & Video' }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('heading', { name: 'Check Video' }),
    ).toBeInTheDocument();
  });

  it('disables every card when no workflow matches a card name', async () => {
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'GET /api/v1/workflows': { status: 200, body: [echoWorkflow('wf-other', 'Some Other')] },
    });

    renderHome();

    await screen.findByRole('heading', { name: 'Dougyin Translation & Dubbing' });
    expect(screen.queryAllByRole('link', { name: /Launch Workflow/ })).toHaveLength(0);
    expect(screen.getAllByText('Coming soon')).toHaveLength(3);
  });

  it('links a card to its API workflow when one with the exact name exists', async () => {
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'GET /api/v1/workflows': {
        status: 200,
        body: [probeWorkflow('wf-check', 'Check Video')],
      },
    });

    renderHome();

    const link = await screen.findByRole('link', { name: /Launch Workflow/ });
    expect(link).toHaveAttribute('href', '/workflows/wf-check');
  });

  it('renders the stage chips for each card', async () => {
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'GET /api/v1/workflows': { status: 200, body: [] },
    });

    renderHome();

    await screen.findByRole('heading', { name: 'Dougyin Translation & Dubbing' });
    expect(screen.getByText('Video Ingest')).toBeInTheDocument();
    expect(screen.getByText('Demucs BGM Separation')).toBeInTheDocument();
    expect(screen.getByText('ElevenLabs V6 Dub')).toBeInTheDocument();
    expect(screen.getByText('Shopee/Shop Scrape')).toBeInTheDocument();
    expect(screen.getByText('USF Feature Extraction')).toBeInTheDocument();
    expect(screen.getByText('Inspect the file')).toBeInTheDocument();
    expect(screen.getByText('Extract source audio')).toBeInTheDocument();
  });

  it('shows a load error when the API fails', async () => {
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'GET /api/v1/workflows': { status: 500, body: { detail: 'boom' } },
    });

    renderHome();

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load workflows');
  });
});
