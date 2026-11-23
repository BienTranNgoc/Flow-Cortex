import { ImageSquare } from '@phosphor-icons/react';
import { useTranslation } from 'react-i18next';

interface ProductVideoPreviewPanelProps {
  productName: string;
  description: string;
  previewUrl: string | null;
}

/** Live 9:16 layout preview shown alongside the start form. */
export function ProductVideoPreviewPanel({
  productName,
  description,
  previewUrl,
}: Readonly<ProductVideoPreviewPanelProps>) {
  const { t } = useTranslation();

  return (
    <aside className="pv-preview-panel">
      <section className="pv-studio-card" aria-label={t('workflow.productVideo.previewHeading')}>
        <div className="pv-panel-heading">
          <h2>{t('workflow.productVideo.previewHeading')}</h2>
          <span className="pv-pill pv-pill--accent">{t('workflow.productVideo.previewBadge')}</span>
        </div>

        <div className="pv-phone-stage">
          <div className="pv-phone-frame">
            <div className="pv-phone-media">
              {previewUrl ? (
                <img src={previewUrl} alt={t('workflow.productVideo.imagePreviewAlt')} />
              ) : (
                <div className="pv-phone-empty">
                  <ImageSquare size={32} aria-hidden="true" />
                  <span>{t('workflow.productVideo.previewEmptyImage')}</span>
                </div>
              )}
            </div>
            <div className="pv-phone-caption">
              <p className="pv-phone-title">
                {productName.trim() || t('workflow.productVideo.previewPlaceholderTitle')}
              </p>
              <p className="pv-phone-desc">
                {description.trim() || t('workflow.productVideo.previewPlaceholderDesc')}
              </p>
            </div>
          </div>
        </div>
      </section>
    </aside>
  );
}
