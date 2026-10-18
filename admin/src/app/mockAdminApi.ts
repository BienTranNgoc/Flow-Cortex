/**
 * Mock data for the admin pages whose backend endpoints are not yet shipped.
 * Each function returns a `Promise` to keep the call sites API-first: when the
 * real endpoints land in `flowCortexApi.ts`, replace the body of each function
 * with a generated-client call and delete the export.
 *
 * Every consumer tags the data with `[TODO: backend]` so the work is visible.
 */

export interface AdminWorkflow {
  id: string;
  name: string;
  description: string;
  status: 'Complete' | 'Incomplete';
  videoLimitMB: number;
  audioLimitMB: number;
  imageLimitMB: number;
  allowedExtensions: string[];
  magicBytesEnforced: boolean;
  runCount: number;
  visible: boolean;
}

export interface AdminApproval {
  id: string;
  runId: string;
  workflowName: string;
  ownerEmail: string;
  stepName: string;
  scriptPreview?: string;
  waitingForMinutes: number;
  createdAt: string;
}

export interface CreditSummary {
  totalGranted: number;
  totalConsumedToday: number;
  totalRefunded: number;
  activeWallets: number;
}

export interface AdminCreditWallet {
  userId: string;
  email: string;
  currentBalance: number;
  totalConsumed: number;
  lastGranted: string;
  totalGranted: number;
}

export interface CreditTransaction {
  id: string;
  type: 'STEP_CHARGE' | 'SYSTEM_REFUND' | 'MANUAL_GRANT' | 'MANUAL_ADJUST';
  runId?: string;
  stepName?: string;
  amount: number;
  email: string;
  createdAt: string;
}

export interface ModelProvider {
  id: string;
  name: string;
  status: 'Operational' | 'Degraded' | 'Down';
  tokensToday: number;
  tokensThisMonth: number;
  estimatedCostUsd: number;
  rateLimitPerMinute: number;
}

export interface SmsOutboxEntry {
  id: string;
  phone: string;
  code: string;
  status: 'PENDING' | 'SENT' | 'EXPIRED' | 'FAILED';
  attempts: number;
  resendCooldownSeconds: number;
  hourlyCount: number;
  dailyCount: number;
  createdAt: string;
}

export interface FeatureFlag {
  key: string;
  description: string;
  enabled: boolean;
  updatedBy: string;
  updatedAt: string;
}

export interface AuditEntry {
  id: string;
  timestamp: string;
  actor: string;
  action:
    | 'FORCE_STOP_RUN'
    | 'MANUAL_REFUND_CREDITS'
    | 'TOGGLE_FEATURE_FLAG'
    | 'DISABLE_USER'
    | 'ENABLE_USER'
    | 'CHANGE_ROLE'
    | 'OVERRIDE_APPROVAL'
    | 'GRANT_CREDITS';
  target: string;
  ip: string;
  status: 'Success' | 'Failed';
}

const now = () => new Date().toISOString();

const ADMIN_WORKFLOWS: AdminWorkflow[] = [
  {
    id: 'wf-check-video',
    name: 'Check a video file',
    description: 'Validate codec, duration, and resolution of an uploaded video.',
    status: 'Complete',
    videoLimitMB: 500,
    audioLimitMB: 100,
    imageLimitMB: 10,
    allowedExtensions: ['.mp4', '.mov', '.webm'],
    magicBytesEnforced: true,
    runCount: 1284,
    visible: true,
  },
  {
    id: 'wf-douyin',
    name: 'Douyin Video Repurpose',
    description: 'Repurpose a Douyin short into three platform-specific cuts.',
    status: 'Complete',
    videoLimitMB: 500,
    audioLimitMB: 100,
    imageLimitMB: 10,
    allowedExtensions: ['.mp4'],
    magicBytesEnforced: true,
    runCount: 318,
    visible: true,
  },
  {
    id: 'wf-echo-demo',
    name: 'Echo Demo',
    description: 'Echo the input media back to demonstrate the worker queue.',
    status: 'Incomplete',
    videoLimitMB: 500,
    audioLimitMB: 100,
    imageLimitMB: 10,
    allowedExtensions: ['.mp4', '.wav', '.png'],
    magicBytesEnforced: false,
    runCount: 0,
    visible: false,
  },
  {
    id: 'wf-product-video',
    name: 'Product Video',
    description: 'Generate a product demo from a script and a product image.',
    status: 'Complete',
    videoLimitMB: 500,
    audioLimitMB: 100,
    imageLimitMB: 10,
    allowedExtensions: ['.mp4', '.wav', '.png', '.jpg'],
    magicBytesEnforced: true,
    runCount: 562,
    visible: true,
  },
];

