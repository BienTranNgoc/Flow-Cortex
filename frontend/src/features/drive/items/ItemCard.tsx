import type { DriveItemResponse } from '@flow-cortex/api-client';
import { Eye, PencilSimple, Trash } from '@phosphor-icons/react';
import { useTranslation } from 'react-i18next';
import { activeLocale } from '../../../lib/i18n';
import { formatBytes } from '../model/formatBytes';
import { SignedMedia } from '../shared/SignedMedia';
import { formatWhen } from './formFields';
import '../shared/DriveShared.css';
import './DriveItems.css';

interface ItemCardProps {
  item: DriveItemResponse;
  onOpen: (item: DriveItemResponse) => void;
  onEdit: (item: DriveItemResponse) => void;
  onDelete: (item: DriveItemResponse) => void;
  /** Asks for a fresh list when a signed link failed because it expired. */
  onRenew: () => void;
}

/** One saved item: what it is, where it came from, a preview and its Open, Edit and Delete actions. */
export function ItemCard({ item, onOpen, onEdit, onDelete, onRenew }: Readonly<ItemCardProps>) {
  const { t } = useTranslation();
  const locale = activeLocale();
  return (
    <li className="drive-card">
      <h3 className="drive-card__title">{item.name}</h3>
      <p className="drive-card__meta">
        {t(`drive.roles.${item.semanticRole}`, { defaultValue: item.semanticRole })}
        {' · '}
        {t(`drive.sources.${item.source}`)}
      </p>
      {item.contentType === 'text' ? (
        <p className="drive-card__text">{item.textValue ?? ''}</p>
      ) : (
        <SignedMedia
          kind={item.contentType}
          url={item.url}
          expiresAt={item.urlExpiresAt}
          label={item.name}
          onRenew={onRenew}
          className="drive-card__media"
        />
      )}
      <p className="drive-card__meta">
        {formatWhen(item.updatedAt, locale)}
        {item.sizeBytes !== undefined && ` · ${formatBytes(item.sizeBytes, locale)}`}
      </p>
      <div className="drive-card__actions">
        <button
          type="button"
          className="drive-button"
          aria-label={t('driveItems.card.openLabel', { name: item.name })}
          onClick={() => onOpen(item)}
        >
          <Eye aria-hidden="true" />
          {t('driveItems.card.open')}
        </button>
        <button
          type="button"
          className="drive-button"
          aria-label={t('driveItems.card.editLabel', { name: item.name })}
          onClick={() => onEdit(item)}
        >
          <PencilSimple aria-hidden="true" />
          {t('driveItems.card.edit')}
        </button>
        <button
          type="button"
          className="drive-button drive-button--danger"
          aria-label={t('driveItems.card.deleteLabel', { name: item.name })}
          onClick={() => onDelete(item)}
        >
          <Trash aria-hidden="true" />
          {t('driveItems.card.delete')}
        </button>
      </div>
    </li>
  );
}
