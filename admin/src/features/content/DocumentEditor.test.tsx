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

const richText = (body: string) => ({
  type: 'richText',
  schemaVersion: 1,
  data: { body: text(body) },
});

function renderEditor(version: number, sectionBodies: readonly string[] = []) {
  renderRoute(
    '/',
    '/',
    <DocumentEditor
      slug="terms"
      summary={SUMMARY}
      draft={{ ...draft(version), sections: sectionBodies.map(richText) }}
      me={EDITOR}
    />,
  );
}

const bodies = () =>
  screen
    .getAllByRole<HTMLTextAreaElement>('textbox', { name: /^Content/ })
    .map((field) => field.value);
const focusedName = () => document.activeElement?.getAttribute('aria-label') ?? '<none>';

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

describe('DocumentEditor sections', () => {
  const sectionButton = (name: string) => screen.getByRole('button', { name });

  it('moves a section two places when Enter is pressed twice on its Move up button', async () => {
    renderEditor(3, ['A', 'B', 'C']);

    sectionButton('Move section 3 up').focus();
    await userEvent.keyboard('{Enter}');
    expect(bodies()).toEqual(['A', 'C', 'B']);
    expect(focusedName()).toBe('Move section 2 up');

    await userEvent.keyboard('{Enter}');
    expect(bodies()).toEqual(['C', 'A', 'B']);
  });

  it('keeps focus on the moved section when its action is unavailable at the edge', async () => {
    renderEditor(3, ['A', 'B', 'C']);

    await userEvent.click(sectionButton('Move section 2 up'));
    expect(bodies()).toEqual(['B', 'A', 'C']);
    expect(focusedName()).toBe('Move section 1 down');

    await userEvent.click(sectionButton('Move section 2 down'));
    expect(bodies()).toEqual(['B', 'C', 'A']);
    expect(focusedName()).toBe('Move section 3 up');
  });

  it('announces where a moved section landed in the status output', async () => {
    renderEditor(3, ['A', 'B', 'C']);

    await userEvent.click(sectionButton('Move section 3 up'));

    expect(screen.getByRole('status')).toHaveTextContent('Section moved to position 2 of 3.');
  });

  it('puts focus on the section now at the removed position', async () => {
    renderEditor(3, ['A', 'B', 'C']);

    await userEvent.click(sectionButton('Remove section 2'));

    expect(bodies()).toEqual(['A', 'C']);
    expect(focusedName()).toBe('Remove section 2');
    expect(screen.getByRole('status')).toHaveTextContent('Section 2 removed.');
  });

  it('puts focus on the previous section when the last one is removed', async () => {
    renderEditor(3, ['A', 'B', 'C']);

    await userEvent.click(sectionButton('Remove section 3'));

    expect(bodies()).toEqual(['A', 'B']);
    expect(focusedName()).toBe('Remove section 2');
  });

  it('puts focus on the add section control when the only section is removed', async () => {
    renderEditor(3, ['A']);

    await userEvent.click(sectionButton('Remove section 1'));

    expect(screen.getByText('This document has no sections yet.')).toBeInTheDocument();
    expect(document.activeElement).toBe(sectionButton('Add section'));
  });

  it('treats an untouched document as saved and sends no client id when saving', async () => {
    const calls = stubApi({ [SAVE_PATH]: { status: 200, body: draft(4) } });
    renderEditor(3, ['A', 'B']);

    expect(screen.getByRole('status')).toHaveTextContent('Draft version 3');
    expect(screen.getByRole('button', { name: 'Save' })).toHaveAttribute('aria-disabled', 'true');

    await userEvent.click(sectionButton('Add section'));
    await userEvent.click(sectionButton('Move section 3 up'));
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    expect(await screen.findByText(/Saved as version 4/)).toBeInTheDocument();
    const [put] = calls.filter((call) => call.method === 'PUT');
    expect(put?.body).toMatchObject({
      sections: [richText('A'), expect.objectContaining({ type: 'richText' }), richText('B')],
    });
    const sent = (put?.body as { sections: object[] }).sections;
    for (const section of sent)
      expect(Object.keys(section).sort()).toEqual(['data', 'schemaVersion', 'type']);
  });
});
