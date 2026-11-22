import { ApiError, type RbacRuntime } from '@flow-cortex/api-client';
import type { UseQueryResult } from '@tanstack/react-query';
import { useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { formatVietnamTime } from '../support/vietnamTime';
import { RbacRuntimeDialog } from './RbacRuntimeDialog';
import { useUpdateRbacRuntimeMutation } from './useRbacRuntime';

interface RbacRuntimeSwitchProps {
  readonly runtime: UseQueryResult<RbacRuntime>;
}

/** The super admin's switch for role and permission edits: state, source, last change and a toggle that asks for a reason. */
export function RbacRuntimeSwitch({ runtime }: Readonly<RbacRuntimeSwitchProps>) {
  const { t } = useTranslation();
  const labelId = useId();
  const vetoId = useId();
  const toggleRef = useRef<HTMLButtonElement>(null);
  const update = useUpdateRbacRuntimeMutation();
  const [confirming, setConfirming] = useState(false);
  const state = runtime.data;

  if (runtime.isPending) {
    return <output className="cortex-admin-loading">{t('roles.runtime.loading')}</output>;
  }
  if (!state) return null;

  const vetoed = state.source === 'VETOED';
  const change = state.updatedAt
    ? t(state.updatedBy ? 'roles.runtime.changedBy' : 'roles.runtime.changedAt', {
        email: state.updatedBy,
        at: formatVietnamTime(state.updatedAt),
      })
    : null;
  const failure = update.error
    ? ((update.error instanceof ApiError ? update.error.problem?.detail : undefined) ??
      t('roles.runtime.saveFailed'))
    : undefined;

  const close = () => {
    setConfirming(false);
    update.reset();
    toggleRef.current?.focus();
  };

  return (
    <section className="cortex-admin-panel roles-runtime" aria-labelledby={labelId}>
      <div className="roles-runtime__head">
        <div>
          <h2 id={labelId} className="roles-runtime__label">
            {t('roles.runtime.label')}
          </h2>
          <p className="roles-runtime__meta">
            <span
              className={`cortex-admin-pill ${state.source === 'STORED' ? '' : 'cortex-admin-pill--muted'}`}
            >
              {t(`roles.runtime.source.${state.source}`)}
            </span>
            {change && <span className="roles-runtime__change">{change}</span>}
          </p>
        </div>
        <button
          ref={toggleRef}
          type="button"
          role="switch"
          aria-checked={state.mutationsEnabled}
          aria-labelledby={labelId}
          className="roles-runtime__toggle"
          disabled={vetoed}
          aria-describedby={vetoed ? vetoId : undefined}
          onClick={() => setConfirming(true)}
        >
          {t(state.mutationsEnabled ? 'roles.runtime.on' : 'roles.runtime.off')}
        </button>
      </div>
      {vetoed && (
        <p id={vetoId} className="roles-runtime__veto">
          {t('roles.runtime.vetoed')}
        </p>
      )}
      {confirming && (
        <RbacRuntimeDialog
          turnOn={!state.mutationsEnabled}
          pending={update.isPending}
          error={failure}
          onConfirm={(reason) =>
            update.mutateAsync({
              mutationsEnabled: !state.mutationsEnabled,
              ...(reason === '' ? {} : { reason }),
            })
          }
          onClose={close}
        />
      )}
    </section>
  );
}
