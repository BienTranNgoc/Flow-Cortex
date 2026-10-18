import { useState } from 'react';
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
  readonly lastUsed?: string;
  readonly maskedKey?: string;
  readonly category: ProviderCategory;
  readonly Logo: (props: { size?: number; title?: string }) => JSX.Element;
}

interface SocialAccount {
  readonly id: string;
  readonly platform: 'douyin' | 'tiktok' | 'youtube';
  readonly handle: string;
  readonly status: 'connected' | 'expired' | 'never';
}

const AI_APIS: readonly ModelApi[] = [
  {
    id: 'openai',
    provider: 'OpenAI',
    label: 'GPT-4o / GPT-4o-mini',
    connected: true,
    lastUsed: '2h ago',
    maskedKey: 'sk-proj-••••••••a9F2',
    category: 'text',
    Logo: OpenAILogo,
  },
  {
    id: 'anthropic',
    provider: 'Anthropic',
    label: 'Claude 3.5 Sonnet',
    connected: true,
    lastUsed: 'yesterday',
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
  { id: 's-youtube', platform: 'youtube', handle: '— not connected —', status: 'never' },
];

const PLATFORM_LABEL: Record<SocialAccount['platform'], string> = {
  douyin: 'Douyin',
  tiktok: 'TikTok',
  youtube: 'YouTube',
};

const CATEGORY_TITLES: Record<ProviderCategory, string> = {
  text: 'Text LLMs',
  media: 'Media / Video AI',
  audio: 'Audio & Voice AI',
  social: 'Social',
};

type Tab = 'models' | 'social';

export function IntegrationsPage() {
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
        <p className="eyebrow">CONNECTED SERVICES</p>
        <h1>Integrations</h1>
        <p className="integrations-page__subtitle">
          Bring the AI providers and social accounts your workflows depend on. Keys stay on your account and are reused across every run.
        </p>
      </header>

      <fieldset className="integrations-page__tabs">
        <legend className="visually-hidden">Integration categories</legend>
        <button
          type="button"
          aria-pressed={tab === 'models'}
          className={`integrations-page__tab${tab === 'models' ? ' is-active' : ''}`}
          onClick={() => setTab('models')}
        >
          AI providers
        </button>
        <button
          type="button"
          aria-pressed={tab === 'social'}
          className={`integrations-page__tab${tab === 'social' ? ' is-active' : ''}`}
          onClick={() => setTab('social')}
        >
          Social accounts
        </button>
      </fieldset>

      {tab === 'models' ? (
        (['text', 'media', 'audio'] as const).map((category) => (
          <section key={category} className="integrations-page__group" aria-label={CATEGORY_TITLES[category]}>
            <h2 className="integrations-page__group-title">{CATEGORY_TITLES[category]}</h2>
            <ul className="integrations-page__list">
              {grouped[category].map((api) => (
                <li key={api.id} className="integrations-page__row">
                  <div className="integrations-page__row-main">
                    <api.Logo size={22} title={api.provider} />
                    <div className="integrations-page__row-text">
                      <strong className="integrations-page__row-name">{api.provider}</strong>
                      <span className="integrations-page__row-label">{api.label}</span>
                      {api.connected && api.maskedKey && (
                        <code className="integrations-page__key-mask" aria-label="API key (masked)">
                          {api.maskedKey}
                        </code>
                      )}
                    </div>
                  </div>
                  <div className="integrations-page__row-actions">
                    <span className={`integrations-page__status ${api.connected ? 'is-on' : 'is-off'}`}>
                      {api.connected ? 'Connected' : 'Not connected'}
                    </span>
                    {api.lastUsed && <span className="integrations-page__meta">last used {api.lastUsed}</span>}
                    <button type="button" className="cortex-btn cortex-btn--ghost">
                      {api.connected ? 'Rotate key' : 'Add key'}
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
                <span className="integrations-page__row-label">{account.handle}</span>
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
                  {account.status === 'connected' && 'Connected'}
                  {account.status === 'expired' && 'Reconnect needed'}
                  {account.status === 'never' && 'Not connected'}
                </span>
                <button type="button" className="cortex-btn cortex-btn--ghost">
                  {account.status === 'connected' ? 'Manage' : 'Connect'}
                </button>
              </div>
            </li>
          ))}
        </ul>
      )}
    </main>
  );
}
