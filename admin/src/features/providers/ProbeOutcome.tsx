import type { ProbeResult } from '@flow-cortex/api-client';
import type { TFunction } from 'i18next';
import { useTranslation } from 'react-i18next';
import { StatusPill, type StatusTone } from '../../app/StatusPill';

const KNOWN_CODES: ReadonlySet<string> = new Set([
  'missing_config',
  'quota_exceeded',
  'rejected',
  'blocked',
  'timeout',
  'unreachable',
  'upstream',
  'unusable',
]);

/** The translated reason for a probe code; a code this console does not know still names itself. */
function probeReason(t: TFunction, code: string): string {
  return KNOWN_CODES.has(code)
    ? t(`providers.probe.code.${code}`)
    : t('providers.probe.unknown', { code });
}

function describe(t: TFunction, result: ProbeResult): { tone: StatusTone; text: string } {
  const ms = result.latencyMs ?? 0;
  if (result.ok) {
    if (!result.tested) return { tone: 'info', text: t('providers.probe.notTested') };
    return result.outputTokens == null
      ? { tone: 'success', text: t('providers.probe.reachable', { ms }) }
      : { tone: 'success', text: t('providers.probe.ok', { ms, tokens: result.outputTokens }) };
  }
  const reason = probeReason(t, result.code ?? 'unknown');
  const names = result.missingSettings ?? [];
  if (result.code === 'missing_config') {
    return {
      tone: 'warning',
      text: t('providers.probe.missingSettings', { reason, names: names.join(', ') }),
    };
  }
  return { tone: 'error', text: t('providers.probe.failedIn', { reason, ms }) };
}

/** Inline result of a model or provider test, kept until the next test replaces it. */
export function ProbeOutcome({
  result,
  failed,
}: Readonly<{ result: ProbeResult | undefined; failed: boolean }>) {
  const { t } = useTranslation();
  if (failed) {
    return (
      <p role="alert" className="providers-probe">
        <StatusPill tone="error" label={t('providers.probe.requestFailed')} />
      </p>
    );
  }
  if (!result) return null;
  const { tone, text } = describe(t, result);
  return (
    <p role="status" className="providers-probe">
      <StatusPill tone={tone} label={text} />
    </p>
  );
}
