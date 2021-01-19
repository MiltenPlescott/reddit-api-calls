/*
 * reddit-api-calls
 *
 * Copyright (c) 2021, Milten Plescott. All rights reserved.
 *
 * SPDX-License-Identifier: MIT
 */

package com.github.miltenplescott.reddit;

import jakarta.json.Json;
import jakarta.json.JsonReader;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.validator.routines.UrlValidator;

public class App {

    private static final HttpClient client = HttpClient.newHttpClient();
    private static final String INPUT_DIR = System.getProperty("user.dir") + File.separator + "input" + File.separator;
    private static final String OUTPUT_DIR = System.getProperty("user.dir") + File.separator + "output" + File.separator;

    private static int requestsToMake = 0;
    private static AtomicInteger requestsMade = new AtomicInteger(0);

    public static void main(String[] args) throws IOException, URISyntaxException {
        Files.createDirectories(Path.of(INPUT_DIR));
        Files.createDirectories(Path.of(OUTPUT_DIR));

        String file = INPUT_DIR + "urls.txt";
        try (Stream<String> stream = Files.lines(Path.of(file), StandardCharsets.UTF_8);) {
            Map<Boolean, List<String>> urlValidityToUrl = stream
                .filter(s -> !s.isBlank())
                .distinct()
                .collect(Collectors.groupingBy(
                    App::isValid,
                    HashMap::new,
                    Collectors.toCollection(ArrayList::new)
                ));

            int numMalformed = (urlValidityToUrl.containsKey(false) ? urlValidityToUrl.get(false).size() : 0);
            System.err.println("Number of malformed URLs: " + numMalformed + "\n");
            if (urlValidityToUrl.containsKey(false)) {
                urlValidityToUrl.get(false).stream()
                    .map(s -> "    " + s)
                    .forEach(System.err::println);
            }

            requestsToMake = (int) urlValidityToUrl.get(true).stream().count();
            HashMap<String, HttpResponse<String>> urlToJson = urlValidityToUrl.get(true).stream()
                .peek(App::printProgress)
                .collect(Collectors.toMap(
                    s -> s, // URL as urlValidityToUrl key
                    s -> sendGetRequest(s), // GET json
                    (a, b) -> {
                        throw new IllegalStateException("There shouldn't be any URL collisions.");
                    },
                    HashMap::new
                ));

            System.err.println("\n\nNumber of failed GET requests: " + urlToJson.values().stream().filter(e -> e == null).count());
            System.out.println("\nNumber of successful GET requests: " + urlToJson.values().stream().filter(e -> e != null).count());
            urlToJson.entrySet().stream()
                .filter(e -> e.getValue() == null)
                .map(e -> "    " + e.getKey())
                .forEach(System.err::println);

            List<String> csvLines = urlToJson.entrySet().stream()
                .map(e -> getAuthorName(e.getValue().body()) + "," + e.getKey())
                .collect(Collectors.toCollection(ArrayList::new));

            String outputPath = OUTPUT_DIR + LocalDateTime.now(ZoneId.systemDefault()).toString().replace(':', '-') + ".csv";
            try (PrintWriter pw = new PrintWriter(outputPath, "UTF-8")) {
                csvLines.stream().forEach(pw::println);
                if (pw.checkError()) {
                    System.err.println("Error encountered when writing output to file: " + outputPath);
                }
            }
        }
    }

    private static boolean isValid(String url) {
        UrlValidator validator = new UrlValidator(new String[]{"https"});
        return validator.isValid(url);
    }

    private static HttpResponse<String> sendGetRequest(String uri) {
        String aboutUri = uri.endsWith("/") ? uri : uri + "/";
        aboutUri = aboutUri + "about.json";
        try {
            return client.send(
                HttpRequest.newBuilder().GET().uri(new URI(aboutUri)).header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
        }
        catch (IOException | InterruptedException | URISyntaxException ex) {
            return null;
        }
    }

    private static void printProgress(String s) {
        StringBuilder builder = new StringBuilder();
        builder.append('\r')
            .append("GET requests progress: ")
            .append(requestsMade.incrementAndGet())
            .append("/")
            .append(requestsToMake);
        System.out.print(builder.toString());
    }

    private static String getAuthorName(String json) {
        Reader r = new StringReader(json);
        JsonReader jsonReader = Json.createReader(r);
        return jsonReader.readArray()
            .getJsonObject(0)
            .getJsonObject("data")
            .getJsonArray("children")
            .getJsonObject(0)
            .getJsonObject("data")
            .getString("author");
    }

}
