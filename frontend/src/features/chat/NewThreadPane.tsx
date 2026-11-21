import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { chatProblem } from './chatProblem';
import { Composer } from './Composer';
import { useCreateConversation } from './useConversations';

/** `/chat` without an id: an empty thread whose first message creates the conversation. */
export function NewThreadPane({ chatEnabled }: Readonly<{ chatEnabled: boolean }>) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const create = useCreateConversation();
  const problem = create.error ? chatProblem(create.error, t) : null;
  const chatOff = !chatEnabled || problem?.kind === 'disabled';

  return (
    <>
      <div className="chat-thread">
        <p className="chat-thread__empty">{t('chat.thread.empty')}</p>
      </div>
      {chatOff ? (
        <p className="chat-off" role="status">
          {t('chat.problems.off')}
        </p>
      ) : (
        <Composer
          focusOnMount
          busy={false}
          sending={create.isPending}
          problem={problem}
          onSend={async (text, key, attachments) => {
            const created = await create.mutateAsync({
              firstMessage: attachments.length > 0 ? { text, attachments } : { text },
              key,
            });
            void navigate(`/chat/${created.id}`);
          }}
          onStop={() => {}}
          stopping={false}
        />
      )}
    </>
  );
}
