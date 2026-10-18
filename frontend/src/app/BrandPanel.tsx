import { Trans, useTranslation } from 'react-i18next';

const BULLET_KEYS = [
  'common.shell.brandPanel.pipelines',
  'common.shell.brandPanel.reviewGates',
  'common.shell.brandPanel.retention',
] as const;

export function BrandPanel() {
  const { t } = useTranslation();
  return (
    <aside className="brand-panel" aria-hidden="false">
      <div className="brand-panel__inner">
        <p className="eyebrow">FLOWCORTEX AI 2.5</p>
        <h2 className="brand-panel__headline">
          <Trans i18nKey="common.shell.brandPanel.headline" components={{ highlight: <span /> }} />
        </h2>
        <p className="brand-panel__lede">{t('common.shell.brandPanel.lede')}</p>
        <ul className="brand-panel__bullets">
          {BULLET_KEYS.map((key) => (
            <li key={key}>
              <span className="brand-panel__bullet-dot" aria-hidden="true" />
              <span>{t(key)}</span>
            </li>
          ))}
        </ul>
      </div>
    </aside>
  );
}
