package com.flowcortex.billing.receipt;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.UUID;
import org.springframework.context.MessageSource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.LocaleResolver;

/**
 * The receipt of a member's own collected payment as JSON and as a PDF in the request's language. The caller is
 * the token's subject and the read is filtered by it, so another member's payment, an unknown one and a
 * malformed identifier all answer the same 404.
 */
@RestController
@RequestMapping("/api/v1/billing/transactions/{transactionId}/receipt")
class ReceiptsController {

    private static final String PDF = "application/pdf";

    private final ReceiptReader reader;
    private final MessageSource messages;
    private final LocaleResolver localeResolver;

    ReceiptsController(ReceiptReader reader, MessageSource messages, LocaleResolver localeResolver) {
        this.reader = reader;
        this.messages = messages;
        this.localeResolver = localeResolver;
    }

    @Operation(operationId = "getBillingReceipt", summary = "Read the receipt of one of the member's payments",
            description = "Amounts come from the tax split stored when the money was collected.")
    @ApiResponse(responseCode = "200", description = "The receipt")
    @ApiResponse(responseCode = "401", description = "Not signed in", content = @Content)
    @ApiResponse(responseCode = "404", description = "Not the member's payment (code billing_receipt_not_found)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "The payment is not collected yet (code"
            + " billing_receipt_not_collected)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ReceiptResponse get(Principal principal, @PathVariable String transactionId) {
        return ReceiptResponse.from(find(principal, transactionId));
    }

    @Operation(operationId = "downloadBillingReceiptPdf", summary = "Download the receipt as a PDF",
            description = "A4 document in the request's language (Accept-Language vi or en).")
    @ApiResponse(responseCode = "200", description = "The receipt PDF, saved as bien-nhan-<orderRef>.pdf",
            content = @Content(mediaType = PDF, schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "401", description = "Not signed in", content = @Content)
    @ApiResponse(responseCode = "404", description = "Not the member's payment (code billing_receipt_not_found)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "The payment is not collected yet (code"
            + " billing_receipt_not_collected)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(value = "/pdf", produces = {PDF, MediaType.APPLICATION_PROBLEM_JSON_VALUE})
    ResponseEntity<byte[]> download(Principal principal, @PathVariable String transactionId,
            HttpServletRequest request) {
        Receipt receipt = find(principal, transactionId);
        byte[] pdf = ReceiptPdf.render(receipt, new ReceiptCopy(messages, localeResolver.resolveLocale(request)));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(PDF))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(receipt.pdfFileName()).build().toString())
                .body(pdf);
    }

    private Receipt find(Principal principal, String transactionId) {
        UUID member = UUID.fromString(principal.getName());
        return reader.findOwn(member, parseId(transactionId)).orElseThrow(ReceiptNotFoundException::new);
    }

    private static UUID parseId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new ReceiptNotFoundException();
        }
    }
}
