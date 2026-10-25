
package com.coreproof.backend.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

@RestController
public class DiscoveryController {

    private static final Logger log =
            LoggerFactory.getLogger(DiscoveryController.class);

    private final JdbcTemplate jdbcTemplate;

    private final List<Map<String, Object>> results =
            new CopyOnWriteArrayList<>();

    private final List<Map<String, Object>> specifications =
            new CopyOnWriteArrayList<>();

    public DiscoveryController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/connections/{ref}")
    public Map<String, Object> getConnection(
            @PathVariable String ref) {

        return Map.of(
                "dbType", "POSTGRESQL",
                "host", "localhost",
                "port", 5432,
                "schemaName", "public",
                "connectionRef", ref
        );
    }

    @GetMapping("/introspect/db-schema")
    public Map<String, Object> introspect() {

        String columnSql = """
                SELECT
                    c.table_name,
                    c.column_name,
                    c.data_type,
                    c.is_nullable,
                    c.column_default,
                    CASE
                        WHEN pk.column_name IS NOT NULL THEN true
                        ELSE false
                    END AS is_primary_key
                FROM information_schema.columns c
                LEFT JOIN (
                    SELECT
                        kcu.table_schema,
                        kcu.table_name,
                        kcu.column_name
                    FROM information_schema.table_constraints tc
                    JOIN information_schema.key_column_usage kcu
                      ON tc.constraint_name = kcu.constraint_name
                     AND tc.constraint_schema = kcu.constraint_schema
                     AND tc.table_schema = kcu.table_schema
                    WHERE tc.constraint_type = 'PRIMARY KEY'
                ) pk
                  ON c.table_schema = pk.table_schema
                 AND c.table_name = pk.table_name
                 AND c.column_name = pk.column_name
                WHERE c.table_schema = 'public'
                  AND c.table_catalog = current_database()
                ORDER BY c.table_name, c.ordinal_position
                """;

        List<Map<String, Object>> columns =
                jdbcTemplate.queryForList(columnSql);

        String foreignKeySql = """
                SELECT
                    tc.table_name,
                    kcu.column_name,
                    ccu.table_name AS referenced_table,
                    ccu.column_name AS referenced_column,
                    tc.constraint_name
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_name = kcu.constraint_name
                 AND tc.constraint_schema = kcu.constraint_schema
                 AND tc.table_schema = kcu.table_schema
                JOIN information_schema.constraint_column_usage ccu
                  ON tc.constraint_name = ccu.constraint_name
                 AND tc.constraint_schema = ccu.constraint_schema
                 AND tc.table_schema = ccu.table_schema
                WHERE tc.constraint_type = 'FOREIGN KEY'
                  AND tc.table_schema = 'public'
                ORDER BY tc.table_name, kcu.column_name
                """;

        List<Map<String, Object>> relationships =
                jdbcTemplate.queryForList(foreignKeySql);

        Set<String> tableNames = new LinkedHashSet<>();

        for (Map<String, Object> column : columns) {
            tableNames.add((String) column.get("table_name"));
        }

        Map<String, Object> response = new LinkedHashMap<>();

        response.put("source", "POSTGRESQL_METADATA");
        response.put(
                "database",
                jdbcTemplate.queryForObject(
                        "SELECT current_database()",
                        String.class
                )
        );
        response.put("schema", "public");
        response.put("tables", tableNames);
        response.put("columns", columns);
        response.put("relationships", relationships);
        response.put("columnCount", columns.size());

        log.info(
                "Discovered {} tables, {} columns and {} relationships",
                tableNames.size(),
                columns.size(),
                relationships.size()
        );

        return response;
    }

    @PostMapping("/discovery-agent/results")
    public Map<String, Object> receive(
            @RequestBody Map<String, Object> body) {

        if (body.get("runId") == null
                || body.get("discoveredSchema") == null) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "runId and discoveredSchema are required"
            );
        }

        log.info("Discovery result received: {}", body);

        results.add(new LinkedHashMap<>(body));

        return Map.of(
                "status", "PENDING_APPROVAL",
                "stored", results.size()
        );
    }

    @GetMapping("/discovery-agent/results")
    public List<Map<String, Object>> list() {
        return results;
    }

    @PostMapping("/specifications")
    public Map<String, Object> receiveSpecification(
            @RequestBody Map<String, Object> body) {

        Object runId = body.get("runId");
        Object specification = body.get("specification");

        if (runId == null
                || !(specification instanceof Map<?, ?>)) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "runId and a valid specification object are required"
            );
        }

        Map<String, Object> result = new LinkedHashMap<>();

        result.put("runId", runId);
        result.put("status", "PENDING_APPROVAL");
        result.put("specification", specification);

        specifications.add(new LinkedHashMap<>(result));

        log.info(
                "OpenAPI specification received for runId={}",
                runId
        );

        return result;
    }

    @GetMapping("/specifications")
    public List<Map<String, Object>> listSpecifications() {
        return specifications;
    }

    @PostMapping("/adapters")
    public Map<String, Object> saveAdapter(
            @RequestBody Map<String, Object> body) {

        Object runId = body.get("runId");
        Object specification = body.get("specification");
        Object generatedCode = body.get("generatedCode");

        if (runId == null
                || !(specification instanceof Map<?, ?>)
                || !(generatedCode instanceof String)) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "runId, specification object, and generatedCode string are required"
            );
        }

        // Convert the specification map into valid JSON.
        String specificationJson;

        try {
            specificationJson =
                    new ObjectMapper().writeValueAsString(specification);

        } catch (JsonProcessingException e) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Could not serialize specification to JSON",
                    e
            );
        }

        String sql = """
                INSERT INTO public.generated_adapters
                    (run_id, specification, generated_code, language, status)
                VALUES (?, ?::jsonb, ?, ?, ?)
                RETURNING adapter_id
                """;

        Long adapterId = jdbcTemplate.queryForObject(
                sql,
                Long.class,
                runId.toString(),
                specificationJson,
                generatedCode,
                body.getOrDefault("language", "JAVA").toString(),
                "PENDING_APPROVAL"
        );

        log.info(
                "Generated adapter stored: adapterId={}, runId={}",
                adapterId,
                runId
        );

        return Map.of(
                "adapterId", adapterId,
                "runId", runId,
                "status", "PENDING_APPROVAL"
        );
    }

    @PatchMapping("/adapters/{adapterId}/status")
    public Map<String, Object> updateAdapterStatus(
            @PathVariable Long adapterId,
            @RequestBody Map<String, String> body) {

        String status = body.get("status");

        if (!"APPROVED".equals(status) && !"REJECTED".equals(status)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Status must be APPROVED or REJECTED"
            );
        }

        int updated = jdbcTemplate.update(
                """
                UPDATE public.generated_adapters
                SET status = ?
                WHERE adapter_id = ?
                """,
                status,
                adapterId
        );

        if (updated == 0) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Adapter not found"
            );
        }

        return Map.of(
                "adapterId", adapterId,
                "status", status
        );
    }
}
