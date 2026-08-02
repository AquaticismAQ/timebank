package cn.timebank.organization.application;

import cn.timebank.organization.application.dto.FeatureFlagsDto;

/**
 * B2.11. Read side feeds `/me` (contract §2.2). The reserved Phase 3/4
 * endpoints that reject mutations at the API boundary don't exist yet
 * (Design.md §14 — nothing flagged is built), so this interface has no
 * write/enforcement method to add until they do.
 */
public interface FeatureFlagService {

    FeatureFlagsDto getFlags();
}
