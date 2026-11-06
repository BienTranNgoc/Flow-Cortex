import { CLOSED_PANEL, supportPanelReducer, type SupportPanelState } from './supportPanelState';

const PREFILL = { description: 'Hoàn tiền thế nào?', aiConversationId: 'c-1' };

function withPrefill(): SupportPanelState {
  return supportPanelReducer(
    { ...CLOSED_PANEL, open: true, tab: 'assistant' },
    { type: 'startRequest', prefill: PREFILL },
  );
}

describe('supportPanelReducer', () => {
  it('opens the new-request form carrying the prefill', () => {
    expect(withPrefill()).toMatchObject({ tab: 'requests', newRequest: true, prefill: PREFILL });
  });

  it('opens a bare new-request form without one', () => {
    expect(
      supportPanelReducer({ ...withPrefill(), newRequest: false }, { type: 'startRequest' }),
    ).toMatchObject({ newRequest: true, prefill: null });
  });

  it('keeps the prefill with the open form while another tab is shown', () => {
    const away = supportPanelReducer(withPrefill(), { type: 'selectTab', tab: 'home' });
    const back = supportPanelReducer(away, { type: 'selectTab', tab: 'requests' });

    expect(back).toMatchObject({ tab: 'requests', newRequest: true, prefill: PREFILL });
  });

  it('returns to the request list after another tab was shown, like Guides returns to its list', () => {
    const reading = supportPanelReducer(
      { ...CLOSED_PANEL, open: true, tab: 'requests' },
      { type: 'selectRequest', request: 12 },
    );
    const away = supportPanelReducer(reading, { type: 'selectTab', tab: 'guides' });
    const back = supportPanelReducer(away, { type: 'selectTab', tab: 'requests' });

    expect(back).toMatchObject({ tab: 'requests', request: null });
  });

  it.each([
    ['selecting a request', { type: 'selectRequest', request: 12 } as const],
    ['going back to the list', { type: 'selectRequest', request: null } as const],
  ])('drops the prefill on %s', (_case, action) => {
    expect(supportPanelReducer(withPrefill(), action).prefill).toBeNull();
  });
});
