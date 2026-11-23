import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { StoryboardOutputView } from './StoryboardOutputView';

const STORYBOARD = {
  scene1Prompt: 'Wide tabletop shot with warm morning light.',
  scene2Prompt: 'Macro pour shot with crisp overlay badge.',
};

function sceneCard(title: RegExp): HTMLElement {
  const card = screen.getByRole('heading', { name: title }).closest('article');
  if (!card) throw new Error('no scene card');
  return card;
}

describe('StoryboardOutputView', () => {
  const clipboard = Object.getOwnPropertyDescriptor(navigator, 'clipboard');
  afterEach(() => {
    if (clipboard) Object.defineProperty(navigator, 'clipboard', clipboard);
    vi.restoreAllMocks();
  });

  it('says "Copied" only once the prompt is on the clipboard', async () => {
    const user = userEvent.setup();
    render(<StoryboardOutputView storyboard={STORYBOARD} />);

    await user.click(
      within(sceneCard(/Scene 1/)).getByRole('button', { name: 'Copy scene prompt' }),
    );

    expect(await navigator.clipboard.readText()).toBe(STORYBOARD.scene1Prompt);
    expect(within(sceneCard(/Scene 1/)).getByRole('button')).toHaveTextContent('Copied');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('shows a failure and never "Copied" when the clipboard refuses the write', async () => {
    const user = userEvent.setup();
    vi.spyOn(navigator.clipboard, 'writeText').mockRejectedValue(new DOMException('denied'));
    render(<StoryboardOutputView storyboard={STORYBOARD} />);

    await user.click(
      within(sceneCard(/Scene 2/)).getByRole('button', { name: 'Copy scene prompt' }),
    );

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Could not copy the prompt. Select the text and copy it yourself.',
    );
    expect(within(sceneCard(/Scene 2/)).getByRole('button')).toHaveTextContent('Copy scene prompt');
    expect(screen.queryByText('Copied')).not.toBeInTheDocument();
  });

  it('shows a failure when the page has no clipboard API', async () => {
    const user = userEvent.setup();
    Object.defineProperty(navigator, 'clipboard', { value: undefined, configurable: true });
    render(<StoryboardOutputView storyboard={STORYBOARD} />);

    await user.click(
      within(sceneCard(/Scene 1/)).getByRole('button', { name: 'Copy scene prompt' }),
    );

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    expect(screen.queryByText('Copied')).not.toBeInTheDocument();
  });
});
