package dev.jvmd.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/** Implements 4.1: shared record-aware JSON serialization. */
public final class Json {
    public static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private Json() { }
}
