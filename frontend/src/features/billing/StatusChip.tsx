import { useTranslation } from 'react-i18next';
import './billing.css';

interface StatusChipProps {
  readonly status: string;
}

/** The payment status as words on a tone, so colour never carries the meaning alone. */
export function StatusChip({ status }: Readonly<StatusChipProps>) {
  const { t } = useTranslation();
  return (
    <span className={`billing-chip billing-chip--${status}`}>{t(`billing.status.${status}`)}</span>
  );
}
