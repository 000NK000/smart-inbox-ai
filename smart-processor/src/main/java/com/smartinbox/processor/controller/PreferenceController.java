package com.smartinbox.processor.controller;
import com.smartinbox.processor.entity.AppPreference;
import com.smartinbox.processor.repository.AppPreferenceRepository;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;
@RestController @RequestMapping("/api/dashboard/preferences")
public class PreferenceController {
    private final AppPreferenceRepository repository;
    public PreferenceController(AppPreferenceRepository repository){this.repository=repository;}
    @GetMapping public Map<String,String> get(){ var result=new TreeMap<String,String>(); repository.findAll().forEach(p->result.put(p.getName(),p.getValue())); return result; }
    public static void validate(Map<String,String> data){
        if(data==null || data.size()>4) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"设置数据无效");
        data.forEach((k,v)->{
            if(k==null || v==null || !(switch(k){
                case "weatherLocation" -> Set.of("waterloo","ithaca","beijing","new-york","toronto").contains(v);
                case "mailBlockAd","remindersEnabled" -> Set.of("true","false").contains(v);
                case "entertainmentDailyLimitMinutes" -> v.matches("[0-9]{1,4}") && Integer.parseInt(v) >= 1 && Integer.parseInt(v) <= 1440;
                default -> false;
            })) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"未知设置或设置值无效");
        });
    }
    @PutMapping public Map<String,String> put(@RequestBody Map<String,String> data){validate(data); repository.saveAll(data.entrySet().stream().map(e->new AppPreference(e.getKey(),e.getValue())).toList()); return get();}
}
