package com.bugeon.fintechledger.auth.domain;

/**
 * Lifecycle states for a {@link User}.
 *
 * Transitions:
 *   ACTIVE → SUSPENDED  (admin action)
 *   ACTIVE → DELETED    (soft-delete)
 *   SUSPENDED → ACTIVE  (admin reinstate)
 *   SUSPENDED → DELETED
 *   DELETED   — terminal, no transition allowed
 */
public enum UserStatus {
    ACTIVE,
    SUSPENDED,
    DELETED
}
