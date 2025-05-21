package com.faithBreak;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TranslationService {
    private final FaithBreak plugin;
    private final Map<String, CachedTranslation> translationCache = new ConcurrentHashMap<>();
    private static final String GOOGLE_TRANSLATE_API_URL = "https://translation.googleapis.com/language/translate/v2";
    private static final String DEEPL_API_URL = "https://api-free.deepl.com/v2/translate";

    public TranslationService(FaithBreak plugin) {
        this.plugin = plugin;
    }

    public String getTranslatedMessage(String text, String targetLanguage) {
        FileConfiguration config = plugin.getConfig();
        String cacheKey = text + "_" + targetLanguage;
        
        // Check cache first
        CachedTranslation cached = translationCache.get(cacheKey);
        if (cached != null && !cached.isExpired(config.getLong("translation-cache-duration", 60))) {
            return cached.translation;
        }

        String translationService = config.getString("translation-service", "google");
        String translation;

        try {
            if ("google".equalsIgnoreCase(translationService)) {
                translation = translateWithGoogle(text, targetLanguage);
            } else if ("deepl".equalsIgnoreCase(translationService)) {
                translation = translateWithDeepL(text, targetLanguage);
            } else {
                plugin.getLogger().warning("Invalid translation service specified in config!");
                return text;
            }

            // Cache the translation
            translationCache.put(cacheKey, new CachedTranslation(translation));
            return translation;

        } catch (Exception e) {
            plugin.getLogger().warning("Translation failed: " + e.getMessage());
            return config.getBoolean("fallback-to-default", true) ? text : null;
        }
    }

    private String translateWithGoogle(String text, String targetLanguage) throws Exception {
        String apiKey = plugin.getConfig().getString("google-translate-api-key");
        if (apiKey == null || apiKey.isEmpty()) {
            throw new Exception("Google Translate API key not configured!");
        }

        String encodedText = URLEncoder.encode(text, StandardCharsets.UTF_8);
        String url = String.format("%s?q=%s&target=%s&key=%s", 
            GOOGLE_TRANSLATE_API_URL, encodedText, targetLanguage, apiKey);

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json");

        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            JsonObject response = JsonParser.parseReader(br).getAsJsonObject();
            return response.getAsJsonObject("data")
                         .getAsJsonArray("translations")
                         .get(0).getAsJsonObject()
                         .get("translatedText").getAsString();
        }
    }

    private String translateWithDeepL(String text, String targetLanguage) throws Exception {
        String apiKey = plugin.getConfig().getString("deepl-api-key");
        if (apiKey == null || apiKey.isEmpty()) {
            throw new Exception("DeepL API key not configured!");
        }

        String encodedText = URLEncoder.encode(text, StandardCharsets.UTF_8);
        String url = String.format("%s?text=%s&target_lang=%s", 
            DEEPL_API_URL, encodedText, targetLanguage.toUpperCase());

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "DeepL-Auth-Key " + apiKey);
        conn.setRequestProperty("Accept", "application/json");

        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            JsonObject response = JsonParser.parseReader(br).getAsJsonObject();
            return response.getAsJsonArray("translations")
                         .get(0).getAsJsonObject()
                         .get("text").getAsString();
        }
    }

    private static class CachedTranslation {
        final String translation;
        final long timestamp;

        CachedTranslation(String translation) {
            this.translation = translation;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired(long cacheDurationMinutes) {
            return System.currentTimeMillis() - timestamp > cacheDurationMinutes * 60 * 1000;
        }
    }
}
