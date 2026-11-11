import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Navigate } from 'react-router-dom';
import { useFeaturesQuery } from '../../app/useFeatures';

/** Chat routes exist only while the chat switch is on; off (or unreadable) sends the visitor home. */
export function ChatFeatureGate({ children }: Readonly<{ children: ReactNode }>) {
  const { t } = useTranslation();
  const features = useFeaturesQuery();
  if (features.isPending) return <output className="dashboard-loading">{t('common.home.loading')}</output>;
  return features.data?.chat ? children : <Navigate to="/" replace />;
}
