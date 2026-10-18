import { setToken, type WorkflowResponse } from '@flow-cortex/api-client';
import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route } from 'react-router-dom';
import { renderRoute, stubApi, type Handler, type RecordedCall } from '../../test/fakeApi';
import { FakeXhr } from '../../test/fakeXhr';
import { WorkflowDetailPage } from './WorkflowDetailPage';

const PRODUCT_VIDEO_ID = '9aa35334-b672-4b6c-93e8-1a4c2cc14301';

const PRODUCT_VIDEO: WorkflowResponse = {
  id: PRODUCT_VIDEO_ID,
  name: 'Product Video',
  isShared: true,
  steps: [
    { id: 's1', stepOrder: 1, name: 'Generate script', type: 'provider-script', mode: 'remote' },
  ],
};

const GRANT = {
  uploadId: 'up-1',
  url: 'http://localhost:9000/flowcortex-uploads/u/up-1?X-Amz-Signature=abc',
  method: 'PUT',
  headers: { 'Content-Type': 'image/png' },
  expiresAt: '2026-10-11T10:15:00Z',
  maxBytes: 10_485_760,
};

function image(name: string, size = 10) {
  const picked = new File(['0123456789'], name);
  Object.defineProperty(picked, 'size', { value: size });
  Object.defineProperty(picked, 'type', { value: 'image/png' });
  return picked;
}

function renderForm(routes: Record<string, Handler> = {}) {
  const calls = stubApi({
    'GET /api/v1/workflows': { status: 200, body: [PRODUCT_VIDEO] },
    'GET /api/v1/capabilities': {
      status: 200,
      body: {
        mediaEnabled: true,
        productVideoEnabled: true,
        productVideoWorkflowId: PRODUCT_VIDEO_ID,
      },
    },
    [`GET /api/v1/workflows/${PRODUCT_VIDEO_ID}`]: { status: 200, body: PRODUCT_VIDEO },
    'POST /api/v1/uploads': { status: 201, body: GRANT },
    'POST /api/v1/product-videos/runs': { status: 200, body: { id: 'r1', status: 'running' } },
    ...routes,
  });
  renderRoute(
    '/workflows/:id',
    `/workflows/${PRODUCT_VIDEO_ID}`,
    <WorkflowDetailPage />,
    <Route path="/runs/r1" element={<p>Product video run r1</p>} />,
  );
  return calls;
}

function sent(calls: RecordedCall[]) {
  return calls.filter((call) => call.method !== 'GET');
}

async function fillRequired(user: ReturnType<typeof userEvent.setup>) {
  await screen.findByLabelText('Product name *');
  await user.type(screen.getByLabelText('Product name *'), 'Bình giữ nhiệt');
  await user.type(screen.getByLabelText('Description *'), 'Giữ nhiệt 12 tiếng');
  await user.upload(screen.getByLabelText(/Product image/), [
    image('front.png'),
  ]);
}

