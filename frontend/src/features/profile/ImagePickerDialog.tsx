import { useEffect, useId, useRef, useState, type ChangeEvent } from 'react';
import Cropper from 'react-easy-crop';
import { useTranslation } from 'react-i18next';
import { ApiError } from '@flow-cortex/api-client';
import { renderCrop, toBlobUrl, type CropArea } from './cropImage';
import {
  uploadProfileImage,
  useOriginalsQuery,
  useRecordOriginal,
  useSetCurrent,
  type ImageSlot,
} from './useProfileImages';
import './ImagePickerDialog.css';

export interface ImagePickerConfig {
  readonly slot: ImageSlot;
  readonly aspect: number;
  readonly cropShape: 'round' | 'rect';
  readonly maxWidth: number;
  readonly maxHeight: number;
}

interface ImagePickerDialogProps extends ImagePickerConfig {
  readonly open: boolean;
  readonly title: string;
  readonly onClose: () => void;
}

const ACCEPTED_TYPES = 'image/png,image/jpeg,image/webp';
const ZOOM_MIN = 1;
const ZOOM_MAX = 3;
const KEYBOARD_STEP = 10;

function failureLine(error: unknown, fallback: string): string {
  return (error instanceof ApiError && error.problem?.detail) || fallback;
}

interface BodyProps extends ImagePickerDialogProps {
  readonly titleId: string;
}

function PickerBody({
  slot,
  aspect,
  cropShape,
  maxWidth,
  maxHeight,
  title,
  titleId,
  onClose,
}: Readonly<BodyProps>) {
  const { t } = useTranslation();
  const originals = useOriginalsQuery(slot);
  const recordOriginal = useRecordOriginal(slot);
  const setCurrent = useSetCurrent(slot);
  const fileRef = useRef<HTMLInputElement>(null);
  const zoomId = useId();
  const [source, setSource] = useState<string | null>(null);
  const [crop, setCrop] = useState({ x: 0, y: 0 });
  const [zoom, setZoom] = useState(ZOOM_MIN);
  const [area, setArea] = useState<CropArea | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(
    () => () => {
      if (source) URL.revokeObjectURL(source);
    },
    [source],
  );

  const openCrop = (url: string) => {
    setCrop({ x: 0, y: 0 });
    setZoom(ZOOM_MIN);
    setArea(null);
    setSource(url);
  };

  const onFile = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) return;
    setBusy(true);
    setError(null);
    try {
      const uploadId = await uploadProfileImage(file, file.name);
      await recordOriginal.mutateAsync(uploadId);
      openCrop(URL.createObjectURL(file));
    } catch (failure) {
      setError(failureLine(failure, t('auth.profile.images.uploadFailed')));
    } finally {
      setBusy(false);
    }
  };

  const onPickOriginal = async (url: string) => {
    setBusy(true);
    setError(null);
    try {
      openCrop(await toBlobUrl(url));
    } catch {
      setError(t('auth.profile.images.openFailed'));
    } finally {
      setBusy(false);
    }
  };

  const onSave = async () => {
    if (!source || !area) return;
    setBusy(true);
    setError(null);
    try {
      const blob = await renderCrop(source, area, maxWidth, maxHeight);
      const uploadId = await uploadProfileImage(blob, 'crop.jpg');
      await setCurrent.mutateAsync(uploadId);
      onClose();
    } catch (failure) {
      setError(failureLine(failure, t('auth.profile.images.saveFailed')));
      setBusy(false);
    }
  };

  const items = originals.data ?? [];

  return (
    <>
      <h2 id={titleId}>{title}</h2>
      {source ? (
        <div className="image-picker__crop">
          <div className="image-picker__stage">
            <Cropper
              image={source}
              crop={crop}
              zoom={zoom}
              minZoom={ZOOM_MIN}
              maxZoom={ZOOM_MAX}
              aspect={aspect}
              cropShape={cropShape}
              keyboardStep={KEYBOARD_STEP}
              onCropChange={setCrop}
              onZoomChange={setZoom}
              onCropComplete={(_, pixels) => setArea(pixels)}
            />
          </div>
          <label htmlFor={zoomId} className="image-picker__zoom">
            <span>{t('auth.profile.images.zoom')}</span>
            <input
              id={zoomId}
              type="range"
              min={ZOOM_MIN}
              max={ZOOM_MAX}
              step={0.1}
              value={zoom}
              onChange={(event) => setZoom(Number(event.target.value))}
            />
          </label>
        </div>
      ) : (
        <div className="image-picker__pick">
          <input
            ref={fileRef}
            type="file"
            accept={ACCEPTED_TYPES}
            hidden
            onChange={(event) => void onFile(event)}
          />
          <button
            type="button"
            className="cortex-btn cortex-btn--primary"
            onClick={() => fileRef.current?.click()}
            disabled={busy}
            aria-busy={busy}
          >
            {busy ? t('auth.profile.images.uploading') : t('auth.profile.images.upload')}
          </button>
          <h3>{t('auth.profile.images.history')}</h3>
          {originals.isPending && <output>{t('auth.profile.images.loading')}</output>}
          {originals.isError && (
            <p role="alert" className="form-error">
              {t('auth.profile.images.historyFailed')}{' '}
              <button type="button" onClick={() => void originals.refetch()}>
                {t('auth.profile.retry')}
              </button>
            </p>
          )}
          {originals.isSuccess && items.length === 0 && (
            <p className="image-picker__empty">{t('auth.profile.images.empty')}</p>
          )}
          {items.length > 0 && (
            <ul className="image-picker__grid">
              {items.map((item, index) => (
                <li key={item.id ?? index}>
                  <button
                    type="button"
                    disabled={busy || !item.url}
                    onClick={() => item.url && void onPickOriginal(item.url)}
                  >
                    <img
                      src={item.url}
                      alt={t('auth.profile.images.originalAlt', { number: index + 1 })}
                      loading="lazy"
                    />
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
      {error && (
        <p role="alert" className="form-error">
          {error}
        </p>
      )}
      <div className="image-picker__actions">
        <button type="button" className="cortex-btn cortex-btn--ghost" onClick={onClose} disabled={busy}>
          {t('auth.profile.cancel')}
        </button>
        {source && (
          <button
            type="button"
            className="cortex-btn cortex-btn--primary"
            onClick={() => void onSave()}
            disabled={busy || !area}
            aria-busy={busy}
          >
            {busy ? t('auth.profile.saving') : t('auth.profile.save')}
          </button>
        )}
      </div>
    </>
  );
}

/**
 * The picker as a native modal dialog: history of earlier originals, upload, then a crop that is
 * saved as the slot's current image. Escape cancels.
 */
export function ImagePickerDialog(props: Readonly<ImagePickerDialogProps>) {
  const { open, onClose } = props;
  const titleId = useId();
  const dialogRef = useRef<HTMLDialogElement>(null);
  const openerRef = useRef<HTMLElement | null>(null);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (open) {
      if (!dialog.open) {
        openerRef.current =
          document.activeElement instanceof HTMLElement ? document.activeElement : null;
        dialog.showModal();
      }
      return;
    }
    if (dialog.open) dialog.close();
    const opener = openerRef.current;
    openerRef.current = null;
    if (opener?.isConnected) opener.focus();
  }, [open]);

  return (
    <dialog
      ref={dialogRef}
      className="image-picker"
      aria-labelledby={titleId}
      onCancel={(event) => {
        event.preventDefault();
        onClose();
      }}
      onClose={() => {
        if (open) onClose();
      }}
    >
      {open && <PickerBody {...props} titleId={titleId} />}
    </dialog>
  );
}
