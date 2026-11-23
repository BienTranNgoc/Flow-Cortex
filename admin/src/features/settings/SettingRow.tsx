import { ApiError, type AdminSetting } from '@flow-cortex/api-client';
import { useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { formatVietnamTime } from '../support/vietnamTime';
import { LowerValueDialog } from './LowerValueDialog';
import { SettingHistory } from './SettingHistory';
import { loweringToConfirm, type Lowering } from './settingConfirmation';
import { parseSettingInput } from './settingInput';
import { useResetSetting, useUpdateSetting } from './useSettings';

interface SettingRowProps {
  readonly setting: AdminSetting;
  readonly canUpdate: boolean;
}

/** The refused-value text the server wrote in the caller's language, else the generic save failure. */
function refusal(error: unknown): string | undefined {
  return error instanceof ApiError && error.status === 422 ? error.problem?.errors?.value : undefined;
}

/** A lowering the admin has not confirmed yet: the change it releases and the button that asked. */
interface HeldChange {
  readonly lowering: Lowering;
  readonly run: () => void;
  readonly opener: HTMLButtonElement | null;
}

export function SettingRow({ setting, canUpdate }: Readonly<SettingRowProps>) {
  const { t } = useTranslation();
  const controlId = useId();
  const messageId = useId();
  const update = useUpdateSetting(setting.key);
  const reset = useResetSetting(setting.key);
  const decided = setting.decidedValue;
  const [draft, setDraft] = useState(String(decided));
  const [bounds, setBounds] = useState<string | null>(null);
  const [held, setHeld] = useState<HeldChange | null>(null);
  const saveButton = useRef<HTMLButtonElement>(null);
  const resetButton = useRef<HTMLButtonElement>(null);
  const focusAfterDialog = useRef<HTMLButtonElement | null>(null);
  const busy = update.isPending || reset.isPending;
  const isSwitch = setting.type === 'SWITCH';

  useEffect(() => {
    setDraft(String(decided));
    setBounds(null);
  }, [decided]);

  useEffect(() => {
    if (held === null) {
      focusAfterDialog.current?.focus();
      focusAfterDialog.current = null;
    }
  }, [held]);

  const label = t(`settings.keys.${setting.key}.label`, { defaultValue: setting.key });
  const help = t(`settings.keys.${setting.key}.help`, { defaultValue: '' });
  const min = setting.min ?? 1;
  const max = setting.max ?? Number.MAX_SAFE_INTEGER;
  const failure = update.error ?? reset.error;
  const message =
    bounds ??
    (failure ? (refusal(failure) ?? t('settings.saveFailed')) : null);

  const submitNumber = (event: FormEvent) => {
    event.preventDefault();
    if (busy) return;
    const parsed = parseSettingInput(draft, min, max);
    if (!parsed.ok) {
      setBounds(t('settings.integerRange', { min: parsed.min, max: parsed.max }));
      return;
    }
    setBounds(null);
    const value = parsed.value;
    const send = () => {
      reset.reset();
      update.mutate(value);
    };
    const lowering = loweringToConfirm(setting.key, setting.value, value);
    if (lowering) setHeld({ lowering, run: send, opener: saveButton.current });
    else send();
  };

  const resetToConfigured = () => {
    if (busy) return;
    const send = () => {
      update.reset();
      setBounds(null);
      reset.mutate();
    };
    const lowering = loweringToConfirm(setting.key, setting.value, setting.configuredValue);
    if (lowering) setHeld({ lowering, run: send, opener: resetButton.current });
    else send();
  };

  const closeDialog = (change: HeldChange) => {
    focusAfterDialog.current = change.opener;
    setHeld(null);
  };

  const toggle = (value: boolean) => {
    if (busy) return;
    reset.reset();
    update.mutate(value);
  };

  const change = setting.updatedAt
    ? t(setting.updatedBy ? 'settings.changedBy' : 'settings.changedAt', {
        email: setting.updatedBy,
        at: formatVietnamTime(setting.updatedAt),
      })
    : null;

  return (
    <li className="cortex-admin-panel settings-item" data-setting={setting.key}>
      <div className="settings-item-head">
        <div>
          <label htmlFor={controlId} className="settings-item-label">
            {label}
          </label>
          {help && <p className="settings-muted">{help}</p>}
        </div>
        {isSwitch ? (
          <input
            id={controlId}
            className="settings-switch"
            type="checkbox"
            role="switch"
            checked={decided === true}
            disabled={!canUpdate}
            aria-disabled={busy}
            aria-describedby={message ? messageId : undefined}
            onChange={(event) => toggle(event.target.checked)}
          />
        ) : setting.type === 'ENUM' ? (
          <output id={controlId}>{String(decided)}</output>
        ) : (
          <form className="settings-number" onSubmit={submitNumber} noValidate>
            <input
              id={controlId}
              type="text"
              inputMode="numeric"
              value={draft}
              readOnly={!canUpdate}
              aria-invalid={message !== null}
              aria-describedby={message ? messageId : undefined}
              onChange={(event) => {
                setDraft(event.target.value);
                setBounds(null);
              }}
            />
            {canUpdate && (
              <button
                ref={saveButton}
                type="submit"
                className="cortex-admin-btn cortex-admin-btn--primary cortex-admin-btn--sm"
                aria-disabled={busy || draft.trim() === String(decided)}
              >
                {t(update.isPending ? 'settings.saving' : 'settings.save')}
              </button>
            )}
          </form>
        )}
      </div>

      <p className="settings-meta">
        <span className={`cortex-admin-pill ${setting.source === 'STORED' ? '' : 'cortex-admin-pill--muted'}`}>
          {t(`settings.source.${setting.source}`)}
        </span>
        {!isSwitch && setting.min != null && setting.max != null && (
          <span className="settings-muted">
            {t('settings.bounds', { min: setting.min, max: setting.max })}
          </span>
        )}
      </p>
      {setting.source === 'VETOED' && (
        <p className="cortex-admin-pill cortex-admin-pill--warning settings-notice">
          {t('settings.vetoedNote')}
        </p>
      )}
      {setting.source === 'DEGRADED' && (
        <p className="cortex-admin-pill cortex-admin-pill--warning settings-notice">
          {t('settings.degradedNote')}
        </p>
      )}
      {change && <p className="settings-muted">{change}</p>}
      {message && (
        <p id={messageId} role="alert" className="cortex-admin-form-error">
          {message}
        </p>
      )}
      {canUpdate && setting.storedValue != null && (
        <button
          ref={resetButton}
          type="button"
          className="cortex-admin-btn cortex-admin-btn--ghost cortex-admin-btn--sm"
          aria-disabled={busy}
          onClick={resetToConfigured}
        >
          {t('settings.reset')}
        </button>
      )}
      <SettingHistory setting={setting} />
      {held && (
        <LowerValueDialog
          lowering={held.lowering}
          pending={busy}
          onConfirm={() => {
            held.run();
            closeDialog(held);
          }}
          onCancel={() => closeDialog(held)}
        />
      )}
    </li>
  );
}
