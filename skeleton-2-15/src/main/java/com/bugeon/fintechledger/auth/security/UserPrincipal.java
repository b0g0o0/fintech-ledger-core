package com.bugeon.fintechledger.auth.security;

import com.bugeon.fintechledger.auth.domain.User;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Spring Security {@link UserDetails} adapter wrapping the domain {@link User}.
 *
 * Stored in the {@link org.springframework.security.core.context.SecurityContextHolder}
 * after successful JWT validation and accessible in controllers via
 * {@link org.springframework.security.core.annotation.AuthenticationPrincipal}.
 *
 * The {@code userId} field is added beyond the standard contract so controllers
 * can identify the caller without an additional DB query.
 */
@Getter
public class UserPrincipal implements UserDetails {

    private final UUID                                    userId;
    private final String                                  email;
    private final String                                  passwordHash;
    private final boolean                                 active;
    private final Collection<? extends GrantedAuthority> authorities;

    private UserPrincipal(UUID userId, String email, String passwordHash,
                          boolean active, Collection<? extends GrantedAuthority> authorities) {
        this.userId       = userId;
        this.email        = email;
        this.passwordHash = passwordHash;
        this.active       = active;
        this.authorities  = authorities;
    }

    public static UserPrincipal from(User user) {
        return new UserPrincipal(
                user.getId(),
                user.getEmail(),
                user.getPasswordHash(),
                user.isActive(),
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
    }

    /** Test-only factory — creates a minimal principal with userId and email. */
    public static UserPrincipal of(UUID userId, String email) {
        return new UserPrincipal(userId, email, "", true,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    // ── UserDetails contract ──────────────────────────────────────────────────

    /** Spring Security treats this as the "username" — we use email. */
    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override public boolean isAccountNonExpired()     { return active; }
    @Override public boolean isAccountNonLocked()      { return active; }
    @Override public boolean isCredentialsNonExpired() { return true;   }
    @Override public boolean isEnabled()               { return active; }
}
