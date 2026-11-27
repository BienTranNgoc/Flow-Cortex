import { setToken } from '@flow-cortex/api-client';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderRoute, settled, stubApi } from '../../test/fakeApi';
import { stubModalDialog } from '../../test/modalDialog';
import { BillingProfilesPage } from './BillingProfilesPage';
import { MAX_PROFILES } from './useBillingProfiles';

const LIST = 'GET /api/v1/billing/profiles';
const CREATE = 'POST /api/v1/billing/profiles';

describe('BillingProfilesPage', () => {
  let restoreDialog: () => void;
  beforeEach(() => {
    setToken('t', true);
    restoreDialog = stubModalDialog();
  });
  afterEach(() => {
    restoreDialog();
    setToken(null);
    vi.unstubAllGlobals();
  });

  async function openCreateDialog() {
    const user = userEvent.setup();
    const { queryClient } = renderRoute('/billing/profiles', '/billing/profiles', <BillingProfilesPage />);
    await settled(queryClient);
    await user.click(await screen.findByRole('button', { name: 'Add invoice details' }));
    return { user, dialog: await screen.findByRole('dialog') };
  }

  it('offers an empty state with an add action when the member has no profile', async () => {
    stubApi({ [LIST]: { status: 200, body: [] } });
    const { queryClient } = renderRoute('/billing/profiles', '/billing/profiles', <BillingProfilesPage />);
    await settled(queryClient);

    expect(await screen.findByText(/no invoice details yet/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Add invoice details' })).toBeInTheDocument();
  });

  it('shows the fields of the chosen type only', async () => {
    stubApi({ [LIST]: { status: 200, body: [] } });
    const { user, dialog } = await openCreateDialog();

    expect(within(dialog).getByLabelText('Tax code')).toBeInTheDocument();
    expect(within(dialog).queryByLabelText('Personal ID number')).not.toBeInTheDocument();

    await user.selectOptions(within(dialog).getByLabelText('Type'), 'personal');

    expect(within(dialog).queryByLabelText('Tax code')).not.toBeInTheDocument();
    expect(within(dialog).getByLabelText('Personal ID number')).toBeInTheDocument();
  });

  it('refuses a business without a tax code on the field and sends nothing', async () => {
    const calls = stubApi({ [LIST]: { status: 200, body: [] } });
    const { user, dialog } = await openCreateDialog();

    await user.type(within(dialog).getByLabelText('Name on the invoice'), 'Công ty ABC');
    await user.type(within(dialog).getByLabelText('Address'), '1 Tràng Tiền');
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));

    const taxCode = within(dialog).getByLabelText('Tax code');
    expect(taxCode).toHaveAttribute('aria-invalid', 'true');
    expect(taxCode).toHaveFocus();
    expect(within(dialog).getByText('Enter the tax code.')).toBeInTheDocument();
    expect(calls.some((call) => call.method === 'POST')).toBe(false);
  });

  it('keeps the dialog open and shows the server refusal on its field', async () => {
    stubApi({
      [LIST]: { status: 200, body: [] },
      [CREATE]: {
        status: 422,
        body: {
          status: 422,
          code: 'billing_profile_invalid',
          detail: 'These invoice details are not valid',
          errors: { taxCode: 'The tax code must be 10 digits, optionally followed by a dash and 3 digits' },
        },
      },
    });
    const { user, dialog } = await openCreateDialog();

    await user.type(within(dialog).getByLabelText('Name on the invoice'), 'Công ty ABC');
    await user.type(within(dialog).getByLabelText('Tax code'), '0101243150');
    await user.type(within(dialog).getByLabelText('Address'), '1 Tràng Tiền');
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));

    expect(await within(dialog).findByText(/tax code must be 10 digits/i)).toBeInTheDocument();
    expect(dialog).toHaveAttribute('open');
    expect(within(dialog).getByLabelText('Tax code')).toHaveAttribute('aria-invalid', 'true');
  });

  const profileRows = (count: number) =>
    Array.from({ length: count }, (_, index) => ({
      id: `00000000-0000-4000-8000-${String(index).padStart(12, '0')}`,
      type: 'business',
      legalName: `Công ty ${index + 1}`,
      taxCode: '0101243150',
      address: '1 Tràng Tiền',
      isDefault: index === 0,
      createdAt: '2026-11-26T00:00:00Z',
      updatedAt: '2026-11-26T00:00:00Z',
    }));

  it('disables Add and explains the limit when the member already holds 20 profiles', async () => {
    stubApi({ [LIST]: { status: 200, body: profileRows(MAX_PROFILES) } });
    const user = userEvent.setup();
    const { queryClient } = renderRoute('/billing/profiles', '/billing/profiles', <BillingProfilesPage />);
    await settled(queryClient);

    const add = await screen.findByRole('button', { name: 'Add invoice details' });
    await user.click(add);

    expect(add).toHaveAttribute('aria-disabled', 'true');
    expect(add).toHaveAccessibleDescription(/at most 20 invoice details/i);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('shows the message the server sends when a create is refused for the limit', async () => {
    stubApi({
      [LIST]: { status: 200, body: profileRows(MAX_PROFILES - 1) },
      [CREATE]: {
        status: 422,
        body: {
          status: 422,
          code: 'billing_profile_limit_reached',
          detail: 'You can keep at most 20 invoice details. Delete one to add another.',
        },
      },
    });
    const { user, dialog } = await openCreateDialog();

    await user.type(within(dialog).getByLabelText('Name on the invoice'), 'Công ty ABC');
    await user.type(within(dialog).getByLabelText('Tax code'), '0101243150');
    await user.type(within(dialog).getByLabelText('Address'), '1 Tràng Tiền');
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));

    expect(await within(dialog).findByText(/at most 20 invoice details/i)).toBeInTheDocument();
    expect(dialog).toHaveAttribute('open');
  });

  it('returns focus to the opener when the dialog is closed with Escape', async () => {
    stubApi({ [LIST]: { status: 200, body: [] } });
    const { user, dialog } = await openCreateDialog();
    expect(within(dialog).getByLabelText('Type')).toHaveFocus();

    await user.keyboard('{Escape}');

    expect(dialog).not.toHaveAttribute('open');
    expect(screen.getByRole('button', { name: 'Add invoice details' })).toHaveFocus();
  });
});
