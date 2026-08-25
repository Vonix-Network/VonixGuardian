# VonixGuardian language bundles

This directory holds message bundles for player-facing chat strings.
Log strings stay hardcoded — server operators read English logs.

## Contributing a translation

1. Copy `en_us.properties` to `<locale>.properties` using an
   [ISO 639-1](https://en.wikipedia.org/wiki/List_of_ISO_639-1_codes)
   language code plus an [ISO 3166-1 alpha-2](https://en.wikipedia.org/wiki/ISO_3166-1_alpha-2)
   country code, lowercased and joined with `_` — e.g. `de_de.properties`,
   `pt_br.properties`, `ja_jp.properties`.
2. Translate values only. **Never rename or delete keys** — a missing key
   falls back to the raw key, which is ugly but safe.
3. Preserve `{0}`, `{1}` placeholders in the same order; the calling code
   passes arguments positionally.
4. Escape single quotes by doubling them (`''`) — this is a
   [`MessageFormat`](https://docs.oracle.com/javase/8/docs/api/java/text/MessageFormat.html)
   requirement. Unicode may be entered directly (UTF-8) or as `\uXXXX`.

## Reporting translation issues

Open a thread in the **#guardian-i18n** channel on the Vonix Discord, or
file an issue against the repo. Please include the locale, the affected
key(s), and a suggested revision.

## v1.2.0 scope

Only a small subset of core strings (query-parser errors, `/vg status`
section titles) are extracted so far. The full sweep, cell-side strings,
and locale selection lands in v1.3.0.
