import type { PermissionCatalogEntry } from '@flow-cortex/api-client';
import { CaretDown, CaretRight } from '@phosphor-icons/react';
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { actionLabel, featureLabel, moduleLabel } from './catalogLabels';
import {
  buildTree,
  setFeature,
  setModule,
  toggleAction,
  visibleCatalog,
  type CheckState,
} from './permissionSet';
import './roles.css';

interface PermissionMatrixProps {
  readonly catalog: readonly PermissionCatalogEntry[];
  readonly keys: ReadonlySet<string>;
  readonly roleName: string;
  /** The super admin role: every key shown, all ticked, nothing editable. */
  readonly superAdmin: boolean;
  /** Set for a caller without `system.role.manage`, and while a save is in flight. */
  readonly disabled: boolean;
  readonly onChange: (next: Set<string>) => void;
}

interface TriCheckboxProps {
  readonly state: CheckState;
  readonly label: string;
  readonly disabled: boolean;
  readonly onToggle: (on: boolean) => void;
  readonly children?: ReactNode;
  readonly className?: string;
}

/** Native checkbox whose `partial` state is exposed both as `indeterminate` and `aria-checked="mixed"`. */
function TriCheckbox({ state, label, disabled, onToggle, children, className }: TriCheckboxProps) {
  const ref = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (ref.current) ref.current.indeterminate = state === 'partial';
  }, [state]);
  return (
    <label className={className ?? 'roles-matrix__check'}>
      <input
        ref={ref}
        type="checkbox"
        checked={state === 'on'}
        aria-checked={state === 'partial' ? 'mixed' : state === 'on'}
        aria-label={label}
        disabled={disabled}
        onChange={() => onToggle(state !== 'on')}
      />
      {children}
    </label>
  );
}

/** Module › feature › action matrix; every change it emits is a draft the server accepts. */
export function PermissionMatrix({
  catalog,
  keys,
  roleName,
  superAdmin,
  disabled,
  onChange,
}: Readonly<PermissionMatrixProps>) {
  const { t } = useTranslation();
  const [collapsed, setCollapsed] = useState<ReadonlySet<string>>(new Set());
  const shown = useMemo(() => visibleCatalog(catalog, superAdmin), [catalog, superAdmin]);
  const effective = useMemo(
    () => (superAdmin ? new Set(shown.map((item) => item.key)) : keys),
    [superAdmin, shown, keys],
  );
  const tree = useMemo(() => buildTree(shown, effective), [shown, effective]);
  const locked = disabled || superAdmin;
  const selected = shown.filter((item) => effective.has(item.key)).length;

  const toggleCollapsed = (id: string) =>
    setCollapsed((current) => {
      const next = new Set(current);
      if (!next.delete(id)) next.add(id);
      return next;
    });

  if (shown.length === 0) {
    return <p className="roles-matrix__empty">{t('roles.matrix.empty')}</p>;
  }

  return (
    <div className="roles-matrix">
      <p className="roles-matrix__summary" role="status">
        {t('roles.matrix.summary', { selected, total: shown.length })}
      </p>
      <p className="roles-matrix__legend">{t('roles.matrix.legend')}</p>
      <div
        className="roles-matrix__scroll"
        role="region"
        tabIndex={0}
        aria-label={t('roles.matrix.label', { name: roleName })}
      >
        <div className="roles-matrix__modules">
          {tree.map((module) => {
            const name = moduleLabel(t, module.id);
            const open = !collapsed.has(module.id);
            return (
              <div key={module.id} className="roles-matrix__module">
                <div className="roles-matrix__module-head">
                  <button
                    type="button"
                    className="roles-matrix__collapse"
                    aria-expanded={open}
                    aria-label={t(open ? 'roles.matrix.collapse' : 'roles.matrix.expand', { name })}
                    onClick={() => toggleCollapsed(module.id)}
                  >
                    {open ? <CaretDown aria-hidden="true" /> : <CaretRight aria-hidden="true" />}
                  </button>
                  <TriCheckbox
                    className="roles-matrix__check roles-matrix__check--module"
                    state={module.state}
                    label={t('roles.matrix.moduleToggle', { name })}
                    disabled={locked}
                    onToggle={(on) => onChange(setModule(shown, effective, module.id, on))}
                  >
                    <span className="roles-matrix__module-name">{name}</span>
                  </TriCheckbox>
                  <span className="roles-matrix__count">
                    {t('roles.matrix.count', { selected: module.selected, total: module.total })}
                  </span>
                </div>
                {open && (
                  <div className="roles-matrix__features">
                    {module.features.map((feature) => {
                      const featureName = featureLabel(t, module.id, feature.id);
                      return (
                        <fieldset key={feature.id} className="roles-matrix__feature">
                          <legend className="roles-matrix__feature-legend">
                            <div className="roles-matrix__feature-head">
                              <TriCheckbox
                                state={feature.state}
                                label={t('roles.matrix.featureToggle', { name: featureName })}
                                disabled={locked}
                                onToggle={(on) =>
                                  onChange(setFeature(shown, effective, module.id, feature.id, on))
                                }
                              >
                                <span className="roles-matrix__feature-name">{featureName}</span>
                              </TriCheckbox>
                              <span className="roles-matrix__count">
                                {t('roles.matrix.count', {
                                  selected: feature.selected,
                                  total: feature.total,
                                })}
                              </span>
                            </div>
                          </legend>
                          <ul className="roles-matrix__actions">
                            {feature.actions.map((action) => {
                              const text = actionLabel(t, {
                                key: action.key,
                                module: module.id,
                                feature: feature.id,
                                action: action.action,
                              });
                              return (
                                <li key={action.key}>
                                  <TriCheckbox
                                    state={action.checked ? 'on' : 'off'}
                                    label={text}
                                    disabled={locked}
                                    onToggle={(on) =>
                                      onChange(toggleAction(shown, effective, action.key, on))
                                    }
                                  >
                                    <span>{text}</span>
                                    {action.superAdminOnly && (
                                      <span className="roles-matrix__tag">
                                        {t('roles.matrix.superAdminOnly')}
                                      </span>
                                    )}
                                  </TriCheckbox>
                                </li>
                              );
                            })}
                          </ul>
                        </fieldset>
                      );
                    })}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
