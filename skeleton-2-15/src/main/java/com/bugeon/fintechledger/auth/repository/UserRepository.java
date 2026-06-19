package com.bugeon.fintechledger.auth.repository;

import com.bugeon.fintechledger.auth.domain.User;
import com.bugeon.fintechledger.auth.domain.UserStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /**
     * Finds an ACTIVE user by email.
     * Used by {@link com.bugeon.fintechledger.auth.security.CustomUserDetailsService}
     * to enforce status checks at load time.
     */
    @Query("SELECT u FROM User u WHERE u.email = :email AND u.status = :status")
    Optional<User> findByEmailAndStatus(@Param("email") String email,
                                        @Param("status") UserStatus status);
}
