import type { BillingProfileRequest, BillingProfileResponse } from '@flow-cortex/api-client';
import { useId, useRef, useState, type FormEvent, type RefObject } from 'react';
import { useTranslation } from 'react-i18next';
import { fieldErrorsOf, isGone, useSaveBillingProfile, type ProfileField } from './useBillingProfiles';
import './billing.css';

export const PROFILE_TYPES = ['personal', 'business', 'household'] as const;
export type ProfileType = (typeof PROFILE_TYPES)[number];

export interface ProfileValues {
  readonly type: ProfileType;
  readonly legalName: string;
  readonly taxCode: string;
  readonly personalIdNumber: string;
  readonly address: string;
  readonly email: string;
  readonly isDefault: boolean;
}

const TAX_CODE = /^\d{10}(-\d{3})?$/;
const PERSONAL_ID = /^\d{12}$/;
const EMAIL = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;
const MAX_NAME = 400;
const MAX_ADDRESS = 500;
const MAX_EMAIL = 255;
const FIELD_ORDER: readonly ProfileField[] = [
  'type',
  'legalName',
  'taxCode',
  'personalIdNumber',
  'address',
  'email',
];

const isPictograph = (point: number) =>
  (point >= 0x1f300 && point <= 0x1faff) ||
  (point >= 0x2600 && point <= 0x27bf) ||
  (point >= 0xfe00 && point <= 0xfe0f) ||
  (point >= 0x1f000 && point <= 0x1f02f) ||
  point === 0x200d ||
  point === 0x20e3;

/** A name keeps a character an invoice can print once emoji and whitespace are gone. */
const hasReadableCharacter = (name: string) =>
  [...name].some((char) => !isPictograph(char.codePointAt(0) ?? 0) && char.trim() !== '');

const length = (value: string) => [...value].length;

/**
 * The checks the server applies (it stays the authority), run early so a member sees a refusal before
 * sending. Each message is the key under `billing.profiles.error`.
 */
export function checkProfile(values: ProfileValues): Partial<Record<ProfileField, string>> {
  const errors: Partial<Record<ProfileField, string>> = {};
  const name = values.legalName.trim();
  if (name === '') errors.legalName = 'legalName.required';
  else if (length(name) > MAX_NAME) errors.legalName = 'legalName.tooLong';
  else if (!hasReadableCharacter(name)) errors.legalName = 'legalName.unreadable';

  if (values.type === 'personal') {
    const id = values.personalIdNumber.trim();
    if (id === '') errors.personalIdNumber = 'personalIdNumber.required';
    else if (!PERSONAL_ID.test(id)) errors.personalIdNumber = 'personalIdNumber.invalid';
  } else {
    const code = values.taxCode.trim();
    if (code === '') errors.taxCode = 'taxCode.required';
    else if (!TAX_CODE.test(code)) errors.taxCode = 'taxCode.invalid';
  }

  const address = values.address.trim();
  if (address === '') errors.address = 'address.required';
  else if (length(address) > MAX_ADDRESS) errors.address = 'address.tooLong';

  const email = values.email.trim();
  if (email !== '') {
    if (email.length > MAX_EMAIL) errors.email = 'email.tooLong';
    else if (!EMAIL.test(email)) errors.email = 'email.invalid';
  }
  return errors;
}

function valuesOf(profile: BillingProfileResponse | undefined): ProfileValues {
  return {
    type: (profile?.type as ProfileType | undefined) ?? 'business',
    legalName: profile?.legalName ?? '',
    taxCode: profile?.taxCode ?? '',
    personalIdNumber: profile?.personalIdNumber ?? '',
    address: profile?.address ?? '',
    email: profile?.email ?? '',
    isDefault: false,
  };
}

function bodyOf(values: ProfileValues, editing: BillingProfileResponse | undefined): BillingProfileRequest {
  const email = values.email.trim();
  return {
    type: values.type,
    legalName: values.legalName.trim(),
    address: values.address.trim(),
    ...(values.type === 'personal'
      ? { personalIdNumber: values.personalIdNumber.trim() }
      : { taxCode: values.taxCode.trim() }),
    // An edit sends null to clear the email; a new profile just leaves it out.
    ...(email === '' ? (editing ? { email: null } : {}) : { email }),
    ...(values.isDefault ? { isDefault: true } : {}),
  };
}

interface BillingProfileFormProps {
  /** The profile to edit; absent creates one. */
  readonly profile?: BillingProfileResponse;
  readonly onSaved: (profile: BillingProfileResponse) => void;
  readonly onCancel: () => void;
  /** Gets the focus first, so a dialog around the form can start on a field. */
  readonly firstFieldRef?: RefObject<HTMLSelectElement>;
}

