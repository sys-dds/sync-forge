package com.syncforge.api.identity.application;

import java.util.UUID;

import com.syncforge.api.identity.api.CreateUserRequest;
import com.syncforge.api.identity.model.User;
import com.syncforge.api.identity.store.UserRepository;
import com.syncforge.api.shared.BadRequestException;
import com.syncforge.api.shared.ConflictException;
import com.syncforge.api.shared.NotFoundException;
import com.syncforge.api.shared.RequestValidator;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class UserService {
    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User create(CreateUserRequest request) {
        if (request == null) {
            throw new BadRequestException("INVALID_REQUEST", "Request body is required");
        }
        String externalUserKey = RequestValidator.requiredText(request.externalUserKey(), "externalUserKey");
        String displayName = RequestValidator.requiredText(request.displayName(), "displayName");
        try {
            return userRepository.create(externalUserKey, displayName);
        } catch (DuplicateKeyException exception) {
            throw new ConflictException("USER_ALREADY_EXISTS", "External user key already exists");
        }
    }

    public User get(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("USER_NOT_FOUND", "User not found"));
    }
}
