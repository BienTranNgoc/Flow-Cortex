export type ConflictChoice = 'load-latest' | 'overwrite' | 'load-revision';

/** The editor's content with the draft version its next save will carry as the token. */
export interface Versioned<C> {
  content: C;
  version: number;
}

export interface EditorState<C> extends Versioned<C> {
  unsaved: boolean;
}

/**
 * The editor state after a choice. `other` is the latest draft for a 409 and the snapshot for a
 * revision load. A snapshot keeps the current draft's version as the token: snapshot versions come
 * from independent draft and publication counters, so using one would make the next save a 409.
 */
export function conflictResolution<C>(
  choice: ConflictChoice,
  local: Versioned<C>,
  other: Versioned<C>,
): EditorState<C> {
  switch (choice) {
    case 'load-latest':
      return { content: other.content, version: other.version, unsaved: false };
    case 'overwrite':
      return { content: local.content, version: other.version, unsaved: true };
    case 'load-revision':
      return { content: other.content, version: local.version, unsaved: true };
  }
}
