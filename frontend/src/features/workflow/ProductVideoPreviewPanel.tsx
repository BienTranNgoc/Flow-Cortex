import { ImageSquare } from '@phosphor-icons/react';
import { useTranslation } from 'react-i18next';
import type { ProductVideoTemplateId, VoiceGender } from './productVideoPresets';

interface ProductVideoPreviewPanelProps {
  productName: string;
  description: string;
  previewUrl: string | null;
  templateId?: ProductVideoTemplateId;
  voiceGender?: VoiceGender;
  mcLabel?: string | null;
  mcPreviewUrl?: string | null;
  backgroundLabel?: string | null;
  backgroundPreviewUrl?: string | null;
  imageCount?: number;
}

/** Live 9:16 layout preview shown alongside the start form. */
export function ProductVideoPreviewPanel({
  productName,
  description,
  previewUrl,
  templateId = 'showroom-v1',
  voiceGender = 'male',
  mcLabel,
  mcPreviewUrl,
  backgroundLabel,
  backgroundPreviewUrl,
  imageCount = 0,
}: Readonly<ProductVideoPreviewPanelProps>) {
  const { t } = useTranslation();
  const isMc = templateId === 'mc-v1';

  return (
    <aside className="pv-preview-panel">
      <section className="pv-studio-card" aria-label={t('workflow.productVideo.previewHeading')}>
        <div className="pv-panel-heading">
          <h2>{t('workflow.productVideo.previewHeading')}</h2>
          <span className="pv-pill pv-pill--accent">{t('workflow.productVideo.previewBadge')}</span>
        </div>

        <div className="pv-phone-stage">
          <div className="pv-phone-frame">
            {backgroundPreviewUrl && (
              <>
                <img
                  src={backgroundPreviewUrl}
                  alt=""
                  aria-hidden="true"
                  className="pv-phone-bg"
                />
                <div className="pv-phone-bg-scrim" aria-hidden="true" />
              </>
            )}
            <div className="pv-phone-media">
              {isMc && mcPreviewUrl && (
                <img
                  src={mcPreviewUrl}
                  alt={mcLabel ?? t('workflow.productVideo.mcLabel')}
                  className="pv-phone-mc-badge"
                />
              )}
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

        <ul className="pv-preview-meta">
          <li>
            <strong>{t('workflow.productVideo.previewMetaTemplate')}</strong>{' '}
            {isMc
              ? t('workflow.productVideo.previewMetaTemplateMc')
              : t('workflow.productVideo.previewMetaTemplateShowroom')}
          </li>
          {imageCount > 0 && (
            <li>
              <strong>{t('workflow.productVideo.previewMetaImages')}</strong>{' '}
              {t('workflow.productVideo.previewMetaImagesValue', { count: imageCount })}
            </li>
          )}
          {isMc && mcLabel && (
            <li>
              <strong>{t('workflow.productVideo.previewMetaMc')}</strong> {mcLabel}
            </li>
          )}
          {backgroundLabel && (
            <li>
              <strong>{t('workflow.productVideo.previewMetaBg')}</strong> {backgroundLabel}
            </li>
          )}
          <li>
            <strong>{t('workflow.productVideo.previewMetaVoice')}</strong>{' '}
            {voiceGender === 'female'
              ? t('workflow.productVideo.voiceFemale')
              : t('workflow.productVideo.voiceMale')}
          </li>
        </ul>
      </section>
    </aside>
  );
}
