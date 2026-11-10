import { useTranslation } from 'react-i18next';
import { ImagePickerDialog } from './ImagePickerDialog';

interface Props {
  readonly open: boolean;
  readonly onClose: () => void;
}

export function CoverPickerDialog({ open, onClose }: Readonly<Props>) {
  const { t } = useTranslation();
  return (
    <ImagePickerDialog
      open={open}
      onClose={onClose}
      slot="cover"
      title={t('auth.profile.images.coverTitle')}
      aspect={3}
      cropShape="rect"
      maxWidth={1500}
      maxHeight={500}
    />
  );
}
