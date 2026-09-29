package com.smartinbox.processor.job;

import com.smartinbox.processor.entity.MailSummary;
import com.smartinbox.processor.mail.MailTaskAnalyzer;
import com.smartinbox.processor.service.AiService;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class JobMailAnalyzer {
    private static final Set<String> STAGES=Set.of("APPLIED","ASSESSMENT","INTERVIEW","RESULT");
    private static final Set<String> RESULTS=Set.of("OFFER","REJECTED","WITHDRAWN","OTHER");
    private final AiService ai;
    public JobMailAnalyzer(AiService ai){this.ai=ai;}

    public boolean likely(MailSummary mail){
        String value=(MailTaskAnalyzer.subject(mail)+" "+safe(mail.getSender())+" "+safe(mail.getSummary())).toLowerCase(Locale.ROOT);
        if(value.matches(".*(job alert|jobs you may|recommended jobs|职位推荐|招聘周报|career newsletter).*")) return false;
        return value.matches(".*(application|candidate|recruit|hiring|interview|assessment|coding challenge|online test|offer|position|role|申请|应聘|招聘|候选|笔试|测评|面试|录用|拒信).*" );
    }

    public Analysis analyze(MailSummary mail) throws Exception {
        String subject=MailTaskAnalyzer.subject(mail), body=MailTaskAnalyzer.body(mail);
        if(body.isBlank()) body=safe(mail.getSummary());
        List<String> lines=sourceLines(subject,body);
        var node=ai.analyzeJobMail(subject,safe(mail.getSender()),String.valueOf(mail.getCreatedTime()),lines);
        if(!node.path("recruitment").asBoolean(false)) return Analysis.ignored();
        String stage=node.path("stage").asText("").trim(), result=node.path("result").asText("").trim();
        if(!STAGES.contains(stage) || ("RESULT".equals(stage)?!RESULTS.contains(result):!result.isBlank()))
            throw new IllegalStateException("AI returned an invalid application stage");
        int evidenceLine=node.path("evidenceLine").asInt(-1);
        if(evidenceLine<0||evidenceLine>=lines.size()) throw new IllegalStateException("AI job update lacks evidence");
        String summary=text(node.path("summaryChinese").asText(""),1000,true);
        List<Preparation> preparations=new ArrayList<>();
        var array=node.path("preparations");
        if(array.isArray()) for(var item:array){
            if(preparations.size()==4) break;
            String title=text(item.path("title").asText(""),150,true), details=text(item.path("details").asText(""),600,true);
            String priority=item.path("priority").asText("NORMAL"); if(!Set.of("HIGH","NORMAL","LOW").contains(priority)) priority="NORMAL";
            preparations.add(new Preparation(title,details,priority));
        }
        if(preparations.isEmpty()) preparations.addAll(defaultPreparations(stage,result));
        return new Analysis(true,text(node.path("company").asText(""),200,false),text(node.path("role").asText(""),300,false),
                stage,result,summary,lines.get(evidenceLine),List.copyOf(preparations));
    }

    private static List<String> sourceLines(String subject,String body){
        List<String> result=new ArrayList<>(); result.add(subject);
        for(String paragraph:body.split("(?<=[.!?。！？])\\s+|\\R+")){
            String value=paragraph.trim();
            while(value.length()>480){int split=value.lastIndexOf(' ',480);if(split<120)split=480;result.add(value.substring(0,split));value=value.substring(split).trim();}
            if(!value.isBlank()) result.add(value);
            // Keep the prompt focused on the actionable part of long HTML emails.
            if(result.size()>=30) break;
        }
        return result;
    }
    private static List<Preparation> defaultPreparations(String stage,String result){
        if("ASSESSMENT".equals(stage)) return List.of(new Preparation("确认笔试要求与截止时间","核对邮件中的测评形式、入口和明确截止时间。","HIGH"),new Preparation("准备笔试环境","提前检查设备、网络及允许使用的工具。","NORMAL"));
        if("INTERVIEW".equals(stage)) return List.of(new Preparation("确认面试安排","核对面试时间、时区、形式与会议入口。","HIGH"),new Preparation("准备岗位经历案例","围绕岗位要求准备可量化的项目与行为面试案例。","HIGH"),new Preparation("了解公司与岗位","整理公司业务、岗位职责和准备向面试官提问的问题。","NORMAL"));
        if("RESULT".equals(stage)&&"OFFER".equals(result)) return List.of(new Preparation("核对 Offer 与回复期限","检查职位、薪酬、入职时间、条件及明确的回复截止时间。","HIGH"));
        return List.of();
    }
    private static String text(String value,int max,boolean required){String v=safe(value).trim();if(v.length()>max||(required&&!chinese(v)))throw new IllegalStateException("AI returned invalid job text");return v;}
    private static boolean chinese(String v){return v.codePoints().anyMatch(c->c>=0x4e00&&c<=0x9fff);}
    private static String safe(String v){return v==null?"":v;}
    public record Preparation(String title,String details,String priority){}
    public record Analysis(boolean recruitment,String company,String role,String stage,String result,String summary,String evidence,List<Preparation> preparations){
        static Analysis ignored(){return new Analysis(false,"","","","","","",List.of());}
    }
}
