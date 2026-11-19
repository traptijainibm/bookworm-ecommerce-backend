package com.bookworm.user;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** All queries are parameterized. The native statements below are the only writers of the lockout columns. */
public interface UserRepository extends JpaRepository<User, Long> {

    boolean existsByEmail(String email);

    /**
     * The current-user check run on every authenticated request: the account must still exist and be enabled.
     * Returns the CURRENT role from the database, so deletions, disabling and role changes apply immediately.
     */
    @Query("select new com.bookworm.user.AuthenticatedUser(u.id, u.role) from User u "
            + "where u.id = :id and u.enabled = true")
    Optional<AuthenticatedUser> findAuthenticatedUser(@Param("id") Long id);

    @Query("select new com.bookworm.user.Credentials(u.id, u.passwordHash, u.role, u.enabled, u.lockedUntil) "
            + "from User u where u.email = :email")
    Optional<Credentials> findCredentialsByEmail(@Param("email") String email);

    @Query("select new com.bookworm.user.Credentials(u.id, u.passwordHash, u.role, u.enabled, u.lockedUntil) "
            + "from User u where u.id = :id")
    Optional<Credentials> findCredentialsById(@Param("id") Long id);

    /**
     * Atomic failed-login registration: one statement, so parallel wrong guesses cannot lose increments.
     * An expired lock counts as a counter of 0. Reaching {@code maxAttempts} sets the lock. A row that is
     * currently locked is not touched at all, so the counter does not move while the account is locked.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update users set
                failed_login_attempts =
                    (case when locked_until is not null and locked_until <= cast(:now as timestamptz) then 0
                          else failed_login_attempts end) + 1,
                locked_until = case
                    when (case when locked_until is not null and locked_until <= cast(:now as timestamptz) then 0
                               else failed_login_attempts end) + 1 >= cast(:maxAttempts as integer)
                        then cast(:lockUntil as timestamptz)
                    else null end,
                updated_at = cast(:now as timestamptz)
            where id = :id and (locked_until is null or locked_until <= cast(:now as timestamptz))
            """, nativeQuery = true)
    int registerFailedLogin(@Param("id") Long id, @Param("now") Instant now,
                            @Param("maxAttempts") int maxAttempts, @Param("lockUntil") Instant lockUntil);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update users set failed_login_attempts = 0, locked_until = null
            where id = :id and (failed_login_attempts <> 0 or locked_until is not null)
            """, nativeQuery = true)
    int clearLoginFailures(@Param("id") Long id);

    /**
     * Admin bootstrap insert. {@code ON CONFLICT DO NOTHING} makes it idempotent and guarantees that an
     * existing account (of any role) is never modified, promoted or given new credentials.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            insert into users (email, password_hash, first_name, last_name, role, enabled)
            values (:email, :passwordHash, :firstName, :lastName, 'ADMIN', true)
            on conflict (email) do nothing
            """, nativeQuery = true)
    int insertAdminIfAbsent(@Param("email") String email, @Param("passwordHash") String passwordHash,
                            @Param("firstName") String firstName, @Param("lastName") String lastName);
}
