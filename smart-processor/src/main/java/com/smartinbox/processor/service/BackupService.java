package com.smartinbox.processor.service;

import com.fasterxml.jackson.databind.*;
import com.smartinbox.processor.controller.PreferenceController;
import com.smartinbox.processor.entity.*;
import com.smartinbox.processor.job.JobApplicationService;
import com.smartinbox.processor.repository.*;
import jakarta.persistence.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;

/** Portable personal-data snapshot. Credentials, tokens, raw messages and AI caches are never exported. */
@Service
public class BackupService {
    private final TaskService tasks;
    private final WatchListRepository watch;
    private final MailSummaryRepository mails;
    private final AppPreferenceRepository preferences;
    private final CalendarService calendar;
    private final JobApplicationService jobApplications;
    private final FocusSessionRepository focusSessions;
    private final PracticeProgressRepository practice;
    private final PracticeGroupRepository practiceGroups;
    private final PracticeSolutionRepository practiceSolutions;
    private final PracticeSolutionImageRepository practiceImages;
    private final TaskItemRepository taskItems;
    private final ObjectMapper mapper;
    @PersistenceContext private EntityManager em;
    public BackupService(TaskService tasks,WatchListRepository watch,MailSummaryRepository mails,AppPreferenceRepository preferences,CalendarService calendar,JobApplicationService jobApplications,FocusSessionRepository focusSessions,PracticeProgressRepository practice,PracticeGroupRepository practiceGroups,PracticeSolutionRepository practiceSolutions,PracticeSolutionImageRepository practiceImages,TaskItemRepository taskItems,ObjectMapper mapper){this.tasks=tasks;this.watch=watch;this.mails=mails;this.preferences=preferences;this.calendar=calendar;this.jobApplications=jobApplications;this.focusSessions=focusSessions;this.practice=practice;this.practiceGroups=practiceGroups;this.practiceSolutions=practiceSolutions;this.practiceImages=practiceImages;this.taskItems=taskItems;this.mapper=mapper;}
    public record MailState(Long originalId,String source,String externalId,boolean inboxRead,boolean starred,Long snoozedUntil) {}
    public record SolutionState(Integer number,String content,long updatedAt) {}
    public record SolutionImageState(String id,Integer number,String mimeType,String caption,int position,byte[] data) {}
    public record Payload(List<TaskService.Input> tasks,List<WatchListEntry> watchlist,List<MailState> mailStates,Map<String,String> settings,List<CalendarService.Definition> calendar,List<JobApplicationService.Definition> jobApplications,List<FocusSession> focusSessions,Map<String,Integer> rescheduleCounts,List<PracticeProgress> practice,List<PracticeGroup> practiceGroups,List<SolutionState> practiceSolutions,List<SolutionImageState> practiceImages) {
        public Payload(List<TaskService.Input> tasks,List<WatchListEntry> watchlist,List<MailState> mailStates,Map<String,String> settings,List<CalendarService.Definition> calendar,List<JobApplicationService.Definition> jobApplications,List<FocusSession> focusSessions,Map<String,Integer> rescheduleCounts,List<PracticeProgress> practice) {
            this(tasks,watchlist,mailStates,settings,calendar,jobApplications,focusSessions,rescheduleCounts,practice,null,null,null);
        }
        public Payload(List<TaskService.Input> tasks,List<WatchListEntry> watchlist,List<MailState> mailStates,Map<String,String> settings,List<CalendarService.Definition> calendar,List<JobApplicationService.Definition> jobApplications,List<FocusSession> focusSessions,Map<String,Integer> rescheduleCounts,List<PracticeProgress> practice,List<PracticeGroup> practiceGroups) {
            this(tasks,watchlist,mailStates,settings,calendar,jobApplications,focusSessions,rescheduleCounts,practice,practiceGroups,null,null);
        }
    }
    // Schema 1 checksums were calculated before calendar was part of the JSON payload.
    private record LegacyPayload(List<TaskService.Input> tasks,List<WatchListEntry> watchlist,List<MailState> mailStates,Map<String,String> settings) {}
    private record Schema2Payload(List<TaskService.Input> tasks,List<WatchListEntry> watchlist,List<MailState> mailStates,Map<String,String> settings,List<CalendarService.Definition> calendar) {}
    private record Schema3Payload(List<TaskService.Input> tasks,List<WatchListEntry> watchlist,List<MailState> mailStates,Map<String,String> settings,List<CalendarService.Definition> calendar,List<JobApplicationService.Definition> jobApplications) {}
    private record Schema4Payload(List<TaskService.Input> tasks,List<WatchListEntry> watchlist,List<MailState> mailStates,Map<String,String> settings,List<CalendarService.Definition> calendar,List<JobApplicationService.Definition> jobApplications,List<FocusSession> focusSessions,Map<String,Integer> rescheduleCounts) {}
    private record Schema5Payload(List<TaskService.Input> tasks,List<WatchListEntry> watchlist,List<MailState> mailStates,Map<String,String> settings,List<CalendarService.Definition> calendar,List<JobApplicationService.Definition> jobApplications,List<FocusSession> focusSessions,Map<String,Integer> rescheduleCounts,List<PracticeProgress> practice) {}
    private record Schema6Payload(List<TaskService.Input> tasks,List<WatchListEntry> watchlist,List<MailState> mailStates,Map<String,String> settings,List<CalendarService.Definition> calendar,List<JobApplicationService.Definition> jobApplications,List<FocusSession> focusSessions,Map<String,Integer> rescheduleCounts,List<PracticeProgress> practice,List<PracticeGroup> practiceGroups) {}
    public record Envelope(int schema,String createdAt,String checksum,Payload payload) {}
    public record Preview(int tasks,int watchlist,int calendar,int jobApplications,int practice,int practiceGroups,int practiceSolutions,int practiceImages,int mailStates,int matchedMails,int missingMails,String checksum,String policy) {}
    @Transactional(readOnly=true)
    public Envelope export(){
        var inputs=tasks.list().stream().map(t->new TaskService.Input(t.getId(),t.getText(),t.getCreatedAt(),t.getPriority(),t.getDueAt(),t.getStatus(),t.getCompletedAt(),t.getUpdatedAt(),t.getNotes(),t.getSourceMailId(),t.getSourceSuggestionId(),null)).toList();
        var states=em.createQuery("select new com.smartinbox.processor.service.BackupService$MailState(m.id,m.source,m.externalId,coalesce(m.inboxRead,false),coalesce(m.starred,false),m.snoozedUntil) from MailSummary m where m.externalId is not null order by m.id",MailState.class).getResultList();
        var settings=new TreeMap<String,String>(); preferences.findAll().forEach(p->settings.put(p.getName(),p.getValue()));
        long exportedAt=System.currentTimeMillis();
        var focus=focusSessions.findAll().stream().map(s->{var copy=new FocusSession();copy.setId(s.getId());copy.setCategory(s.getCategory());copy.setTaskId(s.getTaskId());copy.setStartedAt(s.getStartedAt());copy.setEndedAt(FocusService.accountedEnd(s,exportedAt));return copy;}).toList();
        var delays=new TreeMap<String,Integer>();tasks.list().forEach(t->{if(t.getRescheduleCount()>0)delays.put(t.getId(),t.getRescheduleCount());});
        var solutionStates=practiceSolutions.findAll().stream().sorted(Comparator.comparing(PracticeSolution::getNumber))
                .map(solution->new SolutionState(solution.getNumber(),solution.getContent(),solution.getUpdatedAt())).toList();
        var imageStates=practiceImages.findAll().stream().sorted(Comparator.comparing(PracticeSolutionImage::getProblemNumber).thenComparingInt(PracticeSolutionImage::getPosition))
                .map(image->new SolutionImageState(image.getId(),image.getProblemNumber(),image.getMimeType(),image.getCaption(),image.getPosition(),image.getData())).toList();
        var data=new Payload(inputs,watch.findAllByOrderByUpdatedAtDesc(),states,settings,calendar.exportDefinitions(),jobApplications.exportDefinitions(),focus,delays,practice.findAll(),practiceGroups.findAll(),solutionStates,imageStates);
        return new Envelope(7,Instant.ofEpochMilli(exportedAt).toString(),checksum(data,7),data);
    }
    public Preview validate(Envelope file){
        if(file==null || (file.schema()<1 || file.schema()>7) || file.payload()==null || (file.schema()==1 && (file.payload().calendar()!=null||file.payload().jobApplications()!=null)) || (file.schema()==2 && file.payload().jobApplications()!=null) || (file.schema()<4 && (file.payload().focusSessions()!=null||file.payload().rescheduleCounts()!=null)) || (file.schema()<5 && file.payload().practice()!=null) || (file.schema()<6 && file.payload().practiceGroups()!=null) || (file.schema()<7 && (file.payload().practiceSolutions()!=null||file.payload().practiceImages()!=null)) || !checksum(file.payload(),file.schema()).equals(file.checksum())) throw bad("备份版本不支持或校验失败，未修改任何数据");
        var p=file.payload(); tasks.validateBatch(p.tasks()); PreferenceController.validate(p.settings());
        if(file.schema()>=2) calendar.validateBackup(p.calendar());
        if(file.schema()>=3) jobApplications.validateBackup(p.jobApplications());
        if(file.schema()>=4){
            if(p.focusSessions()==null||p.rescheduleCounts()==null||p.focusSessions().size()>50000||p.rescheduleCounts().size()>10000)throw bad("专注备份条数无效");
            var focusIds=new HashSet<String>();
            for(var s:p.focusSessions())if(s==null||!valid(s.getId(),36)||!focusIds.add(s.getId())||!Set.of("JOB","FRENCH","COURSE","ENTERTAINMENT","EFFECTIVE","INEFFECTIVE").contains(s.getCategory())||s.getStartedAt()==null||s.getEndedAt()==null||s.getStartedAt()<0||s.getEndedAt()<s.getStartedAt()||s.getEndedAt()>4102444800000L||(s.getTaskId()!=null&&s.getTaskId().length()>80))throw bad("专注记录无效或重复");
            var taskIds=new HashSet<String>();p.tasks().forEach(t->taskIds.add(t.id()));
            p.rescheduleCounts().forEach((id,count)->{if(!taskIds.contains(id)||count==null||count<0||count>10000)throw bad("延期次数无效");});
        }
        if(file.schema()>=5) {
            if(p.practice()==null || p.practice().size()>10000) throw bad("刷题记录条数无效");
            var numbers=new HashSet<Integer>();
            for(var row:p.practice()) {
                if(row==null || row.getNumber()==null || row.getNumber()<1 || row.getNumber()>99999 || !numbers.add(row.getNumber())
                        || !valid(row.getTitle(),200) || !valid(row.getTopic(),80)
                        || !Set.of("GREEN","YELLOW","RED").contains(row.getStatus())
                        || row.getAttempts()<1 || row.getAttempts()>100000
                        || row.getGreenCount()<0 || row.getYellowCount()<0 || row.getRedCount()<0
                        || (long)row.getGreenCount()+row.getYellowCount()+row.getRedCount()!=row.getAttempts()
                        || row.getTotalMinutes()<0 || row.getReviewStage()<0 || row.getReviewStage()>2
                        || row.getLastPracticedAt()<0 || row.getLastPracticedAt()>4102444800000L
                        || (row.getNextReviewAt()!=null && (row.getNextReviewAt()<0 || row.getNextReviewAt()>4102444800000L))
                        || (row.getMistake()!=null && !Set.of("NONE","METHOD","BOUNDARY","JAVA","COMPLEXITY","OTHER").contains(row.getMistake()))
                        || (row.getNote()!=null && row.getNote().length()>1000)) throw bad("刷题记录无效或重复");
            }
        }
        if(file.schema()>=6) {
            if(p.practiceGroups()==null || p.practiceGroups().size()>1000) throw bad("题型分组条数无效");
            var names=new HashSet<String>();
            for(var group:p.practiceGroups()) {
                if(group==null || !valid(group.getName(),80) || !group.getName().equals(group.getName().trim())
                        || !names.add(group.getName()) || group.getName().matches("(?s).*[\\p{Cntrl}].*")
                        || group.getNote()==null || group.getNote().length()>4000) throw bad("题型分组或心得无效");
            }
        }
        if(file.schema()>=7) {
            if(p.practiceSolutions()==null || p.practiceImages()==null || p.practiceSolutions().size()>10000 || p.practiceImages().size()>80000) throw bad("单题题解备份条数无效");
            var numbers=new HashSet<Integer>();
            for(var solution:p.practiceSolutions()) {
                if(solution==null || solution.number()==null || solution.number()<1 || solution.number()>99999 || !numbers.add(solution.number())
                        || solution.content()==null || solution.content().length()>20000 || solution.updatedAt()<0 || solution.updatedAt()>4102444800000L)
                    throw bad("单题题解无效或重复");
            }
            var imageIds=new HashSet<String>(); var perNote=new HashMap<Integer,Integer>(); var bytesPerNote=new HashMap<Integer,Long>();
            long allBytes=0;
            for(var image:p.practiceImages()) {
                if(image==null || image.id()==null || image.id().length()!=36 || !imageIds.add(image.id())
                        || !numbers.contains(image.number()) || image.caption()==null || image.caption().length()>200
                        || image.position()<0 || image.position()>=PracticeSolutionService.MAX_IMAGES
                        || !PracticeSolutionService.supportedImage(image.mimeType(),image.data())) throw bad("题解截图无效或重复");
                try { UUID.fromString(image.id()); } catch(IllegalArgumentException invalid) { throw bad("题解截图编号无效"); }
                if(perNote.merge(image.number(),1,Integer::sum)>PracticeSolutionService.MAX_IMAGES
                        || bytesPerNote.merge(image.number(),(long)image.data().length,Long::sum)>PracticeSolutionService.MAX_TOTAL_IMAGE_BYTES)
                    throw bad("单题截图数量或大小超出限制");
                allBytes+=image.data().length;
                if(allBytes>256L*1024*1024) throw bad("题解截图备份总大小超出限制");
            }
        }
        if(p.watchlist()==null || p.mailStates()==null || p.watchlist().size()>10000 || p.mailStates().size()>50000) throw bad("备份条数超出限制");
        var ids=new HashSet<String>(); var titles=new HashSet<String>();
        for(var w:p.watchlist()) {
            if(w==null || !valid(w.getId(),80) || !valid(w.getTitle(),300) || !ids.add(w.getId()) || !Set.of("MOVIE","TV","VARIETY").contains(Objects.toString(w.getKind(),"")) || !Set.of("PLANNED","WATCHING","COMPLETED").contains(Objects.toString(w.getStatus(),""))) throw bad("追剧记录无效或重复");
            if(w.getCurrentEpisode()<0 || w.getCurrentEpisode()>100000 || (w.getTotalEpisodes()!=null && (w.getTotalEpisodes()<1 || w.getTotalEpisodes()>100000 || w.getCurrentEpisode()>w.getTotalEpisodes()))) throw bad("追剧集数无效");
            if((w.getNotes()!=null && w.getNotes().length()>2000)||(w.getSource()!=null && w.getSource().length()>60)||(w.getUrl()!=null && w.getUrl().length()>2048)) throw bad("追剧字段过长");
            if(w.getUrl()!=null && !w.getUrl().isBlank()) { try {var uri=java.net.URI.create(w.getUrl());if(!Set.of("https","http").contains(Objects.toString(uri.getScheme(),"")) || uri.getHost()==null || uri.getUserInfo()!=null) throw bad("追剧链接无效");}catch(IllegalArgumentException error){throw bad("追剧链接无效");} }
            String key=Normalizer.normalize(w.getTitle().trim(),Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+"," ");
            if(key.length()>300 || !titles.add(w.getKind()+":"+key)) throw bad("追剧片名重复"); w.setTitleKey(key);
        }
        ids.clear(); int matched=0;
        for(var s:p.mailStates()) {
            if(s==null || !valid(s.source(),255) || !valid(s.externalId(),128) || !ids.add(s.source()+":"+s.externalId()) || (s.snoozedUntil()!=null && (s.snoozedUntil()<0 || s.snoozedUntil()>4102444800000L))) throw bad("邮件状态无效或重复");
            if(mails.existsBySourceAndExternalId(s.source(),s.externalId())) matched++;
        }
        return new Preview(p.tasks().size(),p.watchlist().size(),p.calendar()==null?0:p.calendar().size(),p.jobApplications()==null?0:p.jobApplications().size(),p.practice()==null?0:p.practice().size(),p.practiceGroups()==null?0:p.practiceGroups().size(),p.practiceSolutions()==null?0:p.practiceSolutions().size(),p.practiceImages()==null?0:p.practiceImages().size(),p.mailStates().size(),matched,p.mailStates().size()-matched,file.checksum(),"合并恢复：已有任务、追剧、日历、求职申请、专注、刷题记录、题型心得及单题题解保留；匹配邮件的本地状态及设置恢复到备份值。旧版备份不影响已有新模块记录。");
    }
    @Transactional
    public Preview restore(Envelope file){
        var preview=validate(file); var p=file.payload(); var mailIds=new HashMap<Long,Long>();
        for(var s:p.mailStates()) mails.findFirstBySourceAndExternalId(s.source(),s.externalId()).ifPresent(m->{
            if(s.originalId()!=null) mailIds.put(s.originalId(),m.getId());
            m.setInboxRead(s.inboxRead());m.setStarred(s.starred());m.setSnoozedUntil(s.snoozedUntil());m.setReminderNotifiedAt(null);mails.save(m);
        });
        var remapped=p.tasks().stream().map(t->{Long linked=t.sourceMailId()==null?null:mailIds.get(t.sourceMailId());return new TaskService.Input(t.id(),t.text(),t.createdAt(),t.priority(),t.dueAt(),t.status(),t.completedAt(),t.updatedAt(),t.notes(),linked,linked==null?null:t.sourceSuggestionId(),null);}).toList();
        var existingTaskIds=new HashSet<String>();taskItems.findAll().forEach(t->existingTaskIds.add(t.getId()));
        tasks.importTasks(remapped,false);
        if(file.schema()>=4)p.rescheduleCounts().forEach((id,count)->{if(!existingTaskIds.contains(id))taskItems.findById(id).ifPresent(t->{t.setRescheduleCount(count);taskItems.save(t);});});
        for(var w:p.watchlist()) if(!watch.existsById(w.getId()) && watch.findByKindAndTitleKey(w.getKind(),w.getTitleKey()).isEmpty()) {w.setVersion(null);w.setUpdatedAt(System.currentTimeMillis());watch.save(w);}
        preferences.saveAll(p.settings().entrySet().stream().map(e->new AppPreference(e.getKey(),e.getValue())).toList());
        if(file.schema()>=2) calendar.restoreBackup(p.calendar());
        if(file.schema()>=3) jobApplications.restoreBackup(p.jobApplications(),mailIds);
        if(file.schema()>=4)for(var session:p.focusSessions())if(!focusSessions.existsById(session.getId()))focusSessions.save(session);
        if(file.schema()>=5)for(var record:p.practice())if(!practice.existsById(record.getNumber())) {record.setVersion(null);practice.save(record);}
        if(file.schema()>=6)for(var group:p.practiceGroups())if(!practiceGroups.existsById(group.getName())) {group.setVersion(null);practiceGroups.save(group);}
        if(file.schema()>=7) {
            var inserted=new HashSet<Integer>();
            for(var solution:p.practiceSolutions()) if(!practiceSolutions.existsById(solution.number())) {
                var item=new PracticeSolution();item.setNumber(solution.number());item.setContent(solution.content());item.setUpdatedAt(solution.updatedAt());
                practiceSolutions.save(item);inserted.add(solution.number());
            }
            for(var image:p.practiceImages()) if(inserted.contains(image.number())) {
                if(practiceImages.existsById(image.id())) throw bad("题解截图编号与现有数据冲突");
                var item=new PracticeSolutionImage();item.setId(image.id());item.setProblemNumber(image.number());item.setMimeType(image.mimeType());
                item.setCaption(image.caption());item.setPosition(image.position());item.setData(image.data());practiceImages.save(item);
            }
        }
        em.flush(); return preview;
    }
    private boolean valid(String s,int max){return s!=null && !s.isBlank() && s.length()<=max;}
    private String checksum(Payload payload,int schema){try{Object content=schema==1?new LegacyPayload(payload.tasks(),payload.watchlist(),payload.mailStates(),payload.settings()):schema==2?new Schema2Payload(payload.tasks(),payload.watchlist(),payload.mailStates(),payload.settings(),payload.calendar()):schema==3?new Schema3Payload(payload.tasks(),payload.watchlist(),payload.mailStates(),payload.settings(),payload.calendar(),payload.jobApplications()):schema==4?new Schema4Payload(payload.tasks(),payload.watchlist(),payload.mailStates(),payload.settings(),payload.calendar(),payload.jobApplications(),payload.focusSessions(),payload.rescheduleCounts()):schema==5?new Schema5Payload(payload.tasks(),payload.watchlist(),payload.mailStates(),payload.settings(),payload.calendar(),payload.jobApplications(),payload.focusSessions(),payload.rescheduleCounts(),payload.practice()):schema==6?new Schema6Payload(payload.tasks(),payload.watchlist(),payload.mailStates(),payload.settings(),payload.calendar(),payload.jobApplications(),payload.focusSessions(),payload.rescheduleCounts(),payload.practice(),payload.practiceGroups()):payload;return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(content)));}catch(Exception e){throw bad("备份格式无效");}}
    private ResponseStatusException bad(String m){return new ResponseStatusException(HttpStatus.BAD_REQUEST,m);}
}
