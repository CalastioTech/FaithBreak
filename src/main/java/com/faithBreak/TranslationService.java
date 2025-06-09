package com.faithBreak;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class TranslationService {
    private static final String DEFAULT_LANGUAGE = "en";
    private final Map<String, YamlConfiguration> languages = new HashMap<>();
    private final FaithBreak plugin;

    public TranslationService(FaithBreak plugin) {
        this.plugin = plugin;
        loadLanguages();
    }

    private void loadLanguages() {
        File languagesDir = new File(plugin.getDataFolder(), "languages");
        if (!languagesDir.exists()) {
            languagesDir.mkdirs();
        }

        // Load all YAML files from languages directory
        File[] languageFiles = languagesDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (languageFiles != null) {
            for (File file : languageFiles) {
                String langCode = file.getName().replace(".yml", "");
                languages.put(langCode, YamlConfiguration.loadConfiguration(file));
            }
        }

        // Load default from resources if not present
        if (!languages.containsKey(DEFAULT_LANGUAGE)) {
            languages.put(DEFAULT_LANGUAGE, YamlConfiguration.loadConfiguration(
                new InputStreamReader(plugin.getResource("languages/en.yml"))
            ));
        }
    }

    public String getMessage(Player player, String key) {
        return getMessage(player, key, new String[0]);
    }

    public String getMessage(Player player, String key, String... args) {
        String locale = player.getLocale().toLowerCase().split("_")[0]; // Use language part only
        YamlConfiguration langConfig = languages.getOrDefault(locale, languages.get(DEFAULT_LANGUAGE));
        
        String message = langConfig.getString(key);
        if (message == null) {
            message = languages.get(DEFAULT_LANGUAGE).getString(key, key); // Fallback to key if not found
        }
        
        // Replace placeholders {0}, {1}, etc.
        for (int i = 0; i < args.length; i++) {
            message = message.replace("{" + i + "}", args[i]);
        }
        
        return message;
    }
}