describe('ProductVideoStartForm', () => {
  beforeEach(() => {
    setToken('t', false);
    FakeXhr.instances.length = 0;
    vi.stubGlobal('XMLHttpRequest', FakeXhr);
    vi.stubGlobal('scrollTo', vi.fn());
  });
  afterEach(() => {
    setToken(null);
    vi.unstubAllGlobals();
  });

  it('renders the product input fields and a single image picker', async () => {
    renderForm();

    expect(await screen.findByLabelText('Product name *')).toBeInTheDocument();
    expect(screen.getByLabelText('Description *')).toBeInTheDocument();
    expect(screen.queryByLabelText('Call to action *')).not.toBeInTheDocument();
    const picker = screen.getByLabelText(/Product image/);
    expect(picker).not.toHaveAttribute('multiple');
    expect(picker).toHaveAttribute('accept', 'image/jpeg,image/png,image/webp');
    expect(picker).toBeRequired();
  });

  it('asks for at least one product image when Start is pressed without one', async () => {
    const user = userEvent.setup();
    const calls = renderForm();

    await screen.findByLabelText('Product name *');
    await user.type(screen.getByLabelText('Product name *'), 'Bình giữ nhiệt');
    await user.type(screen.getByLabelText('Description *'), 'Giữ nhiệt 12 tiếng');
    await user.click(screen.getByRole('button', { name: 'Start run' }));

    expect(await screen.findByText('Add at least one product image')).toBeInTheDocument();
    expect(sent(calls)).toEqual([]);
  });

  it('refuses Start when a required text field is blank', async () => {
    const user = userEvent.setup();
    const calls = renderForm();

    await user.upload(await screen.findByLabelText(/Product image/), [image('front.png')]);
    await user.click(screen.getByRole('button', { name: 'Start run' }));

    expect(await screen.findByText('Product name is required.')).toBeInTheDocument();
    expect(sent(calls)).toEqual([]);
  });

  it('rejects an .avi on pick and Start sends nothing', async () => {
    const user = userEvent.setup();
    const calls = renderForm();

    await user.upload(await screen.findByLabelText(/Product image/), [image('holiday.avi')]);

    expect(screen.getByText('Use .jpg, .jpeg, .png or .webp.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Start run' }));
    expect(sent(calls)).toEqual([]);
  });

  it('rejects an over-10MB image on pick', async () => {
    const user = userEvent.setup();
    const calls = renderForm();

    await user.upload(await screen.findByLabelText(/Product image/), [
      image('huge.png', 11 * 1024 * 1024),
    ]);

    expect(
      screen.getByText('Files of this type must be at most 10 MB. Accepted: .jpg, .jpeg, .png, .webp.'),
    ).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Start run' }));
    expect(sent(calls)).toEqual([]);
  });

  it('uploads the image, then starts the run, then opens the run page', async () => {
    const user = userEvent.setup();
    const calls = renderForm();
    await fillRequired(user);

    await user.click(screen.getByRole('button', { name: 'Start run' }));

    await waitFor(() => expect(FakeXhr.instances).toHaveLength(1));
    act(() => FakeXhr.instances[0]!.respond(200));

    expect(await screen.findByText('Product video run r1')).toBeInTheDocument();
    expect(sent(calls).map((call) => call.path)).toEqual([
      '/api/v1/uploads',
      '/api/v1/product-videos/runs',
    ]);
    const start = sent(calls).find((call) => call.path === '/api/v1/product-videos/runs')!;
    expect(start.body).toEqual({
      productName: 'Bình giữ nhiệt',
      description: 'Giữ nhiệt 12 tiếng',
      uploadIds: ['up-1'],
      video: { aspectRatio: '9:16' },
    });
  });

  it('shows the upload-failed message and Retry restarts from /uploads', async () => {
    const user = userEvent.setup();
    const calls = renderForm();
    await fillRequired(user);

    await user.click(screen.getByRole('button', { name: 'Start run' }));
    await waitFor(() => expect(FakeXhr.instances).toHaveLength(1));
    act(() => FakeXhr.instances[0]!.failNetwork());

    expect(await screen.findByText("One of the uploads didn't finish. Try again.")).toBeInTheDocument();
    expect(sent(calls).map((call) => call.path)).toEqual(['/api/v1/uploads']);

    await user.click(screen.getByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(FakeXhr.instances).toHaveLength(2));
    act(() => FakeXhr.instances[1]!.respond(200));

    expect(await screen.findByText('Product video run r1')).toBeInTheDocument();
    expect(sent(calls).filter((call) => call.path === '/api/v1/uploads')).toHaveLength(2);
    expect(sent(calls).filter((call) => call.path === '/api/v1/product-videos/runs')).toHaveLength(1);
  });

  it('shows a 503 from the run start on the form', async () => {
    const user = userEvent.setup();
    renderForm({
      'POST /api/v1/product-videos/runs': {
        status: 503,
        body: { status: 503, detail: 'Product video is currently disabled' },
      },
    });
    await fillRequired(user);

    await user.click(await screen.findByRole('button', { name: 'Start run' }));
    await waitFor(() => expect(FakeXhr.instances).toHaveLength(1));
    act(() => FakeXhr.instances[0]!.respond(200));

    expect(
      await screen.findByText('Product video is currently disabled'),
    ).toBeInTheDocument();
  });
});
