import type { RunResponse } from '@flow-cortex/api-client';
import {
  ArrowsOutSimple,
  FrameCorners,
  MagnifyingGlassMinus,
  MagnifyingGlassPlus,
  X,
} from '@phosphor-icons/react';
import {
  applyNodeChanges,
  Background,
  BackgroundVariant,
  getStraightPath,
  Handle,
  MiniMap,
  Panel,
  Position,
  ReactFlow,
  ReactFlowProvider,
  useNodesInitialized,
  useReactFlow,
  useStore,
  useStoreApi,
  type CoordinateExtent,
  type Edge,
  type EdgeProps,
  type EdgeTypes,
  type InternalNode,
  type Node,
  type NodeChange,
  type NodeProps,
  type NodeTypes,
  type OnMoveStart,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useId,
  useMemo,
  useReducer,
  useRef,
  useState,
  type CSSProperties,
  type FocusEvent,
  type ReactNode,
} from 'react';
import { useTranslation } from 'react-i18next';
import { activeLocale } from '../../lib/i18n';
import { useModalDialog } from '../runHistory/useModalDialog';
import { PipelineNode, type PipelineStep } from './Pipeline';
import {
  FIT_PADDING,
  MAX_ZOOM,
  MIN_ZOOM,
  NODE_PITCH,
  NODE_WIDTH,
  elapsedSeconds,
  initialView,
  inputSummary,
  panIntoView,
  runCanvasGraph,
  runProgress,
  shouldFollow,
  type Box,
  type CanvasGraph,
  type InputSummary,
  type RunProgress,
} from './runCanvasModel';
import './RunCanvas.css';
import {
  formatSeconds,
  stepLabel,
  stepNameId,
  stepState,
  type EdgeState,
  type StepState,
} from './stepDisplay';
import { isLiveRunStatus } from './useRunEvents';

const WIDE_QUERY = '(min-width: 1024px)';
const REDUCED_MOTION_QUERY = '(prefers-reduced-motion: reduce)';
const FLOW_CLASS = '.react-flow';
/** "Fit diagram" frames every node, so it may go below the zoom floor the member can reach by hand. */
const FIT_MIN_ZOOM = 0.01;
/** Distance a zoom button must be from a bound to count as reaching it. */
const ZOOM_BOUND_EPSILON = 1e-6;

function wideQuery(): MediaQueryList | undefined {
  return typeof window.matchMedia === 'function' ? window.matchMedia(WIDE_QUERY) : undefined;
}

/** Whether the window is wide enough for the diagram; false where `matchMedia` is missing. */
// eslint-disable-next-line react-refresh/only-export-components -- the hook gates the component below
export function useRunCanvasWide(): boolean {
  const [wide, setWide] = useState(() => wideQuery()?.matches ?? false);
  useEffect(() => {
    const query = wideQuery();
    if (!query || typeof query.addEventListener !== 'function') {
      return;
    }
    const update = () => setWide(query.matches);
    update();
    query.addEventListener('change', update);
    return () => query.removeEventListener('change', update);
  }, []);
  return wide;
}

function readStandardDuration(): number {
  const raw = getComputedStyle(document.documentElement).getPropertyValue('--dur-standard').trim();
  const value = Number.parseFloat(raw);
  if (!Number.isFinite(value)) {
    return 0;
  }
  return raw.endsWith('ms') ? value : value * 1000;
}

function prefersReducedMotion(): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia(REDUCED_MOTION_QUERY).matches;
}

interface NodeInfo {
  step: PipelineStep;
  order: number;
  state: StepState;
  elapsed: string | undefined;
}

interface CanvasContextValue {
  info: ReadonlyMap<string, NodeInfo>;
  input: InputSummary;
  selectedId: string | undefined;
  panelId: string | undefined;
  onSelect: (id: string) => void;
  /** Appended to a node's name id where the same step is drawn twice on the page. */
  nameSuffix: string;
  registerButton: ((id: string, button: HTMLButtonElement | null) => void) | undefined;
  /** True once after the member picked a step on this canvas, so the page's own pick never pans. */
  consumeOwnSelect: () => boolean;
  /** Length of a view change in ms: the standard duration, 0 under reduced motion. */
  viewDuration: () => number;
}

