package org.example.leaderboardservice.service;

import org.example.leaderboardservice.dto.LeaderboardDtos.*;
import org.example.leaderboardservice.exception.LeaderboardException;
import org.example.leaderboardservice.repository.LeaderboardRepository;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LeaderboardServiceTest {
    private final LeaderboardRepository repository = mock(LeaderboardRepository.class);
    private final LeaderboardService service = new LeaderboardService(repository);

    @Test void absentPlayerIsNotFoundRatherThanRankZero() {
        when(repository.read("g", "b", "player", Order.desc, "p", "0")).thenReturn(List.of());
        assertThatThrownBy(() -> service.player("g", "b", "p", Order.desc))
                .isInstanceOfSatisfying(LeaderboardException.class, e -> assertThat(e.status().value()).isEqualTo(404));
    }
    @Test void absentNeighborTargetIsNotAnEmptySuccessfulPage() {
        when(repository.read("g", "b", "neighbors", Order.asc, "p", "3")).thenReturn(List.of());
        assertThatThrownBy(() -> service.neighbors("g", "b", "p", 3, Order.asc)).isInstanceOf(LeaderboardException.class);
    }
    @Test void pagePreservesAbsoluteRanks() {
        when(repository.read("g", "b", "entries", Order.desc, "20", "10"))
                .thenReturn(List.of(new Entry("p", 50, 21)));
        var page = service.entries("g", "b", 20, 10, Order.desc);
        assertThat(page.offset()).isEqualTo(20);
        assertThat(page.entries().getFirst().rank()).isEqualTo(21);
    }
}
