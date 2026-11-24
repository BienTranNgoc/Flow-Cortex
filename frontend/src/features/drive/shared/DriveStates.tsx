import { useTranslation } from 'react-i18next';
import { StatusBlock } from '../../../app/StatusBlock';
import './DriveShared.css';

/** A polite status line while a read is in flight. */
export function DriveLoading({ label }: Readonly<{ label?: string }>) {
  const { t } = useTranslation();
  return <output className="drive-loading">{label ?? t('drive.status.loading')}</output>;
}

interface DriveFailedProps {
  /** The server's own sentence when it sent one; the generic line otherwise. */
  message?: string;
  onRetry: () => void;
}

/** A failed read with the one way forward: try it again. */
export function DriveFailed({ message, onRetry }: Readonly<DriveFailedProps>) {
  const { t } = useTranslation();
  return (
    <StatusBlock
      kind="error"
      message={message ?? t('drive.status.failed')}
      action={{ label: t('drive.status.retry'), onClick: onRetry }}
    />
  );
}
