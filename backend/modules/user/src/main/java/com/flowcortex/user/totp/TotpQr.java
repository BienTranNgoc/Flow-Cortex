package com.flowcortex.user.totp;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/** Renders an {@code otpauth://} URI as an SVG QR code, returned as a {@code data:} URL. */
final class TotpQr {

    private static final int QUIET_ZONE = 4;

    private TotpQr() {
    }

    static String dataUrl(String otpauthUri) {
        BitMatrix matrix;
        try {
            matrix = new QRCodeWriter().encode(otpauthUri, BarcodeFormat.QR_CODE, 0, 0, Map.of(
                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                    EncodeHintType.MARGIN, QUIET_ZONE,
                    EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name()));
        } catch (WriterException ex) {
            throw new IllegalStateException("The authenticator URI does not fit a QR code", ex);
        }
        return "data:image/svg+xml;base64,"
                + Base64.getEncoder().encodeToString(svg(matrix).getBytes(StandardCharsets.UTF_8));
    }

    /** One white background and one path of the dark modules, one run per row; the matrix already holds the quiet zone. */
    private static String svg(BitMatrix matrix) {
        int width = matrix.getWidth();
        int height = matrix.getHeight();
        StringBuilder path = new StringBuilder();
        for (int y = 0; y < height; y++) {
            int x = 0;
            while (x < width) {
                if (!matrix.get(x, y)) {
                    x++;
                    continue;
                }
                int start = x;
                while (x < width && matrix.get(x, y)) {
                    x++;
                }
                path.append('M').append(start).append(' ').append(y)
                        .append('h').append(x - start).append("v1h-").append(x - start).append('z');
            }
        }
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + width + ' ' + height
                + "\" shape-rendering=\"crispEdges\"><rect width=\"" + width + "\" height=\"" + height
                + "\" fill=\"#fff\"/><path d=\"" + path + "\" fill=\"#000\"/></svg>";
    }
}
