import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Navigate } from 'react-router-dom';
import { useFeaturesQuery } from '../../app/useFeatures';

/** The Drive route exists only while the Drive switch is on; off (or unreadable) sends the visitor home. */
export function DriveFeatureGate({ children }: Readonly<{ children: ReactNode }>) {
  const { t } = useTranslation();
  const features = useFeaturesQuery();
  if (features.isPending) return <output className="dashboard-loading">{t('drive.gate.loading')}</output>;
  return features.data?.drive ? children : <Navigate to="/" replace />;
}
