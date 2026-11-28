import { setToken } from '@flow-cortex/api-client';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderRoute, stubApi } from '../../test/fakeApi';
import { joinMcScript, ScriptEditor, splitMcScript } from './ScriptEditor';

const TASK = {
  id: 't1',
  runId: 'r1',
  runStepId: 's2',
  reviewType: 'APPROVAL_CHECKPOINT',
  status: 'pending',
  currentRevisionId: 'rev-1',
  version: 2,
  expiresAt: '2026-12-08T10:00:00Z',
};

describe('ScriptEditor', () => {
  beforeEach(() => {
    setToken('t', true);
  });
  afterEach(() => {
    setToken(null);
    vi.unstubAllGlobals();
  });

  it('splits and rejoins MC two-segment scripts cleanly', () => {
    expect(splitMcScript('Lời MC mở đầu.\n\nLời sản phẩm chốt đơn.')).toEqual([
      'Lời MC mở đầu.',
      'Lời sản phẩm chốt đơn.',
    ]);
    expect(splitMcScript('Câu một hay lắm! Câu hai chốt đơn ngay.')).toEqual([
      'Câu một hay lắm!',
      'Câu hai chốt đơn ngay.',
    ]);
    expect(splitMcScript('… Bình này giữ lạnh rất lâu! Mua ngay hôm nay.')).toEqual([
      '… Bình này giữ lạnh rất lâu!',
      'Mua ngay hôm nay.',
    ]);
    expect(splitMcScript('… Bình này giữ lạnh rất lâu!')).toEqual([
      '… Bình này giữ lạnh rất lâu!',
      '',
    ]);
    expect(splitMcScript('Bình giữ lạnh! Mua ngay.\u2028Miễn phí giao hàng.')).toEqual([
      'Bình giữ lạnh! Mua ngay.',
      'Miễn phí giao hàng.',
    ]);
    expect(splitMcScript('Bình giữ lạnh! Mua ngay.\rMiễn phí giao hàng.')).toEqual([
      'Bình giữ lạnh! Mua ngay.',
      'Miễn phí giao hàng.',
    ]);
    expect(joinMcScript('  Đoạn 1 ', ' Đoạn 2  ')).toBe('Đoạn 1\n\nĐoạn 2');
    // Collapses inner blank lines so the \n\n boundary survives API content.trim() intact.
    expect(splitMcScript(joinMcScript('Dòng 1\n\nDòng 2', 'Lời SP').trim())).toEqual([
      'Dòng 1\nDòng 2',
      'Lời SP',
    ]);
  });

  it('blocks saving when either MC segment is left empty', async () => {
    const user = userEvent.setup();
    const blocked = vi.fn();

    renderRoute(
      '/',
      '/',
      <ScriptEditor
        runId="r1"
        task={TASK}
        revisions={[{ id: 'rev-1', revisionNumber: 1, content: 'Lời MC cũ\n\nLời SP cũ' }]}
        isMcTemplate
        onApprovalBlockedChange={blocked}
      />,
    );

    const hookBox = screen.getByLabelText(/MC/i);
    await user.clear(hookBox);

    expect(screen.getByRole('button', { name: /Lưu bản sửa|Save revision/i })).toBeDisabled();
    expect(blocked).toHaveBeenLastCalledWith(true);
  });

  it('renders two separate segment editors for MC runs and joins them with blank line on save', async () => {
    const user = userEvent.setup();
    const calls = stubApi({
      'POST /api/v1/review-tasks/t1/revisions': {
        status: 201,
        body: { id: 'rev-2', revisionNumber: 2, content: 'MC mới\n\nSP mới' },
      },
    });
    const blocked = vi.fn();

    renderRoute(
      '/',
      '/',
      <ScriptEditor
        runId="r1"
        task={TASK}
        revisions={[{ id: 'rev-1', revisionNumber: 1, content: 'Lời MC cũ\n\nLời SP cũ' }]}
        isMcTemplate
        onApprovalBlockedChange={blocked}
      />,
    );

    const hookBox = screen.getByLabelText(/MC/i);
    const closeBox = screen.getByLabelText(/sản phẩm|product/i);
    expect(hookBox).toHaveValue('Lời MC cũ');
    expect(closeBox).toHaveValue('Lời SP cũ');

    await user.clear(hookBox);
    await user.type(hookBox, 'MC mới');
    await user.clear(closeBox);
    await user.type(closeBox, 'SP mới');

    await user.click(screen.getByRole('button', { name: /Lưu bản sửa|Save revision/i }));

    await waitFor(() => {
      const saveCall = calls.find((c) => c.path === '/api/v1/review-tasks/t1/revisions');
      expect(saveCall?.body).toEqual({ content: 'MC mới\n\nSP mới', expectedVersion: 2 });
    });
  });
});
