package cn.timebank.account.application.dto;

/**
 * The aggregate behind Time Coin Accounts' three summary cards (issue 128).
 *
 * Aggregated in SQL over <em>everything the caller may see</em>, which is the
 * whole point: the cards used to sum the rows on screen and call the result a
 * total. Counted in minimum accounting units, like every other coin figure on
 * the wire — never a float.
 *
 * {@code averageBalance} is carried rather than left to the client so that
 * "average over how many" has one answer: every account in scope, including
 * those sitting at zero.
 */
public record AccountSummaryDto(long totalBalance, long accountCount, long accountsWithBalance, long averageBalance) {
}
