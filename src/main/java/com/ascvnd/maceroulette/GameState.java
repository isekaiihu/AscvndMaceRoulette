package com.ascvnd.maceroulette;

/** The high level state of the minigame. */
public enum GameState {
    /** No game running, players may join the queue. */
    IDLE,
    /** Enough players queued, the 60 second lobby countdown is running. */
    QUEUE_COUNTDOWN,
    /** Players are in the arena, the 10 second mace roulette spin is running (players frozen). */
    ROULETTE,
    /** A round is live, players can fight. */
    COMBAT,
    /** The game has finished, winner/loser screens are showing. */
    ENDING
}
