import { setToken } from '@flow-cortex/api-client';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderRoute, stubApi } from '../../test/fakeApi';
import { openInCurrentTab } from '../workflow/browser';
import { OAuthConsentRoute } from './OAuthConsentPage';

vi.mock('../workflow/browser', async (importOriginal) => ({
  ...(await importOriginal<object>()),
  openInCurrentTab: vi.fn(),
}));

const ME = { id: 'u1', email: 'admin@flowcortex.dev', name: 'Admin', role: 'ADMIN' };

describe('OAuthConsentPage — a request with a redirectable fault', () => {
  beforeEach(() => setToken('t', true));
  afterEach(() => {
    setToken(null);
    vi.unstubAllGlobals();
    vi.mocked(openInCurrentTab).mockClear();
  });

  it('waits for the visitor before sending them to the redirect URI, which anyone can register', async () => {
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'GET /api/v1/oauth/authorize/request': {
        status: 200,
        body: {
          clientId: 'mcp_1',
          clientName: 'Some app',
          redirectUri: 'https://elsewhere.example/cb',
          scope: 'mcp',
          eligible: true,
          error: 'unsupported_response_type',
        },
      },
    });

    renderRoute(
      '/oauth/authorize',
      '/oauth/authorize?client_id=mcp_1&redirect_uri=https%3A%2F%2Felsewhere.example%2Fcb&response_type=token&state=s1',
      <OAuthConsentRoute />,
    );

    const back = await screen.findByRole('button', { name: /elsewhere\.example/ });
    expect(openInCurrentTab).not.toHaveBeenCalled();

    await userEvent.click(back);

    expect(openInCurrentTab).toHaveBeenCalledOnce();
    expect(openInCurrentTab).toHaveBeenCalledWith(
      'https://elsewhere.example/cb?error=unsupported_response_type&state=s1',
    );
  });
});
