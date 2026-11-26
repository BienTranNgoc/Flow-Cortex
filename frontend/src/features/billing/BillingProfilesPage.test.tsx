import { setToken } from '@flow-cortex/api-client';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderRoute, settled, stubApi } from '../../test/fakeApi';
import { stubModalDialog } from '../../test/modalDialog';
import { BillingProfilesPage } from './BillingProfilesPage';

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

  it('returns focus to the opener when the dialog is closed with Escape', async () => {
    stubApi({ [LIST]: { status: 200, body: [] } });
    const { user, dialog } = await openCreateDialog();
    expect(within(dialog).getByLabelText('Type')).toHaveFocus();

    await user.keyboard('{Escape}');

    expect(dialog).not.toHaveAttribute('open');
    expect(screen.getByRole('button', { name: 'Add invoice details' })).toHaveFocus();
  });
});
