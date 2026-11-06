import { ApiError } from '@flow-cortex/api-client';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import i18n from '../../lib/i18n';
import { startProblem } from '../workflow/startProblem';
import { CreditsRefusal } from './CreditsRefusal';

const REFUSAL_BODY: Record<string, unknown> = {
  status: 402,
  code: 'INSUFFICIENT_CREDITS',
  detail: 'Not enough credits',
  balance: 1500,
  required: 12000,
  shortfall: 10500,
};
const REFUSAL = new ApiError(402, REFUSAL_BODY);

describe('CreditsRefusal in Vietnamese', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('vi');
  });
  afterEach(async () => {
    await i18n.changeLanguage('en');
  });

  it('states the shortfall with vi-VN grouping and offers "Vẫn chạy"', async () => {
    const problem = startProblem(REFUSAL);
    if (problem.kind !== 'credits') throw new Error(`expected a credits refusal, got ${problem.kind}`);
    const onRunAnyway = vi.fn();
    render(<CreditsRefusal message={problem.message} busy={false} onRunAnyway={onRunAnyway} />);

    expect(screen.getByRole('alert')).toHaveTextContent(
      'Không đủ Credit: bạn có 1.500, lượt chạy này cần 12.000 (còn thiếu 10.500).',
    );
    await userEvent.setup().click(screen.getByRole('button', { name: 'Vẫn chạy' }));
    expect(onRunAnyway).toHaveBeenCalledTimes(1);
  });
});
