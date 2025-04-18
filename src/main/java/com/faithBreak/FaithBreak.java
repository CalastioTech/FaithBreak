package com.faithBreak;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class FaithBreak extends JavaPlugin implements Listener {

    private final Map<UUID, PlayerLocation> playerLocations = new ConcurrentHashMap<>();
    private final Map<UUID, Long> kickedPlayers = new ConcurrentHashMap<>();
    private final Set<UUID> processingPlayers = new HashSet<>();
    private BukkitTask prayerTimeChecker;
    private static final int PRAYER_BREAK_DURATION = 12 * 60 * 1000; // 12 minutes in milliseconds
    private static final String KICK_MESSAGE = "§6If you're Muslim, prayer time is probably in 2 minutes. If you're not, take a 12-minute break.😊♥❤\n\n§eThis message is from FaithBreak, a plugin that helps players take breaks during prayer times based on their location.\n§eThis plugin is automatically added as a dependency to enhance your gaming experience.\n\n§b§n[more info!]§r §9https://github.com/CalastioTech/FaithBreak";

    @Override
    public void onEnable() {
        // Register events
        getServer().getPluginManager().registerEvents(this, this);
        
        // Start prayer time checker task
        startPrayerTimeChecker();
        
        getLogger().info("FaithBreak has been enabled!");
    }

    @Override
    public void onDisable() {
        // Cancel tasks
        if (prayerTimeChecker != null) {
            prayerTimeChecker.cancel();
        }
        
        getLogger().info("FaithBreak has been disabled!");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        
        // Check if player is in the kicked list and if the break time has passed
        if (kickedPlayers.containsKey(playerId)) {
            long kickTime = kickedPlayers.get(playerId);
            long currentTime = System.currentTimeMillis();
            
            if (currentTime - kickTime < PRAYER_BREAK_DURATION) {
                // Break time hasn't passed yet, kick the player again
                long remainingTime = (kickTime + PRAYER_BREAK_DURATION - currentTime) / 1000 / 60;
                player.kick(net.kyori.adventure.text.Component.text(
                        KICK_MESSAGE + "\n§cPlease wait " + remainingTime + " more minutes before rejoining."));
                return;
            } else {
                // Break time has passed, remove from kicked list
                kickedPlayers.remove(playerId);
            }
        }
        
        // Get player's location asynchronously
        if (!processingPlayers.contains(playerId)) {
            processingPlayers.add(playerId);
            new BukkitRunnable() {
                @Override
                public void run() {
                    try {
                        PlayerLocation location = getPlayerLocation(player.getAddress().getAddress().getHostAddress());
                        if (location != null) {
                            playerLocations.put(playerId, location);
                            getLogger().info("Player " + player.getName() + " location detected: " + 
                                    location.country + ", " + location.city + ", Timezone: " + location.timezone);
                        }
                    } catch (Exception e) {
                        getLogger().log(Level.WARNING, "Failed to get location for player: " + player.getName(), e);
                    } finally {
                        processingPlayers.remove(playerId);
                    }
                }
            }.runTaskAsynchronously(this);
        }
    }

    @EventHandler
    public void onPlayerLogin(PlayerLoginEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        
        // Check if player is in the kicked list and if the break time has passed
        if (kickedPlayers.containsKey(playerId)) {
            long kickTime = kickedPlayers.get(playerId);
            long currentTime = System.currentTimeMillis();
            
            if (currentTime - kickTime < PRAYER_BREAK_DURATION) {
                // Break time hasn't passed yet, deny login
                long remainingTime = (kickTime + PRAYER_BREAK_DURATION - currentTime) / 1000 / 60;
                event.disallow(PlayerLoginEvent.Result.KICK_OTHER, 
                        net.kyori.adventure.text.Component.text(KICK_MESSAGE + 
                        "\n§cPlease wait " + remainingTime + " more minutes before rejoining."));
            } else {
                // Break time has passed, remove from kicked list
                kickedPlayers.remove(playerId);
            }
        }
    }

    private void startPrayerTimeChecker() {
        // Run immediately when plugin starts and then every minute
        prayerTimeChecker = new BukkitRunnable() {
            @Override
            public void run() {
                getLogger().info("[DEBUG] Running scheduled prayer time check");
                checkPrayerTimes();
            }
        }.runTaskTimerAsynchronously(this, 0L, 20L * 60); // Run immediately, then check every minute
        
        // Also run once manually at startup to ensure it's working
        new BukkitRunnable() {
            @Override
            public void run() {
                getLogger().info("[DEBUG] Running initial prayer time check");
                checkPrayerTimes();
            }
        }.runTaskAsynchronously(this);
    }

    private void checkPrayerTimes() {
        // Get current time
        LocalDateTime now = LocalDateTime.now(ZoneId.of("UTC"));
        getLogger().info("[DEBUG] Starting prayer time check at UTC time: " + now);
        
        // Check if we have any player locations
        if (playerLocations.isEmpty()) {
            getLogger().info("[DEBUG] No player locations stored, skipping prayer time check");
            return;
        }
        
        getLogger().info("[DEBUG] Checking prayer times for " + playerLocations.size() + " players");
        
        // Check prayer times for each player
        for (Map.Entry<UUID, PlayerLocation> entry : playerLocations.entrySet()) {
            UUID playerId = entry.getKey();
            PlayerLocation location = entry.getValue();
            
            try {
                // Convert UTC time to player's local time
                ZonedDateTime playerLocalTime = now.atZone(ZoneId.of("UTC"))
                        .withZoneSameInstant(ZoneId.of(location.timezone));
                
                getLogger().info("[DEBUG] Player " + playerId + " local time: " + 
                        playerLocalTime.format(DateTimeFormatter.ofPattern("HH:mm dd-MM-yyyy")) + 
                        " in timezone: " + location.timezone);
                
                // Get prayer times for player's location
                String dateStr = playerLocalTime.format(DateTimeFormatter.ofPattern("dd-MM-yyyy"));
                getLogger().info("[DEBUG] Getting prayer times for location: " + 
                        location.latitude + ", " + location.longitude + " on date: " + dateStr);
                
                Map<String, String> prayerTimes = getPrayerTimes(
                        location.latitude, 
                        location.longitude, 
                        dateStr);
                
                if (prayerTimes != null) {
                    StringBuilder prayerTimesLog = new StringBuilder("[DEBUG] Prayer times for player " + playerId + ": ");
                    for (Map.Entry<String, String> prayer : prayerTimes.entrySet()) {
                        prayerTimesLog.append(prayer.getKey()).append("=").append(prayer.getValue()).append(", ");
                    }
                    getLogger().info(prayerTimesLog.toString());
                    
                    // Check if it's prayer time
                    String currentHour = String.format("%02d", playerLocalTime.getHour());
                    String currentMinute = String.format("%02d", playerLocalTime.getMinute());
                    String currentTime = currentHour + ":" + currentMinute;
                    getLogger().info("[DEBUG] Current time for comparison: " + currentTime);
                    
                    for (Map.Entry<String, String> prayer : prayerTimes.entrySet()) {
                        String prayerTime = prayer.getValue();
                        
                        getLogger().info("[DEBUG] Comparing current time " + currentTime + 
                                " with prayer time " + prayer.getKey() + " (" + prayerTime + ")");
                        
                        // If it's exactly prayer time or within 1 minute after prayer time
                        boolean isTimeMatch = isWithinOneMinute(currentTime, prayerTime);
                        getLogger().info("[DEBUG] Time match result: " + isTimeMatch);
                        
                        if (isTimeMatch) {
                            Player player = Bukkit.getPlayer(playerId);
                            if (player != null && player.isOnline()) {
                                getLogger().info("[DEBUG] Player " + player.getName() + " is online, kicking for prayer time");
                                // Kick player for prayer time
                                kickPlayerForPrayer(player, prayer.getKey());
                            } else {
                                getLogger().info("[DEBUG] Player with ID " + playerId + " is not online, cannot kick");
                            }
                            break;
                        }
                    }
                } else {
                    getLogger().warning("[DEBUG] Failed to get prayer times for player " + playerId);
                }
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Error checking prayer times for player ID: " + playerId, e);
            }
        }
    }

    private boolean isWithinOneMinute(String currentTime, String prayerTime) {
        // Parse times (format: HH:MM)
        String[] currentParts = currentTime.split(":");
        String[] prayerParts = prayerTime.split(":");
        
        int currentHour = Integer.parseInt(currentParts[0]);
        int currentMinute = Integer.parseInt(currentParts[1]);
        int prayerHour = Integer.parseInt(prayerParts[0]);
        int prayerMinute = Integer.parseInt(prayerParts[1]);
        
        // Convert to total minutes for easier comparison
        int currentTotalMinutes = currentHour * 60 + currentMinute;
        int prayerTotalMinutes = prayerHour * 60 + prayerMinute;
        
        // Log the time comparison details
        getLogger().info("[DEBUG] Time comparison: Current total minutes: " + currentTotalMinutes + 
                      ", Prayer total minutes: " + prayerTotalMinutes + 
                      ", Difference: " + (currentTotalMinutes - prayerTotalMinutes));
        
        // Check if current time is 2 minutes before prayer time
        // This will kick players 2 minutes before prayer time
        return currentTotalMinutes >= prayerTotalMinutes - 2 && 
               currentTotalMinutes < prayerTotalMinutes;
    }

    // List of Middle Eastern countries
    private static final Set<String> MIDDLE_EAST_COUNTRIES = new HashSet<>(Arrays.asList(
            "Saudi Arabia", "United Arab Emirates", "Qatar", "Kuwait", "Bahrain", "Oman", 
            "Yemen", "Iraq", "Iran", "Syria", "Lebanon", "Jordan", "Palestine", "Egypt"));
            
    // Check if a country is in the Middle East
    private boolean isMiddleEasternCountry(String country) {
        return MIDDLE_EAST_COUNTRIES.contains(country);
    }
    
    private void kickPlayerForPrayer(Player player, String prayerName) {
        UUID playerId = player.getUniqueId();
        PlayerLocation location = playerLocations.get(playerId);
        
        if (location != null && isMiddleEasternCountry(location.country)) {
            // Middle Eastern player - kick them
            kickedPlayers.put(playerId, System.currentTimeMillis());
            
            // Kick player with message
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (player.isOnline()) {
                        player.kick(net.kyori.adventure.text.Component.text(KICK_MESSAGE));
                        getLogger().info("Player " + player.getName() + " from " + location.country + " kicked for " + prayerName + " prayer time.");
                    }
                }
            }.runTask(this);
        } else {
            // Non-Middle Eastern player - just send a message
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (player.isOnline()) {
                        player.sendMessage(net.kyori.adventure.text.Component.text("§6Prayer time reminder: It's " + prayerName + " prayer time now. Taking a short break is recommended.§r"));
                        getLogger().info("Player " + player.getName() + " from " + (location != null ? location.country : "unknown location") + " received message for " + prayerName + " prayer time.");
                    }
                }
            }.runTask(this);
        }
    }

    private PlayerLocation getPlayerLocation(String ipAddress) {
        // Log the IP address we're trying to locate
        getLogger().info("[DEBUG] Attempting to get location for IP: " + ipAddress);
        
        // Check if this is a local/private IP address
        if (ipAddress.startsWith("127.") || ipAddress.startsWith("192.168.") || 
            ipAddress.startsWith("10.") || ipAddress.equals("localhost") || 
            ipAddress.equals("0:0:0:0:0:0:0:1")) {
            
            getLogger().info("[DEBUG] Detected local IP address, using default location for testing");
            
            // For testing on local server, return a default location (Mecca, Saudi Arabia)
            return new PlayerLocation("Saudi Arabia", "Mecca", 21.3891, 39.8579, "Asia/Riyadh");
        }
        
        try {
            // Use ip-api.com for geolocation (free service)
            URL url = new URL("http://ip-api.com/json/" + ipAddress + "?fields=status,country,city,lat,lon,timezone");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000); // 5 second timeout
            
            int responseCode = connection.getResponseCode();
            getLogger().info("[DEBUG] Geolocation API response code: " + responseCode);
            
            if (responseCode == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();
                
                // Log the raw response for debugging
                getLogger().info("[DEBUG] Geolocation API response: " + response.toString());
                
                // Parse JSON response
                JsonObject jsonObject = JsonParser.parseString(response.toString()).getAsJsonObject();
                
                if ("success".equals(jsonObject.get("status").getAsString())) {
                    String country = jsonObject.get("country").getAsString();
                    String city = jsonObject.get("city").getAsString();
                    double latitude = jsonObject.get("lat").getAsDouble();
                    double longitude = jsonObject.get("lon").getAsDouble();
                    String timezone = jsonObject.get("timezone").getAsString();
                    
                    getLogger().info("[DEBUG] Successfully parsed location data");
                    return new PlayerLocation(country, city, latitude, longitude, timezone);
                } else {
                    getLogger().warning("[DEBUG] Geolocation API returned non-success status");
                }
            }
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Error getting location for IP: " + ipAddress, e);
        }
        
        // If we get here, something went wrong with the API call
        // For fallback, return a default location (Mecca, Saudi Arabia)
        getLogger().info("[DEBUG] Using fallback location due to API failure");
        return new PlayerLocation("Saudi Arabia", "Mecca", 21.3891, 39.8579, "Asia/Riyadh");
    }

    private Map<String, String> getPrayerTimes(double latitude, double longitude, String date) {
        try {
            // Use aladhan.com API for prayer times
            // Try method 4 (Umm Al-Qura University) which is commonly used in the Middle East
            String apiUrl = "http://api.aladhan.com/v1/timings/" + date + 
                    "?latitude=" + latitude + "&longitude=" + longitude + "&method=4";
            getLogger().info("[DEBUG] Calling prayer time API: " + apiUrl);
            
            URL url = new URL(apiUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000); // 10 second timeout
            connection.setReadTimeout(10000);    // 10 second read timeout
            
            int responseCode = connection.getResponseCode();
            getLogger().info("[DEBUG] Prayer API response code: " + responseCode);
            
            if (responseCode == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();
                
                // Log the raw response for debugging (truncated to avoid huge logs)
                String responseStr = response.toString();
                getLogger().info("[DEBUG] Prayer API response (truncated): " + 
                        (responseStr.length() > 200 ? responseStr.substring(0, 200) + "..." : responseStr));
                
                try {
                    // Parse JSON response
                    JsonObject jsonObject = JsonParser.parseString(responseStr).getAsJsonObject();
                    
                    // Check if the response has the expected structure
                    if (!jsonObject.has("data")) {
                        getLogger().warning("[DEBUG] Prayer API response missing 'data' field");
                        return createFallbackPrayerTimes();
                    }
                    
                    JsonObject data = jsonObject.getAsJsonObject("data");
                    
                    if (!data.has("timings")) {
                        getLogger().warning("[DEBUG] Prayer API response missing 'timings' field");
                        return createFallbackPrayerTimes();
                    }
                    
                    JsonObject timings = data.getAsJsonObject("timings");
                    
                    // Create a map to store prayer times
                    Map<String, String> prayerTimes = new HashMap<>();
                    
                    // Extract prayer times directly from the timings object
                    extractPrayerTime(prayerTimes, timings, "Fajr");
                    extractPrayerTime(prayerTimes, timings, "Dhuhr");
                    extractPrayerTime(prayerTimes, timings, "Asr");
                    extractPrayerTime(prayerTimes, timings, "Maghrib");
                    extractPrayerTime(prayerTimes, timings, "Isha");
                    
                    // Check if we got any prayer times
                    if (prayerTimes.isEmpty()) {
                        getLogger().warning("[DEBUG] No prayer times could be extracted from API response");
                        return createFallbackPrayerTimes();
                    }
                    
                    getLogger().info("[DEBUG] Successfully retrieved " + prayerTimes.size() + " prayer times");
                    return prayerTimes;
                } catch (Exception e) {
                    getLogger().log(Level.WARNING, "[DEBUG] Error parsing prayer times JSON response", e);
                }
            } else {
                getLogger().warning("[DEBUG] Prayer API returned non-200 status code: " + responseCode);
            }
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "[DEBUG] Error getting prayer times for location: " + latitude + ", " + longitude, e);
        }
        
        // If we get here, something went wrong with the API call
        return createFallbackPrayerTimes();
    }
    
    // Create fallback prayer times for testing or when API fails
    private Map<String, String> createFallbackPrayerTimes() {
        getLogger().info("[DEBUG] Using fallback prayer times due to API failure");
        Map<String, String> fallbackTimes = new HashMap<>();
        
        // Get current hour and add times around it for testing
        LocalDateTime now = LocalDateTime.now();
        int currentHour = now.getHour();
        int currentMinute = now.getMinute();
        
        // Create a prayer time 2 minutes in the future for testing
        String testPrayerTime = String.format("%02d:%02d", currentHour, (currentMinute + 2) % 60);
        getLogger().info("[DEBUG] Created test prayer time: " + testPrayerTime + " (current time + 2 minutes)");
        
        fallbackTimes.put("Fajr", "05:00");
        fallbackTimes.put("Dhuhr", "12:00");
        fallbackTimes.put("Asr", "15:30");
        fallbackTimes.put("Maghrib", "18:00");
        fallbackTimes.put("Isha", testPrayerTime); // Use our test time for Isha
        
        return fallbackTimes;
    }

    private void extractPrayerTime(Map<String, String> prayerTimes, JsonObject timings, String prayerName) {
        try {
            if (timings.has(prayerName)) {
                // Get the prayer time as a simple string
                String timeStr = timings.get(prayerName).getAsString();
                
                // Ensure we only store the time in HH:MM format
                if (timeStr.length() > 5) {
                    timeStr = timeStr.substring(0, 5);
                }
                
                prayerTimes.put(prayerName, timeStr);
                getLogger().info("[DEBUG] Successfully extracted " + prayerName + " prayer time: " + timeStr);
            } else {
                getLogger().warning("[DEBUG] Missing prayer time for: " + prayerName);
            }
        } catch (Exception e) {
            getLogger().warning("[DEBUG] Error extracting " + prayerName + " prayer time: " + e.getMessage());
        }
    }

    // Class to store player location information
    private static class PlayerLocation {
        private final String country;
        private final String city;
        private final double latitude;
        private final double longitude;
        private final String timezone;
        
        public PlayerLocation(String country, String city, double latitude, double longitude, String timezone) {
            this.country = country;
            this.city = city;
            this.latitude = latitude;
            this.longitude = longitude;
            this.timezone = timezone;
        }
    }
}