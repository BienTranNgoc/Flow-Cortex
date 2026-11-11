import { useTranslation } from 'react-i18next';
import { ROLE_CODES, roleLabel } from './enumLabels';

interface RoleOptionsProps {
  readonly roles: readonly string[];
  /** The role the account holds now, kept in the list so a select still shows a role its holder may not assign. */
  readonly current?: string | undefined;
  /** Names of the custom roles by code; a custom role without one reads as its code. */
  readonly names?: Readonly<Record<string, string>> | undefined;
}

/** `<option>`s for the roles in `roles` plus `current`: the system roles first, then the custom roles in the order given. */
export function RoleOptions({ roles, current, names = {} }: Readonly<RoleOptionsProps>) {
  const { t } = useTranslation();
  const wanted = new Set(current ? [...roles, current] : roles);
  const system: readonly string[] = ROLE_CODES;
  const codes = [
    ...ROLE_CODES.filter((code) => wanted.has(code)),
    ...[...wanted].filter((code) => !system.includes(code)),
  ];
  return codes.map((code) => (
    <option key={code} value={code}>
      {system.includes(code) ? t(`users.roleOptions.${code}`) : roleLabel(t, code, names[code])}
    </option>
  ));
}