const CanvasContext = createContext<CanvasContextValue | null>(null);

function useCanvasContext(): CanvasContextValue {
  const context = useContext(CanvasContext);
  if (!context) {
    throw new Error('Run canvas nodes render inside a RunCanvas.');
  }
  return context;
}

type FlowNode = Node;
type ChainEdge = Edge<{ state: EdgeState }, 'chain'>;

function boxOf(node: InternalNode | undefined): Box | null {
  const width = node?.measured.width;
  const height = node?.measured.height;
  if (!node || width === undefined || height === undefined) {
    return null;
  }
  return { x: node.internals.positionAbsolute.x, y: node.internals.positionAbsolute.y, width, height };
}

function unionBox(boxes: readonly Box[]): Box {
  const left = Math.min(...boxes.map((box) => box.x));
  const top = Math.min(...boxes.map((box) => box.y));
  const right = Math.max(...boxes.map((box) => box.x + box.width));
  const bottom = Math.max(...boxes.map((box) => box.y + box.height));
  return { x: left, y: top, width: right - left, height: bottom - top };
}

function resetScroll(element: HTMLElement) {
  element.scrollTop = 0;
  element.scrollLeft = 0;
}

function StepNode({ id }: Readonly<NodeProps>) {
  const context = useCanvasContext();
  const { getInternalNode, getViewport, setViewport } = useReactFlow();
  const pointerFocus = useRef(false);
  const { registerButton } = context;
  const buttonRef = useCallback(
    (button: HTMLButtonElement | null) => registerButton?.(id, button),
    [id, registerButton],
  );
  const info = context.info.get(id);

  // A pointer pick never moves the view; keyboard focus brings its node whole into the canvas.
  const onFocus = (event: FocusEvent<HTMLDivElement>) => {
    const flow = event.currentTarget.closest<HTMLElement>(FLOW_CLASS);
    if (!flow) {
      return;
    }
    // The browser scrolls the flow container to reveal a focused node; only the view may offset it.
    resetScroll(flow);
    requestAnimationFrame(() => resetScroll(flow));
    if (pointerFocus.current) {
      pointerFocus.current = false;
      return;
    }
    const box = boxOf(getInternalNode(id));
    if (!box) {
      return;
    }
    const viewport = getViewport();
    const next = panIntoView(box, viewport, { width: flow.clientWidth, height: flow.clientHeight });
    if (next !== viewport) {
      void setViewport(next, { duration: context.viewDuration() });
    }
  };

  if (!info) {
    return null;
  }
  return (
    <div
      className="run-canvas__node"
      onFocus={onFocus}
      onBlur={() => {
        pointerFocus.current = false;
      }}
      onPointerDown={() => {
        pointerFocus.current = true;
      }}
      onPointerUp={() => {
        pointerFocus.current = false;
      }}
    >
      <Handle type="target" position={Position.Left} isConnectable={false} className="run-canvas__handle" />
      <PipelineNode
        step={info.step}
        order={info.order}
        state={info.state}
        selected={id === context.selectedId}
        panelId={context.panelId}
        onSelect={context.onSelect}
        problem={false}
        elapsed={info.elapsed}
        nameId={context.nameSuffix ? `${stepNameId(id)}${context.nameSuffix}` : undefined}
        buttonRef={buttonRef}
      />
      <Handle type="source" position={Position.Right} isConnectable={false} className="run-canvas__handle" />
    </div>
  );
}

