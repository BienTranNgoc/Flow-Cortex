import { useTranslation } from 'react-i18next';
import { ImagePickerDialog } from './ImagePickerDialog';

interface Props {
  readonly open: boolean;
  readonly onClose: () => void;
}

export function AvatarPickerDialog({ open, onClose }: Readonly<Props>) {
  const { t } = useTranslation();
  return (
    <ImagePickerDialog
      open={open}
      onClose={onClose}
      slot="avatar"
      title={t('auth.profile.images.avatarTitle')}
      aspect={1}
      cropShape="round"
      maxWidth={512}
      maxHeight={512}
    />
  );
}
