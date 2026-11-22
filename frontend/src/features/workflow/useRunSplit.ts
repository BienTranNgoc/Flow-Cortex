import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type KeyboardEvent,
  type PointerEvent,
  type RefObject,
} from 'react';
import { SPLIT_DEFAULT, clampSplit, readSplit, splitKey } from './runCanvasModel';

export const RUN_SPLIT_KEY = 'flow-cortex:run-split:v1';

const HANDLED_KEYS = new Set(['ArrowLeft', 'ArrowRight', 'Home', 'End', 'Enter']);

function readStored(): number {
  try {
    return readSplit(localStorage.getItem(RUN_SPLIT_KEY));
  } catch {
    // Storage can be blocked; the split then lives in memory for the page.
    return SPLIT_DEFAULT;
  }
}

function store(percent: number): void {
  try {
    localStorage.setItem(RUN_SPLIT_KEY, String(percent));
  } catch {
    // Not persisted; the split still applies until reload.
  }
}

export interface RunSplitHandleBinding {
  percent: number;
  onPointerDown: (event: PointerEvent<HTMLDivElement>) => void;
  onPointerMove: (event: PointerEvent<HTMLDivElement>) => void;
  onPointerUp: (event: PointerEvent<HTMLDivElement>) => void;
  onPointerCancel: (event: PointerEvent<HTMLDivElement>) => void;
  onKeyDown: (event: KeyboardEvent<HTMLDivElement>) => void;
  onDoubleClick: () => void;
}

export interface RunSplit {
  /** Goes on the grid that holds the canvas, the handle and the details. */
  gridRef: RefObject<HTMLDivElement>;
  percent: number;
  handle: RunSplitHandleBinding;
}

/**
 * The canvas share of the run row, 30 to 75 percent. Dragging and keys move it; it is remembered
 * in the browser and follows other tabs.
 */
export function useRunSplit(): RunSplit {
  const gridRef = useRef<HTMLDivElement>(null);
  const [percent, setPercent] = useState(readStored);
  const current = useRef(percent);
  // Distance from the handle's left edge to the pointer at the start of a drag, so the handle
  // does not jump under the pointer.
  const grabOffset = useRef<number>();

  const apply = useCallback((next: number) => {
    current.current = next;
    setPercent(next);
  }, []);

  const commit = useCallback(
    (next: number) => {
      if (next === current.current) {
        return;
      }
      apply(next);
      store(next);
    },
    [apply],
  );

  useEffect(() => {
    const onStorage = (event: StorageEvent) => {
      if (event.key === RUN_SPLIT_KEY) {
        apply(readSplit(event.newValue));
      } else if (event.key === null) {
        apply(SPLIT_DEFAULT);
      }
    };
    window.addEventListener('storage', onStorage);
    return () => window.removeEventListener('storage', onStorage);
  }, [apply]);

  const onPointerDown = useCallback((event: PointerEvent<HTMLDivElement>) => {
    event.currentTarget.setPointerCapture(event.pointerId);
    grabOffset.current = event.clientX - event.currentTarget.getBoundingClientRect().left;
  }, []);

  const onPointerMove = useCallback(
    (event: PointerEvent<HTMLDivElement>) => {
      const grid = gridRef.current;
      if (grabOffset.current === undefined || !grid) {
        return;
      }
      const rect = grid.getBoundingClientRect();
      if (rect.width <= 0) {
        return;
      }
      apply(clampSplit(((event.clientX - grabOffset.current - rect.left) / rect.width) * 100));
    },
    [apply],
  );

  const endDrag = useCallback((event: PointerEvent<HTMLDivElement>) => {
    if (grabOffset.current === undefined) {
      return;
    }
    grabOffset.current = undefined;
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId);
    }
    store(current.current);
  }, []);

  const onKeyDown = useCallback(
    (event: KeyboardEvent<HTMLDivElement>) => {
      if (!HANDLED_KEYS.has(event.key)) {
        return;
      }
      event.preventDefault();
      commit(splitKey(current.current, event.key));
    },
    [commit],
  );

  const onDoubleClick = useCallback(() => commit(SPLIT_DEFAULT), [commit]);

  return {
    gridRef,
    percent,
    handle: {
      percent,
      onPointerDown,
      onPointerMove,
      onPointerUp: endDrag,
      onPointerCancel: endDrag,
      onKeyDown,
      onDoubleClick,
    },
  };
}
