import { useTranslation } from 'react-i18next';
import { ROLE_CODES } from './enumLabels';

/** `<option>`s for the roles in `roles`, plus `current` so a select still shows a role its holder may not assign. */
export function RoleOptions({
  roles,
  current,
}: Readonly<{ roles: readonly string[]; current?: string | undefined }>) {
  const { t } = useTranslation();
  return ROLE_CODES.filter((code) => roles.includes(code) || code === current).map((code) => (
    <option key={code} value={code}>
      {t(`users.roleOptions.${code}`)}
    </option>
  ));
}
