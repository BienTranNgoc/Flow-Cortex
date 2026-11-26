package com.flowcortex.app.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.app.workflow.AbstractWorkflowHttpIT;
import com.flowcortex.user.api.UserView;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "flowcortex.billing.receipt.seller.legal-name=Công ty TNHH Nodi",
        "flowcortex.billing.receipt.seller.tax-code=0312345678",
        "flowcortex.billing.receipt.seller.address=12 Nguyễn Huệ, Quận 1, TP.HCM",
        "flowcortex.billing.receipt.seller.email=billing@nodi.vn"
})
@AutoConfigureTestRestTemplate
class ReceiptsIT extends AbstractWorkflowHttpIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant PAID_AT = Instant.parse("2026-12-31T17:05:00Z");

    @Autowired
    private JdbcTemplate jdbc;

    private UserView memberA;
    private UserView memberB;
    private String tokenA;
    private UUID collected;
    private String collectedRef;
    private UUID pending;
    private UUID foreign;

    @BeforeEach
    void payments() {
        memberA = newUser("receipt-a");
        memberB = newUser("receipt-b");
        tokenA = loginAs(memberA);
        collected = ReceiptFixtures.collected(jdbc, memberA.id(), "Nạp 1.000 credit", 100_000, 90_909, 9_091, PAID_AT);
        collectedRef = "fc_ffe" + collected.toString().replace("-", "").substring(0, 29);
        jdbc.update("UPDATE payment_transaction SET order_ref = ? WHERE id = ?", collectedRef, collected);
        pending = ReceiptFixtures.pending(jdbc, memberA.id(), "Nạp 500 credit", 50_000, PAID_AT);
        foreign = ReceiptFixtures.collected(jdbc, memberB.id(), "Nạp 2.000 credit", 200_000, 181_818, 18_182, PAID_AT);
    }

    @AfterEach
    void clean() {
        for (UUID member : List.of(memberA.id(), memberB.id())) {
            jdbc.update("DELETE FROM payment_transaction WHERE user_id = ?", member);
        }
    }

    private ResponseEntity<byte[]> fetch(String path, String token, String language) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        if (language != null) {
            headers.set(HttpHeaders.ACCEPT_LANGUAGE, language);
        }
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
    }

    private static JsonNode json(ResponseEntity<byte[]> response) throws IOException {
        return JSON.readTree(response.getBody());
    }

    private static String pdfText(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document).replaceAll("\\s+", " ");
        }
    }

    @Test
    void aCollectedPaymentAnswersTheStoredSplitAndTheAmountInWords() throws Exception {
        ResponseEntity<byte[]> response = fetch("/api/v1/billing/transactions/" + collected + "/receipt", tokenA, "vi");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode receipt = json(response);
        assertThat(receipt.get("transactionId").asText()).isEqualTo(collected.toString());
        assertThat(receipt.get("orderRef").asText()).isEqualTo(collectedRef);
        assertThat(receipt.get("kind").asText()).isEqualTo("topup");
        assertThat(Instant.parse(receipt.get("paidAt").asText())).isEqualTo(PAID_AT);
        assertThat(receipt.get("provider").asText()).isEqualTo("momo");
        assertThat(receipt.get("instrument").asText()).isEqualTo("wallet");
        assertThat(receipt.get("providerTransactionId").asText()).isEqualTo("4088878653");
        assertThat(receipt.get("lines")).hasSize(1);
        JsonNode line = receipt.get("lines").get(0);
        assertThat(line.get("description").asText()).isEqualTo("Nạp 1.000 credit");
        assertThat(line.get("quantity").asInt()).isEqualTo(1);
        assertThat(line.get("unitPriceVnd").asLong()).isEqualTo(90_909);
        assertThat(line.get("amountVnd").asLong()).isEqualTo(90_909);
        assertThat(receipt.get("subtotalVnd").asLong()).isEqualTo(90_909);
        assertThat(receipt.get("vatRateBps").asInt()).isEqualTo(1_000);
        assertThat(receipt.get("vatAmountVnd").asLong()).isEqualTo(9_091);
        assertThat(receipt.get("totalVnd").asLong()).isEqualTo(100_000);
        assertThat(receipt.get("currency").asText()).isEqualTo("VND");
        assertThat(receipt.get("totalInWords").asText()).isEqualTo("Một trăm nghìn đồng");
    }

    @Test
    void theAmountsAreTheStoredSplitNeverARecomputation() throws Exception {
        UUID odd = ReceiptFixtures.collected(jdbc, memberA.id(), "Nạp 1.000 credit", 100_000, 90_000, 10_000, PAID_AT);

        JsonNode receipt = json(fetch("/api/v1/billing/transactions/" + odd + "/receipt", tokenA, "vi"));

        assertThat(receipt.get("subtotalVnd").asLong()).isEqualTo(90_000);
        assertThat(receipt.get("vatAmountVnd").asLong()).isEqualTo(10_000);
        assertThat(receipt.get("lines").get(0).get("amountVnd").asLong()).isEqualTo(90_000);
    }

    @Test
    void theConfiguredSellerIsPrinted() throws Exception {
        JsonNode seller = json(fetch("/api/v1/billing/transactions/" + collected + "/receipt", tokenA, "vi"))
                .get("seller");

        assertThat(seller.get("legalName").asText()).isEqualTo("Công ty TNHH Nodi");
        assertThat(seller.get("taxCode").asText()).isEqualTo("0312345678");
        assertThat(seller.get("address").asText()).isEqualTo("12 Nguyễn Huệ, Quận 1, TP.HCM");
        assertThat(seller.get("email").asText()).isEqualTo("billing@nodi.vn");
    }

    @Test
    void aPendingPaymentOfTheMemberAnswers409OnBothRoutes() throws Exception {
        for (String suffix : List.of("/receipt", "/receipt/pdf")) {
            ResponseEntity<byte[]> response = fetch("/api/v1/billing/transactions/" + pending + suffix, tokenA, "en");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(json(response).get("code").asText()).isEqualTo("billing_receipt_not_collected");
        }
    }

    @Test
    void anotherMembersPaymentARandomIdAndANonUuidAllAnswerTheSame404() throws Exception {
        List<String> ids = List.of(foreign.toString(), UUID.randomUUID().toString(), "abc");
        for (String suffix : List.of("/receipt", "/receipt/pdf")) {
            List<String> details = new java.util.ArrayList<>();
            for (String id : ids) {
                ResponseEntity<byte[]> response = fetch("/api/v1/billing/transactions/" + id + suffix, tokenA, "en");

                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                JsonNode problem = json(response);
                assertThat(problem.get("code").asText()).isEqualTo("billing_receipt_not_found");
                details.add(problem.get("detail").asText());
            }
            assertThat(details).hasSize(3).containsOnly(details.getFirst());
        }
    }

    @Test
    void noTokenIs401OnBothRoutes() {
        assertThat(fetch("/api/v1/billing/transactions/" + collected + "/receipt", null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(fetch("/api/v1/billing/transactions/" + collected + "/receipt/pdf", null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void theEnglishPdfCarriesTheOrderReferenceTheAmountAndItsFileName() throws Exception {
        ResponseEntity<byte[]> response = fetch("/api/v1/billing/transactions/" + collected + "/receipt/pdf", tokenA,
                "en");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("application/pdf");
        assertThat(response.getHeaders().getContentDisposition().getFilename()).isEqualTo("bien-nhan-" + collectedRef
                + ".pdf");
        assertThat(new String(response.getBody(), 0, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        assertThat(pdfText(response.getBody())).contains("Payment successful", collectedRef, "100.000 ₫",
                "Công ty TNHH Nodi", "This receipt confirms that Nodi collected the amount above and serves as an "
                        + "electronic payment record. It is generated automatically.");
    }

    @Test
    void aVietnameseRequestGetsVietnameseLabels() throws Exception {
        ResponseEntity<byte[]> response = fetch("/api/v1/billing/transactions/" + collected + "/receipt/pdf", tokenA,
                "vi");

        assertThat(pdfText(response.getBody())).contains("Thanh toán thành công", "Thuế GTGT (10%)", collectedRef,
                "Biên nhận này xác nhận Nodi đã thu khoản tiền trên và có giá trị như chứng từ thanh toán điện tử.");
    }

    @Test
    void aLanguageThePlatformDoesNotServeGetsTheEnglishLabelsAsEveryOtherAnswer() throws Exception {
        ResponseEntity<byte[]> response = fetch("/api/v1/billing/transactions/" + collected + "/receipt/pdf", tokenA,
                "fr");
        ResponseEntity<byte[]> missing = fetch("/api/v1/billing/transactions/" + foreign + "/receipt/pdf", tokenA,
                "fr");

        assertThat(pdfText(response.getBody())).contains("Payment successful");
        assertThat(json(missing).get("detail").asText()).isEqualTo("No such payment for this account");
    }
}
