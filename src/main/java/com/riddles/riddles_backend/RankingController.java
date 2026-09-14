package com.riddles.riddles_backend;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ranking")
@CrossOrigin(origins = "*")
public class RankingController {

    private final RankingRepository rankingRepository;

    public RankingController(RankingRepository rankingRepository) {
        this.rankingRepository = rankingRepository;
    }

    @GetMapping
    public List<RankingEntry> getTop10Ranking() {
        return rankingRepository.findTop10ByOrderByScoreDescCreatedAtAsc();
    }

    @PostMapping
    public ResponseEntity<?> saveScore(@RequestBody Map<String, Object> payload) {
        String name = (String) payload.get("name");
        String email = (String) payload.get("email");
        Integer scoreObj = payload.get("score") != null ? ((Number) payload.get("score")).intValue() : 0;
        String mode = (String) payload.getOrDefault("mode", "single");

        if (name == null || name.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Nome é obrigatório."));
        }
        if (email == null || email.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "E-mail é obrigatório."));
        }

        RankingEntry entry = new RankingEntry(name.trim(), email.trim(), scoreObj, mode);
        rankingRepository.save(entry);

        return ResponseEntity.ok(Map.of("success", true, "entry", entry));
    }
}
