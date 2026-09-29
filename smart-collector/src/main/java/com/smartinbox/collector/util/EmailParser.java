package com.smartinbox.collector.util;
import jakarta.mail.*;
import java.util.*;
/** Keeps original HTML separate from the text used by the summarizer. */
public final class EmailParser {
    public record Body(String text, String html) {}
    public static Body parse(Part message) throws Exception {
        List<String> text = new ArrayList<>();
        List<String> html = new ArrayList<>();
        Map<String, String> images = new HashMap<>();
        collect(message, text, html, images, new int[]{0}, 0);
        String rich = String.join("\n", html);
        for (var image : images.entrySet()) rich = rich.replace("cid:" + image.getKey(), image.getValue());
        return new Body(text.isEmpty() ? rich : String.join("\n", text), rich);
    }
    public static String getTextFromMessage(Message message) {
        try { return parse(message).text(); }
        catch (Exception exception) { throw new IllegalStateException("Could not parse mail body", exception); }
    }
    private static void collect(Part part, List<String> text, List<String> html,
            Map<String, String> images, int[] imageBytes, int depth) throws Exception {
        if (depth > 20 || Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) return;
        if (part.isMimeType("text/plain")) text.add(String.valueOf(part.getContent()));
        else if (part.isMimeType("text/html")) html.add(String.valueOf(part.getContent()));
        else if (part.isMimeType("multipart/*")) {
            Multipart parts = (Multipart) part.getContent();
            for (int i = 0; i < parts.getCount(); i++) collect(parts.getBodyPart(i), text, html, images, imageBytes, depth + 1);
        } else if (part.isMimeType("image/png") || part.isMimeType("image/jpeg") || part.isMimeType("image/gif") || part.isMimeType("image/webp")) {
            String[] ids = part.getHeader("Content-ID");
            if (ids == null || ids.length == 0 || imageBytes[0] >= 512_000) return;
            try (var stream = part.getInputStream()) {
                byte[] bytes = stream.readNBytes(512_001 - imageBytes[0]);
                if (bytes.length + imageBytes[0] > 512_000) return;
                imageBytes[0] += bytes.length;
                String type = part.getContentType().split(";")[0].trim().toLowerCase(Locale.ROOT);
                images.put(ids[0].replace("<", "").replace(">", ""), "data:" + type + ";base64," + Base64.getEncoder().encodeToString(bytes));
            }
        }
    }
}
