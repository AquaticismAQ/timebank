/**
 * Records, revisions, submission validation, the {@link
 * cn.timebank.servicerecord.domain.ServiceRecordStateMachine} template
 * (B3.1-B3.3). Evidence *binding* (checking a file's scan status before
 * allowing it to be attached) is B3.5, a deliberate follow-up — {@code
 * evidenceFileIds} here is structural only until the `file` module exists.
 */
package cn.timebank.servicerecord;
