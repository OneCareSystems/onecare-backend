
package com.onecare.backend.config;

import com.onecare.backend.security.ApiRateLimitFilter;
import com.onecare.backend.security.AppUserDetailsService;
import com.onecare.backend.security.JwtAuthFilter;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.http.HttpMethod;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;

import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(
        prePostEnabled = true,
        securedEnabled = true
)
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    private final AppUserDetailsService userDetailsService;

    private final ApiRateLimitFilter apiRateLimitFilter;

    /**
     * Comma-separated frontend origins.
     *
     * Example:
     * frontend.allowed-origins=http://localhost:5173
     */
    @Value("${frontend.allowed-origins}")
    private String allowedOrigins;

    /**
     * Argon2 cost parameters. The defaults are production strength; tests lower
     * them (src/test/resources/application.properties) so the per-test user
     * creation and login hashing does not dominate suite runtime.
     */
    @Value("${app.security.argon2.parallelism:1}")
    int argon2Parallelism;

    @Value("${app.security.argon2.memory-kb:65536}")
    int argon2MemoryKb;

    @Value("${app.security.argon2.iterations:2}")
    int argon2Iterations;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter, AppUserDetailsService userDetailsService,
            ApiRateLimitFilter apiRateLimitFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.userDetailsService = userDetailsService;
        this.apiRateLimitFilter = apiRateLimitFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http
    ) throws Exception {

        http

                
                .cors(cors ->
                        cors.configurationSource(
                                corsConfigurationSource()
                        )
                )

                
                .csrf(csrf -> csrf.disable())

                .sessionManagement(session ->
                        session.sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        )
                )

                
                .exceptionHandling(ex -> ex

                        .authenticationEntryPoint(
                                (request, response, authException) ->

                                        response.sendError(
                                                HttpServletResponse.SC_UNAUTHORIZED,
                                                "Unauthorized"
                                        )
                        )

                        .accessDeniedHandler(
                                accessDeniedHandler()
                        )
                )

                .authorizeHttpRequests(auth -> auth

                        .requestMatchers(
                                "/api/auth/login",
                                "/api/auth/refresh",
                                "/api/auth/forgot-password",
                                "/api/auth/reset-password",
                                "/api/public/**",
                                "/v3/api-docs/**",
                                "/swagger-ui/**")
                        .permitAll()
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/chatbot/query"
                        )
                            .permitAll()
                        .requestMatchers("/api/users/**").hasAnyRole("SUPER_ADMIN", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/doctors/*/schedule")
                            .hasAnyRole("SUPER_ADMIN", "DOCTOR")
                        .requestMatchers("/api/doctors/**").hasRole("SUPER_ADMIN")
                        .requestMatchers("/api/roles/**").hasRole("SUPER_ADMIN")
                        .requestMatchers("/api/system/**").hasRole("SUPER_ADMIN")
                        .requestMatchers("/api/patients/**").hasAnyRole("SUPER_ADMIN", "ADMIN", "DOCTOR")
                        .requestMatchers("/api/medicines/**").hasAnyRole("SUPER_ADMIN", "ADMIN", "PHARMACIST")
                        .requestMatchers("/api/prescriptions/**").hasAnyRole("SUPER_ADMIN", "ADMIN", "DOCTOR", "PHARMACIST")
                        // clinical notes are readable by doctors and pharmacists (DDP-20 clinical notes move)
                        .requestMatchers("/api/appointments/*/clinical-notes")
                            .hasAnyRole("DOCTOR", "PHARMACIST")
                        .requestMatchers("/api/appointments/**").hasAnyRole("SUPER_ADMIN", "ADMIN", "DOCTOR")
                        .requestMatchers("/api/invoices/**").hasAnyRole("SUPER_ADMIN", "ADMIN","PHARMACIST")
                        // reports (Table 9): clinic - SUPER_ADMIN/ADMIN, pharmacy - SUPER_ADMIN/PHARMACIST
                        .requestMatchers("/api/reports/clinic/**").hasAnyRole("SUPER_ADMIN", "ADMIN")
                        .requestMatchers("/api/reports/pharmacy/**").hasAnyRole("SUPER_ADMIN", "PHARMACIST")
                        .requestMatchers("/api/audit/**").hasAnyRole("SUPER_ADMIN", "ADMIN")
                        .requestMatchers("/api/secure/**").authenticated()
                        .anyRequest().authenticated())
                .authenticationProvider(authenticationProvider())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(apiRateLimitFilter, JwtAuthFilter.class);

        return http.build();
    }

   
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {

        CorsConfiguration configuration =
                new CorsConfiguration();

        configuration.setAllowedOrigins(

                Arrays.stream(
                                allowedOrigins.split(",")
                        )
                        .map(String::trim)
                        .filter(origin -> !origin.isEmpty())
                        .toList()
        );

        configuration.setAllowedMethods(
                List.of(
                        "GET",
                        "POST",
                        "PUT",
                        "PATCH",
                        "DELETE",
                        "OPTIONS"
                )
        );

        configuration.setAllowedHeaders(
                List.of(
                        "Authorization",
                        "Content-Type",
                        "Accept",
                        "X-Requested-With"
                )
        );

      
        configuration.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();

        source.registerCorsConfiguration(
                "/**",
                configuration
        );

        return source;
    }

   
    @Bean
    public AccessDeniedHandler accessDeniedHandler() {

        return (request, response, accessDeniedException) -> {

            response.setStatus(
                    HttpServletResponse.SC_FORBIDDEN
            );

            response.setContentType(
                    "application/json"
            );

            response.setCharacterEncoding("UTF-8");

            response.getWriter().write(
                    "{\"success\":false,"
                            + "\"message\":\"Access denied: insufficient permissions\","
                            + "\"data\":null}"
            );
        };
    }

   
    @Bean
    public PasswordEncoder passwordEncoder() {

        return new Argon2PasswordEncoder(
                16,                 // salt length
                32,                 // hash length
                argon2Parallelism,  // parallelism
                argon2MemoryKb,     // memory in KB
                argon2Iterations    // iterations
        );
    }

    
    @Bean
    public DaoAuthenticationProvider authenticationProvider() {

        DaoAuthenticationProvider provider =
                new DaoAuthenticationProvider(
                        userDetailsService
                );

        provider.setPasswordEncoder(
                passwordEncoder()
        );

        return provider;
    }

    
    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration configuration
    ) throws Exception {

        return configuration.getAuthenticationManager();
    }
}
