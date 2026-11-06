import { createAppI18n } from '@flow-cortex/i18n';
import enAuth from '../locales/en/auth.json';
import enCommon from '../locales/en/common.json';
import enDashboard from '../locales/en/dashboard.json';
import enMediaJobs from '../locales/en/mediaJobs.json';
import enRuns from '../locales/en/runs.json';
import enSmsOutbox from '../locales/en/smsOutbox.json';
import enSupport from '../locales/en/support.json';
import enUsers from '../locales/en/users.json';
import viAuth from '../locales/vi/auth.json';
import viCommon from '../locales/vi/common.json';
import viDashboard from '../locales/vi/dashboard.json';
import viMediaJobs from '../locales/vi/mediaJobs.json';
import viRuns from '../locales/vi/runs.json';
import viSmsOutbox from '../locales/vi/smsOutbox.json';
import viSupport from '../locales/vi/support.json';
import viUsers from '../locales/vi/users.json';

export type { Locale } from '@flow-cortex/i18n';

const { i18n, activeLocale, setLocale } = createAppI18n({
  vi: {
    common: viCommon,
    auth: viAuth,
    users: viUsers,
    runs: viRuns,
    mediaJobs: viMediaJobs,
    dashboard: viDashboard,
    smsOutbox: viSmsOutbox,
    support: viSupport,
  },
  en: {
    common: enCommon,
    auth: enAuth,
    users: enUsers,
    runs: enRuns,
    mediaJobs: enMediaJobs,
    dashboard: enDashboard,
    smsOutbox: enSmsOutbox,
    support: enSupport,
  },
});

export default i18n;
export { activeLocale, setLocale };
