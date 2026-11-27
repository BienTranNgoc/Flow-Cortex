import { setToken, type WorkflowResponse } from '@flow-cortex/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import i18n from '../../lib/i18n';
import { renderRoute, stubApi, type Handler, type RecordedCall } from '../../test/fakeApi';
import { FakeXhr } from '../../test/fakeXhr';
import { WorkflowDetailPage } from './WorkflowDetailPage';

const PRODUCT_VIDEO_ID = '9aa35334-b672-4b6c-93e8-1a4c2cc14301';

const PRODUCT_VIDEO: WorkflowResponse = {
  id: PRODUCT_VIDEO_ID,
  name: 'Product Video',
  visibility: 'public',
  available: true,
  allowFork: false,
  forkCount: 0,
  startInput: 'upload',
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
  await user.upload(screen.getByLabelText(/Product image/), [image('front.png')]);
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

  it('renders the product input fields and a multi-image picker', async () => {
    renderForm();

    expect(await screen.findByLabelText('Product name *')).toBeInTheDocument();
    expect(screen.getByLabelText('Description *')).toBeInTheDocument();
    expect(screen.queryByLabelText('Call to action *')).not.toBeInTheDocument();
    const picker = screen.getByLabelText(/Product image/);
    expect(picker).toHaveAttribute('multiple');
    expect(picker).toHaveAttribute('accept', 'image/jpeg,image/png,image/webp');
    expect(picker).toBeRequired();
  });

  it('supports picking up to 3 images, removing one, and rejecting a 4th', async () => {
    const user = userEvent.setup();
    renderForm();
    const picker = await screen.findByLabelText(/Product image/);

    await user.upload(picker, [image('front.png'), image('side.png')]);
    expect(screen.getByText('front.png')).toBeInTheDocument();
    expect(screen.getByText('side.png')).toBeInTheDocument();
    expect(screen.getByText('2 / 3 photos')).toBeInTheDocument();

    await user.upload(picker, [image('back.png')]);
    expect(screen.getByText('back.png')).toBeInTheDocument();
    expect(screen.getByText('3 / 3 photos')).toBeInTheDocument();

    await user.upload(picker, [image('extra.png')]);
    expect(screen.getByText('At most 3 images per run.')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Remove extra.png' }));
    expect(screen.queryByText('At most 3 images per run.')).not.toBeInTheDocument();
    expect(screen.getByText('3 / 3 photos')).toBeInTheDocument();
  });

  it('keeps the hidden file input out of the tab order so only the visible picker takes focus', async () => {
    renderForm();

    expect(await screen.findByLabelText(/Product image/)).toHaveAttribute('tabindex', '-1');
  });

  it('ties the image error to the visible picker button', async () => {
    const user = userEvent.setup();
    renderForm();

    await screen.findByLabelText('Product name *');
    await user.type(screen.getByLabelText('Product name *'), 'Bình giữ nhiệt');
    await user.type(screen.getByLabelText('Description *'), 'Giữ nhiệt 12 tiếng');
    await user.click(screen.getByRole('button', { name: 'Start run' }));

    await screen.findByText('Add at least one product image');
    const picker = screen.getByRole('button', { name: /Drag & drop 1–3 photos/ });
    expect(picker).toHaveAccessibleDescription('Add at least one product image');
  });

  it('ties the image error to the Add photo button once a rejected file is chosen', async () => {
    const user = userEvent.setup();
    renderForm();

    await user.upload(await screen.findByLabelText(/Product image/), [image('holiday.avi')]);

    expect(await screen.findByRole('button', { name: 'Add photo' })).toHaveAccessibleDescription(
      'Use .jpg, .jpeg, .png or .webp.',
    );
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

  it('starts the run once the blank field is filled after a refusal', async () => {
    const user = userEvent.setup();
    const calls = renderForm();

    await user.upload(await screen.findByLabelText(/Product image/), [image('front.png')]);
    await user.click(screen.getByRole('button', { name: 'Start run' }));
    expect(await screen.findByText('Product name is required.')).toBeInTheDocument();

    await user.type(screen.getByLabelText('Product name *'), 'Bình giữ nhiệt');
    expect(screen.queryByText('Product name is required.')).not.toBeInTheDocument();
    await user.type(screen.getByLabelText('Description *'), 'Giữ nhiệt 12 tiếng');
    await user.click(screen.getByRole('button', { name: 'Start run' }));

    await waitFor(() => expect(FakeXhr.instances).toHaveLength(1));
    act(() => FakeXhr.instances[0]!.respond(200));
    expect(await screen.findByText('Product video run r1')).toBeInTheDocument();
    expect(sent(calls).map((call) => call.path)).toEqual([
      '/api/v1/uploads',
      '/api/v1/product-videos/runs',
    ]);
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
      screen.getByText(
        'Files of this type must be at most 10 MB. Accepted: .jpg, .jpeg, .png, .webp.',
      ),
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
      templateId: 'showroom-v1',
      voiceGender: 'male',
    });
  });

  it('disables mc-v1 by default until enabled by catalog, and submits mc-v1 when available', async () => {
    renderForm();
    await screen.findByLabelText('Product name *');
    expect(screen.getByRole('radio', { name: /MC giới thiệu sản phẩm/ })).toBeDisabled();
    expect(screen.getByText('Coming soon')).toBeInTheDocument();
  });

  it('switches to mc-v1 when available, auto-syncs female voice from MC preset, and submits preset & background fields', async () => {
    const user = userEvent.setup();
    const { FALLBACK_PRODUCT_VIDEO_CATALOG } = await import('./productVideoPresets');
    const calls = renderForm({
      'GET /api/v1/product-videos/templates': {
        status: 200,
        body: {
          ...FALLBACK_PRODUCT_VIDEO_CATALOG,
          templates: FALLBACK_PRODUCT_VIDEO_CATALOG.templates.map((tpl) => ({
            ...tpl,
            available: true,
          })),
        },
      },
    });
    await fillRequired(user);

    const mcRadio = screen.getByRole('radio', { name: /MC giới thiệu sản phẩm/ });
    await waitFor(() => expect(mcRadio).toBeEnabled());
    await user.click(mcRadio);
    expect(screen.getByRole('radio', { name: 'Female voice' })).toHaveAttribute(
      'aria-checked',
      'true',
    );

    await user.click(screen.getByRole('button', { name: /Studio tối giản/ }));
    await user.click(screen.getByRole('button', { name: 'Start run' }));

    await waitFor(() => expect(FakeXhr.instances).toHaveLength(1));
    act(() => FakeXhr.instances[0]!.respond(200));

    expect(await screen.findByText('Product video run r1')).toBeInTheDocument();
    const start = sent(calls).find((call) => call.path === '/api/v1/product-videos/runs')!;
    expect(start.body).toEqual({
      productName: 'Bình giữ nhiệt',
      description: 'Giữ nhiệt 12 tiếng',
      uploadIds: ['up-1'],
      video: { aspectRatio: '9:16' },
      templateId: 'mc-v1',
      voiceGender: 'female',
      mcPresetId: 'mc-nu-tre',
      backgroundPresetId: 'bg-studio-minimal',
    });
  });

  it('supports custom Vietnamese sceneNote for background', async () => {
    const user = userEvent.setup();
    const calls = renderForm();
    await fillRequired(user);

    await user.click(screen.getByRole('tab', { name: 'Describe' }));
    await user.type(
      screen.getByPlaceholderText(/Warm wooden counter/),
      'Quầy gỗ sồi ánh nắng sáng',
    );
    await user.click(screen.getByRole('button', { name: 'Start run' }));

    await waitFor(() => expect(FakeXhr.instances).toHaveLength(1));
    act(() => FakeXhr.instances[0]!.respond(200));

    expect(await screen.findByText('Product video run r1')).toBeInTheDocument();
    const start = sent(calls).find((call) => call.path === '/api/v1/product-videos/runs')!;
    expect(start.body).toEqual({
      productName: 'Bình giữ nhiệt',
      description: 'Giữ nhiệt 12 tiếng',
      uploadIds: ['up-1'],
      video: { aspectRatio: '9:16' },
      templateId: 'showroom-v1',
      voiceGender: 'male',
      sceneNote: 'Quầy gỗ sồi ánh nắng sáng',
    });
  });

  it('shows the upload-failed message and Retry restarts from /uploads', async () => {
    const user = userEvent.setup();
    const calls = renderForm();
    await fillRequired(user);

    await user.click(screen.getByRole('button', { name: 'Start run' }));
    await waitFor(() => expect(FakeXhr.instances).toHaveLength(1));
    act(() => FakeXhr.instances[0]!.failNetwork());

    expect(
      await screen.findByText("One of the uploads didn't finish. Try again."),
    ).toBeInTheDocument();
    expect(sent(calls).map((call) => call.path)).toEqual(['/api/v1/uploads']);

    await user.click(screen.getByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(FakeXhr.instances).toHaveLength(2));
    act(() => FakeXhr.instances[1]!.respond(200));

    expect(await screen.findByText('Product video run r1')).toBeInTheDocument();
    expect(sent(calls).filter((call) => call.path === '/api/v1/uploads')).toHaveLength(2);
    expect(sent(calls).filter((call) => call.path === '/api/v1/product-videos/runs')).toHaveLength(
      1,
    );
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

    expect(await screen.findByText('Product video is currently disabled')).toBeInTheDocument();
  });

  describe('in Vietnamese', () => {
    beforeEach(async () => {
      await i18n.changeLanguage('vi');
    });
    afterEach(async () => {
      await i18n.changeLanguage('en');
    });

    it('labels every field and names the picked image', async () => {
      const user = userEvent.setup();
      renderForm();

      expect(
        await screen.findByRole('form', { name: 'Bắt đầu lượt chạy video sản phẩm' }),
      ).toBeInTheDocument();
      expect(screen.getByLabelText('Tên sản phẩm *')).toBeInTheDocument();
      expect(screen.getByLabelText('Mô tả *')).toBeInTheDocument();
      const picker = screen.getByLabelText('Ảnh sản phẩm (JPG/PNG/WebP, tối đa 10 MB) *');

      await user.upload(picker, [image('front.png')]);

      expect(screen.getByText('front.png')).toBeInTheDocument();
      expect(screen.getByText('1 / 3 ảnh')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Bắt đầu lượt chạy' })).toBeInTheDocument();
    });
  });

  it('updates live 9:16 preview, supports image replacement/removal, and prefills draft state', async () => {
    const user = userEvent.setup();
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    const fullSevenSteps: WorkflowResponse = {
      ...PRODUCT_VIDEO,
      estimatedCredits: 25,
      steps: [
        { id: 's1', stepOrder: 1, type: 'generate_script', mode: 'remote' },
        { id: 's2', stepOrder: 2, type: 'APPROVAL_CHECKPOINT', mode: 'inline' },
        { id: 's3', stepOrder: 3, type: 'generate_storyboard', mode: 'remote' },
        { id: 's4', stepOrder: 4, type: 'generate_speech', mode: 'remote' },
        { id: 's5', stepOrder: 5, type: 'generate_video', mode: 'remote' },
        { id: 's6', stepOrder: 6, type: 'generate_video', mode: 'remote' },
        { id: 's7', stepOrder: 7, type: 'render_video', mode: 'remote' },
      ],
    };

    stubApi({
      'GET /api/v1/workflows': { status: 200, body: [fullSevenSteps] },
      'GET /api/v1/capabilities': {
        status: 200,
        body: {
          mediaEnabled: true,
          productVideoEnabled: true,
          productVideoWorkflowId: PRODUCT_VIDEO_ID,
        },
      },
      [`GET /api/v1/workflows/${PRODUCT_VIDEO_ID}`]: { status: 200, body: fullSevenSteps },
    });

    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter
          initialEntries={[
            {
              pathname: `/workflows/${PRODUCT_VIDEO_ID}`,
              state: {
                productDraft: {
                  productName: 'Warm Flask',
                  description: 'Keeps coffee hot 12h.',
                },
              },
            },
          ]}
        >
          <Routes>
            <Route path="/workflows/:id" element={<WorkflowDetailPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    expect(await screen.findByLabelText('Product name *')).toHaveValue('Warm Flask');
    expect(screen.getByLabelText('Description *')).toHaveValue('Keeps coffee hot 12h.');

    const preview = screen.getByRole('region', { name: '9:16 preview' });
    expect(within(preview).getByText('Warm Flask')).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: '7-step pipeline' })).not.toBeInTheDocument();
    expect(screen.getByText('Estimated cost: 25 credits')).toBeInTheDocument();

    const picker = screen.getByLabelText('Product image (JPG/PNG/WebP, ≤ 10 MB) *');
    await user.upload(picker, [image('bottle.png')]);
    expect(screen.getByText('bottle.png')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Remove' }));
    expect(screen.queryByText('bottle.png')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Call to action *')).not.toBeInTheDocument();
  });

  it('restores product images passed via productDraft state on retry', async () => {
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    const baseFetch = globalThis.fetch;
    stubApi({
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
    });
    const stubbedFetch = globalThis.fetch;
    vi.stubGlobal(
      'fetch',
      vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
        const url = typeof input === 'string' ? input : input.toString();
        if (url.startsWith('http://minio.test/')) {
          return Promise.resolve(
            new Response(new Blob(['imgdata'], { type: 'image/jpeg' }), {
              status: 200,
              headers: { 'Content-Type': 'image/jpeg' },
            }),
          );
        }
        return stubbedFetch(input, init);
      }),
    );

    try {
      render(
        <QueryClientProvider client={queryClient}>
          <MemoryRouter
            initialEntries={[
              {
                pathname: `/workflows/${PRODUCT_VIDEO_ID}`,
                state: {
                  productDraft: {
                    productName: 'Royal M239',
                    description: 'Mũ bảo hiểm 1/2 đầu',
                    images: [
                      { name: 'image_1', mimeType: 'image/jpeg', url: 'http://minio.test/image_1' },
                      { name: 'image_2', mimeType: 'image/jpeg', url: 'http://minio.test/image_2' },
                    ],
                  },
                },
              },
            ]}
          >
            <Routes>
              <Route path="/workflows/:id" element={<WorkflowDetailPage />} />
            </Routes>
          </MemoryRouter>
        </QueryClientProvider>,
      );

      expect(await screen.findByText('image_1.jpg')).toBeInTheDocument();
      expect(screen.getByText('image_2.jpg')).toBeInTheDocument();
      expect(screen.getByText('2 / 3 photos')).toBeInTheDocument();
    } finally {
      vi.stubGlobal('fetch', baseFetch);
    }
  });

  describe('restoring the images of a previous run', () => {
    const baseFetch = globalThis.fetch;
    afterEach(() => {
      vi.stubGlobal('fetch', baseFetch);
    });

    /** Renders the form arriving with two images to restore; `answer` serves each image URL. */
    function renderRestoring(answer: (url: string) => Promise<Response>) {
      stubApi({
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
      });
      const stubbedFetch = globalThis.fetch;
      vi.stubGlobal(
        'fetch',
        vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
          const url = typeof input === 'string' ? input : input.toString();
          return url.startsWith('http://minio.test/') ? answer(url) : stubbedFetch(input, init);
        }),
      );
      const queryClient = new QueryClient({
        defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
      });
      render(
        <QueryClientProvider client={queryClient}>
          <MemoryRouter
            initialEntries={[
              {
                pathname: `/workflows/${PRODUCT_VIDEO_ID}`,
                state: {
                  productDraft: {
                    productName: 'Royal M239',
                    description: 'Mũ bảo hiểm 1/2 đầu',
                    images: [
                      { name: 'image_1', mimeType: 'image/jpeg', url: 'http://minio.test/image_1' },
                      { name: 'image_2', mimeType: 'image/jpeg', url: 'http://minio.test/image_2' },
                    ],
                  },
                },
              },
            ]}
          >
            <Routes>
              <Route path="/workflows/:id" element={<WorkflowDetailPage />} />
            </Routes>
          </MemoryRouter>
        </QueryClientProvider>,
      );
    }

    const jpeg = () =>
      new Response(new Blob(['imgdata'], { type: 'image/jpeg' }), {
        status: 200,
        headers: { 'Content-Type': 'image/jpeg' },
      });

    it('keeps the images that came back and says how many did not', async () => {
      renderRestoring((url) =>
        Promise.resolve(url.endsWith('image_2') ? new Response(null, { status: 403 }) : jpeg()),
      );

      expect(await screen.findByText('image_1.jpg')).toBeInTheDocument();
      expect(screen.queryByText('image_2.jpg')).not.toBeInTheDocument();
      expect(screen.getByText('1 / 3 photos')).toBeInTheDocument();
      expect(await screen.findByRole('alert')).toHaveTextContent(
        '1 photo from the previous run could not be restored. Add it again before you start.',
      );
      expect(screen.queryByText(/Restoring the product photos/)).not.toBeInTheDocument();
    });

    it('says so when no image came back', async () => {
      renderRestoring(() => Promise.reject(new TypeError('network down')));

      expect(await screen.findByRole('alert')).toHaveTextContent(
        '2 photos from the previous run could not be restored. Add them again before you start.',
      );
      expect(screen.getByText('0 / 3 photos')).toBeInTheDocument();
    });

    it('shows a loading line and holds the start button while the images are fetched', async () => {
      let release: (response: Response) => void = () => undefined;
      const pending = new Promise<Response>((resolve) => (release = resolve));
      renderRestoring((url) => (url.endsWith('image_1') ? pending : Promise.resolve(jpeg())));

      const loading = await screen.findByText(
        'Restoring the product photos from the previous run…',
      );
      expect(loading).toHaveAttribute('role', 'status');
      expect(screen.getByRole('button', { name: 'Start run' })).toBeDisabled();

      await act(async () => release(jpeg()));

      await waitFor(() => expect(loading).not.toBeInTheDocument());
      expect(screen.getByRole('button', { name: 'Start run' })).toBeEnabled();
      expect(screen.getByText('2 / 3 photos')).toBeInTheDocument();
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    });

    it('ignores mc and background images when restoring productDraft images', async () => {
      stubApi({
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
      });
      const stubbedFetch = globalThis.fetch;
      vi.stubGlobal(
        'fetch',
        vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
          const url = typeof input === 'string' ? input : input.toString();
          return url.startsWith('http://minio.test/')
            ? Promise.resolve(jpeg())
            : stubbedFetch(input, init);
        }),
      );
      const queryClient = new QueryClient({
        defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
      });
      render(
        <QueryClientProvider client={queryClient}>
          <MemoryRouter
            initialEntries={[
              {
                pathname: `/workflows/${PRODUCT_VIDEO_ID}`,
                state: {
                  productDraft: {
                    productName: 'Redmi Watch 5 Lite',
                    description: 'Màn hình AMOLED',
                    images: [
                      { name: 'mc', mimeType: 'image/jpeg', url: 'http://minio.test/mc' },
                      { name: 'image_1', mimeType: 'image/jpeg', url: 'http://minio.test/image_1' },
                      {
                        name: 'background',
                        mimeType: 'image/jpeg',
                        url: 'http://minio.test/background',
                      },
                    ],
                  },
                },
              },
            ]}
          >
            <Routes>
              <Route path="/workflows/:id" element={<WorkflowDetailPage />} />
            </Routes>
          </MemoryRouter>
        </QueryClientProvider>,
      );

      expect(await screen.findByText('image_1.jpg')).toBeInTheDocument();
      expect(screen.queryByText('mc.jpg')).not.toBeInTheDocument();
      expect(screen.queryByText('background.jpg')).not.toBeInTheDocument();
      expect(screen.getByText('1 / 3 photos')).toBeInTheDocument();
    });
  });
});
