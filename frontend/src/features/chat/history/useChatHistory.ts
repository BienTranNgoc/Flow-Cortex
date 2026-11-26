import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  countChatConversations,
  createChatFolder,
  deleteChatFolder,
  listChatFolders,
  moveChatConversation,
  pinChatConversation,
  renameChatFolder,
  type CountChatConversationsParams,
} from '@flow-cortex/api-client';
import {
  chatKeys,
  conversationPagesOptions,
  invalidateConversationViews,
  requestInit,
  unwrapChat,
  type ConversationFilters,
} from '../useConversations';

/** Cursor-paged conversations for one filter set. */
export function useConversationPages(filters: ConversationFilters) {
  return useInfiniteQuery(conversationPagesOptions(filters));
}

/** Group sizes for the history page; `todayStart` and `weekStart` must be the instants the groups use. */
export function useConversationCounts(filters: CountChatConversationsParams) {
  return useQuery({
    queryKey: chatKeys.countsFor(filters),
    queryFn: () => unwrapChat(countChatConversations(filters, requestInit())),
  });
}

export function useFolders() {
  return useQuery({
    queryKey: chatKeys.folders,
    queryFn: async () => (await unwrapChat(listChatFolders(requestInit()))).items ?? [],
  });
}

/*
 * Every write below settles into a refetch, success or refusal: a 404 means another tab changed the
 * thing, and the views must show what is there now.
 */

export function usePin() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, pinned }: { id: string; pinned: boolean }) =>
      unwrapChat(pinChatConversation(id, { pinned }, requestInit())),
    onSettled: () => invalidateConversationViews(queryClient),
  });
}

/** `folderId` null returns the conversation to Recent. */
export function useMoveToFolder() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, folderId }: { id: string; folderId: string | null }) =>
      unwrapChat(moveChatConversation(id, { folderId }, requestInit())),
    onSettled: () => invalidateConversationViews(queryClient, { folders: true }),
  });
}

export function useCreateFolder() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (name: string) => unwrapChat(createChatFolder({ name }, requestInit())),
    onSettled: () => queryClient.invalidateQueries({ queryKey: chatKeys.folders }),
  });
}

export function useRenameFolder() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, name }: { id: string; name: string }) =>
      unwrapChat(renameChatFolder(id, { name }, requestInit())),
    onSettled: () => queryClient.invalidateQueries({ queryKey: chatKeys.folders }),
  });
}

/** Its conversations return to Recent, so every list and count view is stale as well. */
export function useDeleteFolder() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => unwrapChat(deleteChatFolder(id, requestInit())),
    onSettled: () => invalidateConversationViews(queryClient, { folders: true }),
  });
}
