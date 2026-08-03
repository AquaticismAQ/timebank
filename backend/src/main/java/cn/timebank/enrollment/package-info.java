/**
 * Registrations, waitlist, confirmation, completion (the contract's
 * Application, B4.6-B4.8). Capacity is serialized against {@code
 * project}'s row lock (B4.7, {@code ProjectService.lockForRegistration}),
 * not an entity of this module's own.
 */
package cn.timebank.enrollment;
