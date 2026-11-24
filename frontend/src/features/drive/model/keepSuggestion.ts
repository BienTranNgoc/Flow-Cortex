import type { CreateDriveItemRequest, SuggestionResponse } from '@flow-cortex/api-client';
import { defaultRoleOf, rolesOf } from './driveRoles';
import { NAME_MAX } from './itemForm';

export interface KeepDefaults {
  name: string;
  role: string;
  roles: string[];
}

export interface KeepRefusal {
  refusal: 'tooLong';
}

function cutName(value: string): string {
  const cut = value.slice(0, NAME_MAX);
  // Cutting between the halves of a surrogate pair would leave a lone half the server counts as a character.
  const last = cut.charCodeAt(cut.length - 1);
  return (last >= 0xd800 && last <= 0xdbff ? cut.slice(0, -1) : cut).trim();
}

function withoutExtension(fileName: string): string {
  const dot = fileName.lastIndexOf('.');
  return dot > 0 ? fileName.slice(0, dot) : fileName;
}

/** An item name from a file name: its base name, cut to the name limit. */
export function nameFromFileName(fileName: string): string {
  return cutName(withoutExtension(fileName));
}

export function keepDefaults(candidate: SuggestionResponse): KeepDefaults {
  const source =
    candidate.kind === 'text' || !candidate.runOutput
      ? candidate.previewText
      : withoutExtension(candidate.runOutput.name);
  return {
    name: cutName(source),
    role: defaultRoleOf(candidate.kind, candidate.roleHint),
    roles: rolesOf(candidate.kind),
  };
}

/** The create request that keeps `candidate`, or the refusal when its text is too long to be stored. */
export function keepRequest(
  candidate: SuggestionResponse,
  name: string,
  role: string,
): CreateDriveItemRequest | KeepRefusal {
  if (candidate.kind === 'text') {
    if (candidate.tooLong || candidate.textValue === undefined) {
      return { refusal: 'tooLong' };
    }
    return { name, contentType: 'text', semanticRole: role, textValue: candidate.textValue };
  }
  if (!candidate.runOutput) {
    throw new Error(`Media suggestion ${candidate.id} carries no run output`);
  }
  const { runId, stepOrder, name: outputName } = candidate.runOutput;
  return {
    name,
    contentType: candidate.kind,
    semanticRole: role,
    runOutput: { runId, stepOrder, name: outputName },
  };
}
