import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
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
  type ChatConversation,
  type ChatConversationList,
  type ChatFirstMessage,
} from '@flow-cortex/api-client';
import { newUuidV4 } from '../support/uuid';
import { chatProblemCode } from './chatProblem';

export const chatKeys = {
  list: ['chat', 'conversations'] as const,
  messages: (conversationId: string) => ['chat', 'messages', conversationId] as const,
};

type SuccessData<T> = Extract<T, { status: 200 | 201 | 202 | 204 }> extends { data: infer D } ? D : never;

/** `unwrap` narrowed to the success body: it throws for every status >= 400. */
async function unwrapChat<T extends { status: number; data: unknown }>(
  request: Promise<T>,
): Promise<SuccessData<T>> {
  return (await unwrap(request)) as SuccessData<T>;
}

function requestInit(idempotencyKey?: string): RequestInit {
  const base = authHeaders().headers as Record<string, string> | undefined;
  return { headers: { ...base, ...(idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {}) } };
}

function mapItems(
  list: ChatConversationList | undefined,
  change: (items: ChatConversation[]) => ChatConversation[],
): ChatConversationList | undefined {
  return list && { ...list, items: change(list.items ?? []) };
}

/** Conversations as served: newest activity first. */
export function useConversationList() {
  return useQuery({
    queryKey: chatKeys.list,
    queryFn: () => unwrapChat(listChatConversations(requestInit())),
  });
}

export function useMessages(conversationId: string | undefined) {
  return useQuery({
    queryKey: chatKeys.messages(conversationId ?? ''),
    queryFn: () => unwrapChat(listChatMessages(conversationId as string, requestInit())),
    enabled: conversationId !== undefined,
  });
}

export interface CreateConversationInput {
  firstMessage?: ChatFirstMessage;
  /** Kept by the caller across retries of the same click. */
  key: string;
}

export function useCreateConversation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ firstMessage, key }: CreateConversationInput) =>
      unwrapChat(createChatConversation(firstMessage ? { firstMessage } : {}, requestInit(key))),
    onSuccess: (created) => {
      const { userMessage: _first, assistantMessageId: _answer, ...conversation } = created;
      queryClient.setQueryData<ChatConversationList>(chatKeys.list, (list) =>
        mapItems(list, (items) => [
          conversation,
          ...items.filter((item) => item.id !== conversation.id),
        ]),
      );
    },
  });
}

export function useRenameConversation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, title }: { id: string; title: string }) =>
      unwrapChat(renameChatConversation(id, { title }, requestInit())),
    onSuccess: (renamed) => {
      queryClient.setQueryData<ChatConversationList>(chatKeys.list, (list) =>
        mapItems(list, (items) =>
          items.map((item) =>
            item.id === renamed.id && renamed.title !== undefined
              ? { ...item, title: renamed.title }
              : item,
          ),
        ),
      );
    },
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
      queryClient.setQueryData<ChatConversationList>(chatKeys.list, (list) =>
        mapItems(list, (items) => items.filter((item) => item.id !== id)),
      );
      queryClient.removeQueries({ queryKey: chatKeys.messages(id) });
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
      void queryClient.invalidateQueries({ queryKey: chatKeys.list });
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
      void queryClient.invalidateQueries({ queryKey: chatKeys.list });
    },
  });
}
