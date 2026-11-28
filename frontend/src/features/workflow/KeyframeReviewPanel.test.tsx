import { setToken } from '@flow-cortex/api-client';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderRoute, stubApi } from '../../test/fakeApi';
import { KeyframeReviewPanel } from './KeyframeReviewPanel';

const KEYFRAME_STEP = {
  id: 's5',
  stepOrder: 5,
  type: 'generate_keyframe',
  status: 'completed',
  attempt: 1,
  deliveryCount: 1,
  mode: 'remote' as const,
  children: [],
  outputs: [{ name: 'keyframe.jpg', mime: 'image/jpeg', bytes: 120_000, downloadable: true }],
};

describe('KeyframeReviewPanel', () => {
  beforeEach(() => {
    setToken('t', true);
  });
  afterEach(() => {
    setToken(null);
    vi.unstubAllGlobals();
  });

  it('loads 9:16 keyframe preview, lets user pick background + note, and calls regenerate endpoint', async () => {
    const user = userEvent.setup();
    const calls = stubApi({
      'GET /api/v1/product-videos/templates': { status: 500, body: {} },
      'GET /api/v1/runs/r1/steps/5/outputs/keyframe.jpg/url': {
        status: 200,
        body: { url: 'https://cdn.example/keyframe.jpg', expiresAt: '2026-12-08T10:00:00Z' },
      },
      'POST /api/v1/product-videos/runs/r1/keyframes/regenerate': {
        status: 200,
        body: { id: 'r1', status: 'running', steps: [] },
      },
    });
    const onApprove = vi.fn();
    const onReject = vi.fn();

    renderRoute(
      '/',
      '/',
      <KeyframeReviewPanel
        runId="r1"
        keyframeStep={KEYFRAME_STEP}
        onApprove={onApprove}
        onReject={onReject}
        approvePending={false}
        rejectPending={false}
      />,
    );

    const img = await waitFor(() => {
      const found = screen.getByRole('img', { name: /9:16/i });
      expect(found).toHaveAttribute('src', 'https://cdn.example/keyframe.jpg');
      return found;
    });

    const approveBtn = screen.getByRole('button', {
      name: /Duyệt & Sinh video|Approve & generate/i,
    });
    expect(approveBtn).toBeDisabled();
    fireEvent.load(img);
    expect(approveBtn).toBeEnabled();

    await user.click(screen.getByRole('button', { name: /Phòng nắng ấm/i }));
    await user.type(screen.getByLabelText(/Ghi chú bối cảnh|Scene note/i), 'Ánh sáng dịu hơn');
    await user.click(screen.getByRole('button', { name: /Tạo lại ảnh|Regenerate image/i }));

    await waitFor(() => {
      const regenCall = calls.find(
        (c) => c.path === '/api/v1/product-videos/runs/r1/keyframes/regenerate',
      );
      expect(regenCall?.body).toEqual({
        backgroundPresetId: 'bg-warm-wood',
        sceneNote: 'Ánh sáng dịu hơn',
        acknowledgeCost: false,
      });
    });

    await user.click(approveBtn);
    expect(onApprove).toHaveBeenCalledTimes(1);
  });

  it('offers cost confirmation on 402 INSUFFICIENT_CREDITS with positive balance and resends with acknowledgeCost', async () => {
    const user = userEvent.setup();
    let attempt = 0;
    const calls = stubApi({
      'GET /api/v1/product-videos/templates': { status: 500, body: {} },
      'GET /api/v1/runs/r1/steps/5/outputs/keyframe.jpg/url': {
        status: 200,
        body: { url: 'https://cdn.example/keyframe.jpg', expiresAt: '2026-12-08T10:00:00Z' },
      },
      'POST /api/v1/product-videos/runs/r1/keyframes/regenerate': () => {
        attempt += 1;
        if (attempt === 1) {
          return {
            status: 402,
            body: { status: 402, code: 'INSUFFICIENT_CREDITS', balance: 20, required: 50, shortfall: 30 },
          };
        }
        return { status: 200, body: { id: 'r1', status: 'running', steps: [] } };
      },
    });

    renderRoute(
      '/',
      '/',
      <KeyframeReviewPanel
        runId="r1"
        keyframeStep={KEYFRAME_STEP}
        onApprove={vi.fn()}
        onReject={vi.fn()}
        approvePending={false}
        rejectPending={false}
      />,
    );

    await user.click(screen.getByRole('button', { name: /Tạo lại ảnh|Regenerate image/i }));
    const confirmBtn = await screen.findByRole('button', {
      name: /Xác nhận tạo lại|Confirm regenerate/i,
    });
    await user.click(confirmBtn);

    await waitFor(() => {
      const regenCalls = calls.filter(
        (c) => c.path === '/api/v1/product-videos/runs/r1/keyframes/regenerate',
      );
      expect(regenCalls.map((c) => c.body)).toEqual([
        { acknowledgeCost: false },
        { acknowledgeCost: true },
      ]);
    });
  });
});
