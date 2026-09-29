package com.smartinbox.processor.job;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinbox.processor.entity.*;
import com.smartinbox.processor.mail.MailTaskAnalyzer;
import com.smartinbox.processor.mail.MailWindow;
import com.smartinbox.processor.repository.*;
import com.smartinbox.processor.service.TaskService;
import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class JobApplicationService {
    private static final List<String> STAGES=List.of("PREPARING","APPLIED","ASSESSMENT","INTERVIEW","RESULT");
    private static final Set<String> RESULTS=Set.of("OFFER","REJECTED","WITHDRAWN","OTHER");
    private static final long MAX_DATE=4102444800000L;
    private final JobApplicationRepository applications; private final JobMailLinkRepository links;
    private final JobMailSuggestionRepository suggestions; private final MailSummaryRepository mails;
    private final JobMailAnalyzer analyzer; private final TaskService tasks; private final ObjectMapper mapper;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"job-mail-analysis");t.setDaemon(true);return t;});
    private final AtomicReference<Progress> progress=new AtomicReference<>(new Progress("IDLE",0,0,0,null));
    public JobApplicationService(JobApplicationRepository a,JobMailLinkRepository l,JobMailSuggestionRepository s,MailSummaryRepository m,JobMailAnalyzer j,TaskService t,ObjectMapper o){applications=a;links=l;suggestions=s;mails=m;analyzer=j;tasks=t;mapper=o;}

    @Transactional(readOnly=true) public Overview overview(){
        List<ApplicationView> items=applications.findAllByOrderByUpdatedAtDesc().stream().map(this::view).toList();
        List<SuggestionView> open=suggestions.findAllByRecruitmentTrueAndStatusOrderByAnalyzedAtDesc("OPEN").stream().map(this::suggestionView).toList();
        Map<String,Long> counts=new LinkedHashMap<>(); for(String stage:STAGES)counts.put(stage,items.stream().filter(v->stage.equals(v.stage())).count());
        return new Overview(items,open,counts,progress.get());
    }
    @Transactional public ApplicationView create(Input input){JobApplication value=validated(input,null);value.setId(UUID.randomUUID().toString());long now=System.currentTimeMillis();value.setCreatedAt(now);value.setUpdatedAt(now);return view(applications.saveAndFlush(value));}
    @Transactional public ApplicationView update(String id,Input input){JobApplication value=find(id);requireVersion(input==null?null:input.version());checkVersion(value,input.version());JobApplication valid=validated(input,value);copy(valid,value);touch(value);return view(applications.saveAndFlush(value));}
    @Transactional public void delete(String id,Long version){requireVersion(version);JobApplication value=find(id);checkVersion(value,version);links.deleteAllByApplicationId(id);applications.delete(value);applications.flush();}

    @Transactional public MoveMailResult moveMail(String sourceId,Long mailId,MailLinkInput input){
        requireVersion(input==null?null:input.applicationVersion());
        requireVersion(input.targetApplicationVersion());
        JobApplication source=find(sourceId);checkVersion(source,input.applicationVersion());
        if(sourceId.equals(input.targetApplicationId()))throw bad("请选择另一条申请记录");
        JobApplication target=find(input.targetApplicationId());checkVersion(target,input.targetApplicationVersion());
        JobMailLink sourceLink=findLink(sourceId,mailId);
        String targetLinkId=target.getId()+":"+mailId;
        if(links.existsById(targetLinkId))throw conflict("邮件已关联到目标申请");
        JobMailSuggestion suggestion=suggestions.findById(mailId).orElse(null);
        if(suggestion!=null)checkCompany(suggestion,target);

        // Updating both application versions makes concurrent moves, edits and deletes conflict.
        touch(source);touch(target);applications.saveAllAndFlush(List.of(source,target));
        JobMailLink targetLink=new JobMailLink();targetLink.setId(targetLinkId);
        targetLink.setApplicationId(target.getId());targetLink.setMailId(mailId);targetLink.setLinkedAt(sourceLink.getLinkedAt());
        links.delete(sourceLink);links.saveAndFlush(targetLink);
        if(suggestion!=null&&"APPLIED".equals(suggestion.getStatus())&&sourceId.equals(suggestion.getApplicationId())){
            suggestion.setApplicationId(target.getId());suggestions.saveAndFlush(suggestion);
        }
        return new MoveMailResult(view(source),view(target));
    }

    @Transactional public ApplicationView unlinkMail(String sourceId,Long mailId,Long version){
        requireVersion(version);JobApplication source=find(sourceId);checkVersion(source,version);
        JobMailLink sourceLink=findLink(sourceId,mailId);
        JobMailSuggestion suggestion=suggestions.findById(mailId).orElse(null);
        touch(source);applications.saveAndFlush(source);
        links.delete(sourceLink);links.flush();
        if(suggestion!=null&&"APPLIED".equals(suggestion.getStatus())&&sourceId.equals(suggestion.getApplicationId())){
            List<JobMailLink> remaining=links.findAllByMailIdOrderByLinkedAtDesc(mailId);
            if(remaining.isEmpty()){suggestion.setStatus("OPEN");suggestion.setApplicationId(null);}
            else suggestion.setApplicationId(remaining.get(0).getApplicationId());
            suggestions.saveAndFlush(suggestion);
        }
        return view(source);
    }
    @Transactional(readOnly=true) public List<Definition> exportDefinitions(){return applications.findAllByOrderByUpdatedAtDesc().stream().map(value->new Definition(value.getId(),value.getCompany(),value.getRole(),value.getStage(),value.getResult(),value.getLocation(),value.getJobUrl(),value.getNotes(),value.getAppliedAt(),value.getNextActionAt(),value.getCreatedAt(),links.findAllByApplicationIdOrderByLinkedAtDesc(value.getId()).stream().map(JobMailLink::getMailId).toList())).toList();}
    public void validateBackup(List<Definition> definitions){backupEntities(definitions);}
    @Transactional public void restoreBackup(List<Definition> definitions,Map<Long,Long> mailIds){for(BackupRow row:backupEntities(definitions)){if(!applications.existsById(row.application().getId()))applications.save(row.application());for(Long oldMail:row.mailIds()){Long mailId=mailIds.get(oldMail);if(mailId==null)continue;String linkId=row.application().getId()+":"+mailId;if(!links.existsById(linkId)){JobMailLink link=new JobMailLink();link.setId(linkId);link.setApplicationId(row.application().getId());link.setMailId(mailId);link.setLinkedAt(System.currentTimeMillis());links.save(link);}}}applications.flush();links.flush();}
    private List<BackupRow> backupEntities(List<Definition> definitions){if(definitions==null||definitions.size()>10000)throw bad("求职申请备份最多包含 10000 条记录");Set<String> ids=new HashSet<>();List<BackupRow> rows=new ArrayList<>();for(Definition d:definitions){if(d==null||d.id()==null||!d.id().matches("[A-Za-z0-9-]{1,80}")||!ids.add(d.id()))throw bad("求职申请备份编号无效或重复");JobApplication value=validated(new Input(d.company(),d.role(),d.stage(),d.result(),d.location(),d.jobUrl(),d.notes(),d.appliedAt(),d.nextActionAt(),null),null);value.setId(d.id());value.setCreatedAt(d.createdAt()==null?System.currentTimeMillis():date(d.createdAt()));value.setUpdatedAt(System.currentTimeMillis());List<Long> linkIds=d.linkedMailIds()==null?List.of():d.linkedMailIds();if(linkIds.size()>50000||linkIds.stream().anyMatch(id->id==null||id<1)||linkIds.stream().distinct().count()!=linkIds.size())throw bad("求职申请关联邮件无效");rows.add(new BackupRow(value,List.copyOf(linkIds)));}return rows;}

    public Progress analyzeNewMail(){
        Progress current=progress.get(); if("RUNNING".equals(current.status()))return current;
        var candidates=mails.findAllByCreatedTimeGreaterThanEqualAndSourceInOrderByCreatedTimeDesc(MailWindow.cutoff(),MailWindow.SOURCES).stream().filter(analyzer::likely).limit(100).toList();
        List<Long> pending=candidates.stream().filter(mail->{var cached=suggestions.findById(mail.getId());return cached.isEmpty()||!MailTaskAnalyzer.fingerprint(mail).equals(cached.get().getFingerprint());}).map(MailSummary::getId).toList();
        if(pending.isEmpty()){Progress done=new Progress("DONE",0,0,0,Instant.now());progress.set(done);return done;}
        progress.set(new Progress("RUNNING",pending.size(),0,0,null)); worker.submit(()->runAnalysis(pending)); return progress.get();
    }
    private void runAnalysis(List<Long> ids){int done=0,failed=0;for(Long id:ids){if(Thread.currentThread().isInterrupted())break;try{MailSummary mail=mails.findById(id).orElse(null);if(mail!=null){String fingerprint=MailTaskAnalyzer.fingerprint(mail);JobMailAnalyzer.Analysis result=analyzer.analyze(mail);JobMailSuggestion saved=suggestions.findById(id).orElseGet(JobMailSuggestion::new);saved.setMailId(id);saved.setFingerprint(fingerprint);saved.setRecruitment(result.recruitment());saved.setCompany(result.company());saved.setRole(result.role());saved.setSuggestedStage(result.stage());saved.setSuggestedResult(result.result());saved.setSummaryChinese(result.summary());saved.setEvidence(result.evidence());saved.setPreparationsJson(mapper.writeValueAsString(result.preparations()));saved.setStatus(result.recruitment()?"OPEN":"IGNORED");saved.setApplicationId(null);saved.setAnalyzedAt(Instant.now());suggestions.saveAndFlush(saved);}}catch(Exception error){failed++;}finally{done++;progress.set(new Progress("RUNNING",ids.size(),done,failed,null));}}progress.set(new Progress(failed==ids.size()?"FAILED":"DONE",ids.size(),done,failed,Instant.now()));}

    @Transactional public ApplyResult applySuggestion(Long mailId,ApplyInput input){
        JobMailSuggestion suggestion=suggestions.findById(mailId).orElseThrow(()->bad("招聘邮件建议不存在"));
        requireVersion(input==null?null:input.suggestionVersion()); if(!Objects.equals(suggestion.getVersion(),input.suggestionVersion()))throw conflict("建议已变化，请刷新后再确认");
        if(!suggestion.isRecruitment()||!"OPEN".equals(suggestion.getStatus()))throw bad("该建议已经处理");
        JobApplication application=find(input.applicationId()); requireVersion(input.applicationVersion());checkVersion(application,input.applicationVersion());
        checkCompany(suggestion,application);
        int current=STAGES.indexOf(application.getStage()),next=STAGES.indexOf(blank(suggestion.getSuggestedStage()));
        if(next<0||("RESULT".equals(suggestion.getSuggestedStage())&&!RESULTS.contains(blank(suggestion.getSuggestedResult()))))throw bad("邮件建议阶段或结果无效，请重新分析");
        if(next<current)throw bad("建议阶段早于当前阶段，请先核对邮件或手动修改记录");
        List<PreparedTask> prepared=prepareTasks(mailId,suggestion,application,input.preparationIndexes());
        application.setStage(suggestion.getSuggestedStage());application.setResult("RESULT".equals(suggestion.getSuggestedStage())?suggestion.getSuggestedResult():null);touch(application);applications.saveAndFlush(application);
        String linkId=application.getId()+":"+mailId;if(!links.existsById(linkId)){JobMailLink link=new JobMailLink();link.setId(linkId);link.setApplicationId(application.getId());link.setMailId(mailId);link.setLinkedAt(System.currentTimeMillis());links.save(link);}
        int created=0;
        for(PreparedTask item:prepared){boolean existed=tasks.findConverted(mailId,item.source()).isPresent();tasks.fromJobSuggestion(mailId,item.source(),item.title(),item.priority(),item.notes());if(!existed)created++;}
        suggestion.setStatus("APPLIED");suggestion.setApplicationId(application.getId());suggestions.saveAndFlush(suggestion);
        return new ApplyResult(view(application),created);
    }

    private List<PreparedTask> prepareTasks(Long mailId,JobMailSuggestion suggestion,JobApplication application,List<Integer> indexes){
        List<JobMailAnalyzer.Preparation> available=preparations(suggestion);
        Set<Integer> selected=new LinkedHashSet<>(indexes==null?List.of():indexes);
        List<PreparedTask> prepared=new ArrayList<>();List<TaskService.Input> validation=new ArrayList<>();
        for(Integer index:selected){
            if(index==null||index<0||index>=available.size()||available.get(index)==null)throw bad("准备事项选择无效");
            if(blank(suggestion.getFingerprint()).length()<12)throw bad("邮件建议暂时不可用，请重新分析");
            JobMailAnalyzer.Preparation item=available.get(index);
            String source="job:"+mailId+":"+index+":"+suggestion.getFingerprint().substring(0,12);
            String notes=blank(item.details())+"\n关联申请："+application.getCompany()+" · "+application.getRole();
            prepared.add(new PreparedTask(source,item.title(),item.priority(),notes));
            validation.add(new TaskService.Input(null,item.title(),null,item.priority(),null,"OPEN",null,null,notes,mailId,source,null));
        }
        // Validate every selection before changing a stage, link, suggestion or task.
        tasks.validateBatch(validation);
        if(!prepared.isEmpty()&&!mails.existsById(mailId))throw bad("招聘邮件来源无效");
        return prepared;
    }
    @Transactional public void dismissSuggestion(Long mailId,Long version){JobMailSuggestion value=suggestions.findById(mailId).orElseThrow(()->bad("建议不存在"));requireVersion(version);if(!Objects.equals(value.getVersion(),version))throw conflict("建议已变化，请刷新后再试");value.setStatus("DISMISSED");suggestions.saveAndFlush(value);}

    private JobApplication validated(Input input,JobApplication previous){if(input==null)throw bad("申请记录不能为空");JobApplication value=new JobApplication();value.setCompany(text(input.company(),200,true));value.setRole(text(input.role(),300,true));if(!STAGES.contains(input.stage()))throw bad("申请阶段无效");value.setStage(input.stage());String result=blank(input.result());if("RESULT".equals(input.stage())){if(!RESULTS.contains(result))throw bad("请选择最终结果");}else result=null;value.setResult(result);value.setLocation(text(input.location(),300,false));value.setJobUrl(url(input.jobUrl()));value.setNotes(text(input.notes(),4000,false));value.setAppliedAt(date(input.appliedAt()));value.setNextActionAt(date(input.nextActionAt()));if(previous!=null){value.setId(previous.getId());value.setCreatedAt(previous.getCreatedAt());}return value;}
    private ApplicationView view(JobApplication value){List<MailView> mailLinks=links.findAllByApplicationIdOrderByLinkedAtDesc(value.getId()).stream().map(link->mails.findById(link.getMailId()).map(mail->new MailView(mail.getId(),MailTaskAnalyzer.subject(mail),mail.getSender(),mail.getSource(),mail.getCreatedTime()==null?null:mail.getCreatedTime().toString())).orElse(new MailView(link.getMailId(),"原邮件已不存在","","",null))).toList();return new ApplicationView(value.getId(),value.getCompany(),value.getRole(),value.getStage(),value.getResult(),value.getLocation(),value.getJobUrl(),value.getNotes(),value.getAppliedAt(),value.getNextActionAt(),value.getCreatedAt(),value.getUpdatedAt(),value.getVersion(),mailLinks);}
    private SuggestionView suggestionView(JobMailSuggestion value){MailSummary mail=mails.findById(value.getMailId()).orElse(null);String matched=bestMatch(value);return new SuggestionView(value.getMailId(),mail==null?"原邮件已不存在":MailTaskAnalyzer.subject(mail),mail==null?"":mail.getSender(),mail==null?null:(mail.getCreatedTime()==null?null:mail.getCreatedTime().toString()),value.getCompany(),value.getRole(),value.getSuggestedStage(),value.getSuggestedResult(),value.getSummaryChinese(),value.getEvidence(),preparations(value),matched,value.getVersion());}
    private String bestMatch(JobMailSuggestion value){return JobApplicationMatcher.bestMatch(value,applications.findAll());}
    private List<JobMailAnalyzer.Preparation> preparations(JobMailSuggestion value){try{List<JobMailAnalyzer.Preparation> result=mapper.readValue(value.getPreparationsJson(),new TypeReference<List<JobMailAnalyzer.Preparation>>(){});return result==null?List.of():result;}catch(Exception e){return List.of();}}
    private JobApplication find(String id){if(id==null||id.isBlank())throw bad("请选择申请记录");return applications.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"申请记录不存在"));}
    private JobMailLink findLink(String applicationId,Long mailId){return links.findById(applicationId+":"+mailId).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"邮件关联不存在"));}
    private static void checkCompany(JobMailSuggestion suggestion,JobApplication application){if(JobApplicationMatcher.knownCompany(suggestion.getCompany())&&!JobApplicationMatcher.sameCompany(suggestion.getCompany(),application.getCompany()))throw bad("邮件中的公司与所选申请不一致，请核对公司后再关联");}
    private static void touch(JobApplication value){value.setUpdatedAt(Math.max(System.currentTimeMillis(),value.getUpdatedAt()==null?0:value.getUpdatedAt()+1));}
    private static void copy(JobApplication a,JobApplication b){b.setCompany(a.getCompany());b.setRole(a.getRole());b.setStage(a.getStage());b.setResult(a.getResult());b.setLocation(a.getLocation());b.setJobUrl(a.getJobUrl());b.setNotes(a.getNotes());b.setAppliedAt(a.getAppliedAt());b.setNextActionAt(a.getNextActionAt());}
    private static String text(String v,int max,boolean required){String s=blank(v);if((required&&s.isBlank())||s.length()>max)throw bad("文本为空或超过长度限制");return s;}
    private static String url(String v){String s=blank(v);if(s.isBlank())return "";try{URI u=URI.create(s);if(!Set.of("http","https").contains(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null)throw new Exception();return s;}catch(Exception e){throw bad("岗位链接须为有效的 http 或 https 地址");}}
    private static Long date(Long v){if(v!=null&&(v<0||v>MAX_DATE))throw bad("日期超出范围");return v;}
    private static String blank(String v){return v==null?"":v.trim();}
    private static void requireVersion(Long v){if(v==null||v<0)throw bad("请提供最新版本");}private static void checkVersion(JobApplication v,Long version){if(!Objects.equals(v.getVersion(),version))throw conflict("申请记录已被其他页面修改，请刷新后重试");}
    private static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}private static ResponseStatusException conflict(String message){return new ResponseStatusException(HttpStatus.CONFLICT,message);}
    @PreDestroy public void close(){worker.shutdownNow();}
    public record Input(String company,String role,String stage,String result,String location,String jobUrl,String notes,Long appliedAt,Long nextActionAt,Long version){}
    public record MailView(Long id,String subject,String sender,String source,String receivedAt){}
    public record ApplicationView(String id,String company,String role,String stage,String result,String location,String jobUrl,String notes,Long appliedAt,Long nextActionAt,Long createdAt,Long updatedAt,Long version,List<MailView> linkedMails){}
    public record SuggestionView(Long mailId,String mailSubject,String sender,String receivedAt,String company,String role,String suggestedStage,String suggestedResult,String summary,String evidence,List<JobMailAnalyzer.Preparation> preparations,String matchedApplicationId,Long version){}
    public record Progress(String status,int total,int processed,int failed,Instant finishedAt){}
    public record Overview(List<ApplicationView> applications,List<SuggestionView> suggestions,Map<String,Long> stageCounts,Progress analysis){}
    public record ApplyInput(String applicationId,Long applicationVersion,Long suggestionVersion,List<Integer> preparationIndexes){}
    public record ApplyResult(ApplicationView application,int tasksCreated){}
    public record MailLinkInput(String targetApplicationId,Long applicationVersion,Long targetApplicationVersion){}
    public record MoveMailResult(ApplicationView source,ApplicationView target){}
    public record Definition(String id,String company,String role,String stage,String result,String location,String jobUrl,String notes,Long appliedAt,Long nextActionAt,Long createdAt,List<Long> linkedMailIds){}
    private record BackupRow(JobApplication application,List<Long> mailIds){}
    private record PreparedTask(String source,String title,String priority,String notes){}
}
