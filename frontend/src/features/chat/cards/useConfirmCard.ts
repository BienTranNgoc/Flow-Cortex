import { authHeaders, confirmChatCard, unwrap, type ChatCardConfirm } from '@flow-cortex/api-client';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { invalidateBalance } from '../../credits/useBalance';
import { chatProblemCode } from '../chatProblem';
import { chatKeys } from '../useConversations';

/**
 * Confirms one card. The server answers a repeated confirm with the same run, so a retry after a
 * lost reply is safe; the thread is reloaded either way, because the card (started, or images
 * expired) and the run message below it live there.
 */
export function useConfirmCard(conversationId: string, messageId: string) {
  const queryClient = useQueryClient();
  const reloadThread = () => queryClient.invalidateQueries({ queryKey: chatKeys.messages(conversationId) });
  return useMutation({
    mutationFn: (body: ChatCardConfirm) => unwrap(confirmChatCard(conversationId, messageId, body, authHeaders())),
    onSuccess: () => {
      void reloadThread();
      void invalidateBalance(queryClient);
    },
    onError: (error) => {
      if (chatProblemCode(error) === 'chat-card-images-expired') void reloadThread();
    },
  });
}
