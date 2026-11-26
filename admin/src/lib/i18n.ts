import { createAppI18n } from '@flow-cortex/i18n';
import enAdminAccounts from '../locales/en/adminAccounts.json';
import enAuth from '../locales/en/auth.json';
import enChat from '../locales/en/chat.json';
import enCommon from '../locales/en/common.json';
import enContent from '../locales/en/content.json';
import enDashboard from '../locales/en/dashboard.json';
import enMediaJobs from '../locales/en/mediaJobs.json';
import enOnboarding from '../locales/en/onboarding.json';
import enRoles from '../locales/en/roles.json';
import enPhoneVerification from '../locales/en/phoneVerification.json';
import enProviders from '../locales/en/providers.json';
import enPaymentSettings from '../locales/en/paymentSettings.json';
import enRuns from '../locales/en/runs.json';
import enSettings from '../locales/en/settings.json';
import enSmsOutbox from '../locales/en/smsOutbox.json';
import enSupport from '../locales/en/support.json';
import enUsers from '../locales/en/users.json';
import enWorkflowReports from '../locales/en/workflowReports.json';
import enWorkflows from '../locales/en/workflows.json';
import viAdminAccounts from '../locales/vi/adminAccounts.json';
import viAuth from '../locales/vi/auth.json';
import viChat from '../locales/vi/chat.json';
import viCommon from '../locales/vi/common.json';
import viContent from '../locales/vi/content.json';
import viDashboard from '../locales/vi/dashboard.json';
import viMediaJobs from '../locales/vi/mediaJobs.json';
import viOnboarding from '../locales/vi/onboarding.json';
import viRoles from '../locales/vi/roles.json';
import viPhoneVerification from '../locales/vi/phoneVerification.json';
import viProviders from '../locales/vi/providers.json';
import viPaymentSettings from '../locales/vi/paymentSettings.json';
import viRuns from '../locales/vi/runs.json';
import viSettings from '../locales/vi/settings.json';
import viSmsOutbox from '../locales/vi/smsOutbox.json';
import viSupport from '../locales/vi/support.json';
import viUsers from '../locales/vi/users.json';
import viWorkflowReports from '../locales/vi/workflowReports.json';
import viWorkflows from '../locales/vi/workflows.json';

export type { Locale } from '@flow-cortex/i18n';

const { i18n, activeLocale, setLocale } = createAppI18n({
  vi: {
    common: viCommon,
    auth: viAuth,
    users: viUsers,
    adminAccounts: viAdminAccounts,
    roles: viRoles,
    providers: viProviders,
    runs: viRuns,
    workflows: viWorkflows,
    workflowReports: viWorkflowReports,
    mediaJobs: viMediaJobs,
    dashboard: viDashboard,
    smsOutbox: viSmsOutbox,
    phoneVerification: viPhoneVerification,
    settings: viSettings,
    paymentSettings: viPaymentSettings,
    support: viSupport,
    onboarding: viOnboarding,
    chat: viChat,
    content: viContent,
  },
  en: {
    common: enCommon,
    auth: enAuth,
    users: enUsers,
    adminAccounts: enAdminAccounts,
    roles: enRoles,
    providers: enProviders,
    runs: enRuns,
    workflows: enWorkflows,
    workflowReports: enWorkflowReports,
    mediaJobs: enMediaJobs,
    dashboard: enDashboard,
    smsOutbox: enSmsOutbox,
    phoneVerification: enPhoneVerification,
    settings: enSettings,
    paymentSettings: enPaymentSettings,
    support: enSupport,
    onboarding: enOnboarding,
    chat: enChat,
    content: enContent,
  },
});

export default i18n;
export { activeLocale, setLocale };
