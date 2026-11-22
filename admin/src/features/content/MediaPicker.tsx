import type { ContentMediaItem } from '@flow-cortex/api-client';
import { useTranslation } from 'react-i18next';
import { ContentDialog } from './ContentDialog';
import { MediaBrowser } from './MediaBrowser';

interface MediaPickerProps {
  readonly canUpload: boolean;
  readonly onPick: (item: ContentMediaItem) => void;
  readonly onClose: () => void;
}

/** The library in a dialog: choosing an item hands it to the section that opened the picker. */
export function MediaPicker({ canUpload, onPick, onClose }: MediaPickerProps) {
  const { t } = useTranslation();
  return (
    <ContentDialog title={t('content.picker.title')} onClose={onClose} wide>
      <MediaBrowser canUpload={canUpload} onChoose={onPick} />
      <div className="content-dialog__actions">
        <button type="button" className="cortex-admin-btn" onClick={onClose}>
          {t('content.dialog.cancel')}
        </button>
      </div>
    </ContentDialog>
  );
}
