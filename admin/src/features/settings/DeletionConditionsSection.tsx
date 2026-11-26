import { ApiError, type DeletionBlockConditionView } from '@flow-cortex/api-client';
import { useEffect, useId, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { formatVietnamTime } from '../support/vietnamTime';
import { parseSettingInput } from './settingInput';
import { useDeletionConditions, useUpdateDeletionCondition } from './useDeletionConditions';

const SORT_ORDER_MIN = 0;
const SORT_ORDER_MAX = 999;
const MESSAGE_MAX = 500;

/** The text the server wrote for each refused field, else `undefined` so the generic failure shows. */
function refusal(error: unknown): string | undefined {
  if (!(error instanceof ApiError) || error.status !== 400) return undefined;
  const fields = Object.values(error.problem?.errors ?? {});
  return fields.length > 0 ? fields.join(' ') : undefined;
}

interface ConditionRowProps {
  readonly condition: DeletionBlockConditionView;
  readonly canUpdate: boolean;
}

function ConditionRow({ condition, canUpdate }: Readonly<ConditionRowProps>) {
  const { t } = useTranslation();
  const switchId = useId();
  const orderId = useId();
  const viId = useId();
  const enId = useId();
  const messageId = useId();
  const update = useUpdateDeletionCondition(condition.key);
  const [enabled, setEnabled] = useState(condition.enabled);
  const [order, setOrder] = useState(String(condition.sortOrder));
  const [messageVi, setMessageVi] = useState(condition.messageVi);
  const [messageEn, setMessageEn] = useState(condition.messageEn);
  const [bounds, setBounds] = useState<string | null>(null);

  useEffect(() => {
    setEnabled(condition.enabled);
    setOrder(String(condition.sortOrder));
    setMessageVi(condition.messageVi);
    setMessageEn(condition.messageEn);
    setBounds(null);
  }, [condition]);

  const dirty =
    enabled !== condition.enabled ||
    order.trim() !== String(condition.sortOrder) ||
    messageVi !== condition.messageVi ||
    messageEn !== condition.messageEn;
  const busy = update.isPending;
  const message =
    bounds ?? (update.error ? (refusal(update.error) ?? t('settings.saveFailed')) : null);
  const describedBy = message ? messageId : undefined;

  const edit = (apply: () => void) => {
    apply();
    setBounds(null);
    update.reset();
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (busy || !dirty) return;
    const parsed = parseSettingInput(order, SORT_ORDER_MIN, SORT_ORDER_MAX);
    if (!parsed.ok) {
      setBounds(t('settings.integerRange', { min: parsed.min, max: parsed.max }));
      return;
    }
    setBounds(null);
    update.mutate({ enabled, sortOrder: parsed.value, messageVi, messageEn });
  };

  const change = t(condition.updatedBy ? 'settings.changedBy' : 'settings.changedAt', {
    email: condition.updatedBy,
    at: formatVietnamTime(condition.updatedAt),
  });

  return (
    <li data-condition={condition.key}>
      <form className="cortex-admin-panel" onSubmit={submit} noValidate>
        <div className="settings-item-head">
          <div>
            <p className="settings-item-label">
              {t(`settings.deletionConditions.names.${condition.key}`, {
                defaultValue: condition.key,
              })}
            </p>
            <p className="settings-muted">{condition.key}</p>
          </div>
          <div className="settings-number">
            <label htmlFor={switchId} className="settings-muted">
              {t('settings.deletionConditions.enabled')}
            </label>
            <input
              id={switchId}
              className="settings-switch"
              type="checkbox"
              role="switch"
              checked={enabled}
              disabled={!canUpdate}
              aria-describedby={describedBy}
              onChange={(event) => edit(() => setEnabled(event.target.checked))}
            />
          </div>
        </div>

        <div className="cortex-admin-field">
          <label htmlFor={orderId}>{t('settings.deletionConditions.order')}</label>
          <input
            id={orderId}
            type="text"
            inputMode="numeric"
            value={order}
            readOnly={!canUpdate}
            aria-invalid={bounds !== null}
            aria-describedby={describedBy}
            onChange={(event) => edit(() => setOrder(event.target.value))}
          />
          <span className="settings-muted">
            {t('settings.bounds', { min: SORT_ORDER_MIN, max: SORT_ORDER_MAX })}
          </span>
        </div>

        <div className="cortex-admin-field">
          <label htmlFor={viId}>{t('settings.deletionConditions.messageVi')}</label>
          <textarea
            id={viId}
            rows={3}
            maxLength={MESSAGE_MAX}
            value={messageVi}
            readOnly={!canUpdate}
            aria-describedby={describedBy}
            onChange={(event) => edit(() => setMessageVi(event.target.value))}
          />
        </div>

        <div className="cortex-admin-field">
          <label htmlFor={enId}>{t('settings.deletionConditions.messageEn')}</label>
          <textarea
            id={enId}
            rows={3}
            maxLength={MESSAGE_MAX}
            value={messageEn}
            readOnly={!canUpdate}
            aria-describedby={describedBy}
            onChange={(event) => edit(() => setMessageEn(event.target.value))}
          />
          <span className="settings-muted">
            {t('settings.deletionConditions.messageLimit', { max: MESSAGE_MAX })}
          </span>
        </div>

        <p className="settings-muted">{change}</p>
        {message && (
          <p id={messageId} role="alert" className="cortex-admin-form-error">
            {message}
          </p>
        )}
        {canUpdate && (
          <button
            type="submit"
            className="cortex-admin-btn cortex-admin-btn--primary cortex-admin-btn--sm"
            aria-disabled={busy || !dirty}
          >
            {t(busy ? 'settings.saving' : 'settings.save')}
          </button>
        )}
      </form>
    </li>
  );
}

interface DeletionConditionsSectionProps {
  readonly canUpdate: boolean;
}

/** Every condition that can stop a member from requesting deletion, each row saved on its own. */
export function DeletionConditionsSection({ canUpdate }: Readonly<DeletionConditionsSectionProps>) {
  const { t } = useTranslation();
  const query = useDeletionConditions();
  const conditions = query.data;

  return (
    <section
      className="settings-group"
      aria-labelledby="settings-deletion-conditions"
      data-section="deletion-conditions"
    >
      <h2 id="settings-deletion-conditions">{t('settings.deletionConditions.title')}</h2>
      <p className="settings-muted">{t('settings.deletionConditions.description')}</p>

      {query.isPending && <output>{t('settings.loading')}</output>}
      {query.isError && !conditions && (
        <div className="cortex-admin-error">
          <p>{t('settings.deletionConditions.loadFailed')}</p>
          <button type="button" className="cortex-admin-btn" onClick={() => query.refetch()}>
            {t('settings.retry')}
          </button>
        </div>
      )}
      {query.isError && conditions && (
        <p className="cortex-admin-pill cortex-admin-pill--warning">{t('settings.refreshFailed')}</p>
      )}
      {conditions && conditions.length === 0 && (
        <p className="settings-muted">{t('settings.deletionConditions.empty')}</p>
      )}
      {conditions && conditions.length > 0 && (
        <ul className="settings-list">
          {conditions.map((condition) => (
            <ConditionRow key={condition.key} condition={condition} canUpdate={canUpdate} />
          ))}
        </ul>
      )}
    </section>
  );
}
