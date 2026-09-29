package com.smartinbox.collector.service.impl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class OutlookDesktopServiceTest {
    @Test void emptyMessageIdUsesDistinctEntryIds() throws Exception {
        var mapper = new ObjectMapper();
        assertNotEquals(OutlookDesktopService.externalId(mapper.readTree("{\"internetMessageId\":\"\",\"entryId\":\"first\"}")),
                OutlookDesktopService.externalId(mapper.readTree("{\"internetMessageId\":\"\",\"entryId\":\"second\"}")));
        assertThrows(IllegalArgumentException.class, () -> OutlookDesktopService.externalId(mapper.readTree("{}")));
    }
}
