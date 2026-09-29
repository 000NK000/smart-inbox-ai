package com.smartinbox.processor.service;

import com.smartinbox.processor.entity.PracticeSolution;
import com.smartinbox.processor.entity.PracticeSolutionImage;
import com.smartinbox.processor.repository.PracticeSolutionImageRepository;
import com.smartinbox.processor.repository.PracticeSolutionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class PracticeSolutionService {
    public static final int MAX_IMAGES = 8;
    public static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;
    public static final int MAX_TOTAL_IMAGE_BYTES = 16 * 1024 * 1024;
    private final PracticeSolutionRepository solutions;
    private final PracticeSolutionImageRepository images;

    public PracticeSolutionService(PracticeSolutionRepository solutions, PracticeSolutionImageRepository images) {
        this.solutions = solutions;
        this.images = images;
    }

    public record ImageInput(String id, String mimeType, String base64, String caption) {}
    public record Input(String content, List<ImageInput> images, Long version) {}
    public record ImageView(String id, String caption) {}
    public record View(Integer number, String content, List<ImageView> images, Long version, long updatedAt) {}
    public record ImageFile(String mimeType, byte[] data) {}

    @Transactional(readOnly = true)
    public List<Integer> numbers() {
        return solutions.findAllNumbers();
    }

    @Transactional(readOnly = true)
    public View get(Integer number) {
        checkNumber(number);
        return view(number, solutions.findById(number).orElse(null));
    }

    @Transactional(readOnly = true)
    public ImageFile image(String id) {
        var item = images.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "截图不存在"));
        return new ImageFile(item.getMimeType(), item.getData());
    }

    @Transactional
    public synchronized View save(Integer number, Input input) {
        checkNumber(number);
        if (input == null || input.content() == null || input.content().length() > 20000 || input.images() == null || input.images().size() > MAX_IMAGES)
            throw bad("题解文字或截图数量超出限制");
        var current = solutions.findById(number).orElse(null);
        if (current == null ? input.version() != null : !Objects.equals(current.getVersion(), input.version())) throw conflict();
        var existing = images.findByProblemNumberOrderByPositionAsc(number);
        var byId = new HashMap<String, PracticeSolutionImage>();
        existing.forEach(image -> byId.put(image.getId(), image));
        var retained = new HashSet<String>();
        var added = new ArrayList<PracticeSolutionImage>();
        int totalBytes = 0;
        for (int index = 0; index < input.images().size(); index++) {
            var entry = input.images().get(index);
            if (entry == null || entry.caption() == null || entry.caption().length() > 200) throw bad("截图说明无效");
            PracticeSolutionImage item;
            if (entry.id() != null) {
                item = byId.get(entry.id());
                if (item == null || !retained.add(entry.id()) || entry.base64() != null) throw bad("截图不属于这道题或重复");
            } else {
                if (entry.base64() == null || entry.base64().length() > (MAX_IMAGE_BYTES * 4 / 3 + 16)) throw bad("截图过大");
                byte[] data;
                try { data = Base64.getDecoder().decode(entry.base64()); }
                catch (IllegalArgumentException invalid) { throw bad("截图编码无效"); }
                if (!supportedImage(entry.mimeType(), data)) throw bad("只支持真实的 PNG、JPEG、GIF 或 WebP 截图，单张不超过 4 MB");
                item = new PracticeSolutionImage();
                item.setId(UUID.randomUUID().toString());
                item.setProblemNumber(number);
                item.setMimeType(entry.mimeType());
                item.setData(data);
                added.add(item);
            }
            totalBytes += item.getData().length;
            if (totalBytes > MAX_TOTAL_IMAGE_BYTES) throw bad("这道题的截图总大小不能超过 16 MB");
            item.setCaption(entry.caption().trim());
            item.setPosition(index);
        }
        if (input.content().isBlank() && input.images().isEmpty()) {
            images.deleteAll(existing);
            if (current != null) solutions.delete(current);
            return new View(number, "", List.of(), null, 0);
        }
        if (current == null) { current = new PracticeSolution(); current.setNumber(number); }
        current.setContent(input.content().trim());
        current.setUpdatedAt(System.currentTimeMillis());
        var removed = existing.stream().filter(image -> !retained.contains(image.getId())).toList();
        images.deleteAll(removed);
        images.saveAll(existing.stream().filter(image -> retained.contains(image.getId())).toList());
        images.saveAll(added);
        current = solutions.saveAndFlush(current);
        images.flush();
        return view(number, current);
    }

    private View view(Integer number, PracticeSolution solution) {
        if (solution == null) return new View(number, "", List.of(), null, 0);
        var attachments = images.findByProblemNumberOrderByPositionAsc(number).stream()
                .map(image -> new ImageView(image.getId(), image.getCaption())).toList();
        return new View(number, solution.getContent(), attachments, solution.getVersion(), solution.getUpdatedAt());
    }

    private static void checkNumber(Integer number) {
        if (number == null || number < 1 || number > 99999) throw bad("题号无效");
    }

    public static boolean supportedImage(String mime, byte[] data) {
        if (data == null || data.length == 0 || data.length > MAX_IMAGE_BYTES) return false;
        if ("image/png".equals(mime)) return starts(data, new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10});
        if ("image/jpeg".equals(mime)) return starts(data, new byte[]{(byte) 255, (byte) 216, (byte) 255});
        if ("image/gif".equals(mime)) return starts(data, "GIF87a".getBytes(StandardCharsets.US_ASCII)) || starts(data, "GIF89a".getBytes(StandardCharsets.US_ASCII));
        return "image/webp".equals(mime) && starts(data, "RIFF".getBytes(StandardCharsets.US_ASCII))
                && data.length >= 12 && Arrays.equals(Arrays.copyOfRange(data, 8, 12), "WEBP".getBytes(StandardCharsets.US_ASCII));
    }
    private static boolean starts(byte[] data, byte[] prefix) {
        return data.length >= prefix.length && Arrays.equals(Arrays.copyOf(data, prefix.length), prefix);
    }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT, "题解已被其他页面修改，请刷新后重试"); }
}
