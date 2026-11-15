package com.flowcortex.app.support.request;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.user.api.UserService;
import com.flowcortex.user.api.UserView;
import java.net.URI;
import java.util.List;
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

/**
 * {@code flowcortex.media.enabled=false} with the Help Center on: image grants and messages carrying images are
 * refused as 503 {@code attachments-unavailable}, while requests and replies without images still go through.
 * Dedicated context, since the property binds once at startup.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "flowcortex.support.enabled=true",
        "flowcortex.media.enabled=false",
        "flowcortex.support.retention.interval=PT24H"})
@AutoConfigureTestRestTemplate
class SupportAttachmentsMediaOffIT extends AbstractPostgresBootTest {

    private static final URI UNAVAILABLE = URI.create("https://flow-cortex.dev/problems/attachments-unavailable");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserService userService;

    record LoginRequest(String email, String password) {
    }

    record TokenResponse(String accessToken) {
    }

    record UploadRequest(String fileName, Long size) {
    }

    record CreateRequest(String type, String subject, String description, List<UUID> uploadIds) {
    }

    record ReplyRequest(String body, List<UUID> uploadIds) {
    }

    record Problem(int status, URI type, String detail) {
    }

    record DetailView(long number, String status) {
    }

    @Test
    void imagesAreRefusedButARequestAndAReplyWithoutImagesGoThrough() {
        UserView admin = userService.createInitialAdmin(uniqueEmail("media-off-admin"), "password123", "Admin");
        UserView member = userService.createUser(admin.id(), uniqueEmail("media-off"), "password123", "Member",
                "MEMBER");
        String token = login(member.email());

        ResponseEntity<Problem> grant = exchange(HttpMethod.POST, "/api/v1/support/uploads", token,
                new UploadRequest("anh.png", 2048L), Problem.class);
        ResponseEntity<Problem> createWithImage = exchange(HttpMethod.POST, "/api/v1/support/requests", token,
                new CreateRequest("technical", "Ảnh bị lỗi", "Ảnh chụp màn hình lỗi đính kèm.",
                        List.of(UUID.randomUUID())), Problem.class);
        ResponseEntity<DetailView> created = exchange(HttpMethod.POST, "/api/v1/support/requests", token,
                new CreateRequest("technical", "Ảnh bị lỗi", "Không gửi được ảnh nên mô tả bằng chữ.", List.of()),
                DetailView.class);
        String messages = "/api/v1/support/requests/" + created.getBody().number() + "/messages";
        ResponseEntity<Problem> replyWithImage = exchange(HttpMethod.POST, messages, token,
                new ReplyRequest("Thêm ảnh", List.of(UUID.randomUUID())), Problem.class);
        ResponseEntity<DetailView> reply = exchange(HttpMethod.POST, messages, token,
                new ReplyRequest("Bổ sung bằng chữ", List.of()), DetailView.class);

        assertUnavailable(grant);
        assertUnavailable(createWithImage);
        assertUnavailable(replyWithImage);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(reply.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private static void assertUnavailable(ResponseEntity<Problem> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().type()).isEqualTo(UNAVAILABLE);
        assertThat(response.getBody().detail())
                .isEqualTo("Attachments are unavailable right now, you can still send your message.");
    }

    private String login(String email) {
        TokenResponse token = restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(email, "password123"), TokenResponse.class).getBody();
        assertThat(token).isNotNull();
        return token.accessToken();
    }

    private <T> ResponseEntity<T> exchange(HttpMethod method, String path, String token, Object body,
            Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), responseType);
    }

    private static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }
}
