/**
 * Parser and value object for the {@code /vg lookup} filter mini-language.
 *
 * <p>The grammar is defined in {@code SHARED-CONTRACTS.md} § 4.2. {@link
 * network.vonix.guardian.core.query.QueryFilter} is the immutable result;
 * {@link network.vonix.guardian.core.query.QueryParser} is the single-pass
 * tokenizer/parser that builds it; {@link
 * network.vonix.guardian.core.query.QueryParseException} is thrown on any
 * malformed input with a human-readable message.
 */
package network.vonix.guardian.core.query;
