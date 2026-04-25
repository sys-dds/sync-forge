package com.syncforge.api.identity.api;

import java.util.UUID;

import com.syncforge.api.identity.application.UserService;
import com.syncforge.api.shared.RequestValidator;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {
    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(@RequestBody CreateUserRequest request) {
        return UserResponse.from(userService.create(request));
    }

    @GetMapping("/{userId}")
    public UserResponse get(@PathVariable String userId) {
        UUID id = RequestValidator.parseUuid(userId, "userId");
        return UserResponse.from(userService.get(id));
    }
}
