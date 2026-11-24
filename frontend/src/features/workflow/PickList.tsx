import { useTranslation } from 'react-i18next';

/** The reviewer's pick: the zero-based option, their own direction, or nothing yet. */
export type PickChoice = number | 'own' | undefined;

interface PickListProps {
  /** Radio group name, unique per review form. */
  name: string;
  options: readonly Record<string, unknown>[];
  choice: PickChoice;
  onChoice: (choice: PickChoice) => void;
  own: string;
  onOwn: (text: string) => void;
  ownError: string | undefined;
}

const OWN_ERROR_ID = 'approval-payload-problem';

/** An option's title is its first non-empty text field; every other field goes under details. */
function splitOption(option: Record<string, unknown>): {
  title: string | undefined;
  rest: [string, string][];
} {
  const entries = Object.entries(option);
  const titleKey = entries.find(([, value]) => typeof value === 'string' && value.trim() !== '')?.[0];
  const rest = entries
    .filter(([key]) => key !== titleKey)
    .map(([key, value]): [string, string] => [
      key,
      typeof value === 'string' ? value : JSON.stringify(value),
    ]);
  const title = titleKey === undefined ? undefined : (option[titleKey] as string);
  return { title, rest };
}

/** The options of a checkpoint as one numbered choice, then "My own direction" with a text box. */
export function PickList({
  name,
  options,
  choice,
  onChoice,
  own,
  onOwn,
  ownError,
}: Readonly<PickListProps>) {
  const { t } = useTranslation();
  return (
    <div className="pick-list" role="radiogroup" aria-label={t('workflow.approval.pick.region')}>
      <ul>
        {options.map((option, index) => {
          const { title, rest } = splitOption(option);
          const label = t('workflow.approval.pick.option', { number: index + 1 });
          return (
            // The options never reorder, so the position is their identity.
            <li key={index} className="pick-option" data-checked={choice === index}>
              <label>
                <input
                  type="radio"
                  name={name}
                  checked={choice === index}
                  onChange={() => onChoice(index)}
                />
                <span className="pick-option-number">{label}</span>
                {title !== undefined && <span className="pick-option-title">{title}</span>}
              </label>
              {rest.length > 0 && (
                <details>
                  <summary>{t('workflow.approval.pick.details')}</summary>
                  <dl>
                    {rest.map(([key, value]) => (
                      <div key={key}>
                        <dt>{key}</dt>
                        <dd>{value}</dd>
                      </div>
                    ))}
                  </dl>
                </details>
              )}
            </li>
          );
        })}
        <li className="pick-option" data-checked={choice === 'own'}>
          <label>
            <input
              type="radio"
              name={name}
              checked={choice === 'own'}
              onChange={() => onChoice('own')}
            />
            <span className="pick-option-title">{t('workflow.approval.pick.own')}</span>
          </label>
          {choice === 'own' && (
            <div className="field">
              <textarea
                rows={4}
                value={own}
                aria-label={t('workflow.approval.pick.own')}
                onChange={(event) => onOwn(event.target.value)}
                aria-invalid={ownError ? true : undefined}
                aria-describedby={ownError ? OWN_ERROR_ID : undefined}
              />
              {ownError && (
                <div id={OWN_ERROR_ID} className="field-error" role="alert">
                  <p className="form-error">{ownError}</p>
                </div>
              )}
            </div>
          )}
        </li>
      </ul>
    </div>
  );
}