function InputNode() {
  const { t } = useTranslation();
  const { input } = useCanvasContext();
  return (
    <div className="run-canvas__node">
      <div className="run-canvas__input">
        <span className="run-canvas__input-title">{t('workflow.canvas.input')}</span>
        <span
          className="run-canvas__input-text"
          title={input.kind === 'text' ? input.title : undefined}
        >
          {input.kind === 'label' ? t('workflow.canvas.runInput') : input.text}
        </span>
      </div>
      <Handle type="source" position={Position.Right} isConnectable={false} className="run-canvas__handle" />
    </div>
  );
}

/** Length of the bright segment travelling along an active edge. */
const EDGE_SEGMENT = 16;

function ChainEdgeLine({ sourceX, sourceY, targetX, data }: Readonly<EdgeProps<ChainEdge>>) {
  const state = data?.state ?? 'pending';
  const [path] = getStraightPath({ sourceX, sourceY, targetX, targetY: sourceY });
  const run = Math.max(0, targetX - sourceX - EDGE_SEGMENT);
  return (
    <g className="run-canvas__edge" data-state={state}>
      <path className="run-canvas__edge-line" d={path} />
      {state === 'active' && (
        <line
          className="run-canvas__edge-flow"
          x1={sourceX}
          y1={sourceY}
          x2={sourceX + EDGE_SEGMENT}
          y2={sourceY}
          style={{ '--edge-run': `${run}px` } as CSSProperties}
        />
      )}
    </g>
  );
}

// React Flow's built-in `input` type styles its wrapper with a hex border and padding, so the run input uses its own name.
const NODE_TYPES = { step: StepNode, runInput: InputNode } satisfies NodeTypes;
const EDGE_TYPES = { chain: ChainEdgeLine } satisfies EdgeTypes;

function flowNodes(graph: CanvasGraph): FlowNode[] {
  return graph.nodes.map((node) => ({
    id: node.id,
    type: node.kind === 'input' ? 'runInput' : 'step',
    position: node.position,
    data: {},
    width: node.width,
    draggable: false,
    selectable: false,
    connectable: false,
    focusable: false,
    deletable: false,
  }));
}

function flowEdges(graph: CanvasGraph): ChainEdge[] {
  return graph.edges.map((edge) => ({
    id: edge.id,
    type: 'chain',
    source: edge.source,
    target: edge.target,
    data: { state: edge.state },
    selectable: false,
    focusable: false,
    deletable: false,
    ariaRole: 'presentation',
    domAttributes: { 'aria-hidden': true },
  }));
}

/** The diagram box one node pitch wider on every side: how far the page canvas may be panned. */
function panExtent(graph: CanvasGraph): CoordinateExtent {
  const lastX = Math.max(...graph.nodes.map((node) => node.position.x));
  return [
    [-NODE_PITCH, -NODE_PITCH],
    [lastX + NODE_WIDTH + NODE_PITCH, NODE_PITCH],
  ];
}

interface CanvasControlsProps {
  /** Present on the page canvas only; the dialog closes through its own header. */
  onExpand: (() => void) | undefined;
  onFit: () => void;
  onManualZoom: () => void;
}

