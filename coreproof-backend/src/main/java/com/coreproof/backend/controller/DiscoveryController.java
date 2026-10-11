package com.coreproof.backend.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

@RestController
public class DiscoveryController {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryController.class);
    private final List<Map<String, Object>> results = new CopyOnWriteArrayList<>();

    @GetMapping("/connections/{ref}")
    public Map<String, Object> getConnection(@PathVariable String ref) {
        return Map.of(
                "dbType", "POSTGRESQL",
                "host", "localhost",
                "port", 5432,
                "schemaName", "core_banking",
                "authToken", "stub-token-" + ref);
    }

    @GetMapping("/introspect/db-schema")
    public List<Map<String, Object>> introspect() {
        return List.of(
                Map.of("table", "accounts", "column", "account_id", "type", "BIGINT"),
                Map.of("table", "accounts", "column", "balance", "type", "NUMERIC"),
                Map.of("table", "customers", "column", "customer_id", "type", "BIGINT"));
    }

    @PostMapping("/discovery-agent/results")
    public Map<String, Object> receive(@RequestBody Map<String, Object> body) {
        log.info("Discovery result received: {}", body);
        results.add(body);
        return Map.of("status", "PENDING_APPROVAL", "stored", results.size());
    }

    @GetMapping("/discovery-agent/results")
    public List<Map<String, Object>> list() {
        return results;
    }
}