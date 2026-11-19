package com.bookworm.user;

import com.bookworm.common.error.ApiException;
import com.bookworm.common.error.ErrorCode;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only way other features reach user data (entities never leave this class). Callers pass an already
 * normalised (trimmed, lower-case) email and an already hashed password.
 */
@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** Creates a CUSTOMER. The role is fixed here and cannot be supplied by any caller. */
    @Transactional
    public ProfileResponse registerCustomer(String email, String passwordHash, String firstName, String lastName,
                                            String phone) {
        if (userRepository.existsByEmail(email)) {
            throw duplicateEmail();
        }
        try {
            return ProfileResponse.from(userRepository.saveAndFlush(
                    User.create(email, passwordHash, firstName, lastName, phone, Role.CUSTOMER)));
        } catch (DataIntegrityViolationException ex) {
            // Lost a race with a concurrent registration of the same email (unique constraint).
            throw duplicateEmail();
        }
    }

    @Transactional(readOnly = true)
    public boolean emailExists(String email) {
        return userRepository.existsByEmail(email);
    }

    /**
     * Inserts the bootstrap administrator only when the email is free. Returns false (and changes nothing)
     * when any account with that email already exists, whatever its role.
     */
    @Transactional
    public boolean createAdminIfAbsent(String email, String passwordHash) {
        return userRepository.insertAdminIfAbsent(email, passwordHash, "Administrator", "Account") == 1;
    }

    @Transactional(readOnly = true)
    public Optional<Credentials> findCredentialsByEmail(String email) {
        return userRepository.findCredentialsByEmail(email);
    }

    @Transactional(readOnly = true)
    public Optional<Credentials> findCredentialsById(Long id) {
        return userRepository.findCredentialsById(id);
    }

    /** Per-request check: the user must exist and be enabled; the role returned is the current database role. */
    @Transactional(readOnly = true)
    public Optional<AuthenticatedUser> findAuthenticatedUser(Long id) {
        return userRepository.findAuthenticatedUser(id);
    }

    @Transactional(readOnly = true)
    public ProfileResponse getProfile(Long id) {
        return ProfileResponse.from(find(id));
    }

    @Transactional
    public ProfileResponse updateProfile(Long id, UpdateProfileRequest request) {
        User user = find(id);
        user.updateProfile(request.firstName().trim(), request.lastName().trim(), request.phone());
        return ProfileResponse.from(userRepository.saveAndFlush(user));
    }

    @Transactional
    public void changePasswordHash(Long id, String newPasswordHash) {
        User user = find(id);
        user.changePasswordHash(newPasswordHash);
        userRepository.saveAndFlush(user);
    }

    /** Atomic lockout bookkeeping; see {@link UserRepository#registerFailedLogin}. */
    @Transactional
    public void registerFailedLogin(Long id, Instant now, int maxAttempts, Instant lockUntil) {
        userRepository.registerFailedLogin(id, now, maxAttempts, lockUntil);
    }

    @Transactional
    public void clearLoginFailures(Long id) {
        userRepository.clearLoginFailures(id);
    }

    private User find(Long id) {
        // A token for an account that has since been removed is not a "not found" resource: it is invalid.
        return userRepository.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "Authentication is required."));
    }

    private static ApiException duplicateEmail() {
        return new ApiException(ErrorCode.DUPLICATE_RESOURCE, "An account with this email already exists.");
    }
}