function CanvasControls({ onExpand, onFit, onManualZoom }: Readonly<CanvasControlsProps>) {
  const { t } = useTranslation();
  const { viewDuration } = useCanvasContext();
  const { zoomIn, zoomOut, fitView } = useReactFlow();
  const zoom = useStore((state) => state.transform[2]);
  const atMin = zoom <= MIN_ZOOM + ZOOM_BOUND_EPSILON;
  const atMax = zoom >= MAX_ZOOM - ZOOM_BOUND_EPSILON;
  return (
    <Panel position="bottom-left" className="run-canvas__controls nopan nowheel">
      <button
        type="button"
        className="run-canvas__button"
        aria-label={t('workflow.canvas.zoomOut')}
        title={t('workflow.canvas.zoomOut')}
        aria-disabled={atMin}
        onClick={() => {
          if (atMin) return;
          onManualZoom();
          void zoomOut({ duration: viewDuration() });
        }}
      >
        <MagnifyingGlassMinus size={20} aria-hidden="true" />
      </button>
      <span className="run-canvas__level">
        {t('workflow.canvas.zoomLevel', { percent: Math.round(zoom * 100) })}
      </span>
      <button
        type="button"
        className="run-canvas__button"
        aria-label={t('workflow.canvas.zoomIn')}
        title={t('workflow.canvas.zoomIn')}
        aria-disabled={atMax}
        onClick={() => {
          if (atMax) return;
          onManualZoom();
          void zoomIn({ duration: viewDuration() });
        }}
      >
        <MagnifyingGlassPlus size={20} aria-hidden="true" />
      </button>
      <button
        type="button"
        className="run-canvas__button"
        aria-label={t('workflow.canvas.fit')}
        title={t('workflow.canvas.fit')}
        onClick={() => {
          onFit();
          void fitView({
            padding: FIT_PADDING,
            minZoom: FIT_MIN_ZOOM,
            maxZoom: 1,
            duration: viewDuration(),
          });
        }}
      >
        <FrameCorners size={20} aria-hidden="true" />
      </button>
      {onExpand && (
        <button
          type="button"
          className="run-canvas__button"
          aria-label={t('workflow.canvas.expand')}
          title={t('workflow.canvas.expand')}
          onClick={onExpand}
        >
          <ArrowsOutSimple size={20} aria-hidden="true" />
        </button>
      )}
    </Panel>
  );
}

interface CanvasFlowProps {
  variant: 'page' | 'dialog';
  graph: CanvasGraph;
  live: boolean;
  progress: RunProgress | null;
  onExpand?: () => void;
}

function CanvasFlow({ variant, graph, live, progress, onExpand }: Readonly<CanvasFlowProps>) {
  const { t } = useTranslation();
  const context = useCanvasContext();
  const flowId = useId();
  const store = useStoreApi();
  const { getInternalNode, getViewport, setViewport, setCenter } = useReactFlow();
  const nodesReady = useNodesInitialized();
  // The nodes live in state so the measured sizes React Flow reports stay on them; a push changes
  // the data in `context`, never these objects.
  const [nodes, setNodes] = useState(() => flowNodes(graph));
  const onNodesChange = useCallback(
    (changes: NodeChange[]) => setNodes((current) => applyNodeChanges(changes, current)),
    [],
  );
  const edges = useMemo(() => flowEdges(graph), [graph]);
  const extent = useMemo(() => (variant === 'page' ? panExtent(graph) : undefined), [variant, graph]);
  const [framed, setFramed] = useState(false);
  const memberMoved = useRef(false);
  const { selectedId } = context;

  useEffect(() => {
    if (!nodesReady || framed) {
      return;
    }
    const { width, height } = store.getState();
    const boxes = graph.nodes.flatMap((node) => boxOf(getInternalNode(node.id)) ?? []);
    if (boxes.length > 0) {
      const graphBox = unionBox(boxes);
      const focusBox = (selectedId && boxOf(getInternalNode(selectedId))) || graphBox;
      void setViewport(initialView(graphBox, { width, height }, focusBox), { duration: 0 });
    }
    memberMoved.current = false;
    setFramed(true);
  }, [nodesReady, framed, graph, selectedId, store, getInternalNode, setViewport]);

  // A selection the page changed pulls the view to its node, unless the member moved the view.
  const previousSelected = useRef(selectedId);
  useEffect(() => {
    const ownPick = context.consumeOwnSelect();
    if (previousSelected.current === selectedId) {
      return;
    }
    previousSelected.current = selectedId;
    if (variant !== 'page' || ownPick || !framed || !selectedId) {
      return;
    }
    const box = boxOf(getInternalNode(selectedId));
    const state = context.info.get(selectedId)?.state;
    if (!box || !state || !shouldFollow(state, memberMoved.current)) {
      return;
    }
    const viewport = getViewport();
    const { width, height } = store.getState();
    if (panIntoView(box, viewport, { width, height }) === viewport) {
      return;
    }
    memberMoved.current = false;
    void setCenter(box.x + box.width / 2, box.y + box.height / 2, {
      zoom: viewport.zoom,
      duration: context.viewDuration(),
    });
  });

  const onMoveStart: OnMoveStart = (event) => {
    if (event) {
      memberMoved.current = true;
    }
  };

  return (
    <ReactFlow
      id={flowId}
      className="run-canvas__flow"
      data-framed={framed}
      nodes={nodes}
      edges={edges}
      nodeTypes={NODE_TYPES}
      edgeTypes={EDGE_TYPES}
      nodesDraggable={false}
      nodesConnectable={false}
      elementsSelectable={false}
      nodesFocusable={false}
      edgesFocusable={false}
      disableKeyboardA11y
      deleteKeyCode={null}
      selectionKeyCode={null}
      multiSelectionKeyCode={null}
      selectionOnDrag={false}
      connectOnClick={false}
      panOnDrag
      zoomOnScroll
      zoomOnPinch
      zoomOnDoubleClick={false}
      minZoom={MIN_ZOOM}
      maxZoom={MAX_ZOOM}
      {...(extent ? { translateExtent: extent } : {})}
      proOptions={{ hideAttribution: true }}
      onNodesChange={onNodesChange}
      onMoveStart={onMoveStart}
    >
      <Background variant={BackgroundVariant.Dots} gap={24} size={1.5} />
      {variant === 'page' && live && (
        <Panel position="top-left" className="run-canvas__status nopan">
          <span className="run-canvas__live">
            <span className="run-canvas__live-dot" aria-hidden="true" />
            {t('workflow.canvas.live')}
          </span>
          {progress && (
            <span className="run-canvas__chip">
              {t('workflow.canvas.progress', { current: progress.current, total: progress.total })}
            </span>
          )}
        </Panel>
      )}
      <CanvasControls
        onExpand={onExpand}
        onFit={() => {
          memberMoved.current = false;
        }}
        onManualZoom={() => {
          memberMoved.current = true;
        }}
      />
      {variant === 'dialog' && (
        <MiniMap pannable zoomable ariaLabel={t('workflow.canvas.minimap')} />
      )}
    </ReactFlow>
  );
}

