import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import {
  ApiError,
  askSupportAssistant,
  askSupportAssistantAsGuest,
  authHeaders,
  getSupportAssistantGuestLatestConversation,
  getSupportAssistantLatestConversation,
  type SupportAssistantConversation,
  type SupportAssistantHistoryMessage,
  type SupportAssistantQuestion,
  type SupportAssistantReply,
} from '@flow-cortex/api-client';
import { strippedLength } from '@flow-cortex/ui-kit';
import { useFeatures } from '../../app/useFeatures';
import { formatDateTime } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';
import { supportSessionId } from './supportSession';
import { supportProblemCode, unwrapSupport, useSupportSettings } from './useSupportKb';

/** Matches the backend, which counts code points of the stripped question. */
export const MAX_QUESTION_LENGTH = 1000;

export function questionLength(text: string): number {
  return strippedLength(text);
}

export type AssistantAvailability = 'checking' | 'on' | 'off';

/** Off when the feature switch is off or the console has switched the assistant off. */
export function useAssistantAvailability(): AssistantAvailability {
  const { supportAssistant } = useFeatures();
  const featuresStatus = useQueryClient().getQueryState(['features'])?.status;
  const settings = useSupportSettings();
  if (featuresStatus !== 'success' && featuresStatus !== 'error') return 'checking';
  if (!supportAssistant) return 'off';
  if (settings.isPending) return 'checking';
  // A failed settings read leaves the decision to the assistant route's own 503.
  return settings.data?.assistantAvailable === false ? 'off' : 'on';
}

function guestHeaders(): RequestInit {
  return { headers: { 'X-Support-Session': supportSessionId() } };
}

function latestKey(member: boolean) {
  return ['support', 'assistant', 'latest', member ? 'member' : 'guest'] as const;
}

async function readLatest(member: boolean): Promise<SupportAssistantConversation | null> {
  try {
    return member
      ? await unwrapSupport(getSupportAssistantLatestConversation(authHeaders()))
      : await unwrapSupport(getSupportAssistantGuestLatestConversation(guestHeaders()));
  } catch (error) {
    if (supportProblemCode(error) === 'conversation-not-found') return null;
    throw error;
  }
}

// Counts "New conversation" presses: a reply to a question asked before one belongs to a
// conversation the member has left and must not be written back.
const generations = new WeakMap<QueryClient, number>();
const generationOf = (client: QueryClient) => generations.get(client) ?? 0;

/**
 * The actor's latest conversation, or `null` when there is none (or it expired). Kept in the
 * query cache for the session: every answer is appended there, so the tab can remount.
 */
export function useAssistantConversation(member: boolean) {
  const queryClient = useQueryClient();
  const query = useQuery({
    queryKey: latestKey(member),
    queryFn: () => readLatest(member),
    staleTime: Infinity,
  });
  const startOver = () => {
    generations.set(queryClient, generationOf(queryClient) + 1);
    queryClient.setQueryData(latestKey(member), null);
  };
  return { query, startOver };
}

export interface AskInput {
  question: string;
  conversationId: string | null;
}

function send(member: boolean, question: string, conversationId: string | null) {
  const body: SupportAssistantQuestion = conversationId
    ? { question, conversationId }
    : { question };
  return member
    ? unwrapSupport(askSupportAssistant(body, authHeaders()))
    : unwrapSupport(askSupportAssistantAsGuest(body, guestHeaders()));
}

interface AskResult {
  reply: SupportAssistantReply;
  question: string;
  restarted: boolean;
}

/** Sends a question; a conversation the server no longer knows is replaced by a new one. */
export function useAskAssistant(member: boolean) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ question, conversationId }: AskInput): Promise<AskResult> => {
      try {
        return { reply: await send(member, question, conversationId), question, restarted: false };
      } catch (error) {
        if (conversationId === null || supportProblemCode(error) !== 'conversation-not-found') {
          throw error;
        }
        return { reply: await send(member, question, null), question, restarted: true };
      }
    },
    onMutate: () => ({ generation: generationOf(queryClient) }),
    onSuccess: ({ reply, question, restarted }, _input, context) => {
      if (context?.generation !== generationOf(queryClient)) return;
      const now = new Date().toISOString();
      const exchange: SupportAssistantHistoryMessage[] = [
        { role: 'user', text: question, sources: [], createdAt: now },
        { ...reply.message, createdAt: now },
      ];
      queryClient.setQueryData<SupportAssistantConversation | null>(
        latestKey(member),
        (previous) =>
          previous && !restarted && previous.conversationId === reply.conversationId
            ? { ...previous, messages: [...previous.messages, ...exchange] }
            : { conversationId: reply.conversationId, messages: exchange },
      );
    },
  });
}

const DAY_MS = 86_400_000;
// Asia/Ho_Chi_Minh is UTC+7 all year.
const VIETNAM_OFFSET_MS = 7 * 3_600_000;

/**
 * True when a 429 `assistant-quota` comes from the per-minute cap: the daily quota always resets
 * at exactly 00:00 Vietnam time, the minute cap at any other instant. Needs no client clock.
 */
export function isMinuteCap(error: unknown): boolean {
  const retryAt = error instanceof ApiError ? error.problem?.retryAt : undefined;
  const at = retryAt ? new Date(retryAt).getTime() : Number.NaN;
  if (Number.isNaN(at)) return false;
  return (at + VIETNAM_OFFSET_MS) % DAY_MS !== 0;
}

/** "HH:mm" (Vietnam time) when a quota or pause lifts; quotas reset at 00:00 when the server omits it. */
export function resumeTime(error: unknown): string {
  const retryAt = error instanceof ApiError ? error.problem?.retryAt : undefined;
  const at = retryAt ? new Date(retryAt) : null;
  return at && !Number.isNaN(at.getTime())
    ? formatDateTime(at, activeLocale(), {
        hour: '2-digit',
        minute: '2-digit',
        hourCycle: 'h23',
        timeZone: 'Asia/Ho_Chi_Minh',
      })
    : '00:00';
}
