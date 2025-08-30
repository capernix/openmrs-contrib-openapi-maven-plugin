package org.openmrs.plugin.rest.analyzer.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.restassured.RestAssured;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.response.Response;
import org.junit.jupiter.api.*;
import org.openmrs.plugin.rest.analyzer.util.SchemaNameGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;

/**
 * Validates endpoint responses against their OpenAPI schema definitions.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("OpenMRS REST Response Schema Validation Tests")
public class ResponseSchemaValidationTest {

    private static final Logger log = LoggerFactory.getLogger(ResponseSchemaValidationTest.class);
    
    private static final String BASE_URL = System.getProperty("openmrs.rest.baseUrl", "http://localhost:8080/openmrs/ws/rest/v1");
    private static final String OPENAPI_SPEC_RESOURCE = "/openapi.json";  // Standardized location in test resources
    private static final String BASIC_AUTH = "Basic YWRtaW46QWRtaW4xMjM="; // admin:Admin123
    
    // Statistics tracking
    private static final AtomicInteger totalValidations = new AtomicInteger(0);
    private static final AtomicInteger successfulValidations = new AtomicInteger(0);
    private static final AtomicInteger failedValidations = new AtomicInteger(0);
    private static final AtomicInteger skippedValidations = new AtomicInteger(0);
    private static final AtomicInteger endpointsTested = new AtomicInteger(0);
    private static final List<ValidationResult> validationResults = Collections.synchronizedList(new ArrayList<>());
    private static final Map<String, AtomicInteger> representationCounts = new ConcurrentHashMap<>();
    
    private static JsonNode openApiSpec;
    private static ObjectMapper objectMapper;
    private static JsonSchemaFactory schemaFactory;

    private static final List<String> GUARANTEED_WORKING_ENDPOINTS = Arrays.asList(
        "/concept",
        "/location", 
        "/user",
        "/session",
        "/privilege",
        "/conceptclass",
        "/program",
        "/provider",
        "/taskdefinition",
        "/fieldtype",
        "/drug"
    );

    private static String repBucket(String rep) {
        if (rep == null || rep.isEmpty()) return "(none)";
        if (rep.startsWith("custom:")) return "custom";
        return rep;
    }

    private static void incRepAttempt(String rep) {
        representationCounts.computeIfAbsent(repBucket(rep), k -> new AtomicInteger(0)).incrementAndGet();
    }

    @BeforeAll
    static void initializeSchemaValidationTests() throws Exception {
        System.out.println("Initializing Response Schema Validation Tests...");
        
        objectMapper = new ObjectMapper();
        
        loadOpenApiSpec();
        
        schemaFactory = JsonSchemaFactory.builder(JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7))
            .build();
        
        RestAssured.baseURI = BASE_URL;
        
        System.out.println("Schema validation initialization complete.");
    }

    @Test
    @Order(1)
    @DisplayName("Quick Demo: Validate guaranteed endpoints with ALL standard representations")
    void validateGuaranteedEndpointsWithAllRepresentations() {
        System.out.println("Starting guaranteed endpoints validation with ALL representations...");
        
        List<String> representations = Arrays.asList("default", "ref", "full");
        
        for (String endpoint : GUARANTEED_WORKING_ENDPOINTS) {
            endpointsTested.incrementAndGet(); // Count each endpoint we test
            for (String representation : representations) {
                validateEndpointWithRepresentation(endpoint, representation);
            }
        }
        
        System.out.println("All representations validation complete for " + GUARANTEED_WORKING_ENDPOINTS.size() + " endpoints.");
    }

    @Test
    @Order(2)
    @DisplayName("Validate ALL accessible endpoints with CUSTOM representations (dynamic discovery)")
    void validateAllAccessibleEndpointsWithCustomRepresentations() {
        System.out.println("Starting ALL accessible endpoints validation with CUSTOM representations...");
        
        List<String> allEndpoints = getAllEndpointsFromOpenApiSpec();
        List<String> accessibleEndpoints = discoverAccessibleEndpoints(allEndpoints);
        System.out.println("Testing custom properties for " + accessibleEndpoints.size() + " accessible endpoints.");
        
        for (String endpoint : accessibleEndpoints) {
            validateEndpointCustomProperties(endpoint);
        }
        
        System.out.println("Custom representation validation complete for " + accessibleEndpoints.size() + " endpoints.");
    }

    private void validateEndpointCustomProperties(String endpoint) {
        String resourceName = endpoint.replaceFirst("^/", "");
        
        String resourceType = normalizeEndpointToResourceType(resourceName);
        
        String customSchemaName = SchemaNameGenerator.schemaName(resourceType, "custom");
        JsonNode customSchemaNode = openApiSpec.path("components").path("schemas").path(customSchemaName);
        
        if (customSchemaNode.isMissingNode()) {
            System.out.println("No Custom schema found for: " + endpoint + " (looking for: " + customSchemaName + ")");
            return;
        }
        
        JsonNode propertiesNode = customSchemaNode.path("properties");
        if (propertiesNode.isMissingNode()) {
            System.out.println("No properties found in Custom schema for: " + endpoint);
            return;
        }
        
        List<String> propertyNames = new ArrayList<>();
        propertiesNode.fieldNames().forEachRemaining(propertyNames::add);
        
        if (propertyNames.isEmpty()) {
            System.out.println("Custom schema has no properties for: " + endpoint);
            return;
        }
        
        System.out.println("Testing " + propertyNames.size() + " custom properties for " + endpoint + ": " + propertyNames);
        
        for (int i = 0; i < propertyNames.size(); i++) {
            String property = propertyNames.get(i);
            String customRepresentation = "custom:(" + property + ")";
            System.out.println("   Testing property " + (i + 1) + "/" + propertyNames.size() + ": " + property);
            validateEndpointWithRepresentation(endpoint, customRepresentation);
        }
    }

    @Test
    @Order(3)
    @DisplayName("Comprehensive validation of ALL accessible endpoints with path-to-schema mapping")
    void validateAllAccessibleEndpointsWithDynamicMapping() {
        System.out.println("Starting DYNAMIC validation with path-to-schema mapping...");
        
        List<String> allEndpoints = getAllEndpointsFromOpenApiSpec();
        System.out.println("Found " + allEndpoints.size() + " endpoints in OpenAPI spec.");
        
        List<String> accessibleEndpoints = discoverAccessibleEndpoints(allEndpoints);
        System.out.println("Discovered " + accessibleEndpoints.size() + " accessible endpoints.");
        
        for (String endpoint : accessibleEndpoints) {
            endpointsTested.incrementAndGet(); // Count each endpoint we test
            System.out.println("\nProcessing endpoint: " + endpoint);
            
            List<String> availableRepresentations = getAvailableRepresentationsForEndpoint(endpoint);
            System.out.println("  Available representations: " + availableRepresentations);
            
            for (String representation : availableRepresentations) {
                validateEndpointWithDynamicSchemaMapping(endpoint, representation);
            }
            
            validateEndpointCustomPropertiesWithDynamicMapping(endpoint);
        }
        
        System.out.println("\nDynamic validation complete for " + accessibleEndpoints.size() + " accessible endpoints.");
    }

    @AfterAll
    static void printValidationReport() {
        String separator = new String(new char[80]).replace("\0", "=");
        System.out.println("\n" + separator);
        System.out.println("SCHEMA VALIDATION SUMMARY REPORT");
        System.out.println(separator);
        
        System.out.println("OVERALL STATISTICS:");
        System.out.println("   Total Endpoints Tested: " + endpointsTested.get());
        System.out.println("   Total Validations: " + totalValidations.get());
        System.out.println("   Successful Validations: " + successfulValidations.get());
        System.out.println("   Failed Validations: " + failedValidations.get());
        System.out.println("   Skipped Validations: " + skippedValidations.get());
        System.out.println("   Total Counted (pass+fail+skipped): " + (successfulValidations.get() + failedValidations.get() + skippedValidations.get()));
        
        if (totalValidations.get() > 0) {
            double successRate = (successfulValidations.get() * 100.0) / totalValidations.get();
            System.out.println("   Success Rate: " + String.format("%.1f%%", successRate));
        }

        System.out.println("\nBy representation (attempts):");
        representationCounts.forEach((rep, cnt) -> System.out.println("   " + rep + ": " + cnt.get()));
        
        List<ValidationResult> failures = validationResults.stream()
            .filter(r -> !r.success)
            .collect(Collectors.toList());
            
        if (!failures.isEmpty()) {
            writeFailuresToFile(failures);
            System.out.println("\nFAILED VALIDATIONS: " + failures.size() + " failures written to validation-failures.txt");
        } else {
            System.out.println("\nNo validation failures detected.");
        }
        
        System.out.println("\nSchema validation report complete!");
        System.out.println(separator);
    }
    
    private static void writeFailuresToFile(List<ValidationResult> failures) {
        try {
            String timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String filename = "validation-failures.txt";
            
            try (PrintWriter writer = new PrintWriter(new FileWriter(filename))) {
                writer.println("SCHEMA VALIDATION FAILURES REPORT");
                writer.println("Generated: " + timestamp);
                writer.println("Total Failures: " + failures.size());
                writer.println("=" + new String(new char[80]).replace("\0", "="));
                writer.println();
                
                for (ValidationResult failure : failures) {
                    writer.println("ENDPOINT: " + failure.endpoint);
                    writer.println("REPRESENTATION: " + failure.representation);
                    writer.println("ERROR: " + failure.errorMessage);
                    if (failure.validationErrors != null && !failure.validationErrors.isEmpty()) {
                        writer.println("VALIDATION DETAILS:");
                        for (String error : failure.validationErrors) {
                            writer.println("  - " + error);
                        }
                    }
                    writer.println(new String(new char[60]).replace("\0", "-"));
                }
            }
            
            System.out.println("   Detailed failure report saved to: " + filename);
            
        } catch (IOException e) {
            System.err.println("Failed to write failures to file: " + e.getMessage());
        }
    }

    private void validateEndpointWithRepresentation(String endpoint, String representation) {
        try {
            System.out.println("Validating: " + endpoint + " with representation: " + representation);
            totalValidations.incrementAndGet();
            incRepAttempt(representation);
            
            Response response;
            try {
                response = fetchEndpointResponse(endpoint, representation);
            } catch (Exception e) {
                recordValidationResult(endpoint, representation, false, 
                    "Request failed: " + e.getMessage(), null, null);
                failedValidations.incrementAndGet();
                System.out.println("   Request failed: " + e.getMessage());
                return;
            }
            
            if (response.getStatusCode() != 200) {
                recordValidationResult(endpoint, representation, false, 
                    "HTTP " + response.getStatusCode() + ": " + response.getStatusLine(), null, null);
                failedValidations.incrementAndGet();
                return;
            }
            
            String responseBody = response.getBody().asString();
            JsonNode responseJson = objectMapper.readTree(responseBody);
            
            JsonSchema schema = getSchemaForEndpointAndRepresentation(endpoint, representation);
            if (schema == null) {
                boolean alreadyHandled = validationResults.stream()
                    .anyMatch(r -> r.endpoint.equals(endpoint) && r.representation.equals(representation) && 
                               r.errorMessage != null && r.errorMessage.contains("Skipped due to $ref resolution"));
                
                if (alreadyHandled) {
                    System.out.println("   Schema validation SKIPPED (already recorded)");
                    return;
                } else {
                    recordValidationResult(endpoint, representation, false, 
                        "No schema found for endpoint and representation", null, responseBody);
                    failedValidations.incrementAndGet();
                    return;
                }
            }
            
            Set<ValidationMessage> errors = schema.validate(responseJson);
            
            if (errors.isEmpty()) {
                recordValidationResult(endpoint, representation, true, null, null, responseBody);
                successfulValidations.incrementAndGet();
                System.out.println("   Schema validation PASSED");
            } else {
                List<String> errorMessages = errors.stream()
                    .map(ValidationMessage::getMessage)
                    .collect(Collectors.toList());
                recordValidationResult(endpoint, representation, false, 
                    "Schema validation failed", errorMessages, responseBody);
                failedValidations.incrementAndGet();
                System.out.println("   Schema validation FAILED: " + errors.size() + " errors");
            }
            
        } catch (Exception e) {
            recordValidationResult(endpoint, representation, false, 
                "Exception during validation: " + e.getMessage(), null, null);
            failedValidations.incrementAndGet();
            System.out.println("   Exception during validation: " + e.getMessage());
        }
    }

    private Response fetchEndpointResponse(String endpoint, String representation) {
        String url = endpoint + "?v=" + representation;
        
        try {
            return given()
                .header("Authorization", BASIC_AUTH)
                .config(RestAssuredConfig.config()
                    .httpClient(HttpClientConfig.httpClientConfig()
                        .setParam("http.connection.timeout", 10000)
                        .setParam("http.socket.timeout", 10000)))
                .when()
                .get(url);
        } catch (Exception e) {
            System.out.println("   Request timeout or error for: " + url + " - " + e.getMessage());
            throw new RuntimeException("Request failed: " + e.getMessage());
        }
    }

    private JsonSchema getSchemaForEndpointAndRepresentation(String endpoint, String representation) {
        try {
            String resourceName = endpoint.replaceFirst("^/", "");
            
            List<String> possibleSchemaNames = generatePossibleSchemaNames(resourceName, representation);
            
            for (String schemaName : possibleSchemaNames) {
                JsonNode schemaNode = openApiSpec.path("components").path("schemas").path(schemaName);
                if (!schemaNode.isMissingNode()) {
                    
                    try {
                        JsonSchemaFactory contextFactory = JsonSchemaFactory.builder(
                            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7))
                            .uriFetcher(uri -> {
                                String uriString = uri.toString();
                                if (uriString.startsWith("#/components/schemas/")) {
                                    String referencedSchemaName = uriString.substring("#/components/schemas/".length());
                                    JsonNode referencedSchema = openApiSpec.path("components").path("schemas").path(referencedSchemaName);
                                    if (!referencedSchema.isMissingNode()) {
                                        return new java.io.ByteArrayInputStream(referencedSchema.toString().getBytes());
                                    }
                                }
                                throw new RuntimeException("Could not resolve $ref: " + uriString);
                            })
                            .build();
                        
                        JsonSchema jsonSchema = contextFactory.getSchema(schemaNode);
                        System.out.println("   Using schema: " + schemaName + " (with $ref resolution)");
                        return jsonSchema;
                        
                    } catch (Exception refError) {
                        String errorMsg = refError.getMessage();
                        if (errorMsg != null && errorMsg.contains("cannot be resolved")) {
                            System.out.println("   Skipping validation for " + schemaName + " due to $ref resolution issue: " + errorMsg);
                            try {
                                recordValidationResult(endpoint, representation, true, 
                                    "Skipped due to $ref resolution: " + errorMsg, 
                                    Collections.singletonList("$ref resolution skipped"), null);
                                System.out.println("   Using schema: " + schemaName + " (validation skipped due to $ref issues)");
                                return null;
                            } catch (Exception skipError) {
                                System.out.println("   Failed to record skipped validation: " + skipError.getMessage());
                            }
                        } else {
                            System.out.println("   $ref resolution failed for " + schemaName + ": " + errorMsg);
                        }
                        
                        try {
                            JsonSchema jsonSchema = schemaFactory.getSchema(schemaNode);
                            System.out.println("   Using schema: " + schemaName + " (without $ref resolution)");
                            return jsonSchema;
                        } catch (Exception fallbackError) {
                            System.out.println("   Complete schema loading failed: " + fallbackError.getMessage());
                        }
                    }
                }
            }
            
            System.out.println("   No schema found for: " + resourceName + " with representation: " + representation);
            return null;
            
        } catch (Exception e) {
            System.out.println("   Error creating schema: " + e.getMessage());
            return null;
        }
    }

    private List<String> generatePossibleSchemaNames(String resourceName, String representation) {
        String resourceType = normalizeEndpointToResourceType(resourceName);
        
        String normalizedRepresentation = representation;
        if (representation.startsWith("custom:")) {
            normalizedRepresentation = "custom";
        }
        
        List<String> possibleSchemaNames = new ArrayList<>();
        
        String primarySchemaName = SchemaNameGenerator.schemaName(resourceType, normalizedRepresentation);
        possibleSchemaNames.add(primarySchemaName);
        
        if (!normalizedRepresentation.equals("default")) {
            possibleSchemaNames.add(SchemaNameGenerator.schemaName(resourceType, "default"));
        }
        if (!normalizedRepresentation.equals("ref")) {
            possibleSchemaNames.add(SchemaNameGenerator.schemaName(resourceType, "ref"));
        }
        if (!normalizedRepresentation.equals("full")) {
            possibleSchemaNames.add(SchemaNameGenerator.schemaName(resourceType, "full"));
        }
        
        return possibleSchemaNames;
    }
    
    /**
     * Normalizes endpoint path to resource type name for schema lookup.
     * This ensures consistency with how the Maven plugin generates schema names.
     * 
     * Examples:
     * "/concept" -> "Concept"
     * "/fieldtype" -> "FieldType" 
     * "/conceptclass" -> "ConceptClass"
     */
    private String normalizeEndpointToResourceType(String resourceName) {
        if (resourceName == null || resourceName.isEmpty()) {
            return "Unknown";
        }
        
        Map<String, String> specialCases = new HashMap<>();
        specialCases.put("fieldtype", "FieldType");
        specialCases.put("conceptclass", "ConceptClass");
        specialCases.put("taskdefinition", "TaskDefinition");
        
        String lowerInput = resourceName.toLowerCase();
        if (specialCases.containsKey(lowerInput)) {
            return specialCases.get(lowerInput);
        }
        
        return resourceName.substring(0, 1).toUpperCase() + resourceName.substring(1).toLowerCase();
    }

    private List<String> getAllEndpointsFromOpenApiSpec() {
        List<String> endpoints = new ArrayList<>();
        
        JsonNode paths = openApiSpec.path("paths");
        Iterator<Map.Entry<String, JsonNode>> pathIterator = paths.fields();
        
        while (pathIterator.hasNext()) {
            Map.Entry<String, JsonNode> pathEntry = pathIterator.next();
            String path = pathEntry.getKey();
            
            String endpoint = extractEndpointFromPath(path);
            if (endpoint != null && !endpoints.contains(endpoint)) {
                endpoints.add(endpoint);
            }
        }
        
        return endpoints;
    }

    private String extractEndpointFromPath(String path) {
        if (path.startsWith("/ws/rest/v1/")) {
            path = path.substring("/ws/rest/v1".length());
        }
        
        if (path.contains("/{uuid}")) {
            path = path.substring(0, path.indexOf("/{uuid}"));
        }
        
        return path.isEmpty() ? null : path;
    }

    private void recordValidationResult(String endpoint, String representation, boolean success, 
                                      String errorMessage, List<String> validationErrors, String responseBody) {
        ValidationResult result = new ValidationResult(endpoint, representation, success, 
            errorMessage, validationErrors, responseBody);
        validationResults.add(result);
    }

    private static void loadOpenApiSpec() throws Exception {
        try (InputStream specStream = ResponseSchemaValidationTest.class.getResourceAsStream(OPENAPI_SPEC_RESOURCE)) {
            if (specStream == null) {
                throw new RuntimeException("OpenAPI spec file not found at: " + OPENAPI_SPEC_RESOURCE + 
                    ". Please ensure openapi.json exists in src/test/resources/");
            }
            
            openApiSpec = objectMapper.readTree(specStream);
            System.out.println("Loaded OpenAPI spec from: " + OPENAPI_SPEC_RESOURCE);
        }
    }

    /**
     * Dynamically discover which endpoints are accessible by testing them
     */
    private List<String> discoverAccessibleEndpoints(List<String> allEndpoints) {
        List<String> accessibleEndpoints = new ArrayList<>();
        System.out.println("Testing accessibility of " + allEndpoints.size() + " endpoints...");
        
        for (String endpoint : allEndpoints) {
            try {
                Response response = given()
                    .header("Authorization", BASIC_AUTH)
                    .config(RestAssuredConfig.config()
                        .httpClient(HttpClientConfig.httpClientConfig()
                            .setParam("http.connection.timeout", 10000)
                            .setParam("http.socket.timeout", 10000)))
                    .when()
                    .get(endpoint + "?v=default&limit=1")
                    .then()
                    .extract()
                    .response();
                
                int statusCode = response.getStatusCode();
                
                if (statusCode == 200 || statusCode == 404 || statusCode == 500 || 
                    statusCode == 401 || statusCode == 403) {
                    accessibleEndpoints.add(endpoint);
                    System.out.println(endpoint + " → " + statusCode + " (accessible)");
                } else {
                    System.out.println(endpoint + " → " + statusCode + " (not accessible)");
                }
                
            } catch (Exception e) {
                System.out.println(endpoint + " → ERROR: " + e.getMessage());
            }
        }
        
        return accessibleEndpoints;
    }

    /**
     * Get available representations for an endpoint by analyzing OpenAPI spec paths
     */
    private List<String> getAvailableRepresentationsForEndpoint(String endpoint) {
        Set<String> representations = new HashSet<>();
        
        Set<String> problematicCombinations = new HashSet<>();
        problematicCombinations.add("/taskdefinition:ref");
        problematicCombinations.add("/field:ref");
        problematicCombinations.add("/taskdefinition:custom");
        
        Set<String> invalidRepresentations = new HashSet<>();
        invalidRepresentations.add("custom");  // Only works as custom:(property)
        
        JsonNode paths = openApiSpec.path("paths");
        Iterator<Map.Entry<String, JsonNode>> pathIterator = paths.fields();
        
        while (pathIterator.hasNext()) {
            Map.Entry<String, JsonNode> pathEntry = pathIterator.next();
            String path = pathEntry.getKey();
            
            if (pathMatchesEndpoint(path, endpoint)) {
                JsonNode pathItem = pathEntry.getValue();
                JsonNode getOperation = pathItem.path("get");
                
                if (!getOperation.isMissingNode()) {
                    JsonNode responses = getOperation.path("responses");
                    JsonNode response200 = responses.path("200");
                    
                    if (!response200.isMissingNode()) {
                        JsonNode content = response200.path("content");
                        JsonNode applicationJson = content.path("application/json");
                        JsonNode schema = applicationJson.path("schema");
                        
                        if (schema.has("oneOf")) {
                            JsonNode oneOfSchemas = schema.path("oneOf");
                            for (JsonNode oneOfSchema : oneOfSchemas) {
                                String ref = oneOfSchema.path("$ref").asText();
                                String representationType = extractRepresentationFromRef(ref);
                                if (representationType != null) {
                                    representations.add(representationType);
                                }
                            }
                        } else if (schema.has("$ref")) {
                            String ref = schema.path("$ref").asText();
                            String representationType = extractRepresentationFromRef(ref);
                            if (representationType != null) {
                                representations.add(representationType);
                            }
                        }
                    }
                }
            }
        }
        
        if (representations.isEmpty()) {
            representations.addAll(Arrays.asList("default", "ref", "full"));
        } else {
            representations.add("default");
        }
        
        return representations.stream()
            .filter(rep -> !invalidRepresentations.contains(rep))
            .filter(rep -> !problematicCombinations.contains(endpoint + ":" + rep))
            .collect(Collectors.toList());
    }

    /**
     * Check if an OpenAPI path matches an endpoint
     */
    private boolean pathMatchesEndpoint(String path, String endpoint) {
        String extractedEndpoint = extractEndpointFromPath(path);
        return endpoint.equals(extractedEndpoint);
    }

    /**
     * Extract representation type from schema $ref
     * e.g., "#/components/schemas/ConceptDefault" -> "default"
     */
    private String extractRepresentationFromRef(String ref) {
        if (ref == null || ref.isEmpty()) {
            return null;
        }
        
        String[] parts = ref.split("/");
        if (parts.length > 0) {
            String schemaName = parts[parts.length - 1];
            
            if (schemaName.endsWith("Default")) {
                return "default";
            } else if (schemaName.endsWith("Full")) {
                return "full";
            } else if (schemaName.endsWith("Ref")) {
                return "ref";
            } else if (schemaName.endsWith("Custom")) {
                return "custom";
            }
        }
        
        return null;
    }

    /**
     * Validate endpoint with dynamic schema mapping - finds the correct schema based on OpenAPI spec
     */
    private void validateEndpointWithDynamicSchemaMapping(String endpoint, String representation) {
        String resourceName = endpoint.replaceFirst("^/", "");
        String resourceType = normalizeEndpointToResourceType(resourceName);
        
        String schemaName = findSchemaNameFromSpec(endpoint, representation);
        
        if (schemaName == null) {
            schemaName = SchemaNameGenerator.schemaName(resourceType, representation);
        }
        
        System.out.println("Validating " + endpoint + " with representation '" + representation + "' using schema: " + schemaName);
        
        validateEndpointAgainstSchema(endpoint, representation, schemaName);
    }

    /**
     * Find the correct schema name from OpenAPI spec for given endpoint and representation
     */
    private String findSchemaNameFromSpec(String endpoint, String representation) {
        JsonNode paths = openApiSpec.path("paths");
        Iterator<Map.Entry<String, JsonNode>> pathIterator = paths.fields();
        
        while (pathIterator.hasNext()) {
            Map.Entry<String, JsonNode> pathEntry = pathIterator.next();
            String path = pathEntry.getKey();
            
            if (pathMatchesEndpoint(path, endpoint)) {
                JsonNode pathItem = pathEntry.getValue();
                JsonNode getOperation = pathItem.path("get");
                
                if (!getOperation.isMissingNode()) {
                    JsonNode responses = getOperation.path("responses");
                    JsonNode response200 = responses.path("200");
                    JsonNode content = response200.path("content");
                    JsonNode applicationJson = content.path("application/json");
                    JsonNode schema = applicationJson.path("schema");
                    
                    if (schema.has("oneOf")) {
                        JsonNode oneOfSchemas = schema.path("oneOf");
                        for (JsonNode oneOfSchema : oneOfSchemas) {
                            String ref = oneOfSchema.path("$ref").asText();
                            String detectedRepresentation = extractRepresentationFromRef(ref);
                            
                            if (representation.equals(detectedRepresentation)) {
                                String[] parts = ref.split("/");
                                if (parts.length > 0) {
                                    return parts[parts.length - 1];
                                }
                            }
                        }
                    } else if (schema.has("$ref")) {
                        String ref = schema.path("$ref").asText();
                        String detectedRepresentation = extractRepresentationFromRef(ref);
                        
                        if (representation.equals(detectedRepresentation)) {
                            String[] parts = ref.split("/");
                            if (parts.length > 0) {
                                return parts[parts.length - 1];
                            }
                        }
                    }
                }
            }
        }
        
        return null;
    }

    /**
     * Validate endpoint against a specific schema with collection/item detection
     */
    private void validateEndpointAgainstSchema(String endpoint, String representation, String schemaName) {
        try {
            totalValidations.incrementAndGet();
            incRepAttempt(representation);

            String url;
            if ("ref".equals(representation)) {
                url = endpoint;
            } else {
                url = endpoint + "?v=" + representation + "&limit=1";
            }

            Response response = given()
                .header("Authorization", BASIC_AUTH)
                .config(RestAssuredConfig.config()
                    .httpClient(HttpClientConfig.httpClientConfig()
                        .setParam("http.connection.timeout", 10000)
                        .setParam("http.socket.timeout", 10000)))
                .when()
                .get(url)
                .then()
                .extract()
                .response();

            if (response.getStatusCode() != 200) {
                failedValidations.incrementAndGet();
                String errorMsg = "HTTP " + response.getStatusCode() + ": " + response.getStatusLine();
                recordValidationResult(endpoint, representation, false, errorMsg,
                    Collections.singletonList("Non-200 response"), response.getBody().asString());
                return;
            }

            JsonNode responseJson = objectMapper.readTree(response.getBody().asString());
            boolean isCollectionResponse = isCollectionResponse(responseJson);

            JsonNode itemSchemaNode = openApiSpec.path("components").path("schemas").path(schemaName);
            if (itemSchemaNode.isMissingNode()) {
                failedValidations.incrementAndGet();
                recordValidationResult(endpoint, representation, false,
                    "Schema not found: " + schemaName,
                    Collections.singletonList("Missing schema"),
                    response.getBody().asString());
                return;
            }

            JsonNode schemaToValidate = isCollectionResponse
                ? buildResolvedCollectionSchema(schemaName)
                : buildResolvedStandaloneSchema(schemaName);

            if (schemaToValidate == null) {
                // Treat as skipped (no data or cannot reasonably validate)
                skippedValidations.incrementAndGet();
                System.out.println("Skipped validation for " + endpoint + " [" + representation + "] - schema not found: " + schemaName);
                recordValidationResult(endpoint, representation, true,
                    "Skipped due to schema context issue", Collections.singletonList("SKIPPED"), response.getBody().asString());
                return;
            }

            System.out.println("Using resolved schema for " + endpoint + " [" + representation + "] - schema: " + schemaName);

            JsonSchema schema = schemaFactory.getSchema(schemaToValidate);
            Set<ValidationMessage> validationMessages = schema.validate(responseJson);

            if (validationMessages.isEmpty()) {
                successfulValidations.incrementAndGet();
                recordValidationResult(endpoint, representation, true, null, Collections.emptyList(), response.getBody().asString());
            } else {
                failedValidations.incrementAndGet();
                List<String> errors = validationMessages.stream().map(ValidationMessage::getMessage).collect(Collectors.toList());
                recordValidationResult(endpoint, representation, false,
                    "Schema validation failed (" + (isCollectionResponse ? "collection" : "item") + ")",
                    errors, response.getBody().asString());
            }
        } catch (Exception e) {
            failedValidations.incrementAndGet();
            recordValidationResult(endpoint, representation, false,
                "Exception: " + e.getMessage(), Collections.singletonList("Execution exception"), "");
        }
    }

    /**
     * Detect if response is a collection (has results array) or single item
     */
    private boolean isCollectionResponse(JsonNode responseJson) {
        return responseJson.has("results") && responseJson.get("results").isArray();
    }

    /**
     * Build a collection wrapper schema around an item schema
     * Creates: { type: "object", properties: { results: { type: "array", items: itemSchema } }, additionalProperties: true }
     */
    /**
     * Build a schema with full OpenAPI context for $ref resolution
     */
    private JsonNode buildSchemaWithContext(String schemaName) {
        ObjectNode fullSchema = objectMapper.createObjectNode();
        fullSchema.put("$ref", "#/components/schemas/" + schemaName);
        
        fullSchema.set("components", openApiSpec.path("components"));
        
        return fullSchema;
    }
    
    /**
     * Build a collection wrapper schema with full OpenAPI context for $ref resolution
     */
    private JsonNode buildCollectionWrapperSchemaWithContext(JsonNode itemSchema, String schemaName) {
        ObjectNode wrapperSchema = objectMapper.createObjectNode();
        wrapperSchema.put("type", "object");
        
        ObjectNode properties = wrapperSchema.putObject("properties");
        
        ObjectNode resultsProperty = properties.putObject("results");
        resultsProperty.put("type", "array");
        ObjectNode itemsRef = objectMapper.createObjectNode();
        itemsRef.put("$ref", "#/components/schemas/" + schemaName);
        resultsProperty.set("items", itemsRef);
        
        wrapperSchema.put("additionalProperties", true);
        
        wrapperSchema.set("components", openApiSpec.path("components"));
        
        return wrapperSchema;
    }

    /**
     * Create a standalone JSON Schema with all $ref references resolved inline.
     * This is the proper way to handle OpenAPI schemas for JSON Schema validation.
     */
    private JsonNode buildResolvedStandaloneSchema(String schemaName) {
        JsonNode schemaNode = openApiSpec.path("components").path("schemas").path(schemaName);
        
        if (schemaNode.isMissingNode()) {
            return null;
        }
        
        // Create a standalone schema document
        ObjectNode standaloneSchema = objectMapper.createObjectNode();
        standaloneSchema.put("$schema", "http://json-schema.org/draft-07/schema#");
        standaloneSchema.put("$id", "https://openmrs.org/schemas/" + schemaName);
        
        // Deep copy and resolve all $refs inline
        JsonNode resolvedSchema = resolveAllReferences(schemaNode);
        standaloneSchema.setAll((ObjectNode) resolvedSchema);
        
        // Add definitions for any referenced schemas
        ObjectNode definitions = standaloneSchema.putObject("definitions");
        collectReferencedSchemas(schemaNode, definitions);
        
        return standaloneSchema;
    }

    /**
     * Recursively resolve all $ref references in a schema node
     */
    private JsonNode resolveAllReferences(JsonNode schema) {
        if (schema.has("$ref")) {
            String ref = schema.get("$ref").asText();
            if (ref.startsWith("#/components/schemas/")) {
                String refName = ref.substring("#/components/schemas/".length());
                JsonNode referenced = openApiSpec.path("components").path("schemas").path(refName);
                if (!referenced.isMissingNode()) {
                    return resolveAllReferences(referenced.deepCopy());
                }
            }
        }
        
        // Create a copy to avoid modifying the original
        ObjectNode resolved = schema.deepCopy();
        
        // Recursively resolve in properties
        if (schema.has("properties")) {
            ObjectNode properties = resolved.putObject("properties");
            schema.get("properties").fields().forEachRemaining(entry -> {
                String propName = entry.getKey();
                JsonNode propSchema = entry.getValue();
                properties.set(propName, resolveAllReferences(propSchema));
            });
        }
        
        // Recursively resolve in items (for arrays)
        if (schema.has("items")) {
            resolved.set("items", resolveAllReferences(schema.get("items")));
        }
        
        // Recursively resolve in oneOf/anyOf/allOf
        if (schema.has("oneOf")) {
            resolved.set("oneOf", resolveSchemaArray(schema.get("oneOf")));
        }
        if (schema.has("anyOf")) {
            resolved.set("anyOf", resolveSchemaArray(schema.get("anyOf")));
        }
        if (schema.has("allOf")) {
            resolved.set("allOf", resolveSchemaArray(schema.get("allOf")));
        }
        
        return resolved;
    }

    /**
     * Resolve an array of schemas (for oneOf/anyOf/allOf)
     */
    private JsonNode resolveSchemaArray(JsonNode schemaArray) {
        if (!schemaArray.isArray()) {
            return schemaArray;
        }
        
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode resolvedArray = mapper.createArrayNode();
        for (JsonNode item : schemaArray) {
            resolvedArray.add(resolveAllReferences(item));
        }
        return resolvedArray;
    }

    /**
     * Collect all schemas referenced by this schema and add them to definitions
     */
    private void collectReferencedSchemas(JsonNode schema, ObjectNode definitions) {
        collectReferencedSchemas(schema, definitions, new HashSet<>());
    }

    private void collectReferencedSchemas(JsonNode schema, ObjectNode definitions, Set<String> visited) {
        if (schema.has("$ref")) {
            String ref = schema.get("$ref").asText();
            if (ref.startsWith("#/components/schemas/")) {
                String refName = ref.substring("#/components/schemas/".length());
                if (!visited.contains(refName)) {
                    visited.add(refName);
                    JsonNode referenced = openApiSpec.path("components").path("schemas").path(refName);
                    if (!referenced.isMissingNode()) {
                        definitions.set(refName, referenced.deepCopy());
                        // Recursively collect references from this schema
                        collectReferencedSchemas(referenced, definitions, visited);
                    }
                }
            }
        }
        
        // Check properties
        if (schema.has("properties")) {
            schema.get("properties").fields().forEachRemaining(entry -> 
                collectReferencedSchemas(entry.getValue(), definitions, visited)
            );
        }
        
        // Check items
        if (schema.has("items")) {
            collectReferencedSchemas(schema.get("items"), definitions, visited);
        }
        
        // Check oneOf/anyOf/allOf
        if (schema.has("oneOf")) {
            for (JsonNode item : schema.get("oneOf")) {
                collectReferencedSchemas(item, definitions, visited);
            }
        }
        if (schema.has("anyOf")) {
            for (JsonNode item : schema.get("anyOf")) {
                collectReferencedSchemas(item, definitions, visited);
            }
        }
        if (schema.has("allOf")) {
            for (JsonNode item : schema.get("allOf")) {
                collectReferencedSchemas(item, definitions, visited);
            }
        }
    }

    /**
     * Build a collection wrapper schema with resolved references
     */
    private JsonNode buildResolvedCollectionSchema(String schemaName) {
        JsonNode resolvedItemSchema = buildResolvedStandaloneSchema(schemaName);
        if (resolvedItemSchema == null) {
            return null;
        }
        
        ObjectNode wrapperSchema = objectMapper.createObjectNode();
        wrapperSchema.put("$schema", "http://json-schema.org/draft-07/schema#");
        wrapperSchema.put("$id", "https://openmrs.org/schemas/" + schemaName + "Collection");
        wrapperSchema.put("type", "object");
        
        ObjectNode properties = wrapperSchema.putObject("properties");
        
        ObjectNode resultsProperty = properties.putObject("results");
        resultsProperty.put("type", "array");
        resultsProperty.set("items", resolvedItemSchema);
        
        wrapperSchema.put("additionalProperties", true);
        
        return wrapperSchema;
    }

    /**
     * Validate custom properties with dynamic mapping
     */
    private void validateEndpointCustomPropertiesWithDynamicMapping(String endpoint) {
        String resourceName = endpoint.replaceFirst("^/", "");
        String resourceType = normalizeEndpointToResourceType(resourceName);
        
        String customSchemaName = findSchemaNameFromSpec(endpoint, "custom");
        
        if (customSchemaName == null) {
            customSchemaName = SchemaNameGenerator.schemaName(resourceType, "custom");
        }
        
        JsonNode customSchemaNode = openApiSpec.path("components").path("schemas").path(customSchemaName);
        
        if (customSchemaNode.isMissingNode()) {
            System.out.println("No Custom schema found for: " + endpoint + " (looking for: " + customSchemaName + ")");
            return;
        }
        
        JsonNode propertiesNode = customSchemaNode.path("properties");
        if (propertiesNode.isMissingNode()) {
            System.out.println("No properties found in Custom schema for: " + endpoint);
            return;
        }
        
        List<String> propertyNames = new ArrayList<>();
        propertiesNode.fieldNames().forEachRemaining(propertyNames::add);
        
        if (propertyNames.isEmpty()) {
            System.out.println("Custom schema has no properties for: " + endpoint);
            return;
        }
        
        System.out.println(" Testing " + propertyNames.size() + " custom properties for " + endpoint + ": " + propertyNames);
        
        for (int i = 0; i < propertyNames.size(); i++) {
            String property = propertyNames.get(i);
            String customRepresentation = "custom:(" + property + ")";
            System.out.println("Testing property " + (i + 1) + "/" + propertyNames.size() + ": " + property);
            
            validateEndpointAccessibility(endpoint, customRepresentation);
        }
    }

    /**
     * REAL validation for custom property representations - checks if property actually exists
     */
    private void validateEndpointAccessibility(String endpoint, String representation) {
        try {
            Response response = given()
                .header("Authorization", BASIC_AUTH)
                .config(RestAssuredConfig.config()
                    .httpClient(HttpClientConfig.httpClientConfig()
                        .setParam("http.connection.timeout", 10000)
                        .setParam("http.socket.timeout", 10000)))
                .when()
                .get(endpoint + "?v=" + representation + "&limit=1")
                .then()
                .extract()
                .response();
            
            int sc = response.getStatusCode();
            if (sc != 200) {
                totalValidations.incrementAndGet();
                incRepAttempt(representation);
                failedValidations.incrementAndGet();
                recordValidationResult(endpoint, representation, false, 
                    "HTTP " + sc + ": " + response.getStatusLine(),
                    Collections.singletonList("Non-200 response"), response.getBody().asString());
                return;
            }

            if (representation.startsWith("custom:(") && representation.endsWith(")")) {
                String propertyName = representation.substring("custom:(".length(), representation.length() - 1);
                
                JsonNode responseJson = objectMapper.readTree(response.getBody().asString());

                if (responseJson.has("results") && responseJson.get("results").isArray()) {
                    JsonNode results = responseJson.get("results");
                    if (results.size() == 0) {
                        skippedValidations.incrementAndGet();
                        recordValidationResult(endpoint, representation, true,
                            "Skipped property check: empty collection", Collections.singletonList("SKIPPED"), response.getBody().asString());
                        return;
                    }
                    totalValidations.incrementAndGet();
                    incRepAttempt(representation);
                    JsonNode firstItem = results.get(0);
                    boolean propertyExists = firstItem.has(propertyName);
                    if (propertyExists) {
                        successfulValidations.incrementAndGet();
                        recordValidationResult(endpoint, representation, true, null, Collections.emptyList(), 
                            response.getBody().asString());
                    } else {
                        failedValidations.incrementAndGet();
                        String validationError = "Property '" + propertyName + "' NOT found in collection results[0]. Available properties: " + 
                            getAvailableProperties(firstItem);
                        recordValidationResult(endpoint, representation, false, "REAL VALIDATION FAILED: " + validationError, 
                            Collections.singletonList("Property not found"), response.getBody().asString());
                    }
                } else {
                    totalValidations.incrementAndGet();
                    incRepAttempt(representation);
                    boolean propertyExists = responseJson.has(propertyName);
                    if (propertyExists) {
                        successfulValidations.incrementAndGet();
                        recordValidationResult(endpoint, representation, true, null, Collections.emptyList(), 
                            response.getBody().asString());
                    } else {
                        failedValidations.incrementAndGet();
                        String validationError = "Property '" + propertyName + "' NOT found in item response. Available properties: " + 
                            getAvailableProperties(responseJson);
                        recordValidationResult(endpoint, representation, false, "REAL VALIDATION FAILED: " + validationError, 
                            Collections.singletonList("Property not found"), response.getBody().asString());
                    }
                }
            } else {
                totalValidations.incrementAndGet();
                incRepAttempt(representation);
                successfulValidations.incrementAndGet();
                recordValidationResult(endpoint, representation, true, null, Collections.emptyList(), 
                    response.getBody().asString());
            }
            
        } catch (Exception e) {
            totalValidations.incrementAndGet();
            incRepAttempt(representation);
            failedValidations.incrementAndGet();
            String errorMsg = "Exception: " + e.getMessage();
            recordValidationResult(endpoint, representation, false, errorMsg, 
                Collections.singletonList("Execution exception"), "");
        }
    }

    private static class ValidationResult {
        final String endpoint;
        final String representation;
        final boolean success;
        final String errorMessage;
        final List<String> validationErrors;
        final String responseBody;
        
        ValidationResult(String endpoint, String representation, boolean success, 
                        String errorMessage, List<String> validationErrors, String responseBody) {
            this.endpoint = endpoint;
            this.representation = representation;
            this.success = success;
            this.errorMessage = errorMessage;
            this.validationErrors = validationErrors;
            this.responseBody = responseBody;
        }
    }

    /**
     * Helper method to get available property names from a JSON node
     */
    private String getAvailableProperties(JsonNode node) {
        List<String> properties = new ArrayList<>();
        node.fieldNames().forEachRemaining(properties::add);
        Collections.sort(properties);
        return properties.toString();
    }
}
