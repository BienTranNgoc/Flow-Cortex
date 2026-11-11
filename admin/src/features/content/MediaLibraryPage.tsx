import { useTranslation } from 'react-i18next';
import { can } from '../../app/permissions';
import { PageHeading } from '../../app/PageHeading';
import { usePageTitle } from '../../app/usePageTitle';
import { useMeQuery } from '../auth/useAuth';
import './content.css';
import { MediaBrowser } from './MediaBrowser';

export function MediaLibraryPage() {
  const { t } = useTranslation();
  usePageTitle(t('content.media.title'));
  const me = useMeQuery().data;

  return (
    <main>
      <PageHeading
        eyebrow={t('content.eyebrow')}
        title={t('content.media.title')}
        description={t('content.media.description')}
      />
      <MediaBrowser canUpload={can(me, 'content.media.manage')} />
    </main>
  );
}
