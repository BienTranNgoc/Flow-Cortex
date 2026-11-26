package com.flowcortex.app.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.user.api.UserService;
import com.flowcortex.user.api.UserView;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The two catalogue GETs answer without a token, the same with or without one. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class CatalogueEndpointIT extends AbstractPostgresBootTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    record LoginRequest(String email, String password) {
    }

    record TokenResponse(String accessToken) {
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserService userService;

    @Test
    void anAnonymousCallerReadsTheSeededPlans() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/billing/plans", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = JSON.readTree(response.getBody()).get("data");
        assertThat(data).extracting(plan -> plan.get("id").asString())
                .containsExactly("free", "basic", "advanced", "professional");
        JsonNode basic = data.get(1);
        assertThat(basic.get("isPopular").asBoolean()).isFalse();
        assertThat(basic.get("cycles")).hasSize(1);
        assertThat(basic.get("cycles").get(0).get("billingCycle").asString()).isEqualTo("monthly");
        assertThat(basic.get("cycles").get(0).get("priceVnd").asLong()).isEqualTo(849_000);
        assertThat(basic.get("cycles").get(0).get("includedCreditsPerMonth").asLong()).isEqualTo(45_000);
        assertThat(basic.get("cycles").get(0).has("id")).isFalse();
    }

    @Test
    void anAnonymousCallerReadsTheSeededPackagesWithNullEnglishCopy() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/billing/topup-packages", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = JSON.readTree(response.getBody()).get("data");
        assertThat(data).hasSize(8);
        assertThat(data.get(0).get("name").asString()).isEqualTo("Trải Nghiệm");
        assertThat(data.get(0).get("groupKey").isNull()).isTrue();
        assertThat(data.get(0).get("nameEn").isNull()).isTrue();
        assertThat(data.get(7).get("groupKey").asString()).isEqualTo("flexible");
    }

    @Test
    void aTokenDoesNotChangeTheAnswer() {
        UserView member = userService.createInitialAdmin("catalogue-" + UUID.randomUUID() + "@example.com",
                "password123", "Member");
        String token = restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(member.email(), "password123"), TokenResponse.class).getBody().accessToken();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);

        for (String route : new String[] {"/api/v1/billing/plans", "/api/v1/billing/topup-packages"}) {
            ResponseEntity<String> anonymous = restTemplate.getForEntity(route, String.class);
            ResponseEntity<String> signedIn = restTemplate.exchange(route, HttpMethod.GET, new HttpEntity<>(headers),
                    String.class);

            assertThat(signedIn.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(signedIn.getBody()).isEqualTo(anonymous.getBody());
        }
    }

    @Test
    void otherMethodsOnTheCataloguePathsStayBehindTheSharedChain() {
        ResponseEntity<String> response = restTemplate.postForEntity("/api/v1/billing/plans", null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
