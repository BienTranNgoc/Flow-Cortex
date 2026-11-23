import { Check, Copy } from '@phosphor-icons/react';
import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { StoryboardOutput } from './productVideoSchemas';

interface StoryboardOutputViewProps {
  storyboard: StoryboardOutput;
}

/** Displays the two 5-second visual scene prompts planned for the ad video. */
export function StoryboardOutputView({ storyboard }: Readonly<StoryboardOutputViewProps>) {
  const { t } = useTranslation();
  const [copy, setCopy] = useState<{
    scene: 'scene1' | 'scene2';
    outcome: 'copied' | 'failed';
  } | null>(null);
  const resetTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(() => () => clearTimeout(resetTimer.current), []);

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

  const onCopy = async (id: 'scene1' | 'scene2', text: string) => {
    clearTimeout(resetTimer.current);
    let outcome: 'copied' | 'failed' = 'copied';
    try {
      await navigator.clipboard.writeText(text);
    } catch {
      // No clipboard permission or an insecure origin: the prompt stays selectable on the card.
      outcome = 'failed';
    }
    setCopy({ scene: id, outcome });
    resetTimer.current = setTimeout(() => setCopy(null), 1800);
  };

  return (
    <section className="pv-storyboard" aria-label={t('workflow.storyboard.heading')}>
      <h3 className="pv-storyboard-title">{t('workflow.storyboard.heading')}</h3>
      <div className="pv-storyboard-grid">
        {scenes.map((scene) => {
          const isCopied = copy?.scene === scene.id && copy.outcome === 'copied';
          const copyFailed = copy?.scene === scene.id && copy.outcome === 'failed';
          return (
            <article key={scene.id} className="pv-scene-card">
              <div className="pv-scene-header">
                <h4>{t(scene.titleKey)}</h4>
                <button
                  type="button"
                  className="pv-secondary-btn"
                  onClick={() => void onCopy(scene.id, scene.prompt)}
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
              {copyFailed && (
                <div className="field-error" role="alert">
                  <p>{t('workflow.storyboard.copyFailed')}</p>
                </div>
              )}
              <p className="pv-scene-prompt">{scene.prompt}</p>
            </article>
          );
        })}
      </div>
    </section>
  );
}
