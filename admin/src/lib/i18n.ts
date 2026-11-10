import { createAppI18n } from '@flow-cortex/i18n';
import enAdminAccounts from '../locales/en/adminAccounts.json';
import enAuth from '../locales/en/auth.json';
import enChat from '../locales/en/chat.json';
import enCommon from '../locales/en/common.json';
import enDashboard from '../locales/en/dashboard.json';
import enMediaJobs from '../locales/en/mediaJobs.json';
import enPaymentSettings from '../locales/en/paymentSettings.json';
import enRuns from '../locales/en/runs.json';
import enSettings from '../locales/en/settings.json';
import enSmsOutbox from '../locales/en/smsOutbox.json';
import enSupport from '../locales/en/support.json';
import enUsers from '../locales/en/users.json';
import viAdminAccounts from '../locales/vi/adminAccounts.json';
import viAuth from '../locales/vi/auth.json';
import viChat from '../locales/vi/chat.json';
import viCommon from '../locales/vi/common.json';
import viDashboard from '../locales/vi/dashboard.json';
import viMediaJobs from '../locales/vi/mediaJobs.json';
import viPaymentSettings from '../locales/vi/paymentSettings.json';
import viRuns from '../locales/vi/runs.json';
import viSettings from '../locales/vi/settings.json';
import viSmsOutbox from '../locales/vi/smsOutbox.json';
import viSupport from '../locales/vi/support.json';
import viUsers from '../locales/vi/users.json';

export type { Locale } from '@flow-cortex/i18n';

const { i18n, activeLocale, setLocale } = createAppI18n({
  vi: {
    common: viCommon,
    auth: viAuth,
    users: viUsers,
    adminAccounts: viAdminAccounts,
    runs: viRuns,
    mediaJobs: viMediaJobs,
    dashboard: viDashboard,
    smsOutbox: viSmsOutbox,
    settings: viSettings,
    paymentSettings: viPaymentSettings,
    support: viSupport,
    chat: viChat,
  },
  en: {
    common: enCommon,
    auth: enAuth,
    users: enUsers,
    adminAccounts: enAdminAccounts,
    runs: enRuns,
    mediaJobs: enMediaJobs,
    dashboard: enDashboard,
    smsOutbox: enSmsOutbox,
    settings: enSettings,
    paymentSettings: enPaymentSettings,
    support: enSupport,
    chat: enChat,
  },
});

export default i18n;
export { activeLocale, setLocale };
