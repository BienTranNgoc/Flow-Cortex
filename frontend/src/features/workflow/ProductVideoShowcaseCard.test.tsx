import { setToken } from '@flow-cortex/api-client';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderRoute, stubApi } from '../../test/fakeApi';
import { ProductVideoShowcaseCard } from './ProductVideoShowcaseCard';

const MC_STEPS = [
  { id: 's7', stepOrder: 7, type: 'generate_video', status: 'completed', attempt: 1, deliveryCount: 1, mode: 'remote' as const, outputs: [], children: [] },
  { id: 's8', stepOrder: 8, type: 'generate_video', status: 'completed', attempt: 1, deliveryCount: 1, mode: 'remote' as const, outputs: [], children: [] },
  { id: 's9', stepOrder: 9, type: 'lipsync_video', status: 'completed', attempt: 1, deliveryCount: 1, mode: 'remote' as const, outputs: [], children: [] },
  {
    id: 's10',
    stepOrder: 10,
    type: 'render_video',
    status: 'completed',
    attempt: 1,
    deliveryCount: 1,
    mode: 'remote' as const,
    outputs: [{ name: 'final.mp4', mime: 'video/mp4', bytes: 500_000, downloadable: true }],
    children: [],
  },
];

describe('ProductVideoShowcaseCard redo actions', () => {
  beforeEach(() => {
    setToken('t', true);
  });
  afterEach(() => {
    setToken(null);
    vi.unstubAllGlobals();
  });

  it('submits selective scene redo with optional note and handles 402 cost confirmation', async () => {
    const user = userEvent.setup();
    let attempt = 0;
    const calls = stubApi({
      'GET /api/v1/runs/r1/steps/10/outputs/final.mp4/url': {
        status: 200,
        body: { url: 'https://cdn.example/final.mp4', expiresAt: '2026-12-08T10:00:00Z' },
      },
      'POST /api/v1/product-videos/runs/r1/redo': () => {
        attempt += 1;
        if (attempt === 1) {
          return {
            status: 402,
            body: { status: 402, code: 'INSUFFICIENT_CREDITS', balance: 50, required: 120, shortfall: 70 },
          };
        }
        return {
          status: 200,
          body: { id: 'r2', workflowId: '9aa35334-b672-4b6c-93e8-1a4c2cc14302', status: 'running', steps: [] },
        };
      },
    });

    renderRoute(
      '/',
      '/',
      <ProductVideoShowcaseCard
        runId="r1"
        workflowId="9aa35334-b672-4b6c-93e8-1a4c2cc14302"
        renderStep={MC_STEPS[3]!}
        artifact={MC_STEPS[3]!.outputs[0]!}
        steps={MC_STEPS}
      />,
    );

    expect(screen.getByRole('button', { name: /Làm lại Cảnh 1|Redo Scene 1/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Đồng bộ lại khẩu hình|Re-sync lip/i })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Làm lại Cảnh 2|Redo Scene 2/i }));
    await user.type(screen.getByLabelText(/Ghi chú chỉnh cảnh|Scene adjustment note/i), 'Quay chậm hơn');
    await user.click(screen.getByRole('button', { name: /Xác nhận làm lại|Confirm redo/i }));

    const confirmCost = await screen.findByRole('button', { name: /Xác nhận tiếp tục|Confirm anyway/i });
    await user.click(confirmCost);

    await waitFor(() => {
      const redoCalls = calls.filter((c) => c.path === '/api/v1/product-videos/runs/r1/redo');
      expect(redoCalls.map((c) => c.body)).toEqual([
        { redoSteps: [8], sceneNote: 'Quay chậm hơn', acknowledgeCost: false },
        { redoSteps: [8], sceneNote: 'Quay chậm hơn', acknowledgeCost: true },
      ]);
    });
  });
});
