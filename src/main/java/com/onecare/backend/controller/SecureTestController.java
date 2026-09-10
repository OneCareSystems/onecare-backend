package com.onecare.backend.controller;

import com.onecare.backend.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/secure")
public class SecureTestController {

    @GetMapping("/ping")
    public ApiResponse<String> ping() {
        return new ApiResponse<>(true, "Secured Endpoint", "pong");
    }
}