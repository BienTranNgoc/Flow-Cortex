import type { ChatMessage } from '@flow-cortex/api-client';

export interface CardImage {
  uploadId: string;
  fileName: string;
}

export interface ProductFields {
  productName: string;
  description: string;
  cta: string;
  price: string;
  promotion: string;
}

export type CardState = 'pending' | 'images-expired' | 'started';

/** A confirm card as the server stored it. */
export interface CardModel {
  kind: 'run' | 'product_video';
  state: CardState;
  workflowName: string;
  estimatedCredits: number | null;
  uploads: CardImage[];
  input: string;
  product: ProductFields;
  runId: string | null;
}

export const PRODUCT_FIELD_NAMES = ['productName', 'description', 'cta', 'price', 'promotion'] as const;

type Raw = Record<string, unknown>;

const isRecord = (value: unknown): value is Raw =>
  typeof value === 'object' && value !== null && !Array.isArray(value);

const text = (value: unknown): string => (typeof value === 'string' ? value : '');

function readUploads(value: unknown): CardImage[] {
  if (!Array.isArray(value)) return [];
  return value.flatMap((item) =>
    isRecord(item) && typeof item.uploadId === 'string'
      ? [{ uploadId: item.uploadId, fileName: text(item.fileName) }]
      : [],
  );
}

function readState(value: unknown): CardState {
  return value === 'started' || value === 'images-expired' ? value : 'pending';
}

/** The card a `card` message holds; `null` when its payload is not a card with a workflow name. */
export function readCard(payload: ChatMessage['payload']): CardModel | null {
  if (!isRecord(payload)) return null;
  const workflowName = text(payload.workflowName).trim();
  if (workflowName === '') return null;
  const product = isRecord(payload.productVideo) ? payload.productVideo : {};
  const credits = payload.estimatedCredits;
  return {
    kind: payload.type === 'product_video' ? 'product_video' : 'run',
    state: readState(payload.state),
    workflowName,
    estimatedCredits: typeof credits === 'number' && Number.isFinite(credits) ? credits : null,
    uploads: readUploads(payload.uploads),
    input: text(payload.input),
    product: {
      productName: text(product.productName),
      description: text(product.description),
      cta: text(product.cta),
      price: text(product.price),
      promotion: text(product.promotion),
    },
    runId: typeof payload.runId === 'string' && payload.runId !== '' ? payload.runId : null,
  };
}

/** The run a `run` message points at, or a started card's own run. */
export function readRunId(payload: ChatMessage['payload']): string | null {
  const runId = isRecord(payload) ? payload.runId : undefined;
  return typeof runId === 'string' && runId !== '' ? runId : null;
}

/** Every run in a thread, in order, once each. */
export function threadRunIds(messages: readonly ChatMessage[]): string[] {
  const ids = messages.flatMap((message) => {
    const runId = message.kind === 'run' ? readRunId(message.payload) : null;
    return runId === null ? [] : [runId];
  });
  return [...new Set(ids)];
}
