import type { RunStepChildResponse, RunStepResponse } from '@flow-cortex/api-client';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { StepOutcome } from './StepOutcome';

function step(overrides: Partial<RunStepResponse> = {}): RunStepResponse {
  return {
    id: 's1',
    stepOrder: 1,
    type: 'echo',
    mode: 'inline',
    status: 'completed',
    attempt: 1,
    deliveryCount: 0,
    outputs: [],
    children: [],
    ...overrides,
  };
}

const show = (text: string | undefined, overrides: Partial<RunStepResponse> = {}) =>
  render(
    <StepOutcome step={step({ ...overrides, ...(text ? { output: text } : {}) })} text={text} />,
  );

function bodyRows(): string[][] {
  const table = screen.getByRole('table');
  return within(table)
    .getAllByRole('row')
    .slice(1)
    .map((row) =>
      within(row)
        .getAllByRole('cell')
        .map((cell) => cell.textContent ?? ''),
    );
}

describe('the text view', () => {
  it('states the length in code points and shows the text literally', () => {
    show('Xin chào 👋');

    expect(screen.getByText('10 characters')).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Result text' })).toHaveTextContent('Xin chào 👋');
  });

  it('shows markup as characters, never as elements', () => {
    const { container } = show('<img src=x onerror=alert(1)>');

    expect(container.querySelector('img')).toBeNull();
    expect(screen.getByRole('region', { name: 'Result text' })).toHaveTextContent(
      '<img src=x onerror=alert(1)>',
    );
  });

  it('copies the shown text and reads "Copied"', async () => {
    const user = userEvent.setup();
    show('Xin chào 👋');

    await user.click(screen.getByRole('button', { name: 'Copy' }));

    expect(await screen.findByRole('button', { name: 'Copied' })).toBeInTheDocument();
    expect(await navigator.clipboard.readText()).toBe('Xin chào 👋');
  });

  it('says so when the clipboard refuses, without an error escaping', async () => {
    const user = userEvent.setup();
    vi.spyOn(navigator.clipboard, 'writeText').mockRejectedValue(new Error('denied'));
    show('hello');

    await user.click(screen.getByRole('button', { name: 'Copy' }));

    expect(await screen.findByText("Couldn't copy. Select the text instead.")).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Copy' })).toBeInTheDocument();
  });

  it('pretty-prints a list that is not a table', () => {
    show('["a","b"]');

    expect(screen.getByText('16 characters')).toBeInTheDocument();
  });
});

describe('the table view', () => {
  const rows = '[{"name":"A","views":10},{"name":"B","views":null},{"name":"C","views":2}]';

  it('states its size, sorts by a column through ascending, descending and back, and marks the column', async () => {
    const user = userEvent.setup();
    show(rows);

    expect(screen.getByText('1 table · 3 rows · 2 columns')).toBeInTheDocument();
    const header = screen.getByRole('columnheader', { name: /views/ });
    expect(header).not.toHaveAttribute('aria-sort');

    await user.click(screen.getByRole('button', { name: 'Sort by views' }));
    expect(bodyRows().map((row) => row[0])).toEqual(['C', 'A', 'B']);
    expect(header).toHaveAttribute('aria-sort', 'ascending');

    await user.click(screen.getByRole('button', { name: 'Sort by views' }));
    expect(bodyRows().map((row) => row[0])).toEqual(['A', 'C', 'B']);
    expect(header).toHaveAttribute('aria-sort', 'descending');

    await user.click(screen.getByRole('button', { name: 'Sort by views' }));
    expect(bodyRows().map((row) => row[0])).toEqual(['A', 'B', 'C']);
    expect(header).not.toHaveAttribute('aria-sort');
  });

  it('writes booleans as text and null as an empty cell', () => {
    show('[{"a":true,"b":null,"c":"x"}]');

    expect(bodyRows()).toEqual([['true', '', 'x']]);
  });

  it('draws 500 of 1,200 rows and says how many there are', () => {
    const many = JSON.stringify(
      Array.from({ length: 1200 }, (_, index) => ({ id: index, x: 1, y: 2 })),
    );
    show(many);

    expect(screen.getByText('1 table · 1,200 rows · 3 columns')).toBeInTheDocument();
    expect(screen.getAllByRole('row')).toHaveLength(501);
    expect(screen.getByText('Showing the first 500 of 1,200 rows.')).toBeInTheDocument();
  });

  it('scrolls sideways in its own labelled, focusable region', () => {
    show(rows);

    expect(screen.getByRole('region', { name: 'Result table' })).toHaveAttribute('tabindex', '0');
  });
});

describe('the iterate view', () => {
  const child = (overrides: Partial<RunStepChildResponse>): RunStepChildResponse => ({
    id: 'c',
    itemIndex: 0,
    status: 'completed',
    attempt: 1,
    outputs: [],
    ...overrides,
  });

  it('summarises mixed items and lists a row each in item order', () => {
    show(undefined, {
      type: 'iterate',
      children: [
        child({ id: 'c0', itemIndex: 0, output: 'Hello' }),
        child({ id: 'c1', itemIndex: 1, status: 'error' }),
        child({
          id: 'c2',
          itemIndex: 2,
          outputs: [{ name: 'v.mp4', mime: 'video/mp4', bytes: 1 }],
        }),
      ],
    });

    expect(screen.getByText('2 of 3 items succeeded')).toBeInTheDocument();
    const items = screen.getAllByRole('listitem').map((item) => item.textContent);
    expect(items).toEqual(['Item 1·Completed·Hello', 'Item 2·Error', 'Item 3·Completed·1 file']);
  });
});

describe('results with no text', () => {
  it('says a step finished without a result when it holds nothing', () => {
    show(undefined);

    expect(screen.getByText('This step finished without a result.')).toBeInTheDocument();
  });

  it('counts stored files by kind and draws no text block', () => {
    show(undefined, {
      outputs: [
        { name: 'a.mp4', mime: 'video/mp4', bytes: 1 },
        { name: 'b.mp4', mime: 'video/mp4', bytes: 1 },
        { name: 'c.jpg', mime: 'image/jpeg', bytes: 1 },
        { name: 'd.zip', mime: 'application/zip', bytes: 1 },
      ],
    });

    expect(screen.getByText('2 videos · 1 image · 1 file')).toBeInTheDocument();
    expect(screen.queryByRole('region')).toBeNull();
    expect(screen.queryByText('This step finished without a result.')).toBeNull();
  });
});
