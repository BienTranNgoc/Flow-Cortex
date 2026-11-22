import { useTranslation } from 'react-i18next';
import type { ContentType } from '../model/driveRoles';
import { SignedMedia } from './SignedMedia';
import { ViewDialog } from './ViewDialog';
import './DriveShared.css';

interface PreviewDialogProps {
  open: boolean;
  title: string;
  kind: ContentType;
  /** The text of a text item, shown as plain text, never as markup. */
  text?: string | undefined;
  url?: string | undefined;
  urlExpiresAt?: string | undefined;
  onRenew: () => void;
  onClose: () => void;
}

/** A native modal that shows one item in full: an image, a player or its plain text. */
export function PreviewDialog({
  open,
  title,
  kind,
  text,
  url,
  urlExpiresAt,
  onRenew,
  onClose,
}: Readonly<PreviewDialogProps>) {
  const { t } = useTranslation();

  let body;
  if (kind === 'text') {
    body = text ? (
      <p className="drive-preview__text">{text}</p>
    ) : (
      <p className="drive-preview__empty">{t('drive.preview.empty')}</p>
    );
  } else {
    body = (
      <SignedMedia
        kind={kind}
        url={url}
        expiresAt={urlExpiresAt}
        label={title}
        onRenew={onRenew}
        withStatus
        className="drive-preview__media"
      />
    );
  }

  return (
    <ViewDialog open={open} title={title} onClose={onClose}>
      {body}
    </ViewDialog>
  );
}
