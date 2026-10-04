package com.smartinbox.processor.stocks;

import com.smartinbox.processor.stocks.gpt.StockGptService;
import com.smartinbox.processor.stocks.ibkr.StockIbkrService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import java.util.*;

@RestController @RequestMapping("/api/stocks")
public class StockController {
    private final StockService service;
    private final StockIbkrService ibkr;
    private final StockGptService gpt;
    private final StockSessionFilter sessions;
    public StockController(StockService service,StockIbkrService ibkr,StockGptService gpt,StockSessionFilter sessions) {
        this.service=service;this.ibkr=ibkr;this.gpt=gpt;this.sessions=sessions;
    }
    @GetMapping("/session") public Object session() { return Map.of("token",sessions.issue(),"expiresInSeconds",1800); }
    @GetMapping("/status") public Object status() {
        var broker=ibkr.status(); var ai=gpt.status();
        List<Map<String,String>> models=List.of(); String modelMessage="";
        if (Boolean.TRUE.equals(ai.get("connected"))) {
            try { models=gpt.models(); } catch (RuntimeException ignored) { modelMessage="GPT 模型列表暂不可用，请重新连接或稍后刷新"; }
        }
        return Map.of("ibkr",broker,"gpt",ai,"models",models,"modelMessage",modelMessage,
                "canAnalyze",Boolean.TRUE.equals(broker.get("connected"))&&Boolean.TRUE.equals(ai.get("connected"))&&!models.isEmpty());
    }
    @PostMapping("/auth/{provider}/start") public Object start(@PathVariable String provider,@RequestBody(required=false) Map<String,String> options) {
        return switch(provider) {case "ibkr"->ibkr.beginAuthorization();case "gpt"->options!=null&&options.containsKey("registrationId")?gpt.beginAuthorization(options.get("registrationId")):gpt.beginAuthorization();default->throw badProvider();};
    }
    @PostMapping("/auth/gpt/select") public Object selectGpt(@RequestBody Map<String,String> options) { return gpt.selectAccount(options.get("registrationId")); }
    @PostMapping("/auth/{provider}/disconnect") public Object disconnect(@PathVariable String provider) {
        if (provider.equals("gpt")) return gpt.disconnect();
        switch(provider) {case "ibkr"->{ibkr.disconnect();service.clearPortfolio();}default->throw badProvider();}
        return Map.of("disconnected",true);
    }
    @GetMapping(value="/auth/{provider}/callback",produces=MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> callback(@PathVariable String provider,@RequestParam Map<String,String> query) {
        try {
            switch(provider) {case "ibkr"->{ibkr.finishAuthorization(query);service.clearPortfolio();}case "gpt"->gpt.finishAuthorization(query);default->throw badProvider();}
            return callbackPage(true);
        } catch(RuntimeException ignored) { return callbackPage(false); }
    }
    private ResponseEntity<String> callbackPage(boolean success) {
        String title=success?"连接完成 / Connected":"连接未完成 / Connection not completed";
        String description=success?"请关闭此窗口，返回股票分析中心并点击刷新连接。":"请返回股票分析中心重试。授权可能已取消、过期，或当前账户尚不支持。";
        return ResponseEntity.status(success?200:400).header("Cache-Control","no-store").header("Referrer-Policy","no-referrer")
                .header("Content-Security-Policy","default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'")
                .body("<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Smart Inbox</title><style>body{background:#eef4fb;color:#213954;font:18px system-ui;padding:12vh 8vw}main{max-width:620px;margin:auto;padding:40px;background:white;border-radius:24px}p{line-height:1.8}</style><main><small>SMART INBOX · STOCKS</small><h1>"+title+"</h1><p>"+description+"</p></main></html>");
    }
    @GetMapping("/portfolio") public Object portfolio() { return service.portfolio(false); }
    @PostMapping("/portfolio/refresh") public Object refresh() { return service.portfolio(true); }
    @GetMapping("/history") public Object history(@RequestParam(defaultValue="PORTFOLIO") String scope,
            @RequestParam(defaultValue="1M") String range, @RequestParam(required=false) Long contractId,
            @RequestParam(defaultValue="false") boolean refresh) { return service.history(scope,range,contractId,refresh); }
    @GetMapping("/watchlist") public Object watchlist() { return service.watchlist(); }
    @PostMapping("/watchlist") @ResponseStatus(HttpStatus.CREATED) public Object create(@RequestBody StockService.WatchRequest body) { return service.create(body); }
    @PutMapping("/watchlist/{id}") public Object update(@PathVariable String id,@RequestBody StockService.WatchRequest body) { return service.update(id,body); }
    @DeleteMapping("/watchlist/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable String id,@RequestParam Long version) { service.delete(id,version); }
    @GetMapping("/reports") public Object reports() { return service.reports(); }
    @PostMapping("/reports") @ResponseStatus(HttpStatus.CREATED) public Object analyze(@RequestBody StockService.AnalysisRequest body) { return service.analyze(body); }
    @DeleteMapping("/reports/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void deleteReport(@PathVariable String id) { service.deleteReport(id); }
    private ResponseStatusException badProvider() { return new ResponseStatusException(HttpStatus.BAD_REQUEST,"未知连接类型"); }
    @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<?> requested(ResponseStatusException error) { return ResponseEntity.status(error.getStatusCode()).body(Map.of("message",Objects.requireNonNullElse(error.getReason(),"请求失败"))); }
    @ExceptionHandler({DataIntegrityViolationException.class,ObjectOptimisticLockingFailureException.class}) public ResponseEntity<?> conflict() { return ResponseEntity.status(409).body(Map.of("message","记录重复或已更新，请刷新后重试")); }
    @ExceptionHandler(HttpMessageNotReadableException.class) public ResponseEntity<?> invalid() { return ResponseEntity.badRequest().body(Map.of("message","请求格式无效")); }
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> badArgument() { return ResponseEntity.badRequest().body(Map.of("message","请求参数无效，请检查输入")); }
    @ExceptionHandler(Exception.class) public ResponseEntity<?> unavailable(Exception exception) {
        // Provider services use safe Chinese errors; never echo HTTP response bodies, URLs, or tokens.
        String message=exception.getMessage();
        boolean safe=message!=null && message.length()<250 && message.matches("(?s).*[\\u4e00-\\u9fff].*") && !message.contains("http") && !message.contains("token") && !message.contains("{");
        return ResponseEntity.status(502).body(Map.of("message",safe?message:"股票服务暂不可用，请检查连接后重试"));
    }
}