interface RunCanvasDialogProps {
  open: boolean;
  onClose: () => void;
  children: ReactNode;
}

function RunCanvasDialog({ open, onClose, children }: Readonly<RunCanvasDialogProps>) {
  const { t } = useTranslation();
  const titleId = useId();
  const closeRef = useRef<HTMLButtonElement>(null);
  const dialogRef = useModalDialog(open, closeRef);
  return (
    <dialog
      ref={dialogRef}
      className="run-canvas-dialog"
      aria-labelledby={titleId}
      onCancel={(event) => {
        event.preventDefault();
        onClose();
      }}
      onClose={() => {
        if (open) onClose();
      }}
    >
      <header className="run-canvas-dialog__bar">
        <h2 id={titleId} className="run-canvas-dialog__title">
          {t('workflow.canvas.region')}
        </h2>
        <button ref={closeRef} type="button" className="run-canvas__button" onClick={onClose}>
          <X size={20} aria-hidden="true" />
          {t('workflow.canvas.close')}
        </button>
      </header>
      <div className="run-canvas-dialog__body">{open ? children : null}</div>
    </dialog>
  );
}

export interface RunCanvasProps {
  run: RunResponse;
  /** The steps as the list draws them: id, member label and saved step. */
  steps: readonly PipelineStep[];
  selectedId: string | undefined;
  onSelect: (id: string) => void;
  panelId?: string | undefined;
}

/**
 * The run as a pannable, zoomable chain: the Input node, then one node per step, drawn with the
 * list's own node. Selection comes in and goes out exactly as for the list.
 */
