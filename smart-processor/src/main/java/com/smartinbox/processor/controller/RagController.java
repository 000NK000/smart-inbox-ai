package com.smartinbox.processor.controller;

import com.smartinbox.processor.service.AiService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chat")
public class RagController {

    private final AiService aiService;

    public RagController(AiService aiService) {
        this.aiService = aiService;
    }

    @PostMapping
    public String chat(@RequestBody java.util.Map<String, String> payload) {
        String query = payload.get("query");
        if (query == null || query.isBlank()) {
            return "Please provide a query.";
        }

        // 1. Retrieve similar documents
        String context = aiService.retrieveMailContext(query);
        if (context == null) {
            return "RAG feature is currently disabled (Milvus connection unavailable).";
        }
        // 3. Call AI
        return aiService.chatWithContext(context, query);
    }
}
