import { authHeaders, stopChatMessage, unwrap } from '@flow-cortex/api-client';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { chatKeys } from '../useConversations';

/** Asks the server to end the answer being written; the partial text stays and the thread reloads either way. */
export function useStopTurn(conversationId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (messageId: string) => unwrap(stopChatMessage(conversationId, messageId, authHeaders())),
    onSettled: () => queryClient.invalidateQueries({ queryKey: chatKeys.messages(conversationId) }),
  });
}
