import { ArrowCounterClockwise } from '@phosphor-icons/react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { linkNeedsRenewal } from '../../lib/signedLinks';
import type { ProductBrief } from './productVideoSchemas';
import { MAX_IMAGES } from './productVideoUpload';
import { useRunInputsQuery } from './useWorkflows';

interface ProductBriefBannerProps {
  runId: string;
  brief: ProductBrief;
  workflowId?: string | undefined;
  showRetryAction?: boolean | undefined;
}

const NON_PRODUCT_IMAGE_KEYS = new Set(['mc', 'background']);

function isProductImageKey(bindingKey: string | undefined): boolean {
  if (!bindingKey) return false;
  const key = bindingKey.trim().toLowerCase();
  if (NON_PRODUCT_IMAGE_KEYS.has(key)) return false;
  return key === 'image' || key.startsWith('image_') || key.startsWith('product_');
}

/** Compact context strip showing product thumbnail, name, description, and target video format pills. */
export function ProductBriefBanner({
  runId,
  brief,
  workflowId,
  showRetryAction,
}: Readonly<ProductBriefBannerProps>) {
  const { t } = useTranslation();
  const inputsQuery = useRunInputsQuery(runId);
  const [brokenUrl, setBrokenUrl] = useState<string | null>(null);
  const imageInputs = (inputsQuery.data ?? []).filter(
    (item) => item.kind === 'image' && isProductImageKey(item.bindingKey),
  );
  const firstImage = imageInputs[0];
  const imageUrl = firstImage?.url && firstImage.url !== brokenUrl ? firstImage.url : undefined;
  const draftImages = imageInputs
    .filter((item) => Boolean(item.url))
    .slice(0, MAX_IMAGES)
    .map((item) => ({
      name: item.bindingKey,
      mimeType: item.mimeType,
      url: item.url,
    }));

  const onImageError = () => {
    if (!firstImage?.url) return;
    if (linkNeedsRenewal(firstImage.expiresAt)) {
      void inputsQuery.refetch();
      return;
    }
    setBrokenUrl(firstImage.url);
  };

  return (
    <section className="pv-brief-banner" aria-label={t('workflow.productVideo.briefSummary')}>
      {imageUrl && (
        <img
          src={imageUrl}
          alt={t('workflow.inputs.imageAlt', { number: 1 })}
          className="pv-brief-thumb"
          onError={onImageError}
        />
      )}
      <div className="pv-brief-body">
        <h2 className="pv-brief-title">{brief.productName}</h2>
        <p className="pv-brief-desc">{brief.description}</p>
      </div>
      <div className="pv-brief-right">
        <div className="pv-brief-specs">
          <span className="pv-spec-chip">{t('workflow.productVideo.specAspectShort')}</span>
          <span className="pv-spec-chip">{t('workflow.productVideo.specDurationShort')}</span>
          <span className="pv-spec-chip">{t('workflow.productVideo.specFormatShort')}</span>
        </div>
        {showRetryAction && workflowId && (
          <Link
            className="pv-secondary-btn"
            to={`/workflows/${workflowId}`}
            state={{
              productDraft: {
                productName: brief.productName,
                description: brief.description,
                ...(draftImages.length > 0 ? { images: draftImages } : {}),
              },
            }}
          >
            <ArrowCounterClockwise size={16} aria-hidden="true" />
            {t('workflow.productVideo.retryWithBrief')}
          </Link>
        )}
      </div>
    </section>
  );
}
