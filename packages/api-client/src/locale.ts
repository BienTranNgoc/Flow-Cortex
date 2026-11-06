let localeProvider: () => string | null = () => null;

/** Set once at app startup; every API request then carries the returned language as `Accept-Language`. */
export function setLocaleProvider(provider: () => string | null): void {
  localeProvider = provider;
}

/** `headers` plus `Accept-Language` when the provider names a language. */
export function withLocale(headers?: HeadersInit): Headers {
  const localized = new Headers(headers);
  const locale = localeProvider();
  if (locale) {
    localized.set('Accept-Language', locale);
  }
  return localized;
}
