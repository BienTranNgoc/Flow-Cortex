import type { RunStepResponse } from '@flow-cortex/api-client';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { TFunction } from 'i18next';
import i18n from '../../lib/i18n';
import viWorkflow from '../../locales/vi/workflow.json';
import { Pipeline, type PipelineStep } from './Pipeline';
import { stepLabel } from './stepDisplay';

function step(overrides: Partial<RunStepResponse> = {}): RunStepResponse {
  return {
    id: 's1',
    stepOrder: 1,
    type: 'echo',
    mode: 'inline',
    status: 'pending',
    attempt: 1,
    deliveryCount: 0,
    outputs: [],
    ...overrides,
  };
}

const asNodes = (steps: RunStepResponse[], t: TFunction = i18n.getFixedT('en')): PipelineStep[] =>
  steps.map((run) => ({ id: run.id ?? '', name: stepLabel(run, t), run }));

const numbered = (count: number) =>
  Array.from({ length: count }, (_, index) =>
    step({ id: `s${index + 1}`, stepOrder: index + 1, status: 'completed' }),
  );

function renderRun(steps: RunStepResponse[], extra: Partial<Parameters<typeof Pipeline>[0]> = {}) {
  const onSelect = vi.fn();
  render(
    <Pipeline
      steps={asNodes(steps)}
      runStatus="running"
      selectedId="s2"
      panelId="panel"
      onSelect={onSelect}
      {...extra}
    />,
  );
  return onSelect;
}

describe('Pipeline in run mode', () => {
  const steps = [
    step({ status: 'completed', type: 'probe' }),
    step({ id: 's2', stepOrder: 2, type: 'extract_audio', status: 'running', pickedUpAt: 'now' }),
  ];

  it('draws the moving segment only on the connector into the running step', () => {
    renderRun([...steps, step({ id: 's3', stepOrder: 3, status: 'pending' })]);

    const connectors = [...document.querySelectorAll('.pipeline-connector')];
    expect(
      connectors.map((connector) => connector.querySelector('.pipeline-flow') !== null),
    ).toEqual([true, false]);
  });

  it('exposes each step as a button with its name and status word', () => {
    renderRun(steps);

    const nodes = screen.getAllByRole('button');
    expect(nodes).toHaveLength(2);
    expect(nodes[0]).toHaveAccessibleName(/Inspect the file/);
    expect(nodes[0]).toHaveTextContent('Completed');
    expect(nodes[1]).toHaveAccessibleName(/Extract source audio/);
    expect(nodes[1]).toHaveTextContent('Running');
  });

  it('presses the selected node, marks the running one current and names the panel it controls', () => {
    renderRun(steps);

    const [first, second] = screen.getAllByRole('button');
    expect(second).toHaveAttribute('aria-pressed', 'true');
    expect(first).toHaveAttribute('aria-pressed', 'false');
    expect(second).toHaveAttribute('aria-current', 'step');
    expect(first).not.toHaveAttribute('aria-current');
    expect(first).toHaveAttribute('aria-controls', 'panel');
    expect(second).toHaveAttribute('aria-controls', 'panel');
  });

  it('selects a focused node with Enter', async () => {
    const user = userEvent.setup();
    const onSelect = renderRun(steps);

    await user.tab();
    expect(screen.getAllByRole('button')[0]).toHaveFocus();
    await user.keyboard('{Enter}');

    expect(onSelect).toHaveBeenCalledWith('s1');
  });

  it('names the step a waiting run is paused on "Review" and reads "Needs you"', () => {
    renderRun(
      [
        step({ status: 'completed' }),
        step({ id: 's2', stepOrder: 2, type: 'APPROVAL_CHECKPOINT' }),
      ],
      { runStatus: 'waiting_approval' },
    );

    const review = screen.getByRole('button', { name: /Review/ });
    expect(review).toHaveTextContent('Needs you');
    expect(review).toHaveAttribute('aria-current', 'step');
  });

  it('notes a redelivered step as "Retried 2×"', () => {
    renderRun([step({ deliveryCount: 2, status: 'completed' }), step({ id: 's2', stepOrder: 2 })]);

    expect(screen.getByText('Retried 2×')).toBeInTheDocument();
  });

  it('shows the first labelled field of a completed step as its result', () => {
    renderRun([
      step({ status: 'completed', output: '{"durationMs":1000,"width":320,"height":240}' }),
      step({ id: 's2', stepOrder: 2 }),
    ]);

    expect(screen.getByText('Duration: 1.0 s')).toBeInTheDocument();
  });

  it('renders a step written before delivery counts and outputs were recorded', () => {
    const older = { id: 's1', stepOrder: 1, type: 'probe', status: 'completed' } as RunStepResponse;
    renderRun([older, step({ id: 's2', stepOrder: 2 })]);

    expect(screen.getAllByRole('button')).toHaveLength(2);
  });

  it.each([
    [5, 'stack'],
    [4, 'row'],
  ])('lays out %i steps as %s', (count, layout) => {
    renderRun(numbered(count));

    expect(screen.getByRole('region', { name: 'Steps' })).toHaveAttribute('data-layout', layout);
  });

  it('says a step can be selected when there are several, and not for a single step', () => {
    const { unmount } = render(
      <Pipeline steps={asNodes(numbered(2))} runStatus="completed" onSelect={vi.fn()} />,
    );
    expect(screen.getByText('Select a step to see its result.')).toBeInTheDocument();
    unmount();

    render(<Pipeline steps={asNodes(numbered(1))} runStatus="completed" onSelect={vi.fn()} />);
    expect(screen.queryByText('Select a step to see its result.')).not.toBeInTheDocument();
  });
});

describe('Pipeline in Vietnamese', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('vi');
  });
  afterEach(async () => {
    await i18n.changeLanguage('en');
  });

  it('names a finished probe and extract_audio run in Vietnamese, with no "probe" anywhere', () => {
    const run = [
      step({ status: 'completed', type: 'probe' }),
      step({ id: 's2', stepOrder: 2, type: 'extract_audio', status: 'completed' }),
    ];
    render(
      <Pipeline
        steps={asNodes(run, i18n.getFixedT('vi'))}
        runStatus="completed"
        selectedId="s2"
        panelId="panel"
        onSelect={vi.fn()}
      />,
    );

    const [probe, extract] = screen.getAllByRole('button');
    expect(probe).toHaveAccessibleName(new RegExp(viWorkflow.step.label.probe));
    expect(probe).toHaveTextContent(viWorkflow.step.state.completed);
    expect(extract).toHaveAccessibleName(new RegExp(viWorkflow.step.label.extract_audio));
    expect(screen.getByRole('region', { name: viWorkflow.pipeline.region })).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/probe/i);
    expect(document.body.textContent).not.toMatch(/Inspect the file|Completed/);
  });
});

describe('Pipeline in read-only mode', () => {
  it('lists the step names with no button, status word or hint', () => {
    render(
      <Pipeline
        steps={[
          { id: 'a', name: 'Inspect the file' },
          { id: 'b', name: 'Extract source audio' },
        ]}
      />,
    );

    const items = within(screen.getByRole('list')).getAllByRole('listitem');
    expect(items.map((item) => item.textContent)).toEqual([
      expect.stringContaining('Inspect the file'),
      expect.stringContaining('Extract source audio'),
    ]);
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
    expect(screen.queryByText(/Pending|Completed|Running|Waiting|Error|Needs you/)).toBeNull();
    expect(screen.queryByText('Select a step to see its result.')).not.toBeInTheDocument();
  });
});
