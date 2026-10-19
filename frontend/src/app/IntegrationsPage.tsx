import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  OpenAILogo,
  AnthropicLogo,
  GoogleLogo,
  MistralLogo,
  RunwayLogo,
  LumaLogo,
  KlingLogo,
  ElevenLabsLogo,
  WhisperLogo,
} from './providerLogos';
import './IntegrationsPage.css';

type ProviderCategory = 'text' | 'media' | 'audio' | 'social';

interface ModelApi {
  readonly id: string;
  readonly provider: string;
  readonly label: string;
  readonly connected: boolean;
  readonly lastUsedKey?: string;
  readonly maskedKey?: string;
  readonly category: ProviderCategory;
  readonly Logo: (props: { size?: number; title?: string }) => JSX.Element;
}

interface SocialAccount {
  readonly id: string;
  readonly platform: 'douyin' | 'tiktok' | 'youtube';
  /** Absent for an account that was never connected. */
  readonly handle?: string;
  readonly status: 'connected' | 'expired' | 'never';
}

const AI_APIS: readonly ModelApi[] = [
  {
    id: 'openai',
    provider: 'OpenAI',
    label: 'GPT-4o / GPT-4o-mini',
    connected: true,
    lastUsedKey: 'common.integrations.lastUsedHoursAgo',
    maskedKey: 'sk-proj-••••••••a9F2',
    category: 'text',
    Logo: OpenAILogo,
  },
  {
    id: 'anthropic',
    provider: 'Anthropic',
    label: 'Claude 3.5 Sonnet',
    connected: true,
    lastUsedKey: 'common.integrations.lastUsedYesterday',
    maskedKey: 'sk-ant-••••••••7bX1',
    category: 'text',
    Logo: AnthropicLogo,
  },
  {
    id: 'google',
    provider: 'Google',
    label: 'Gemini 2.5 Pro',
    connected: false,
    category: 'text',
    Logo: GoogleLogo,
  },
  {
    id: 'mistral',
    provider: 'Mistral',
    label: 'Mistral Large',
    connected: false,
    category: 'text',
    Logo: MistralLogo,
  },
  {
    id: 'runway',
    provider: 'Runway',
    label: 'Gen-3 Alpha',
    connected: false,
    category: 'media',
    Logo: RunwayLogo,
  },
  {
    id: 'luma',
    provider: 'Luma',
    label: 'Dream Machine',
    connected: false,
    category: 'media',
    Logo: LumaLogo,
  },
  {
    id: 'kling',
    provider: 'Kling',
    label: 'Kling AI v1.5',
    connected: false,
    category: 'media',
    Logo: KlingLogo,
  },
  {
    id: 'elevenlabs',
    provider: 'ElevenLabs',
    label: 'ElevenLabs Voice',
    connected: false,
    category: 'audio',
    Logo: ElevenLabsLogo,
  },
  {
    id: 'whisper',
    provider: 'Whisper',
    label: 'Whisper STT',
    connected: false,
    category: 'audio',
    Logo: WhisperLogo,
  },
];

const SOCIAL: readonly SocialAccount[] = [
  { id: 's-douyin', platform: 'douyin', handle: '@flowcortex.cn', status: 'connected' },
  { id: 's-tiktok', platform: 'tiktok', handle: '@flowcortex', status: 'connected' },
  { id: 's-youtube', platform: 'youtube', status: 'never' },
];

const PLATFORM_LABEL: Record<SocialAccount['platform'], string> = {
  douyin: 'Douyin',
  tiktok: 'TikTok',
  youtube: 'YouTube',
};

const CATEGORY_TITLE_KEYS: Record<ProviderCategory, string> = {
  text: 'common.integrations.category.text',
  media: 'common.integrations.category.media',
  audio: 'common.integrations.category.audio',
  social: 'common.integrations.category.social',
};

const MODEL_CATEGORIES = ['text', 'media', 'audio'] as const;

const SOCIAL_STATUS_KEYS: Record<SocialAccount['status'], string> = {
  connected: 'common.integrations.connected',
  expired: 'common.integrations.reconnectNeeded',
  never: 'common.integrations.notConnected',
};

