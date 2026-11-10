import type { PaymentSettingView } from '@flow-cortex/api-client';
import { useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { StatusPill, type StatusTone } from '../../app/StatusPill';
import { optionLabel, settingDescription, settingLabel, valueLabel } from './paymentSettingCopy';
import { draftError, draftOf, needsReason, requestValue, rowMode } from './paymentSettingRow';
import { ReasonDialog } from './ReasonDialog';
import { problemDetail, useUpdatePaymentSetting } from './usePaymentSettings';

const SOURCE_TONE: Record<PaymentSettingView['source'], StatusTone> = {
  STORED: 'info',
  CONFIGURED: 'muted',
  DEFAULT: 'muted',
  VETOED: 'warning',
  DEGRADED: 'warning',
};

const INT_MIN = -2147483648;
const INT_MAX = 2147483647;

interface PaymentSettingRowProps {
  readonly setting: PaymentSettingView;
  readonly canUpdate: boolean;
}

export function PaymentSettingRow({ setting, canUpdate }: Readonly<PaymentSettingRowProps>) {
  const { t } = useTranslation();
  const controlId = useId();
  const labelId = useId();
  const descriptionId = useId();
  const noteId = useId();
  const hintId = useId();
  const alertId = useId();
  const controlRef = useRef<HTMLElement | null>(null);
  const bindControl = (element: HTMLElement | null) => {
    controlRef.current = element;
  };
  const update = useUpdatePaymentSetting(setting.key);
  const held = draftOf(setting);
  const [draft, setDraft] = useState(held);
  const [reasonOpen, setReasonOpen] = useState(false);
  const wasOpen = useRef(false);

  const editable = rowMode(setting, canUpdate) === 'editable';
  const pending = update.isPending;
  const error = draftError(setting, draft);
  const unchanged = draft.trim() === held;
  const canSave = editable && !pending && error === null && !unchanged;
  const description = settingDescription(t, setting.key);

  useEffect(() => setDraft(held), [held]);

  useEffect(() => {
    if (wasOpen.current && !reasonOpen) controlRef.current?.focus();
    wasOpen.current = reasonOpen;
  }, [reasonOpen]);

  const send = (reason?: string) =>
    update.mutate(
      { value: requestValue(setting, draft), ...(reason === undefined ? {} : { reason }) },
      {
        onError: () => setDraft(held),
        onSettled: () => setReasonOpen(false),
      },
    );

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (!canSave) return;
    update.reset();
    if (needsReason(setting)) setReasonOpen(true);
    else send();
  };

  const edit = (next: string) => {
    if (pending) return;
    update.reset();
    setDraft(next);
  };

  const cancelReason = () => {
    setReasonOpen(false);
    setDraft(held);
  };

  const hint =
    editable && !unchanged && error
      ? t(`paymentSettings.draftError.${error}`, {
          min: setting.min ?? INT_MIN,
          max: setting.max ?? INT_MAX,
        })
      : null;
  const message = update.error
    ? (problemDetail(update.error) ?? t('paymentSettings.errors.save'))
    : null;
  const describedBy =
    [
      description ? descriptionId : '',
      editable ? '' : noteId,
      hint ? hintId : '',
      message ? alertId : '',
    ]
      .filter(Boolean)
      .join(' ') || undefined;
  const common = {
    id: controlId,
    disabled: !editable,
    'aria-describedby': describedBy,
    'aria-invalid': hint !== null || message !== null,
  };

  const allowed =
    setting.type === 'ENUM'
      ? t('paymentSettings.allowedValues', {
          values: setting.options.map((option) => optionLabel(t, setting.key, option)).join(', '),
        })
      : setting.type === 'INTEGER' && setting.min != null && setting.max != null
        ? t('paymentSettings.bounds', { min: setting.min, max: setting.max })
        : null;

  return (
    <li className="cortex-admin-panel payment-row" data-setting={setting.key}>
      <div className="payment-row__head">
        <div className="payment-row__text">
          <label id={labelId} htmlFor={controlId} className="payment-row__label">
            {settingLabel(t, setting.key)}
          </label>
          {description && (
            <p id={descriptionId} className="payment-muted">
              {description}
            </p>
          )}
        </div>
        <form className="payment-row__control" onSubmit={submit} noValidate>
          {setting.type === 'ENUM' && (
            <select
              {...common}
              ref={bindControl}
              className="payment-input"
              value={draft}
              onChange={(event) => edit(event.target.value)}
            >
              {setting.options.map((option) => (
                <option key={option} value={option}>
                  {optionLabel(t, setting.key, option)}
                </option>
              ))}
            </select>
          )}
          {setting.type === 'SWITCH' && (
            <label className="payment-switch">
              <input
                {...common}
                ref={bindControl}
                aria-labelledby={labelId}
                type="checkbox"
                role="switch"
                checked={draft === 'true'}
                onChange={(event) => edit(String(event.target.checked))}
              />
              <span aria-hidden="true">
                {t(draft === 'true' ? 'paymentSettings.switch.on' : 'paymentSettings.switch.off')}
              </span>
            </label>
          )}
          {setting.type === 'INTEGER' && (
            <input
              {...common}
              ref={bindControl}
              className="payment-input payment-input--number"
              type="text"
              inputMode="numeric"
              value={draft}
              onChange={(event) => edit(event.target.value)}
            />
          )}
          {editable && (
            <button
              type="submit"
              className="cortex-admin-btn cortex-admin-btn--primary"
              aria-disabled={!canSave}
            >
              {t(pending ? 'paymentSettings.saving' : 'paymentSettings.save')}
            </button>
          )}
        </form>
      </div>

      <p className="payment-row__meta">
        <StatusPill
          tone={SOURCE_TONE[setting.source]}
          label={t(`paymentSettings.source.${setting.source}`)}
        />
        {setting.dangerous && <StatusPill tone="warning" label={t('paymentSettings.dangerous')} />}
        {allowed && <span className="payment-muted">{allowed}</span>}
      </p>
      {!editable && (
        <p id={noteId} className="payment-muted">
          {t('paymentSettings.readOnly')}
        </p>
      )}
      {hint && (
        <p id={hintId} className="payment-muted">
          {hint}
        </p>
      )}
      {message && (
        <p id={alertId} role="alert" className="cortex-admin-form-error">
          {message}
        </p>
      )}

      {reasonOpen && (
        <ReasonDialog
          description={t('paymentSettings.dialog.description', {
            setting: settingLabel(t, setting.key),
            from: valueLabel(t, setting.key, setting.value),
            to: valueLabel(t, setting.key, requestValue(setting, draft)),
          })}
          pending={pending}
          onConfirm={send}
          onCancel={cancelReason}
        />
      )}
    </li>
  );
}
