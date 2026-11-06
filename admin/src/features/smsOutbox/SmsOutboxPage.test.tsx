import { setToken } from '@flow-cortex/api-client';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { formatDateTime } from '../../lib/format';
import i18n from '../../lib/i18n';
import viCommon from '../../locales/vi/common.json';
import viSmsOutbox from '../../locales/vi/smsOutbox.json';
import { renderRoute, settled, stubApi, type FakeReply } from '../../test/fakeApi';
import { SmsOutboxPage } from './SmsOutboxPage';

const OUTBOX_PATH = 'GET /api/v1/admin/sms-outbox';

function entry(id: string, phone: string, body: string, createdAt: string) {
  return { id, phone, body, createdAt };
}

beforeEach(() => setToken('admin-token-sms', true));
afterEach(() => {
  setToken(null);
  vi.unstubAllGlobals();
});

describe('SmsOutboxPage', () => {
  it('shows a loading status while the outbox is on its way', () => {
    stubApi({ [OUTBOX_PATH]: () => new Promise<FakeReply>(() => {}) });
    renderRoute('/sms-outbox', '/sms-outbox', <SmsOutboxPage />);

    expect(screen.getByText('Loading SMS messages…').tagName).toBe('OUTPUT');
  });

  it('lists the newest message first with number, text and sent time, asking for the latest 50', async () => {
    const calls = stubApi({
      [OUTBOX_PATH]: {
        status: 200,
        body: [
          entry('s2', '+84987654321', 'Your Flow-Cortex code is 654321', '2026-10-16T10:05:00Z'),
          entry('s1', '+84912345678', 'Your Flow-Cortex code is 123456', '2026-10-16T10:00:00Z'),
        ],
      },
    });
    renderRoute('/sms-outbox', '/sms-outbox', <SmsOutboxPage />);

    const table = await screen.findByRole('table');
    expect(within(table).getAllByRole('columnheader').map((header) => header.textContent)).toEqual([
      'Number',
      'Text',
      'Sent',
    ]);
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent('+84987654321');
    expect(rows[0]).toHaveTextContent('Your Flow-Cortex code is 654321');
    expect(within(rows[0]!).getByText(/./, { selector: 'time' })).toHaveAttribute(
      'dateTime',
      '2026-10-16T10:05:00Z',
    );
    expect(rows[1]).toHaveTextContent('+84912345678');
    expect(calls[0]?.query.get('limit')).toBe('50');
  });

  it('says so when no SMS was sent yet', async () => {
    stubApi({ [OUTBOX_PATH]: { status: 200, body: [] } });
    renderRoute('/sms-outbox', '/sms-outbox', <SmsOutboxPage />);

    expect(await screen.findByText('No SMS messages yet.')).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('shows an alert with a retry action when the outbox fails to load', async () => {
    const calls = stubApi({ [OUTBOX_PATH]: { status: 500, body: { status: 500, detail: 'boom' } } });
    renderRoute('/sms-outbox', '/sms-outbox', <SmsOutboxPage />);

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load SMS messages.');
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(calls).toHaveLength(2));
  });

  it('shows the disabled banner and no table on a 503', async () => {
    stubApi({
      [OUTBOX_PATH]: { status: 503, body: { status: 503, detail: 'Admin console is currently disabled' } },
    });
    const { queryClient } = renderRoute('/sms-outbox', '/sms-outbox', <SmsOutboxPage />);

    expect(await screen.findByText('Admin console is disabled')).toBeInTheDocument();
    await settled(queryClient);
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('names the page in the document title', async () => {
    stubApi({ [OUTBOX_PATH]: { status: 200, body: [] } });
    renderRoute('/sms-outbox', '/sms-outbox', <SmsOutboxPage />);

    await waitFor(() => expect(document.title).toBe('SMS outbox · Flow-Cortex Admin'));
  });

  describe('in Vietnamese', () => {
    beforeEach(async () => {
      await i18n.changeLanguage('vi');
    });
    afterEach(async () => {
      await i18n.changeLanguage('en');
    });

    it('labels the page in Vietnamese, formats the sent time and keeps the message text as sent', async () => {
      stubApi({
        [OUTBOX_PATH]: {
          status: 200,
          body: [entry('s1', '+84912345678', 'Your Flow-Cortex code is 123456', '2026-10-16T10:00:00Z')],
        },
      });
      renderRoute('/sms-outbox', '/sms-outbox', <SmsOutboxPage />);

      const table = await screen.findByRole('table');
      expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(viSmsOutbox.title);
      expect(within(table).getAllByRole('columnheader').map((header) => header.textContent)).toEqual([
        viSmsOutbox.columns.number,
        viSmsOutbox.columns.text,
        viSmsOutbox.columns.sent,
      ]);
      expect(screen.getByRole('button', { name: viCommon.shell.list.refresh })).toBeInTheDocument();
      const row = within(table).getAllByRole('row')[1] as HTMLElement;
      expect(row).toHaveTextContent('Your Flow-Cortex code is 123456');
      expect(within(row).getByText(/./, { selector: 'time' })).toHaveTextContent(
        formatDateTime('2026-10-16T10:00:00Z', 'vi', {
          year: 'numeric',
          month: 'numeric',
          day: 'numeric',
          hour: 'numeric',
          minute: 'numeric',
          second: 'numeric',
        }),
      );
      await waitFor(() =>
        expect(document.title).toBe(`${viSmsOutbox.title} · ${viCommon.header.title}`),
      );
    });

    it('shows the empty state in Vietnamese', async () => {
      stubApi({ [OUTBOX_PATH]: { status: 200, body: [] } });
      renderRoute('/sms-outbox', '/sms-outbox', <SmsOutboxPage />);

      expect(await screen.findByText(viSmsOutbox.empty)).toBeInTheDocument();
    });
  });
});
