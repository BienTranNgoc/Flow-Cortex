import { Check, UploadSimple, X } from '@phosphor-icons/react';
import { useEffect, useRef, useState, type ChangeEvent } from 'react';
import { useTranslation } from 'react-i18next';

export type AssetMode = 'preset' | 'upload' | 'note';

export interface PresetThumb {
  id: string;
  label: string;
  imageUrl: string;
  badge?: string;
}

interface AssetPickerFieldProps {
  id: string;
  label: string;
  hint?: string;
  presets: PresetThumb[];
  mode: AssetMode;
  onModeChange: (mode: AssetMode) => void;
  selectedPresetId: string | null;
  onSelectPreset: (id: string) => void;
  uploadedFile: File | null;
  onUploadFile: (file: File | null) => void;
  allowNote?: boolean;
  noteValue?: string;
  onNoteChange?: (value: string) => void;
  notePlaceholder?: string;
  disabled?: boolean;
}

/** Reusable preset/upload/note selector for MC models and 9:16 backgrounds. */
export function AssetPickerField({
  id,
  label,
  hint,
  presets,
  mode,
  onModeChange,
  selectedPresetId,
  onSelectPreset,
  uploadedFile,
  onUploadFile,
  allowNote = false,
  noteValue = '',
  onNoteChange,
  notePlaceholder,
  disabled = false,
}: Readonly<AssetPickerFieldProps>) {
  const { t } = useTranslation();
  const inputRef = useRef<HTMLInputElement>(null);
  const [uploadPreview, setUploadPreview] = useState<string | null>(null);

  useEffect(() => {
    if (!uploadedFile || typeof URL.createObjectURL !== 'function') {
      setUploadPreview(null);
      return;
    }
    const url = URL.createObjectURL(uploadedFile);
    setUploadPreview(url);
    return () => {
      if (typeof URL.revokeObjectURL === 'function') URL.revokeObjectURL(url);
    };
  }, [uploadedFile]);

  const handlePick = (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0] ?? null;
    event.target.value = '';
    if (file) onUploadFile(file);
  };

  return (
    <div className="pv-field">
      <div className="pv-field-header">
        <label htmlFor={`${id}-tabs`}>{label}</label>
        <div id={`${id}-tabs`} className="pv-asset-tabs" role="tablist" aria-label={label}>
          <button
            type="button"
            role="tab"
            aria-selected={mode === 'preset'}
            className={mode === 'preset' ? 'pv-tab pv-tab--active' : 'pv-tab'}
            disabled={disabled}
            onClick={() => onModeChange('preset')}
          >
            {t('workflow.productVideo.tabPreset')}
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={mode === 'upload'}
            className={mode === 'upload' ? 'pv-tab pv-tab--active' : 'pv-tab'}
            disabled={disabled}
            onClick={() => onModeChange('upload')}
          >
            {t('workflow.productVideo.tabUpload')}
          </button>
          {allowNote && (
            <button
              type="button"
              role="tab"
              aria-selected={mode === 'note'}
              className={mode === 'note' ? 'pv-tab pv-tab--active' : 'pv-tab'}
              disabled={disabled}
              onClick={() => onModeChange('note')}
            >
              {t('workflow.productVideo.tabNote')}
            </button>
          )}
        </div>
      </div>

      {hint && <p className="field-hint">{hint}</p>}

      {mode === 'preset' && (
        <div className="pv-preset-grid">
          {presets.map((preset) => {
            const active = selectedPresetId === preset.id;
            return (
              <button
                key={preset.id}
                type="button"
                disabled={disabled}
                aria-pressed={active}
                className={active ? 'pv-preset-card pv-preset-card--active' : 'pv-preset-card'}
                onClick={() => onSelectPreset(preset.id)}
              >
                <div className="pv-preset-thumb-wrap">
                  <img src={preset.imageUrl} alt={preset.label} className="pv-preset-thumb" />
                  {active && (
                    <span className="pv-preset-check" aria-hidden="true">
                      <Check size={12} weight="bold" />
                    </span>
                  )}
                </div>
                <span className="pv-preset-label">{preset.label}</span>
                {preset.badge && <span className="pv-preset-badge">{preset.badge}</span>}
              </button>
            );
          })}
        </div>
      )}

      {mode === 'upload' && (
        <div className="pv-dropzone">
          <input
            ref={inputRef}
            id={`${id}-upload`}
            className="pv-dropzone-input"
            type="file"
            accept="image/jpeg,image/png,image/webp"
            tabIndex={-1}
            onChange={handlePick}
          />
          {uploadedFile ? (
            <div className="pv-image-item">
              <div className="pv-image-thumb-wrap">
                {uploadPreview && (
                  <img src={uploadPreview} alt={uploadedFile.name} className="pv-dropzone-thumb" />
                )}
              </div>
              <div className="pv-dropzone-meta">
                <p className="pv-dropzone-filename">{uploadedFile.name}</p>
              </div>
              <button
                type="button"
                className="pv-image-remove"
                disabled={disabled}
                onClick={() => onUploadFile(null)}
                aria-label={t('workflow.productVideo.removeImageAt', { name: uploadedFile.name })}
              >
                <X size={16} aria-hidden="true" />
              </button>
            </div>
          ) : (
            <button
              type="button"
              className="pv-dropzone-empty"
              disabled={disabled}
              onClick={() => inputRef.current?.click()}
            >
              <UploadSimple size={20} aria-hidden="true" />
              <span className="pv-dropzone-title">
                {t('workflow.productVideo.pickImageFromDevice')}
              </span>
            </button>
          )}
        </div>
      )}

      {mode === 'note' && allowNote && onNoteChange && (
        <input
          id={`${id}-note`}
          type="text"
          className="pv-input"
          maxLength={500}
          value={noteValue}
          disabled={disabled}
          placeholder={notePlaceholder ?? t('workflow.productVideo.sceneNotePlaceholder')}
          onChange={(event) => onNoteChange(event.target.value)}
        />
      )}
    </div>
  );
}
