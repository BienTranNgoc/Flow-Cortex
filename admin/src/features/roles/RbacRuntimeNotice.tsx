import type { RbacRuntime } from '@flow-cortex/api-client';
import type { UseQueryResult } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';

interface RbacRuntimeNoticeProps {
  readonly runtime: UseQueryResult<RbacRuntime>;
}

/** Tells a manager why the edit controls are inert: the switch reads off, or its read failed and can be retried. */
export function RbacRuntimeNotice({ runtime }: Readonly<RbacRuntimeNoticeProps>) {
  const { t } = useTranslation();
  if (runtime.isError) {
    return (
      <div className="cortex-admin-error roles-detail__banner">
        <p role="alert">{t('roles.runtime.loadFailed')}</p>
        <button type="button" className="cortex-admin-btn" onClick={() => runtime.refetch()}>
          {t('roles.runtime.retry')}
        </button>
      </div>
    );
  }
  if (runtime.data?.mutationsEnabled === false) {
    return (
      <p role="status" className="roles-detail__note">
        {t('roles.runtime.notice')}
      </p>
    );
  }
  return null;
}
