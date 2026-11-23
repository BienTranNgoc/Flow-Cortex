import { setToken, type AdminOnboardingSample } from '@flow-cortex/api-client';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ADMIN_ME, renderRoute, staffMe, stubApi } from '../../test/fakeApi';
import { OnboardingSamplePage } from './OnboardingSamplePage';

const ME = '/api/v1/auth/me';
const SAMPLE = '/api/v1/admin/onboarding/sample';

const VIEW: AdminOnboardingSample = {
  workflow: { id: 'wf-1', name: 'Release notes' },
  shown: true,
  reason: null,
  sampleInput: 'Summarise the changelog',
  sourceLocale: 'en',
  sourceHash: 'a'.repeat(64),
  translations: { vi: 'Tóm tắt nhật ký thay đổi' },
  updatedBy: 'admin@nodi.vn',
  updatedAt: '2026-10-19T06:00:00Z',
};

function renderPage() {
  return renderRoute('/onboarding-sample', '/onboarding-sample', <OnboardingSamplePage />);
}

beforeEach(() => setToken('admin-token-onboarding', true));
afterEach(() => {
  setToken(null);
  vi.unstubAllGlobals();
});

describe('OnboardingSamplePage', () => {
  it('is read-only without the update key', async () => {
    stubApi({
      [`GET ${ME}`]: {
        status: 200,
        body: staffMe('CUSTOMER_SUPPORT', ['platform.setting.view']),
      },
      [`GET ${SAMPLE}`]: { status: 200, body: VIEW },
    });
    renderPage();

    expect(
      await screen.findByText('You can view the onboarding sample but not change it.'),
    ).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: 'Workflow ID' })).toHaveAttribute('readonly');
    expect(screen.getByRole('textbox', { name: 'Sample input' })).toHaveAttribute('readonly');
    expect(screen.getByRole('combobox', { name: 'Language of the input' })).toBeDisabled();
    expect(screen.getByRole('textbox', { name: 'Input in Vietnamese' })).toHaveAttribute('readonly');
    expect(screen.queryByRole('button', { name: 'Save' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Clear' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Save translation' })).not.toBeInTheDocument();
  });

  it('keeps every write control for a caller holding the update key', async () => {
    const user = userEvent.setup();
    const calls = stubApi({
      [`GET ${ME}`]: { status: 200, body: ADMIN_ME },
      [`GET ${SAMPLE}`]: { status: 200, body: VIEW },
      [`PUT ${SAMPLE}`]: { status: 200, body: VIEW },
    });
    renderPage();

    const workflow = await screen.findByRole('textbox', { name: 'Workflow ID' });
    expect(screen.queryByText('You can view the onboarding sample but not change it.')).not.toBeInTheDocument();
    expect(workflow).not.toHaveAttribute('readonly');
    expect(screen.getByRole('textbox', { name: 'Input in Vietnamese' })).not.toHaveAttribute('readonly');
    expect(screen.getByRole('button', { name: 'Clear' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Save translation' })).toBeEnabled();

    await user.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() =>
      expect(calls.find((call) => call.method === 'PUT' && call.path === SAMPLE)?.body).toEqual({
        workflowId: 'wf-1',
        sampleInput: 'Summarise the changelog',
        sourceLocale: 'en',
      }),
    );
  });
});
