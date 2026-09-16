package cn.timebank.dataexchange.application.validation;

import java.util.Set;

/**
 * Where rule sets come from (plan §2.1, decision D-1).
 *
 * <p>The decision was repo-versioned YAML, but it is taken behind this
 * interface on purpose. If the school ever needs to edit rules without a
 * deploy, a database-backed implementation — versioned with the same
 * immutable-publish lifecycle {@code cn.timebank.rules} already uses — can be
 * dropped in without the evaluator changing at all.</p>
 */
public interface RuleSetSource {

    /** @throws IllegalArgumentException if the type has no rule set */
    ImportRuleSet forType(String importType);

    /** Import types this source can validate — the authority for what the API accepts. */
    Set<String> supportedTypes();
}
