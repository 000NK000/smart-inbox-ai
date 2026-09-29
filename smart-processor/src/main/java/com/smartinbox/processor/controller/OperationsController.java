package com.smartinbox.processor.controller;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinbox.processor.service.*;
import com.smartinbox.processor.mail.MailTaskPlanService;
import com.smartinbox.processor.repository.MailSummaryRepository;
import com.smartinbox.processor.trend.TrendService;
import com.smartinbox.processor.watch.WatchService;
import jakarta.annotation.PreDestroy;
import jakarta.persistence.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController @RequestMapping("/api/dashboard/operations")
public class OperationsController {
    private final DashboardController news; private final TrendService trends; private final WatchService watch;
    private final AiRequestScheduler scheduler; private final MailTaskPlanService plans; private final AiService ai;
    private final MailSummaryRepository mails; private final ObjectMapper mapper;
    @PersistenceContext private EntityManager em;
    @Value("${spring.ai.openai.base-url:http://localhost:11434}") private String aiUrl;
    @Value("${spring.ai.openai.chat.options.model:qwen3.5:9b-q8_0}") private String model;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final Set<String> retrying=ConcurrentHashMap.newKeySet();
    private final AtomicBoolean repairing=new AtomicBoolean();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"mail-ai-repair");t.setDaemon(true);return t;});
    public OperationsController(DashboardController news,TrendService trends,WatchService watch,AiRequestScheduler scheduler,MailTaskPlanService plans,AiService ai,MailSummaryRepository mails,ObjectMapper mapper){this.news=news;this.trends=trends;this.watch=watch;this.scheduler=scheduler;this.plans=plans;this.ai=ai;this.mails=mails;this.mapper=mapper;}
    @GetMapping public Map<String,Object> status(){
        var result=new LinkedHashMap<String,Object>(); result.put("ai",scheduler.status());result.put("taskPlan",plans.progress());
        result.put("pendingAnalysis",em.createQuery("select count(m) from MailSummary m where m.summary like '[AI Offline]%' and m.createdTime>=:cutoff",Long.class).setParameter("cutoff",com.smartinbox.processor.mail.MailWindow.cutoff()).getSingleResult());
        result.put("repairing",repairing.get());
        var sources=new ArrayList<Map<String,Object>>();
        news.sourceStatus().forEach(s->sources.add(source("news",s.id(),s.name(),s.status(),s.updatedAt())));
        trends.sourceStatus().forEach(s->sources.add(source("trends",s.id(),s.name(),s.status(),s.updatedAt())));
        watch.sourceStatus().forEach(s->sources.add(source("watch",s.id(),s.name()+" · "+s.channel(),s.status(),s.updatedAt())));
        result.put("sources",sources);result.put("model",modelStatus());return result;
    }
    private Map<String,Object> source(String group,String id,String name,String status,Instant time){var m=new LinkedHashMap<String,Object>();m.put("group",group);m.put("id",id);m.put("name",name);m.put("status",status);m.put("lastSuccess",time);m.put("inFlight",retrying.contains(group+":"+id));return m;}
    private Map<String,Object> modelStatus(){
        try {var response=client.send(HttpRequest.newBuilder(URI.create(aiUrl.replaceAll("/+$","")+"/api/ps")).timeout(Duration.ofSeconds(3)).GET().build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200) return Map.of("name",model,"state","unavailable");
            boolean loaded=false;long vram=0;
            for(var m:mapper.readTree(response.body()).path("models")) if(model.equals(m.path("name").asText()) || model.equals(m.path("model").asText())){loaded=true;vram=m.path("size_vram").asLong();}
            return Map.of("name",model,"state",loaded?"loaded":"idle","vramBytes",vram);
        }catch(Exception failure){return Map.of("name",model,"state","unavailable");}
    }
    @PostMapping("/retry/{group}/{id}") public Object retry(@PathVariable String group,@PathVariable String id){
        String key=group+":"+id;if(!retrying.add(key))throw new ResponseStatusException(HttpStatus.CONFLICT,"此来源正在更新");
        try{return switch(group){case "news"->news.retrySource(id);case "trends"->trends.retrySource(id);case "watch"->watch.retrySource(id);default->throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"未知来源");};}finally{retrying.remove(key);}
    }
    @PostMapping("/retry-analysis") @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String,Object> retryAnalysis(){
        if(!repairing.compareAndSet(false,true))return Map.of("running",true);
        try {
        var ids=em.createQuery("select m.id from MailSummary m where m.summary like '[AI Offline]%' and m.createdTime>=:cutoff order by m.createdTime desc",Long.class).setParameter("cutoff",com.smartinbox.processor.mail.MailWindow.cutoff()).setMaxResults(100).getResultList();
        worker.submit(()->{try{for(Long id:ids){var m=mails.findById(id).orElse(null);if(m==null)continue;
            var fixed=ai.processEmail(m.getSender(),m.getOriginalSubject(),m.getContent(),m.getSource(),m.getCreatedTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),m.getExternalId());
            if("IGNORE".equals(fixed.getAction()) || (fixed.getSummary()!=null && !fixed.getSummary().startsWith("[AI Offline]"))) {
                mails.updateAiFields(m.getId(),fixed.getSubject(),fixed.getCategory(),fixed.getSummary(),fixed.getUrgency(),fixed.getAction(),fixed.getStatus(),LocalDateTime.now());
            }
        }}finally{repairing.set(false);}});
        return Map.of("running",true,"selected",ids.size());
        } catch(RuntimeException failure) { repairing.set(false); throw failure; }
    }
    @PreDestroy public void close(){worker.shutdownNow();}
}
