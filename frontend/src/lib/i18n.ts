import { createAppI18n } from '@flow-cortex/i18n';
import enAuth from '../locales/en/auth.json';
import enBilling from '../locales/en/billing.json';
import enChat from '../locales/en/chat.json';
import enCommon from '../locales/en/common.json';
import enCredits from '../locales/en/credits.json';
import enDashboard from '../locales/en/dashboard.json';
import enOnboarding from '../locales/en/onboarding.json';
import enRunHistory from '../locales/en/runHistory.json';
import enNotifications from '../locales/en/notifications.json';
import enSupport from '../locales/en/support.json';
import enWorkflow from '../locales/en/workflow.json';
import viAuth from '../locales/vi/auth.json';
import viBilling from '../locales/vi/billing.json';
import viChat from '../locales/vi/chat.json';
import viCommon from '../locales/vi/common.json';
import viCredits from '../locales/vi/credits.json';
import viDashboard from '../locales/vi/dashboard.json';
import viOnboarding from '../locales/vi/onboarding.json';
import viRunHistory from '../locales/vi/runHistory.json';
import viNotifications from '../locales/vi/notifications.json';
import viSupport from '../locales/vi/support.json';
import viWorkflow from '../locales/vi/workflow.json';

export type { Locale } from '@flow-cortex/i18n';

const { i18n, activeLocale, setLocale } = createAppI18n({
  vi: {
    common: viCommon,
    auth: viAuth,
    workflow: viWorkflow,
    credits: viCredits,
    dashboard: viDashboard,
    runHistory: viRunHistory,
    support: viSupport,
    onboarding: viOnboarding,
    chat: viChat,
    notifications: viNotifications,
    billing: viBilling,
  },
  en: {
    common: enCommon,
    auth: enAuth,
    workflow: enWorkflow,
    credits: enCredits,
    dashboard: enDashboard,
    runHistory: enRunHistory,
    support: enSupport,
    onboarding: enOnboarding,
    chat: enChat,
    notifications: enNotifications,
    billing: enBilling,
  },
});

export default i18n;
export { activeLocale, setLocale };
