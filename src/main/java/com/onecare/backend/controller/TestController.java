package com.onecare.backend.controller;

import com.onecare.backend.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/test")
public class TestController {

    @GetMapping
    public ApiResponse<String> test() {
        return new ApiResponse<>(
                true,
                "Application Is Running",
                "OK"
        );
    }

    @GetMapping("/error")
    public String error() {
        throw new RuntimeException("Test exception");
    }
}