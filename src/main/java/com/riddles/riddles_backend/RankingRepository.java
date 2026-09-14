package com.riddles.riddles_backend;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RankingRepository extends JpaRepository<RankingEntry, Long> {
    List<RankingEntry> findTop10ByOrderByScoreDescCreatedAtAsc();
}
