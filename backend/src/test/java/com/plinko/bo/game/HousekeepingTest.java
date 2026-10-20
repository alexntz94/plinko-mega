package com.plinko.bo.game;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.plinko.repo.GameRepository;
import org.junit.jupiter.api.Test;

/** Review 3.7: the nightly clean-up deletes old launch tokens and sessions in chunks until none are left, on one instance at a time. */
class HousekeepingTest {

    private final GameRepository repository = mock(GameRepository.class);
    private final Housekeeping housekeeping = new Housekeeping(repository);

    @Test
    void oldTokensAndSessionsAreDeletedChunkByChunkUntilNoneAreLeft() {
        when(repository.whileLocked(eq("plinko_housekeeping"), eq(0), org.mockito.ArgumentMatchers.any())).thenAnswer(i -> {
            ((Runnable) i.getArgument(2)).run();
            return true;
        });
        when(repository.deleteOldTokens(anyInt())).thenReturn(5000, 5000, 12);
        when(repository.deleteOldSessions(anyInt())).thenReturn(0);
        housekeeping.cleanUp();
        verify(repository, times(3)).deleteOldTokens(5000);
        verify(repository, times(1)).deleteOldSessions(5000);
    }

    @Test
    void whileAnotherInstanceCleansUpThisOneDoesNothing() {
        when(repository.whileLocked(anyString(), anyInt(), org.mockito.ArgumentMatchers.any())).thenReturn(false);
        housekeeping.cleanUp();
        verify(repository, never()).deleteOldTokens(anyInt());
        verify(repository, never()).deleteOldSessions(anyInt());
    }
}
