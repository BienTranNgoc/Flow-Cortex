import { setToken, type DeletionBlockConditionView } from '@flow-cortex/api-client';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ADMIN_ME, renderRoute, stubApi } from '../../test/fakeApi';
import { SettingsPage } from './SettingsPage';

const ME = '/api/v1/auth/me';
const SETTINGS = '/api/v1/admin/settings';
const CONDITIONS = '/api/v1/admin/account-deletion/block-conditions';
const RUN = 'workflow.active_run';

const ACTIVE_RUN: DeletionBlockConditionView = {
  key: RUN,
  enabled: true,
  sortOrder: 10,
  messageVi: 'Còn quy trình đang chạy.',
  messageEn: 'A workflow is still running.',
  updatedAt: '2026-11-15T06:00:00Z',
};

const OPEN_REQUEST: DeletionBlockConditionView = {
  key: 'support.open_request',
  enabled: true,
  sortOrder: 20,
  messageVi: 'Còn yêu cầu hỗ trợ đang mở.',
  messageEn: 'A support request is open.',
  updatedBy: 'ops@nodi.vn',
  updatedAt: '2026-11-16T06:00:00Z',
};

function renderSettings() {
  return renderRoute('/settings', '/settings', <SettingsPage />);
}

async function rowOf(key: string) {
  return within(await waitFor(() => {
    const row = document.querySelector<HTMLElement>(`[data-condition="${key}"]`);
    if (!row) throw new Error(`no row for ${key}`);
    return row;
  }));
}

beforeEach(() => setToken('admin-token-deletion', true));
afterEach(() => {
  setToken(null);
  vi.unstubAllGlobals();
});

describe('DeletionConditionsSection', () => {
  it('saves one row and shows the answered message with the operator as last change', async () => {
    const calls = stubApi({
      [`GET ${ME}`]: { status: 200, body: ADMIN_ME },
      [`GET ${SETTINGS}`]: { status: 200, body: { items: [] } },
      [`GET ${CONDITIONS}`]: { status: 200, body: [ACTIVE_RUN, OPEN_REQUEST] },
      [`PUT ${CONDITIONS}/${RUN}`]: {
        status: 200,
        body: {
          ...ACTIVE_RUN,
          messageEn: 'Wait for your runs to finish.',
          updatedBy: 'admin@flowcortex.dev',
          updatedAt: '2026-11-20T01:00:00Z',
        },
      },
    });
    const user = userEvent.setup();
    renderSettings();

    expect(
      await screen.findByRole('heading', { name: 'Account deletion conditions' }),
    ).toBeInTheDocument();
    const row = await rowOf(RUN);
    const english = await row.findByRole('textbox', { name: 'Message to the member (English)' });
    await user.clear(english);
    await user.type(english, 'Wait for your runs to finish.');
    await user.click(row.getByRole('button', { name: 'Save' }));

    expect(await row.findByText('Changed by admin@flowcortex.dev at 11/20/2026, 08:00')).toBeInTheDocument();
    expect(calls.find((call) => call.method === 'PUT')?.body).toEqual({
      enabled: true,
      sortOrder: 10,
      messageVi: ACTIVE_RUN.messageVi,
      messageEn: 'Wait for your runs to finish.',
    });
    expect(calls.filter((call) => call.method === 'PUT')).toHaveLength(1);
    expect(row.getByRole('textbox', { name: 'Message to the member (English)' })).toHaveValue(
      'Wait for your runs to finish.',
    );
    const other = await rowOf('support.open_request');
    expect(other.getByText('Changed by ops@nodi.vn at 11/16/2026, 13:00')).toBeInTheDocument();
  });

  it('refuses an order outside 0-999 without calling the server and shows the server message for a refused text', async () => {
    const calls = stubApi({
      [`GET ${ME}`]: { status: 200, body: ADMIN_ME },
      [`GET ${SETTINGS}`]: { status: 200, body: { items: [] } },
      [`GET ${CONDITIONS}`]: { status: 200, body: [ACTIVE_RUN] },
      [`PUT ${CONDITIONS}/${RUN}`]: {
        status: 400,
        body: {
          status: 400,
          type: 'https://flowcortex.dev/problems/validation-failed',
          errors: { messageEn: 'must not be blank' },
        },
      },
    });
    const user = userEvent.setup();
    renderSettings();

    const row = await rowOf(RUN);
    const order = await row.findByRole('textbox', { name: 'Order' });
    await user.clear(order);
    await user.type(order, '1000');
    await user.click(row.getByRole('button', { name: 'Save' }));
    expect(await row.findByRole('alert')).toHaveTextContent('Enter a whole number from 0 to 999.');
    expect(calls.some((call) => call.method === 'PUT')).toBe(false);

    await user.clear(order);
    await user.type(order, '5');
    await user.clear(row.getByRole('textbox', { name: 'Message to the member (English)' }));
    await user.click(row.getByRole('button', { name: 'Save' }));
    expect(await row.findByRole('alert')).toHaveTextContent('must not be blank');
  });
});
