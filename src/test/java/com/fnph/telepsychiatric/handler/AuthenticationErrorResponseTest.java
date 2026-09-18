package com.fnph.telepsychiatric.handler;

import com.fnph.telepsychiatric.session.AuthenticationService;
import com.fnph.telepsychiatric.session.api.AuthController;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthenticationErrorResponseTest {
    private final AuthenticationService authenticationService = mock(AuthenticationService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AuthController(authenticationService))
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
        when(authenticationService.login(any(), any()))
                .thenThrow(new AuthenticationService.AuthenticationFailedException(message));
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"test-user\",\"password\":\"incorrect-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("status").value(401))
                .andExpect(jsonPath("error").value("Unauthorized"))
                .andExpect(jsonPath("message").value(message))
                .andExpect(jsonPath("path").value("/api/v1/auth/login"));
    }

}
