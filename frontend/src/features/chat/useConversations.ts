import {
  infiniteQueryOptions,
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
  type QueryClient,
} from '@tanstack/react-query';
import {
  authHeaders,
  createChatConversation,
  deleteChatConversation,
  listChatConversations,
  listChatMessages,
  renameChatConversation,
  retryChatMessage,
  sendChatMessage,
  unwrap,
  type Attachment,
  type ChatFirstMessage,
  type CountChatConversationsParams,
  type ListChatConversationsParams,
} from '@flow-cortex/api-client';
import { newUuidV4 } from '../support/uuid';
import { chatProblemCode } from './chatProblem';

/** The list query parameters that pick a view; the cursor and page size belong to the paging. */
export type ConversationFilters = Omit<ListChatConversationsParams, 'cursor' | 'limit'>;

/** Everything in no folder and not pinned: the sidebar's Recent group. */
export const RECENT_FILTERS: ConversationFilters = { pinned: 'exclude', folder: 'none' };

/** `list`, `counts` and `folders` are prefixes: invalidating one refetches every open view under it. */
export const chatKeys = {
  list: ['chat', 'conversations'] as const,
  page: (filters: ConversationFilters) => ['chat', 'conversations', filters] as const,
  counts: ['chat', 'counts'] as const,
  countsFor: (filters: CountChatConversationsParams) => ['chat', 'counts', filters] as const,
  folders: ['chat', 'folders'] as const,
  messages: (conversationId: string) => ['chat', 'messages', conversationId] as const,
};

type SuccessData<T> = Extract<T, { status: 200 | 201 | 202 | 204 }> extends { data: infer D } ? D : never;

/** `unwrap` narrowed to the success body: it throws for every status >= 400. */
export async function unwrapChat<T extends { status: number; data: unknown }>(
  request: Promise<T>,
): Promise<SuccessData<T>> {
  return (await unwrap(request)) as SuccessData<T>;
}

export function requestInit(idempotencyKey?: string): RequestInit {
  const base = authHeaders().headers as Record<string, string> | undefined;
  return { headers: { ...base, ...(idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {}) } };
}

/**
 * Marks every conversation list and count view stale (open ones refetch). `folders` also marks the
 * folder list, whose conversation counts change when a conversation moves, goes or is added.
 */
export function invalidateConversationViews(
  queryClient: QueryClient,
  { folders = false }: { folders?: boolean } = {},
): Promise<unknown> {
  return Promise.all([
    queryClient.invalidateQueries({ queryKey: chatKeys.list }),
    queryClient.invalidateQueries({ queryKey: chatKeys.counts }),
    folders ? queryClient.invalidateQueries({ queryKey: chatKeys.folders }) : undefined,
  ]);
}

/** One cursor-paged view of the list; each distinct filter set is its own cache entry. */
export function conversationPagesOptions(filters: ConversationFilters) {
  return infiniteQueryOptions({
    queryKey: chatKeys.page(filters),
    queryFn: ({ pageParam }) =>
      unwrapChat(
        listChatConversations(pageParam ? { ...filters, cursor: pageParam } : filters, requestInit()),
      ),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
  });
}

/** The sidebar's Recent pages; `ChatPage` reads `chatEnabled` from this same cache entry. */
export function useConversationList() {
  return useInfiniteQuery(conversationPagesOptions(RECENT_FILTERS));
}

export function useMessages(conversationId: string | undefined) {
  return useQuery({
    queryKey: chatKeys.messages(conversationId ?? ''),
    queryFn: () => unwrapChat(listChatMessages(conversationId as string, requestInit())),
    enabled: conversationId !== undefined,
  });
}

export interface CreateConversationInput {
  firstMessage: ChatFirstMessage;
  /** Kept by the caller across retries of the same click. */
  key: string;
}

export function useCreateConversation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ firstMessage, key }: CreateConversationInput) =>
      unwrapChat(createChatConversation({ firstMessage }, requestInit(key))),
    onSuccess: () => {
      void invalidateConversationViews(queryClient);
    },
  });
}

export function useRenameConversation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, title }: { id: string; title: string }) =>
      unwrapChat(renameChatConversation(id, { title }, requestInit())),
    onSuccess: () => invalidateConversationViews(queryClient),
  });
}

export function useDeleteConversation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      try {
        await unwrapChat(deleteChatConversation(id, requestInit()));
      } catch (error) {
        // Deleted from another tab: the goal of the request already holds.
        if (chatProblemCode(error) !== 'chat-conversation-not-found') throw error;
      }
      return id;
    },
    onSuccess: (id) => {
      queryClient.removeQueries({ queryKey: chatKeys.messages(id) });
      return invalidateConversationViews(queryClient, { folders: true });
    },
  });
}

export interface SendMessageInput {
  text: string;
  attachments?: Attachment[];
  /** Kept by the caller across retries of the same click. */
  key: string;
}

/** Asks for a new answer to a failed one; the thread and the list are read again afterwards. */
export function useRetryMessage(conversationId: string, messageId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => unwrapChat(retryChatMessage(conversationId, messageId, requestInit(newUuidV4()))),
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: chatKeys.messages(conversationId) });
      void invalidateConversationViews(queryClient);
    },
  });
}

/** Sends one message; on the 202 the thread and the list are read once, live text arrives by stream. */
export function useSendMessage(conversationId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ text, key, attachments }: SendMessageInput) =>
      unwrapChat(
        sendChatMessage(
          conversationId,
          attachments && attachments.length > 0 ? { text, attachments } : { text },
          requestInit(key),
        ),
      ),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: chatKeys.messages(conversationId) });
      void invalidateConversationViews(queryClient);
    },
  });
}
