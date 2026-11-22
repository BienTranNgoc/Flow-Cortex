import { setToken } from '@flow-cortex/api-client';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { stubApi, type FakeReply } from '../../test/fakeApi';
import { stubModalDialog } from '../../test/modalDialog';
import { StepUpDialog } from './StepUpDialog';

const STEP_UP = 'POST /api/v1/me/step-up';

const GRANTED: FakeReply = {
  status: 200,
  body: { stepUpProof: 'proof-1', expiresAt: '2026-11-11T10:05:00Z' },
};

interface HarnessProps {
  onProof: (proof: string) => void;
  onCancel: () => void;
}

/** The caller owns `open`: it opens on a click and closes once a proof arrives or the member cancels. */
function Harness({ onProof, onCancel }: Readonly<HarnessProps>) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button type="button" onClick={() => setOpen(true)}>
        Create token
      </button>
      <StepUpDialog
        purpose="PAT_CREATE"
        open={open}
        onProof={(proof) => {
          setOpen(false);
          onProof(proof);
        }}
        onCancel={() => {
          setOpen(false);
          onCancel();
        }}
      />
    </>
  );
}

async function openDialog(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: 'Create token' }));
  return screen.getByLabelText('Password');
}

describe('StepUpDialog', () => {
  let restoreDialog: () => void;
  beforeEach(() => {
    restoreDialog = stubModalDialog();
    setToken('t', true);
  });
  afterEach(() => {
    restoreDialog();
    setToken(null);
    vi.unstubAllGlobals();
  });

  it('puts focus on the password field when it opens', async () => {
    stubApi({});
    const user = userEvent.setup();
    render(<Harness onProof={vi.fn()} onCancel={vi.fn()} />);

    const field = await openDialog(user);

    expect(screen.getByRole('dialog', { name: 'Confirm your password' })).toBeInTheDocument();
    expect(field).toHaveFocus();
  });

  it('hands over the proof once the password is accepted, without cancelling', async () => {
    const calls = stubApi({ [STEP_UP]: GRANTED });
    const onProof = vi.fn();
    const onCancel = vi.fn();
    const user = userEvent.setup();
    render(<Harness onProof={onProof} onCancel={onCancel} />);

    await user.type(await openDialog(user), 'correct horse');
    await user.click(screen.getByRole('button', { name: 'Confirm' }));

    await waitFor(() => expect(onProof).toHaveBeenCalledWith('proof-1'));
    expect(calls.find((call) => call.method === 'POST')?.body).toEqual({
      password: 'correct horse',
      purpose: 'PAT_CREATE',
    });
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(onCancel).not.toHaveBeenCalled();
  });

  it('says the password is wrong, clears the field and keeps the dialog open', async () => {
    stubApi({
      [STEP_UP]: {
        status: 422,
        body: { status: 422, code: 'STEP_UP_INVALID_PASSWORD', detail: 'Wrong password' },
      },
    });
    const onProof = vi.fn();
    const user = userEvent.setup();
    render(<Harness onProof={onProof} onCancel={vi.fn()} />);

    const field = await openDialog(user);
    await user.type(field, 'wrong');
    await user.click(screen.getByRole('button', { name: 'Confirm' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'That password is not correct. Try again.',
    );
    expect(field).toHaveValue('');
    expect(field).toHaveAttribute('aria-invalid', 'true');
    expect(field).toHaveFocus();
    expect(onProof).not.toHaveBeenCalled();
  });

  it('tells the member to wait when too many attempts were made', async () => {
    stubApi({
      [STEP_UP]: {
        status: 429,
        body: { status: 429, code: 'STEP_UP_RATE_LIMITED', detail: 'Too many attempts' },
      },
    });
    const onProof = vi.fn();
    const user = userEvent.setup();
    render(<Harness onProof={onProof} onCancel={vi.fn()} />);

    await user.type(await openDialog(user), 'anything');
    await user.click(screen.getByRole('button', { name: 'Confirm' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Too many attempts. Try again in 60 seconds.',
    );
    expect(onProof).not.toHaveBeenCalled();
  });

  it('cancels from the Cancel button', async () => {
    stubApi({});
    const onCancel = vi.fn();
    const user = userEvent.setup();
    render(<Harness onProof={vi.fn()} onCancel={onCancel} />);

    await openDialog(user);
    await user.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(onCancel).toHaveBeenCalledTimes(1);
  });
});
