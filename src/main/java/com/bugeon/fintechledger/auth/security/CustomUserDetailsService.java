package com.bugeon.fintechledger.auth.security;

import com.bugeon.fintechledger.auth.domain.User;
import com.bugeon.fintechledger.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads user records for Spring Security's authentication pipeline.
 *
 * This service is invoked:
 *   1. By {@link org.springframework.security.authentication.dao.DaoAuthenticationProvider}
 *      during form-based / DAO authentication (not used directly in JWT flow).
 *   2. By {@link JwtAuthenticationFilter} to hydrate the {@link UserPrincipal}
 *      from the email extracted out of a validated JWT.
 *
 * The "username" in Spring Security's contract maps to the user's email address.
 */
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    /**
     * Loads and returns a {@link UserPrincipal} by email.
     *
     * @param email the value stored in the JWT {@code email} claim (used as username)
     * @throws UsernameNotFoundException if no user exists with this email
     */
    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository
                .findByEmail(email.toLowerCase().trim())
                .orElseThrow(() -> new UsernameNotFoundException(
                        "No user found with email: " + email));
        return UserPrincipal.from(user);
    }
}