type Tab = 'models' | 'social';

export function IntegrationsPage() {
  const { t } = useTranslation();
  const [tab, setTab] = useState<Tab>('models');

  const grouped = AI_APIS.reduce<Record<ProviderCategory, ModelApi[]>>(
    (acc, api) => {
      acc[api.category].push(api);
      return acc;
    },
    { text: [], media: [], audio: [], social: [] },
  );

  return (
    <main className="integrations-page">
      <header className="integrations-page__header">
        <p className="eyebrow">{t('common.integrations.eyebrow')}</p>
        <h1>{t('common.integrations.title')}</h1>
        <p className="integrations-page__subtitle">{t('common.integrations.subtitle')}</p>
      </header>

      <fieldset className="integrations-page__tabs">
        <legend className="visually-hidden">{t('common.integrations.tabsLegend')}</legend>
        <button
          type="button"
          aria-pressed={tab === 'models'}
          className={`integrations-page__tab${tab === 'models' ? ' is-active' : ''}`}
          onClick={() => setTab('models')}
        >
          {t('common.integrations.tabs.models')}
        </button>
        <button
          type="button"
          aria-pressed={tab === 'social'}
          className={`integrations-page__tab${tab === 'social' ? ' is-active' : ''}`}
          onClick={() => setTab('social')}
        >
          {t('common.integrations.tabs.social')}
        </button>
      </fieldset>

      {tab === 'models' ? (
        MODEL_CATEGORIES.map((category) => (
          <section key={category} className="integrations-page__group" aria-label={t(CATEGORY_TITLE_KEYS[category])}>
            <h2 className="integrations-page__group-title">{t(CATEGORY_TITLE_KEYS[category])}</h2>
            <ul className="integrations-page__list">
              {grouped[category].map((api) => (
                <li key={api.id} className="integrations-page__row">
                  <div className="integrations-page__row-main">
                    <api.Logo size={22} title={api.provider} />
                    <div className="integrations-page__row-text">
                      <strong className="integrations-page__row-name">{api.provider}</strong>
                      <span className="integrations-page__row-label">{api.label}</span>
                      {api.connected && api.maskedKey && (
                        <code className="integrations-page__key-mask" aria-label={t('common.integrations.maskedKey')}>
                          {api.maskedKey}
                        </code>
                      )}
                    </div>
                  </div>
                  <div className="integrations-page__row-actions">
                    <span className={`integrations-page__status ${api.connected ? 'is-on' : 'is-off'}`}>
                      {t(api.connected ? 'common.integrations.connected' : 'common.integrations.notConnected')}
                    </span>
                    {api.lastUsedKey && (
                      <span className="integrations-page__meta">
                        {t('common.integrations.lastUsed', { when: t(api.lastUsedKey) })}
                      </span>
                    )}
                    <button type="button" className="cortex-btn cortex-btn--ghost">
                      {t(api.connected ? 'common.integrations.rotateKey' : 'common.integrations.addKey')}
                    </button>
                  </div>
                </li>
              ))}
            </ul>
          </section>
        ))
      ) : (
        <ul className="integrations-page__list">
          {SOCIAL.map((account) => (
            <li key={account.id} className="integrations-page__row">
              <div className="integrations-page__row-main">
                <strong className="integrations-page__row-name">{PLATFORM_LABEL[account.platform]}</strong>
                <span className="integrations-page__row-label">
                  {account.handle ?? t('common.integrations.noHandle')}
                </span>
              </div>
              <div className="integrations-page__row-actions">
                <span
                  className={`integrations-page__status ${
                    account.status === 'connected'
                      ? 'is-on'
                      : account.status === 'expired'
                        ? 'is-warn'
                        : 'is-off'
                  }`}
                >
                  {t(SOCIAL_STATUS_KEYS[account.status])}
                </span>
                <button type="button" className="cortex-btn cortex-btn--ghost">
                  {t(account.status === 'connected' ? 'common.integrations.manage' : 'common.integrations.connect')}
                </button>
              </div>
            </li>
          ))}
        </ul>
      )}
    </main>
  );
}
