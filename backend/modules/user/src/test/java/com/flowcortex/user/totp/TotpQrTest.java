package com.flowcortex.user.totp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

class TotpQrTest {

    private static final String URI = "otpauth://totp/Nodi:m%40example.com?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=Nodi";

    @Test
    void dataUrlDecodesToAWellFormedSvgWithItsQuietZone() throws Exception {
        String dataUrl = TotpQr.dataUrl(URI);

        assertThat(dataUrl).startsWith("data:image/svg+xml;base64,");
        byte[] svg = Base64.getDecoder().decode(dataUrl.substring(dataUrl.indexOf(',') + 1));

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(svg));

        assertThat(document.getDocumentElement().getLocalName()).isEqualTo("svg");
        assertThat(new String(svg, StandardCharsets.UTF_8)).contains("viewBox=\"0 0 ").contains("<path d=\"M");
        assertThat(document.getElementsByTagNameNS("*", "rect").getLength()).isEqualTo(1);
    }

    @Test
    void theSameUriGivesTheSameImage() {
        assertThat(TotpQr.dataUrl(URI)).isEqualTo(TotpQr.dataUrl(URI));
        assertThat(TotpQr.dataUrl(URI)).isNotEqualTo(TotpQr.dataUrl(URI + "&x=1"));
    }
}