export function RunCanvas({ run, steps, selectedId, onSelect, panelId }: Readonly<RunCanvasProps>) {
  const { t } = useTranslation();
  const [viewDuration] = useState(() => {
    const standard = readStandardDuration();
    return () => (prefersReducedMotion() ? 0 : standard);
  });
  const graph = useMemo(() => runCanvasGraph(run), [run]);
  const runSteps = run.steps ?? [];
  const [, tick] = useReducer((count: number) => count + 1, 0);
  const running = runSteps.some((step) => stepState(step, runSteps, run.status) === 'running');
  useEffect(() => {
    if (!running) {
      return;
    }
    const timer = setInterval(tick, 1000);
    return () => clearInterval(timer);
  }, [running]);

  const now = Date.now();
  const listed = new Map(steps.map((step) => [step.id, step]));
  const info = new Map<string, NodeInfo>();
  graph.nodes.forEach((node, index) => {
    const { step } = node;
    if (!step) {
      return;
    }
    const state = stepState(step, runSteps, run.status);
    const seconds = state === 'running' ? elapsedSeconds(step.pickedUpAt, now) : null;
    info.set(node.id, {
      step: listed.get(node.id) ?? { id: node.id, name: stepLabel(step, t), run: step },
      order: index,
      state,
      elapsed: seconds === null ? undefined : formatSeconds(seconds, t, activeLocale()),
    });
  });

  const buttons = useRef(new Map<string, HTMLButtonElement>());
  const registerButton = useCallback((id: string, button: HTMLButtonElement | null) => {
    if (button) {
      buttons.current.set(id, button);
    } else {
      buttons.current.delete(id);
    }
  }, []);
  const ownSelect = useRef(false);
  const selectOnPage = (id: string) => {
    ownSelect.current = true;
    onSelect(id);
  };

  const [expanded, setExpanded] = useState(false);
  const [returnTarget, setReturnTarget] = useState<string | null>(null);
  // Runs after the dialog hook handed focus back to its opener, which the parent's effect follows.
  useEffect(() => {
    if (returnTarget === null) {
      return;
    }
    buttons.current.get(returnTarget)?.focus();
    setReturnTarget(null);
  }, [returnTarget]);

  if (graph.nodes.length === 0) {
    return null;
  }

  const input = inputSummary(run);
  const shared = { info, input, selectedId, panelId, viewDuration };
  const pageContext: CanvasContextValue = {
    ...shared,
    onSelect: selectOnPage,
    nameSuffix: '',
    registerButton,
    consumeOwnSelect: () => {
      const own = ownSelect.current;
      ownSelect.current = false;
      return own;
    },
  };
  const dialogContext: CanvasContextValue = {
    ...shared,
    onSelect: (id) => {
      selectOnPage(id);
      setReturnTarget(id);
      setExpanded(false);
    },
    nameSuffix: '-dialog',
    registerButton: undefined,
    consumeOwnSelect: () => false,
  };
  const live = isLiveRunStatus(run.status);
  const progress = runProgress(runSteps, run.status);
  const structureKey = graph.nodes.map((node) => node.id).join('|');

  return (
    <section className="run-canvas" aria-label={t('workflow.canvas.region')}>
      <div className="run-canvas__stage">
        <CanvasContext.Provider value={pageContext}>
          <ReactFlowProvider key={structureKey}>
            <CanvasFlow
              variant="page"
              graph={graph}
              live={live}
              progress={progress}
              onExpand={() => setExpanded(true)}
            />
          </ReactFlowProvider>
        </CanvasContext.Provider>
      </div>
      {runSteps.length > 1 && <p className="run-canvas__hint">{t('workflow.pipeline.hint')}</p>}
      <RunCanvasDialog open={expanded} onClose={() => setExpanded(false)}>
        <CanvasContext.Provider value={dialogContext}>
          <ReactFlowProvider key={structureKey}>
            <CanvasFlow variant="dialog" graph={graph} live={live} progress={progress} />
          </ReactFlowProvider>
        </CanvasContext.Provider>
      </RunCanvasDialog>
    </section>
  );
}
