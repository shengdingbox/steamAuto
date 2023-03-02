package com.stream.tool;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.util.HashMap;
import java.util.Map;

public class VdfParser {
    public static Map<String, Object> parse(String filePath) throws IOException {
        BufferedReader reader = new BufferedReader(new FileReader(filePath));
        String line;
        Map<String, Object> result = new HashMap<>();
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.startsWith("\"")) {
                String[] parts = line.split("\t");
                String key = parts[0].substring(1, parts[0].length() - 1);
                String value = parts[1].substring(1, parts[1].length() - 1);
                result.put(key, value);
            } else if (line.startsWith("{")) {
                String[] parts = line.split("\"");
                String key = parts[1];
                Map<String, Object> value = parseSub(reader);
                result.put(key, value);
            } else if (line.startsWith("}")) {
                break;
            }
        }
        reader.close();
        return result;
    }

    private static Map<String, Object> parseSub(BufferedReader reader) throws IOException {
        Map<String, Object> result = new HashMap<>();
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.startsWith("\"")) {
                String[] parts = line.split("\t");
                String key = parts[0].substring(1, parts[0].length() - 1);
                String value = parts[1].substring(1, parts[1].length() - 1);
                result.put(key, value);
            } else if (line.startsWith("{")) {
                String[] parts = line.split("\"");
                String key = parts[1];
                Map<String, Object> value = parseSub(reader);
                result.put(key, value);
            } else if (line.startsWith("}")) {
                break;
            }
        }
        return result;
    }
    public static void dump(Map<String, Object> vdf, String filePath) throws IOException {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        String jsonString = gson.toJson(vdf);
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
            writer.write(jsonString);
        }
    }
}
