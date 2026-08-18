package com.karadas.l7defense.riskengine.scoring;

/**
 * The kinds of abuse the engine distinguishes. Mitigation is chosen by type and
 * severity by score, so the response fits the threat rather than only its size
 * (Design Decisions Log 4.5).
 *
 * <p>Declaration order is the tie-break order when two types score equally:
 * resource exhaustion first, because there the attacker consumes someone else's
 * capacity rather than their own.
 */
public enum AttackType {
    RESOURCE_EXHAUSTION,
    CREDENTIAL_ATTACK,
    VOLUMETRIC
}