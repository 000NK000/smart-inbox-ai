package com.smartinbox.processor.service;

import com.smartinbox.processor.entity.PracticeProgress;
import com.smartinbox.processor.entity.PracticeGroup;
import com.smartinbox.processor.repository.PracticeProgressRepository;
import com.smartinbox.processor.repository.PracticeGroupRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.*;

@Service
public class PracticeService {
    private static final Set<String> RESULTS = Set.of("GREEN", "YELLOW");
    private static final Set<String> MISTAKES = Set.of("NONE", "METHOD", "BOUNDARY", "JAVA", "COMPLEXITY", "OTHER");
    private final PracticeProgressRepository repository;
    private final PracticeGroupRepository groups;
    public PracticeService(PracticeProgressRepository repository, PracticeGroupRepository groups) {
        this.repository = repository;
        this.groups = groups;
    }

    @Transactional(readOnly = true)
    public List<PracticeProgress> list() {
        return repository.findAll().stream().sorted(Comparator.comparing(PracticeProgress::getNumber)).toList();
    }
    @Transactional(readOnly = true)
    public List<PracticeGroup> groups() {
        return groups.findAll().stream().sorted(Comparator.comparing(PracticeGroup::getName)).toList();
    }
    public record GroupInput(String name, String note, Long version) {}
    public record MoveInput(String topic, Long version) {}

    @Transactional
    public synchronized PracticeGroup saveGroup(GroupInput input) {
        if (input == null || !text(input.name(), 80) || input.note() == null || input.note().length() > 4000
                || input.name().matches("(?s).*[\\p{Cntrl}].*")) throw bad("题型名称或解题心得无效");
        String name = input.name().trim();
        PracticeGroup group = groups.findById(name).orElse(null);
        if (group == null) {
            if (input.version() != null) throw conflict();
            group = new PracticeGroup();
            group.setName(name);
        } else if (!Objects.equals(group.getVersion(), input.version())) throw conflict();
        group.setNote(input.note().trim());
        return groups.saveAndFlush(group);
    }

    @Transactional
    public synchronized PracticeProgress move(Integer number, MoveInput input) {
        if (input == null || !text(input.topic(), 80) || input.topic().matches("(?s).*[\\p{Cntrl}].*"))
            throw bad("题型名称无效");
        PracticeProgress record = repository.findById(number)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "没有这道题的记录"));
        if (!Objects.equals(record.getVersion(), input.version())) throw conflict();
        record.setTopic(input.topic().trim());
        return repository.saveAndFlush(record);
    }

    public record Attempt(Integer number, String title, String topic, String result, Integer minutes,
                          String mistake, String note, String zone, Long version) { }

    @Transactional
    public synchronized PracticeProgress record(Attempt input) {
        if (input == null || input.number() == null || input.number() < 1 || input.number() > 99999
                || !text(input.title(), 200) || !text(input.topic(), 80) || !RESULTS.contains(input.result())
                || input.minutes() == null || input.minutes() < 0 || input.minutes() > 600
                || !MISTAKES.contains(input.mistake())
                || (input.note() != null && input.note().length() > 1000)) throw bad("题号、题目、练习结果或耗时无效");
        zone(input.zone());
        PracticeProgress progress = repository.findById(input.number()).orElse(null);
        if (progress == null) {
            if (input.version() != null) throw conflict();
            progress = new PracticeProgress();
            progress.setNumber(input.number());
        } else if (!Objects.equals(progress.getVersion(), input.version())) throw conflict();
        long now = System.currentTimeMillis();
        progress.setTitle(input.title().trim());
        progress.setTopic(input.topic().trim());
        progress.setStatus(input.result());
        progress.setAttempts(progress.getAttempts() + 1);
        progress.setGreenCount(progress.getGreenCount() + ("GREEN".equals(input.result()) ? 1 : 0));
        progress.setYellowCount(progress.getYellowCount() + ("YELLOW".equals(input.result()) ? 1 : 0));
        progress.setRedCount(progress.getRedCount() + ("RED".equals(input.result()) ? 1 : 0));
        progress.setTotalMinutes(progress.getTotalMinutes() + input.minutes());
        // The plan is optional: recording a result must not impose a review deadline.
        progress.setReviewStage(0);
        progress.setLastPracticedAt(now);
        progress.setNextReviewAt(null);
        progress.setMistake(input.mistake());
        progress.setNote(input.note() == null ? "" : input.note().trim());
        return repository.saveAndFlush(progress);
    }

    @Transactional
    public synchronized void delete(Integer number, Long version) {
        PracticeProgress current = repository.findById(number).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "没有这道题的记录"));
        if (!Objects.equals(current.getVersion(), version)) throw conflict();
        repository.delete(current);
    }

    private static boolean text(String value, int max) { return value != null && !value.isBlank() && value.length() <= max; }
    private static ZoneId zone(String value) {
        try { return value == null || value.isBlank() ? ZoneId.systemDefault() : ZoneId.of(value); }
        catch (DateTimeException invalid) { throw bad("时区无效"); }
    }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT, "记录已被其他页面更新，请刷新后重试"); }
}