/** Create or edit one profile. It holds no routing: a dialog or a checkout step can mount it. */
export function BillingProfileForm({
  profile,
  onSaved,
  onCancel,
  firstFieldRef,
}: Readonly<BillingProfileFormProps>) {
  const { t } = useTranslation();
  const baseId = useId();
  const [values, setValues] = useState(() => valuesOf(profile));
  const [clientErrors, setClientErrors] = useState<Partial<Record<ProfileField, string>>>({});
  const [serverErrors, setServerErrors] = useState<Partial<Record<ProfileField, string>>>({});
  const [failure, setFailure] = useState<string | null>(null);
  const save = useSaveBillingProfile();
  const formRef = useRef<HTMLFormElement>(null);

  const idOf = (field: string) => `${baseId}-${field}`;
  const errorOf = (field: ProfileField): string | undefined => {
    const clientKey = clientErrors[field];
    return clientKey ? t(`billing.profiles.error.${clientKey}`) : serverErrors[field];
  };

  const change = <K extends keyof ProfileValues>(key: K, value: ProfileValues[K]) => {
    setValues((current) => ({ ...current, [key]: value }));
    setClientErrors((current) => ({ ...current, [key]: undefined }));
    setServerErrors((current) => ({ ...current, [key]: undefined }));
    setFailure(null);
  };

  const focusFirstInvalid = (errors: Partial<Record<ProfileField, string>>) => {
    const first = FIELD_ORDER.find((field) => errors[field]);
    if (first) formRef.current?.querySelector<HTMLElement>(`[name="${first}"]`)?.focus();
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (save.isPending) return;
    const errors = checkProfile(values);
    setClientErrors(errors);
    setServerErrors({});
    setFailure(null);
    if (Object.keys(errors).length > 0) {
      focusFirstInvalid(errors);
      return;
    }
    save.mutate(
      { ...(profile ? { id: profile.id } : {}), body: bodyOf(values, profile) },
      {
        onSuccess: onSaved,
        onError: (error) => {
          const fields = fieldErrorsOf(error);
          if (fields) {
            setServerErrors(fields);
            requestAnimationFrame(() => focusFirstInvalid(fields));
          } else {
            setFailure(t(isGone(error) ? 'billing.profiles.error.notFound' : 'billing.profiles.error.save'));
          }
        },
      },
    );
  };

  const field = (
    name: ProfileField,
    label: string,
    control: (props: Record<string, unknown>) => React.ReactNode,
    hint?: string,
  ) => {
    const error = errorOf(name);
    const describedBy = [hint ? `${idOf(name)}-hint` : null, error ? `${idOf(name)}-error` : null]
      .filter(Boolean)
      .join(' ');
    return (
      <div className="billing-field" data-invalid={error ? 'true' : undefined}>
        <label htmlFor={idOf(name)}>{label}</label>
        {control({
          id: idOf(name),
          name,
          'aria-invalid': error ? true : undefined,
          'aria-describedby': describedBy || undefined,
        })}
        {hint && (
          <p id={`${idOf(name)}-hint`} className="billing-field__hint">
            {hint}
          </p>
        )}
        {error && (
          <p id={`${idOf(name)}-error`} role="alert" className="billing-field__error">
            {error}
          </p>
        )}
      </div>
    );
  };

  return (
    <form ref={formRef} className="billing-form" onSubmit={submit} noValidate>
      {field('type', t('billing.profiles.field.type'), (props) => (
        <select
          {...props}
          ref={firstFieldRef}
          value={values.type}
          onChange={(event) => change('type', event.target.value as ProfileType)}
        >
          {PROFILE_TYPES.map((type) => (
            <option key={type} value={type}>
              {t(`billing.profiles.type.${type}`)}
            </option>
          ))}
        </select>
      ))}
      {field(
        'legalName',
        t('billing.profiles.field.legalName'),
        (props) => (
          <input
            {...props}
            type="text"
            autoComplete="organization"
            value={values.legalName}
            onChange={(event) => change('legalName', event.target.value)}
          />
        ),
        t('billing.profiles.hint.legalName'),
      )}
      {values.type === 'personal'
        ? field(
            'personalIdNumber',
            t('billing.profiles.field.personalIdNumber'),
            (props) => (
              <input
                {...props}
                type="text"
                inputMode="numeric"
                autoComplete="off"
                value={values.personalIdNumber}
                onChange={(event) => change('personalIdNumber', event.target.value)}
              />
            ),
            t('billing.profiles.hint.personalIdNumber'),
          )
        : field(
            'taxCode',
            t('billing.profiles.field.taxCode'),
            (props) => (
              <input
                {...props}
                type="text"
                inputMode="numeric"
                autoComplete="off"
                value={values.taxCode}
                onChange={(event) => change('taxCode', event.target.value)}
              />
            ),
            t('billing.profiles.hint.taxCode'),
          )}
      {field('address', t('billing.profiles.field.address'), (props) => (
        <textarea
          {...props}
          rows={3}
          autoComplete="street-address"
          value={values.address}
          onChange={(event) => change('address', event.target.value)}
        />
      ))}
      {field(
        'email',
        t('billing.profiles.field.email'),
        (props) => (
          <input
            {...props}
            type="email"
            autoComplete="email"
            value={values.email}
            onChange={(event) => change('email', event.target.value)}
          />
        ),
        t('billing.profiles.hint.email'),
      )}
      {!profile?.isDefault && (
        <label className="billing-check">
          <input
            type="checkbox"
            checked={values.isDefault}
            onChange={(event) => change('isDefault', event.target.checked)}
          />
          <span>{t('billing.profiles.field.isDefault')}</span>
        </label>
      )}
      {failure && (
        <p role="alert" className="billing-form__failure">
          {failure}
        </p>
      )}
      <div className="billing-form__actions">
        <button type="button" className="drive-button" onClick={onCancel}>
          {t('billing.profiles.action.cancel')}
        </button>
        <button
          type="submit"
          className="drive-button drive-button--primary"
          aria-disabled={save.isPending || undefined}
          aria-busy={save.isPending || undefined}
        >
          {t(save.isPending ? 'billing.profiles.action.saving' : 'billing.profiles.action.save')}
        </button>
      </div>
    </form>
  );
}
