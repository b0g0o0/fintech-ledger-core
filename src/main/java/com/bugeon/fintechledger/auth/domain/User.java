package com.bugeon.fintechledger.auth.domain;

import com.bugeon.fintechledger.common.domain.BaseEntity;
import com.bugeon.fintechledger.common.exception.BusinessException;
import com.bugeon.fintechledger.common.exception.ErrorCode;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * Aggregate root for the Identity & Access bounded context.
 *
 * Invariants:
 *  - email is normalised to lowercase on creation and is immutable thereafter
 *  - passwordHash is ALWAYS BCrypt(strength=12) — never stored in plaintext
 *  - status FSM:  ACTIVE ⇄ SUSPENDED → DELETED (DELETED is terminal)
 *
 * The {@link #create} factory is the single entry point for instantiation.
 * Direct field assignment is prevented by {@code AccessLevel.PROTECTED}.
 */
@Entity
@Table(
    name = "users",
    indexes = {
        @Index(name = "idx_users_email",  columnList = "email"),
        @Index(name = "idx_users_status", columnList = "status")
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(of = "id", callSuper = false)
@ToString(exclude = "passwordHash")
public class User extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "email", nullable = false, unique = true, length = 255, updatable = false)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 100)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private UserStatus status;

    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * Creates a new {@code ACTIVE} user.
     *
     * @param email        raw email — will be normalised to lowercase + trimmed
     * @param passwordHash BCrypt-encoded password (caller is responsible for encoding)
     * @param fullName     display name
     */
    public static User create(String email, String passwordHash, String fullName) {
        User user        = new User();
        user.email        = email.toLowerCase().trim();
        user.passwordHash = passwordHash;
        user.fullName     = fullName;
        user.status       = UserStatus.ACTIVE;
        return user;
    }

    // ── Domain guards ─────────────────────────────────────────────────────────

    /**
     * Asserts this user is {@code ACTIVE}.
     * Called before issuing tokens to block suspended/deleted accounts.
     *
     * @throws BusinessException {@link ErrorCode#USER_SUSPENDED} if SUSPENDED
     * @throws BusinessException {@link ErrorCode#USER_NOT_FOUND} if DELETED (treat as gone)
     */
    public void validateActive() {
        switch (this.status) {
            case SUSPENDED -> throw new BusinessException(ErrorCode.USER_SUSPENDED);
            case DELETED   -> throw new BusinessException(ErrorCode.USER_NOT_FOUND);
            case ACTIVE    -> { /* ok */ }
        }
    }

    public boolean isActive() {
        return UserStatus.ACTIVE == this.status;
    }

    // ── State transitions ─────────────────────────────────────────────────────

    public void suspend() {
        if (this.status == UserStatus.DELETED) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        this.status = UserStatus.SUSPENDED;
    }

    public void reinstate() {
        if (this.status == UserStatus.DELETED) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        this.status = UserStatus.ACTIVE;
    }

    public void softDelete() {
        this.status = UserStatus.DELETED;
    }

    // ── Mutations ─────────────────────────────────────────────────────────────

    /** Replaces the stored BCrypt hash (e.g. password-change flow). */
    public void updatePasswordHash(String newPasswordHash) {
        this.passwordHash = newPasswordHash;
    }
}