const ADMIN_APPROVALS: AdminApproval[] = [
  {
    id: 'ap-1',
    runId: 'run-89f2a',
    workflowName: 'Douyin Video Repurpose',
    ownerEmail: 'member1@flowcortex.dev',
    stepName: 'approve_product_script',
    scriptPreview:
      'Hook (0-3s): "Wait, this $20 gadget saved me 3 hours a week"\nDemo (3-22s): unbox + use\nCTA (22-30s): link in bio',
    waitingForMinutes: 15,
    createdAt: now(),
  },
  {
    id: 'ap-2',
    runId: 'run-12bcd',
    workflowName: 'Product Video',
    ownerEmail: 'creator@flowcortex.dev',
    stepName: 'approval_checkpoint',
    scriptPreview: 'Short review script pending sign-off.',
    waitingForMinutes: 4,
    createdAt: now(),
  },
];

const CREDIT_SUMMARY: CreditSummary = {
  totalGranted: 487_500,
  totalConsumedToday: 12_840,
  totalRefunded: 2_310,
  activeWallets: 487,
};

const CREDIT_WALLETS: AdminCreditWallet[] = [
  {
    userId: 'u-1001',
    email: 'member1@flowcortex.dev',
    currentBalance: 740,
    totalConsumed: 260,
    lastGranted: '2026-10-01T09:00:00Z',
    totalGranted: 1000,
  },
  {
    userId: 'u-1002',
    email: 'creator@flowcortex.dev',
    currentBalance: 520,
    totalConsumed: 480,
    lastGranted: '2026-10-15T09:00:00Z',
    totalGranted: 1000,
  },
];

const CREDIT_TRANSACTIONS: CreditTransaction[] = [
  {
    id: 'tx-9001',
    type: 'STEP_CHARGE',
    runId: 'run-89f2a',
    stepName: 'transcribe_audio',
    amount: -25,
    email: 'member1@flowcortex.dev',
    createdAt: now(),
  },
  {
    id: 'tx-9002',
    type: 'SYSTEM_REFUND',
    runId: 'run-12bcd',
    stepName: 'render_video',
    amount: 50,
    email: 'creator@flowcortex.dev',
    createdAt: now(),
  },
  {
    id: 'tx-9003',
    type: 'MANUAL_GRANT',
    amount: 200,
    email: 'support@flowcortex.dev',
    createdAt: now(),
  },
];

const MODEL_PROVIDERS: ModelProvider[] = [
  {
    id: 'openai',
    name: 'OpenAI',
    status: 'Operational',
    tokensToday: 1_280_000,
    tokensThisMonth: 38_400_000,
    estimatedCostUsd: 412.55,
    rateLimitPerMinute: 500,
  },
  {
    id: 'anthropic',
    name: 'Anthropic',
    status: 'Operational',
    tokensToday: 820_000,
    tokensThisMonth: 24_700_000,
    estimatedCostUsd: 297.0,
    rateLimitPerMinute: 300,
  },
  {
    id: 'gemini',
    name: 'Google Gemini',
    status: 'Degraded',
    tokensToday: 140_000,
    tokensThisMonth: 4_900_000,
    estimatedCostUsd: 48.0,
    rateLimitPerMinute: 240,
  },
  {
    id: 'mistral',
    name: 'Mistral',
    status: 'Operational',
    tokensToday: 60_000,
    tokensThisMonth: 1_800_000,
    estimatedCostUsd: 12.0,
    rateLimitPerMinute: 120,
  },
];

