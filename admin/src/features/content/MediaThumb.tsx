import { useState } from 'react';
import { useTranslation } from 'react-i18next';

interface MediaThumbProps {
  readonly url: string;
  /** Known for a library item; unknown for an id read from a stored section, where the file type is probed. */
  readonly kind?: 'image' | 'video' | undefined;
  readonly alt: string;
  /** `thumb` is a still or first frame; `full` is the image at its width or a video with controls. */
  readonly variant?: 'thumb' | 'full';
}

type Shown = 'image' | 'video' | 'failed';

/** An image, a video, or a note when the file cannot be loaded. */
export function MediaThumb({ url, kind, alt, variant = 'thumb' }: MediaThumbProps) {
  const { t } = useTranslation();
  const [shown, setShown] = useState<Shown>(kind ?? 'image');
  const className = variant === 'full' ? 'content-preview-media' : 'content-thumb';

  if (shown === 'failed') {
    return (
      <span className={`${className} content-thumb--missing`}>
        {t('content.media.thumbMissing')}
      </span>
    );
  }
  if (shown === 'image') {
    return (
      <img
        className={className}
        src={url}
        alt={alt}
        loading="lazy"
        onError={() => setShown(kind ? 'failed' : 'video')}
      />
    );
  }
  return variant === 'full' ? (
    <video
      className={className}
      src={url}
      controls
      preload="metadata"
      aria-label={alt}
      onError={() => setShown('failed')}
    />
  ) : (
    <video
      className={className}
      src={`${url}#t=0.1`}
      preload="metadata"
      muted
      playsInline
      aria-label={alt}
      onError={() => setShown('failed')}
    />
  );
}
