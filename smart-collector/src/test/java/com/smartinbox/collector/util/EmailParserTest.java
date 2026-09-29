package com.smartinbox.collector.util;

import jakarta.mail.*;
import jakarta.mail.internet.*;
import org.junit.jupiter.api.Test;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

class EmailParserTest {
    @Test void keepsTextAndHtmlButNotTextAttachments() throws Exception {
        var message = new MimeMessage(Session.getInstance(new Properties()));
        var parts = new MimeMultipart("mixed");
        var plain = new MimeBodyPart(); plain.setText("Arriving today 中文", "UTF-8"); parts.addBodyPart(plain);
        var rich = new MimeBodyPart(); rich.setContent("<table><tr><td>Order $45.19<img src=\"https://example.com/product.jpg\"></td></tr></table>", "text/html; charset=UTF-8"); parts.addBodyPart(rich);
        var attachment = new MimeBodyPart(); attachment.setText("NOT BODY"); attachment.setDisposition(Part.ATTACHMENT); parts.addBodyPart(attachment);
        message.setContent(parts); message.saveChanges();
        var body = EmailParser.parse(message);
        assertEquals("Arriving today 中文", body.text());
        assertTrue(body.html().contains("product.jpg"));
        assertFalse(body.text().contains("NOT BODY"));
    }
    @Test void htmlOnlyMessagesKeepFullBody() throws Exception {
        var message = new MimeMessage(Session.getInstance(new Properties()));
        String rich = "<p>" + "完整内容".repeat(2000) + "</p>";
        message.setContent(rich, "text/html; charset=UTF-8"); message.saveChanges();
        assertEquals(rich, EmailParser.parse(message).html());
    }
}
