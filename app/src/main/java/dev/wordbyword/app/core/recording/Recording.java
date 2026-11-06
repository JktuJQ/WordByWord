package dev.wordbyword.app.core.recording;

import java.net.URI;
import java.util.OptionalLong;

public record Recording(String id, URI audioUri, String title, OptionalLong durationMs) {}
