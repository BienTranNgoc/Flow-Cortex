import { Check, Copy } from '@phosphor-icons/react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { StoryboardOutput } from './productVideoSchemas';

interface StoryboardOutputViewProps {
  storyboard: StoryboardOutput;
}

/** Displays the two 5-second visual scene prompts planned for the ad video. */
export function StoryboardOutputView({ storyboard }: Readonly<StoryboardOutputViewProps>) {
  const { t } = useTranslation();
  const [copiedScene, setCopiedScene] = useState<'scene1' | 'scene2' | null>(null);

  const scenes = [
    {
      id: 'scene1' as const,
      titleKey: 'workflow.storyboard.scene1Title',
      prompt: storyboard.scene1Prompt,
    },
    {
      id: 'scene2' as const,
      titleKey: 'workflow.storyboard.scene2Title',
      prompt: storyboard.scene2Prompt,
    },
  ];

  const onCopy = (id: 'scene1' | 'scene2', text: string) => {
    void navigator.clipboard?.writeText(text);
    setCopiedScene(id);
    window.setTimeout(() => {
      setCopiedScene((current) => (current === id ? null : current));
    }, 1800);
  };

  return (
    <section className="pv-storyboard" aria-label={t('workflow.storyboard.heading')}>
      <h3 className="pv-storyboard-title">{t('workflow.storyboard.heading')}</h3>
      <div className="pv-storyboard-grid">
        {scenes.map((scene) => {
          const isCopied = copiedScene === scene.id;
          return (
            <article key={scene.id} className="pv-scene-card">
              <div className="pv-scene-header">
                <h4>{t(scene.titleKey)}</h4>
                <button
                  type="button"
                  className="pv-secondary-btn"
                  onClick={() => onCopy(scene.id, scene.prompt)}
                >
                  {isCopied ? (
                    <>
                      <Check size={14} aria-hidden="true" />
                      {t('workflow.storyboard.copied')}
                    </>
                  ) : (
                    <>
                      <Copy size={14} aria-hidden="true" />
                      {t('workflow.storyboard.copyPrompt')}
                    </>
                  )}
                </button>
              </div>
              <p className="pv-scene-prompt">{scene.prompt}</p>
            </article>
          );
        })}
      </div>
    </section>
  );
}
