import { setToken } from '@flow-cortex/api-client';
import { act, fireEvent, screen, waitFor } from '@testing-library/react';
import { renderRoute, stubApi, type FakeReply } from '../test/fakeApi';
import { Layout } from './Layout';

const PREFERENCES = 'GET /api/v1/auth/profile/preferences';
const SAVE_UI = 'PATCH /api/v1/auth/profile/preferences/ui';
const TO_LIGHT = { name: 'Switch to light theme' };
const TO_DARK = { name: 'Switch to dark theme' };

function preferences(theme: 'light' | 'dark'): FakeReply {
  return {
    status: 200,
    body: {
      ui: { theme },
      privacy: { profile: 'everyone', activity: 'everyone', message: 'everyone', stats: 'show' },
    },
  };
}

describe('AccountPreferencesSync in the shell', () => {
  beforeEach(() => {
    setToken('t', true);
    localStorage.setItem('flow-cortex:theme', 'light');
    document.documentElement.dataset.theme = 'light';
  });
  afterEach(() => {
    setToken(null);
    localStorage.clear();
    vi.unstubAllGlobals();
  });

  it('afterTheAccountAppliesDarkTheTopbarToggleShowsDarkAndOneClickSwitchesToLight', async () => {
    const calls = stubApi({
      [PREFERENCES]: preferences('dark'),
      [SAVE_UI]: { status: 200, body: { theme: 'light' } },
    });
    renderRoute('/', '/', <Layout />);

    const toLight = await screen.findByRole('button', TO_LIGHT);
    expect(document.documentElement.dataset.theme).toBe('dark');
    expect(localStorage.getItem('flow-cortex:theme')).toBe('dark');

    fireEvent.click(toLight);

    expect(await screen.findByRole('button', TO_DARK)).toBeInTheDocument();
    expect(document.documentElement.dataset.theme).toBe('light');
    await waitFor(() =>
      expect(calls.find((call) => call.method === 'PATCH')?.body).toEqual({ theme: 'light' }),
    );
  });

  it('aTokenRotationDuringTheSessionDoesNotApplyTheAccountValuesAgain', async () => {
    const calls = stubApi({
      [PREFERENCES]: preferences('dark'),
      [SAVE_UI]: { status: 500, body: { status: 500, detail: 'boom' } },
    });
    const { queryClient } = renderRoute('/', '/', <Layout />);

    fireEvent.click(await screen.findByRole('button', TO_LIGHT));
    expect(await screen.findByRole('button', TO_DARK)).toBeInTheDocument();
    await waitFor(() => expect(queryClient.isMutating()).toBe(0));

    act(() => setToken('t2', true));
    await waitFor(() =>
      expect(calls.filter((call) => call.path.endsWith('/preferences'))).toHaveLength(2),
    );
    await waitFor(() => expect(queryClient.isFetching()).toBe(0));

    expect(screen.getByRole('button', TO_DARK)).toBeInTheDocument();
    expect(document.documentElement.dataset.theme).toBe('light');
  });

  it('aSaveMadeBeforeTheLoadAnswersIsNotUndoneByIt', async () => {
    let answerLoad: (reply: FakeReply) => void = () => {};
    const load = new Promise<FakeReply>((resolve) => {
      answerLoad = resolve;
    });
    let loads = 0;
    stubApi({
      // The first load is the slow one that started before the save; a reload sees the saved value.
      [PREFERENCES]: () => (loads++ === 0 ? load : preferences('dark')),
      [SAVE_UI]: { status: 200, body: { theme: 'dark' } },
    });
    const { queryClient } = renderRoute('/', '/', <Layout />);

    fireEvent.click(await screen.findByRole('button', TO_DARK));
    await waitFor(() => expect(queryClient.isMutating()).toBe(0));
    await act(async () => {
      answerLoad(preferences('light'));
      await load;
    });
    await waitFor(() => expect(queryClient.isFetching()).toBe(0));

    expect(screen.getByRole('button', TO_LIGHT)).toBeInTheDocument();
    expect(document.documentElement.dataset.theme).toBe('dark');
  });
});
