import { createAppI18n } from '@flow-cortex/i18n';
import enAuth from '../locales/en/auth.json';
import enBilling from '../locales/en/billing.json';
import enChat from '../locales/en/chat.json';
import enCommon from '../locales/en/common.json';
import enCredits from '../locales/en/credits.json';
import enDashboard from '../locales/en/dashboard.json';
import enDrive from '../locales/en/drive.json';
import enDriveItems from '../locales/en/driveItems.json';
import enDriveMemory from '../locales/en/driveMemory.json';
import enOnboarding from '../locales/en/onboarding.json';
import enRunHistory from '../locales/en/runHistory.json';
import enNotifications from '../locales/en/notifications.json';
import enSupport from '../locales/en/support.json';
import enCvCheck from '../locales/en/cvCheck.json';
import enWorkflow from '../locales/en/workflow.json';
import viAuth from '../locales/vi/auth.json';
import viBilling from '../locales/vi/billing.json';
import viChat from '../locales/vi/chat.json';
import viCommon from '../locales/vi/common.json';
import viCredits from '../locales/vi/credits.json';
import viCvCheck from '../locales/vi/cvCheck.json';
import viDashboard from '../locales/vi/dashboard.json';
import viDrive from '../locales/vi/drive.json';
import viDriveItems from '../locales/vi/driveItems.json';
import viDriveMemory from '../locales/vi/driveMemory.json';
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
    drive: viDrive,
    driveItems: viDriveItems,
    driveMemory: viDriveMemory,
    billing: viBilling,
    cvCheck: viCvCheck,
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
    drive: enDrive,
    driveItems: enDriveItems,
    driveMemory: enDriveMemory,
    billing: enBilling,
    cvCheck: enCvCheck,
  },
});

export default i18n;
export { activeLocale, setLocale };
