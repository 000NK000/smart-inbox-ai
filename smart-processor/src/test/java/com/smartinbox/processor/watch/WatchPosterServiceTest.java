package com.smartinbox.processor.watch;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.io.*;
import java.net.http.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WatchPosterServiceTest {
    private final WatchService rankings = mock(WatchService.class);
    private final HttpClient client = mock(HttpClient.class);
    private final WatchPosterService posters = new WatchPosterService(rankings, client);
    private static final String IMAGE = "https://img3.doubanio.com/view/photo/m_ratio_poster/public/p2935294362.jpg";

    @SuppressWarnings("unchecked")
    private void response(int status, String type, byte[] bytes) throws Exception {
        when(rankings.doubanPosterUrl("35322132")).thenReturn(Optional.of(IMAGE));
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type", List.of(type)), (a, b) -> true));
        when(response.body()).thenReturn(new ByteArrayInputStream(bytes));
        doReturn(response).when(client).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test void servesKnownImageWithRefererAndReusesCache() throws Exception {
        byte[] jpeg = new byte[20]; jpeg[0] = (byte) 255; jpeg[1] = (byte) 216; jpeg[2] = (byte) 255;
        response(200, "image/jpeg", jpeg);
        assertArrayEquals(jpeg, posters.get("35322132").bytes());
        assertEquals("image/jpeg", posters.get("35322132").contentType());
        verify(client, times(1)).send(argThat(request -> request.uri().toString().equals(IMAGE)
                && request.headers().firstValue("Referer").orElse("").equals("https://m.douban.com/")), any());
    }

    @Test void rejectsUnknownIdsAndNonDoubanAddressesBeforeNetwork() {
        when(rankings.doubanPosterUrl("123")).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> posters.get("123")).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> posters.get("https://localhost/")).getStatusCode().value());
        for (String url : List.of("http://127.0.0.1/secret", "https://img3.doubanio.com.evil.test/view/photo/m/public/p1.jpg",
                "https://img3.doubanio.com:8080/view/photo/m/public/p1.jpg", "https://img3.doubanio.com/view/photo/m/public/p1.svg",
                "https://img3.doubanio.com/view/photo/m/public/p1.jpg?url=http://localhost/")) {
            when(rankings.doubanPosterUrl("456")).thenReturn(Optional.of(url));
            assertThrows(ResponseStatusException.class, () -> posters.get("456"));
        }
        verifyNoInteractions(client);
    }

    @Test void refusesRedirectsHtmlAndOversizedImages() throws Exception {
        response(302, "image/jpeg", new byte[20]);
        assertThrows(ResponseStatusException.class, () -> posters.get("35322132"));
        response(200, "text/html", "<html>blocked</html>".getBytes());
        assertThrows(ResponseStatusException.class, () -> posters.get("35322132"));
        response(200, "image/jpeg", new byte[WatchPosterService.MAX_IMAGE_BYTES + 1]);
        assertThrows(ResponseStatusException.class, () -> posters.get("35322132"));
        response(200, "image/jpeg", "<html>not an image</html>".getBytes());
        assertThrows(ResponseStatusException.class, () -> posters.get("35322132"));
    }
}
