package com.syncforge.api.resume.model;

public record IssuedResumeToken(
        String token,
        ResumeToken record
) {
}
