/**
 * Stand-in for `XMLHttpRequest`: records each request and lets the test decide when and how it
 * ends. Install with `vi.stubGlobal('XMLHttpRequest', FakeXhr)` and empty `FakeXhr.instances`.
 */
export class FakeXhr {
  static readonly instances: FakeXhr[] = [];

  method = '';
  url = '';
  body: unknown = undefined;
  status = 0;
  readonly headers: Record<string, string> = {};
  readonly upload: { onprogress: ((event: ProgressEvent) => void) | null } = { onprogress: null };
  onload: (() => void) | null = null;
  onerror: (() => void) | null = null;
  onabort: (() => void) | null = null;
  ontimeout: (() => void) | null = null;

  constructor() {
    FakeXhr.instances.push(this);
  }

  open(method: string, url: string) {
    this.method = method;
    this.url = url;
  }

  setRequestHeader(name: string, value: string) {
    this.headers[name] = value;
  }

  send(body: unknown) {
    this.body = body;
  }

  abort() {
    this.onabort?.();
  }

  progress(loaded: number, total: number) {
    this.upload.onprogress?.({ lengthComputable: true, loaded, total } as ProgressEvent);
  }

  respond(status: number) {
    this.status = status;
    this.onload?.();
  }

  failNetwork() {
    this.onerror?.();
  }
}
