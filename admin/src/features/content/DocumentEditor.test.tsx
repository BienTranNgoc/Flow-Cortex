import { setToken, type ContentDocumentDraft } from '@flow-cortex/api-client';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderRoute, stubApi, staffMe } from '../../test/fakeApi';
import { DocumentEditor } from './DocumentEditor';

const SAVE_PATH = 'PUT /api/v1/admin/content/documents/terms';
const EDITOR = staffMe('CONTENT_EDITOR', ['content.document.view', 'content.document.manage']);
const SUMMARY = {
  slug: 'terms',
  title: { vi: 'Điều khoản', en: 'Terms' },
  sectionTypes: ['richText'],
  publicHistory: false,
  draftVersion: 3,
  publishedVersion: 0,
};
const text = (value: string) => ({ vi: value, en: value });
const draft = (version: number): ContentDocumentDraft => ({
  slug: 'terms',
  version,
  sections: [],
  seoMeta: { title: text('Terms'), description: text('Our terms') },
});

function renderEditor(version: number) {
  renderRoute(
    '/',
    '/',
    <DocumentEditor slug="terms" summary={SUMMARY} draft={draft(version)} me={EDITOR} />,
  );
}

beforeEach(() => setToken('admin-token-document-editor', true));
afterEach(() => {
  setToken(null);
  vi.unstubAllGlobals();
});

describe('DocumentEditor Save', () => {
  it('sends no request when the stored draft has no changes', async () => {
    const calls = stubApi({ [SAVE_PATH]: { status: 200, body: draft(4) } });
    renderEditor(3);

    const save = screen.getByRole('button', { name: 'Save' });
    expect(save).toHaveAttribute('aria-disabled', 'true');
    await userEvent.click(save);

    expect(calls.filter((call) => call.method === 'PUT')).toHaveLength(0);
    expect(screen.getByRole('status')).toHaveTextContent('Draft version 3');
  });

  it('saves the changes made to a stored draft against its version', async () => {
    const calls = stubApi({ [SAVE_PATH]: { status: 200, body: draft(4) } });
    renderEditor(3);

    await userEvent.type(screen.getByLabelText(/SEO title/), ' v2');
    const save = screen.getByRole('button', { name: 'Save' });
    expect(save).toHaveAttribute('aria-disabled', 'false');
    await userEvent.click(save);

    expect(await screen.findByText(/Saved as version 4/)).toBeInTheDocument();
    const puts = calls.filter((call) => call.method === 'PUT');
    expect(puts).toHaveLength(1);
    expect(puts[0]?.body).toMatchObject({ version: 3 });
    await waitFor(() => expect(save).toHaveAttribute('aria-disabled', 'true'));
  });

  it('lets a document with no draft yet be saved without any typing', async () => {
    const calls = stubApi({ [SAVE_PATH]: { status: 200, body: draft(1) } });
    renderEditor(0);

    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    expect(await screen.findByText(/Saved as version 1/)).toBeInTheDocument();
    expect(calls.filter((call) => call.method === 'PUT')).toHaveLength(1);
  });
});
