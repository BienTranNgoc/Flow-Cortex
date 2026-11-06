import { useTranslation } from 'react-i18next';
import { FlowCortexLogo } from '@flow-cortex/ui-kit';

const REPOSITORY_URL = 'https://github.com/BienTranNgoc/Flow-Cortex';

export function Footer() {
  const { t } = useTranslation();
  return (
    <footer className="cortex-footer">
      <div className="cortex-footer__inner">
        <div className="cortex-footer__brand">
          <span className="cortex-footer__brand-row">
            <FlowCortexLogo size={28} />
            <span className="cortex-footer__brand-name">FlowCortex</span>
          </span>
          <p className="cortex-footer__tagline">{t('common.shell.footer.tagline')}</p>
          <div className="cortex-footer__social">
            <a
              href={REPOSITORY_URL}
              aria-label={t('common.shell.footer.social', { network: 'GitHub' })}
              className="cortex-footer__social-link"
            >
              <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="18" height="18" fill="currentColor" aria-hidden="true">
                <path d="M12 0c-6.626 0-12 5.373-12 12 0 5.302 3.438 9.8 8.207 11.387.599.111.793-.261.793-.577v-2.234c-3.338.726-4.033-1.416-4.033-1.416-.546-1.387-1.333-1.756-1.333-1.756-1.089-.745.083-.729.083-.729 1.205.084 1.839 1.237 1.839 1.237 1.07 1.834 2.807 1.304 3.492.997.107-.775.418-1.305.762-1.604-2.665-.305-5.467-1.334-5.467-5.931 0-1.311.469-2.381 1.236-3.221-.124-.303-.535-1.524.117-3.176 0 0 1.008-.322 3.301 1.23.957-.266 1.983-.399 3.003-.404 1.02.005 2.047.138 3.006.404 2.291-1.552 3.297-1.23 3.297-1.23.653 1.653.242 2.874.118 3.176.77.84 1.235 1.911 1.235 3.221 0 4.609-2.807 5.624-5.479 5.921.43.372.823 1.102.823 2.222v3.293c0 .319.192.694.801.576 4.765-1.589 8.199-6.086 8.199-11.386 0-6.627-5.373-12-12-12z" />
              </svg>
            </a>
          </div>
        </div>
      </div>

      <div className="cortex-footer__bottom">
        <p>{t('common.shell.footer.copyright', { year: new Date().getFullYear() })}</p>
      </div>
    </footer>
  );
}
