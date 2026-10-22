package com.campus.studyroom.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class InternalServiceAuthenticationProviderTest {
  final String secret = "random-test-machine-secret-64b97859369541848dd73028b34f8f2b";
  final InternalServiceAuthenticationProvider provider =
      new InternalServiceAuthenticationProvider("repair-service", secret);

  @Test
  void validMachineCredentialsAreAuthenticatedWithoutRetainingSecret() {
    var result =
        provider.authenticate(
            UsernamePasswordAuthenticationToken.unauthenticated("repair-service", secret));
    assertTrue(result.isAuthenticated());
    assertEquals("repair-service", result.getName());
    assertNull(result.getCredentials());
    assertEquals("SERVICE", result.getAuthorities().iterator().next().getAuthority());
  }

  @Test
  void unknownUsernameWrongSecretAndEmptyCredentialsAreRejected() {
    for (String[] input :
        new String[][] {
          {"unknown", secret},
          {"repair-service", secret + "x"},
          {"", ""},
          {"repair-service", ""},
          {"", secret}
        }) {
      assertThrows(
          BadCredentialsException.class,
          () ->
              provider.authenticate(
                  UsernamePasswordAuthenticationToken.unauthenticated(input[0], input[1])));
    }
    assertThrows(
        BadCredentialsException.class,
        () ->
            provider.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated("repair-service", null)));
    assertThrows(
        IllegalStateException.class,
        () -> new InternalServiceAuthenticationProvider("repair-service", ""));
  }

  @Test
  void humanPasswordsContinueToUseBcryptCost12() {
    var encoder = new SecurityConfiguration().passwordEncoder();
    String encoded = encoder.encode("HumanPassword123!");
    assertTrue(encoded.startsWith("{bcrypt}$2a$12$"));
    assertTrue(encoder.matches("HumanPassword123!", encoded));
    assertFalse(encoder.matches("incorrect", encoded));
  }
}
