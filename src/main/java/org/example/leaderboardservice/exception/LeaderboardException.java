package org.example.leaderboardservice.exception;

import org.springframework.http.HttpStatus;

public class LeaderboardException extends RuntimeException {
    private final HttpStatus status;
    public LeaderboardException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }
    public HttpStatus status() { return status; }
}
