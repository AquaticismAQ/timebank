/**
 * Accounts, immutable transaction ledger, freeze, adjustment, reversal
 * (B3.8-B3.10, Design.md §12 invariants 1-2, D12). {@code credit} is the
 * only path any other module uses to change a balance — always paired
 * with an appended {@code account_transaction} row inside the same
 * database row lock (B3.9, {@code SELECT ... FOR UPDATE} via {@code
 * AccountRepository.findWithLockById}).
 */
package cn.timebank.account;
