package com.fnph.telepsychiatric.handler;

import com.fnph.telepsychiatric.session.AuthenticationService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthenticationErrorResponseTest {
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new RejectedLoginController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @ParameterizedTest
    @ValueSource(strings = {
            "Incorrect username or password. Please check your details and try again.",
            "Too many attempts. Wait a few minutes before trying again.",
            "That code is not correct."
    })
    void rejectedAuthenticationReturnsAnUnauthorizedResponseWithTheDisplayMessage(String message)
            throws Exception {
        mvc.perform(post("/api/v1/auth/login").param("message", message))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("status").value(401))
                .andExpect(jsonPath("error").value("Unauthorized"))
                .andExpect(jsonPath("message").value(message))
                .andExpect(jsonPath("path").value("/api/v1/auth/login"));
    }

    @RestController
    static class RejectedLoginController {
        @PostMapping("/api/v1/auth/login")
        public void login(@RequestParam("message") String message) {
            throw new AuthenticationService.AuthenticationFailedException(message);
        }
    }
}
