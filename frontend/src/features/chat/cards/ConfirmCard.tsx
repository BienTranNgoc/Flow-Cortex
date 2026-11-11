import type { ChatCardProductVideo } from '@flow-cortex/api-client';
import { useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { activeLocale } from '../../../lib/i18n';
import { CreditsRefusal } from '../../credits/CreditsRefusal';
import { formatCredits } from '../../credits/formatCredits';
import { AttachmentPicker } from '../stream/AttachmentPicker';
import { useAttachments } from '../stream/useAttachments';
import { PRODUCT_FIELD_NAMES, type CardImage, type CardModel, type ProductFields } from './cardModel';
import { cardProblem } from './cardProblem';
import { useConfirmCard } from './useConfirmCard';

const MAX_IMAGES = 10;
const FIELD_MAX: Record<keyof ProductFields, number> = {
  productName: 200,
  description: 4000,
  cta: 500,
  price: 200,
  promotion: 500,
};
const MULTILINE = new Set<keyof ProductFields>(['description']);

interface ConfirmCardProps {
  conversationId: string;
  messageId: string;
  card: CardModel;
}

function ProductForm({
  draft,
  fields,
  locked,
  errors,
  onChange,
}: Readonly<{
  draft: ProductFields;
  fields: readonly (keyof ProductFields)[];
  locked: boolean;
  errors: Record<string, string>;
  onChange: (name: keyof ProductFields, value: string) => void;
}>) {
  const { t } = useTranslation();
  const prefix = useId();
  return (
    <div className="chat-card__fields">
      {fields.map((name) => {
        const id = `${prefix}-${name}`;
        const error = errors[name];
        const required = name === 'productName' || name === 'description';
        const shared = {
          id,
          value: draft[name],
          maxLength: FIELD_MAX[name],
          readOnly: locked,
          required,
          'aria-invalid': error ? true : undefined,
          'aria-describedby': error ? `${id}-error` : undefined,
        };
        return (
          <div key={name} className="chat-card__field">
            <label htmlFor={id}>
              {t(`chat.card.field.${name}`)}
              {!required && <span className="chat-card__optional"> {t('chat.card.optional')}</span>}
            </label>
            {MULTILINE.has(name) ? (
              <textarea
                {...shared}
                className="chat-card__input"
                onChange={(event) => onChange(name, event.target.value)}
              />
            ) : (
              <input
                {...shared}
                className="chat-card__input"
                onChange={(event) => onChange(name, event.target.value)}
              />
            )}
            {error && (
              <p id={`${id}-error`} role="alert" className="form-error">
                {error}
              </p>
            )}
          </div>
        );
      })}
    </div>
  );
}

/**
 * The card the assistant proposes: what will run, with which inputs and what it should cost. The
 * run starts only on "Run"; the server makes a repeated confirm return the same run.
 */
export function ConfirmCard({ conversationId, messageId, card }: Readonly<ConfirmCardProps>) {
  const { t } = useTranslation();
  const confirm = useConfirmCard(conversationId, messageId);
  const isVideo = card.kind === 'product_video';
  const hasImages = isVideo || card.uploads.length > 0;
  const attachments = useAttachments(isVideo ? 1 : MAX_IMAGES);
  const [draft, setDraft] = useState<ProductFields>(card.product);
  const pressed = useRef(false);

  const started = card.state === 'started' || confirm.isSuccess;
  const expired = card.state === 'images-expired' && attachments.ready.length === 0;
  const imageCount = attachments.ready.length || (card.state === 'images-expired' ? 0 : card.uploads.length);
  const edited = PRODUCT_FIELD_NAMES.some((name) => draft[name] !== card.product[name]);
  const problem = confirm.isError ? cardProblem(confirm.error, t, activeLocale()) : null;
  const fieldErrors = problem?.kind === 'invalid' ? problem.fields : {};
  const fieldLess =
    problem?.kind === 'invalid' &&
    !PRODUCT_FIELD_NAMES.some((name) => fieldErrors[name]) &&
    !fieldErrors.attachments;
  const alertMessage = problem?.kind === 'alert' || fieldLess ? (problem?.message ?? null) : null;

  const incomplete =
    (isVideo && (draft.productName.trim() === '' || draft.description.trim() === '')) ||
    (hasImages && (isVideo ? imageCount !== 1 : imageCount < 1));
  const canRun = !started && !confirm.isPending && !attachments.uploading && !incomplete;

  const send = (acknowledgeCost: boolean) => {
    if (pressed.current) return;
    pressed.current = true;
    const productVideo: ChatCardProductVideo = {
      productName: draft.productName.trim(),
      description: draft.description.trim(),
    };
    for (const name of ['cta', 'price', 'promotion'] as const) {
      if (draft[name].trim() !== '') productVideo[name] = draft[name].trim();
    }
    confirm.mutate(
      {
        acknowledgeCost,
        ...(isVideo && edited ? { productVideo } : {}),
        ...(attachments.ready.length > 0 ? { attachments: attachments.ready } : {}),
      },
      { onSettled: () => (pressed.current = false) },
    );
  };

  let shownImages: readonly CardImage[] = card.uploads;
  if (attachments.ready.length > 0) shownImages = attachments.ready;
  else if (card.state === 'images-expired') shownImages = [];
  const editable = !started;

  return (
    <section className="chat-card" aria-label={t('chat.card.label', { name: card.workflowName })}>
      <h3 className="chat-card__title">{card.workflowName}</h3>
      {isVideo ? (
        <ProductForm
          draft={draft}
          fields={PRODUCT_FIELD_NAMES}
          locked={!editable}
          errors={fieldErrors}
          onChange={(name, value) => setDraft((current) => ({ ...current, [name]: value }))}
        />
      ) : (
        card.input.trim() !== '' && (
          <p className="chat-card__input-text">
            <span className="chat-card__caption">{t('chat.card.input')}</span> {card.input}
          </p>
        )
      )}
      {hasImages && (
        <div className="chat-card__images">
          <span className="chat-card__caption">{t('chat.card.images')}</span>
          {shownImages.length > 0 ? (
            <ul className="chat-card__image-list">
              {shownImages.map((image) => (
                <li key={image.uploadId}>{image.fileName}</li>
              ))}
            </ul>
          ) : (
            <span>{t('chat.card.noImages')}</span>
          )}
        </div>
      )}
      {expired && !started && (
        <p role="alert" className="form-error">
          {t('chat.card.imagesExpired')}
        </p>
      )}
      {fieldErrors.attachments && (
        <p role="alert" className="form-error">
          {fieldErrors.attachments}
        </p>
      )}
      {hasImages && editable && (
        <AttachmentPicker
          attachments={attachments}
          max={isVideo ? 1 : MAX_IMAGES}
          label={card.state === 'images-expired' ? t('chat.card.attachAgain') : t('chat.card.changeImages')}
          disabled={confirm.isPending}
        />
      )}
      {card.estimatedCredits !== null && (
        <p className="chat-card__cost">
          {t('credits.estimatedCost', { amount: formatCredits(card.estimatedCredits, activeLocale()) })}
        </p>
      )}
      {problem?.kind === 'credits' && (
        <CreditsRefusal
          message={problem.message}
          busy={confirm.isPending}
          onRunAnyway={problem.canRunAnyway ? () => send(true) : undefined}
        />
      )}
      {alertMessage !== null && (
        <p role="alert" className="form-error">
          {alertMessage}
        </p>
      )}
      {started ? (
        <p className="chat-card__started" role="status">
          {t('chat.card.started')}
        </p>
      ) : (
        <button
          type="button"
          className="chat-button chat-button--primary"
          disabled={!canRun}
          aria-busy={confirm.isPending}
          onClick={() => send(false)}
        >
          {confirm.isPending ? t('chat.card.running') : t('chat.card.run')}
        </button>
      )}
    </section>
  );
}