const SMS_OUTBOX: SmsOutboxEntry[] = [
  {
    id: 'sms-501',
    phone: '+84901234567',
    code: '482910',
    status: 'SENT',
    attempts: 0,
    resendCooldownSeconds: 60,
    hourlyCount: 1,
    dailyCount: 1,
    createdAt: now(),
  },
  {
    id: 'sms-502',
    phone: '+84987654321',
    code: '731205',
    status: 'PENDING',
    attempts: 2,
    resendCooldownSeconds: 120,
    hourlyCount: 1,
    dailyCount: 1,
    createdAt: now(),
  },
  {
    id: 'sms-503',
    phone: '+84555123456',
    code: '116644',
    status: 'EXPIRED',
    attempts: 5,
    resendCooldownSeconds: 300,
    hourlyCount: 2,
    dailyCount: 2,
    createdAt: now(),
  },
];

const FEATURE_FLAGS: FeatureFlag[] = [
  {
    key: 'enable_phone_verification',
    description: 'Run the phone verification flow on signup and sensitive actions.',
    enabled: true,
    updatedBy: 'admin@flowcortex.dev',
    updatedAt: '2026-10-12T08:30:00Z',
  },
  {
    key: 'enable_credit_system',
    description: 'Charge credits when a workflow step starts; refund on server errors.',
    enabled: true,
    updatedBy: 'admin@flowcortex.dev',
    updatedAt: '2026-10-10T11:15:00Z',
  },
  {
    key: 'enable_douyin_workflow',
    description: 'Show the Douyin Video Repurpose template on the launchpad.',
    enabled: true,
    updatedBy: 'admin@flowcortex.dev',
    updatedAt: '2026-10-01T09:00:00Z',
  },
  {
    key: 'strict_magic_bytes_check',
    description: 'Reject uploads whose first bytes do not match the claimed extension.',
    enabled: true,
    updatedBy: 'admin@flowcortex.dev',
    updatedAt: '2026-09-28T16:45:00Z',
  },
];

const AUDIT_LOG: AuditEntry[] = [
  {
    id: 'audit-1',
    timestamp: '2026-10-18T13:42:00Z',
    actor: 'admin@flowcortex.dev',
    action: 'FORCE_STOP_RUN',
    target: 'run-89f2a',
    ip: '203.0.113.42',
    status: 'Success',
  },
  {
    id: 'audit-2',
    timestamp: '2026-10-18T13:15:00Z',
    actor: 'admin@flowcortex.dev',
    action: 'MANUAL_REFUND_CREDITS',
    target: 'member1@flowcortex.dev (200 credits)',
    ip: '203.0.113.42',
    status: 'Success',
  },
  {
    id: 'audit-3',
    timestamp: '2026-10-18T12:01:00Z',
    actor: 'admin@flowcortex.dev',
    action: 'TOGGLE_FEATURE_FLAG',
    target: 'enable_credit_system',
    ip: '203.0.113.42',
    status: 'Success',
  },
  {
    id: 'audit-4',
    timestamp: '2026-10-18T11:48:00Z',
    actor: 'admin@flowcortex.dev',
    action: 'DISABLE_USER',
    target: 'spammer@example.com',
    ip: '203.0.113.42',
    status: 'Success',
  },
];

export function fetchAdminWorkflows(): Promise<AdminWorkflow[]> {
  return Promise.resolve(ADMIN_WORKFLOWS);
}

export function fetchAdminApprovals(): Promise<AdminApproval[]> {
  return Promise.resolve(ADMIN_APPROVALS);
}

export function fetchCreditSummary(): Promise<CreditSummary> {
  return Promise.resolve(CREDIT_SUMMARY);
}

export function fetchCreditWallets(): Promise<AdminCreditWallet[]> {
  return Promise.resolve(CREDIT_WALLETS);
}

export function fetchCreditTransactions(): Promise<CreditTransaction[]> {
  return Promise.resolve(CREDIT_TRANSACTIONS);
}

export function fetchModelProviders(): Promise<ModelProvider[]> {
  return Promise.resolve(MODEL_PROVIDERS);
}

export function fetchSmsOutbox(): Promise<SmsOutboxEntry[]> {
  return Promise.resolve(SMS_OUTBOX);
}

export function fetchFeatureFlags(): Promise<FeatureFlag[]> {
  return Promise.resolve(FEATURE_FLAGS);
}

export function fetchAuditLog(): Promise<AuditEntry[]> {
  return Promise.resolve(AUDIT_LOG);
}
