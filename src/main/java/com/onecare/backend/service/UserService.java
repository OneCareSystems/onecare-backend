package com.onecare.backend.service;

import com.onecare.backend.dto.response.UserResponse;
import com.onecare.backend.entity.User;

public interface UserService {

    boolean recordFailedAttempt(User user);

    void recordSuccessfulLogin(User user);

    UserResponse unlockAccount(Long id);

    boolean isAccountLocked(User user);
}

