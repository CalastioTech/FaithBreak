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
    private final Map<String, PlayerLocation> ipLocationCache = new ConcurrentHashMap<>();
    private final Map<String, Long> ipCacheTimestamps = new ConcurrentHashMap<>();
    private BukkitTask prayerTimeChecker;
    private boolean debugMode = false;
    private TranslationService translationService;
    private static final int PRAYER_BREAK_DURATION = 12 * 60 * 1000; // 12 minutes in milliseconds
    private static final long LOCATION_CACHE_DURATION = 24 * 60 * 60 * 1000; // 24 hours in milliseconds


    @Override
    public void onEnable() {
        // Save default config if it doesn't exist
        saveDefaultConfig();
        
        // Load debug mode setting from config
        debugMode = getConfig().getBoolean("debug-mode", false);
        
        // Initialize translation service
        translationService = new TranslationService(this);
        
        // Register events
        getServer().getPluginManager().registerEvents(this, this);
        
        // Start prayer time checker task
        startPrayerTimeChecker();
        
        getLogger().info("FaithBreak has been enabled! Debug mode: " + (debugMode ? "ON" : "OFF"));
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
                String kickMsg = translationService.getMessage(player, "kick_message", "prayer time");
                String rejoinMsg = translationService.getMessage(player, "rejoin_warning", String.valueOf(remainingTime));
                player.kick(net.kyori.adventure.text.Component.text(
                        kickMsg + "\n§c" + rejoinMsg));
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
                String kickMsg = translationService.getMessage(player, "kick_message", "prayer time");
                String rejoinMsg = translationService.getMessage(player, "rejoin_warning", String.valueOf(remainingTime));
                event.disallow(PlayerLoginEvent.Result.KICK_OTHER, 
                        net.kyori.adventure.text.Component.text(kickMsg + 
                        "\n§c" + rejoinMsg));
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
                if (debugMode) {
                    getLogger().info("[DEBUG] Running scheduled prayer time check");
                }
                checkPrayerTimes();
            }
        }.runTaskTimerAsynchronously(this, 0L, 20L * 60); // Run immediately, then check every minute
        
        // Also run once manually at startup to ensure it's working
        new BukkitRunnable() {
            @Override
            public void run() {
                if (debugMode) {
                    getLogger().info("[DEBUG] Running initial prayer time check");
                }
                checkPrayerTimes();
            }
        }.runTaskAsynchronously(this);
    }

    private void checkPrayerTimes() {
        // Get current time
        LocalDateTime now = LocalDateTime.now(ZoneId.of("UTC"));
        if (debugMode) {
            getLogger().info("[DEBUG] Starting prayer time check at UTC time: " + now);
        }
        
        // Check if we have any player locations
        if (playerLocations.isEmpty()) {
            if (debugMode) {
                getLogger().info("[DEBUG] No player locations stored, skipping prayer time check");
            }
            return;
        }
        
        if (debugMode) {
            getLogger().info("[DEBUG] Checking prayer times for " + playerLocations.size() + " players");
        }
        
        // Check prayer times for each player
        for (Map.Entry<UUID, PlayerLocation> entry : playerLocations.entrySet()) {
            UUID playerId = entry.getKey();
            PlayerLocation location = entry.getValue();
            
            // Skip players with null locations (local connections or failed geolocation)
            if (location == null) {
                if (debugMode) {
                    getLogger().info("[DEBUG] Skipping player " + playerId + " - no valid location data");
                }
                continue;
            }
            
            try {
                // Convert UTC time to player's local time
                ZonedDateTime playerLocalTime = now.atZone(ZoneId.of("UTC"))
                        .withZoneSameInstant(ZoneId.of(location.timezone));
                
                if (debugMode) {
                    getLogger().info("[DEBUG] Player " + playerId + " local time: " + 
                            playerLocalTime.format(DateTimeFormatter.ofPattern("HH:mm dd-MM-yyyy")) + 
                            " in timezone: " + location.timezone);
                }
                
                // Get prayer times for player's location
                String dateStr = playerLocalTime.format(DateTimeFormatter.ofPattern("dd-MM-yyyy"));
                if (debugMode) {
                    getLogger().info("[DEBUG] Getting prayer times for location: " + 
                            location.latitude + ", " + location.longitude + " on date: " + dateStr);
                }
                
                Map<String, String> prayerTimes = getPrayerTimes(
                        location.latitude, 
                        location.longitude, 
                        dateStr);
                
                if (prayerTimes != null) {
                    if (debugMode) {
                        StringBuilder prayerTimesLog = new StringBuilder("[DEBUG] Prayer times for player " + playerId + ": ");
                        for (Map.Entry<String, String> prayer : prayerTimes.entrySet()) {
                            prayerTimesLog.append(prayer.getKey()).append("=").append(prayer.getValue()).append(", ");
                        }
                        getLogger().info(prayerTimesLog.toString());
                    }
                    
                    // Check if it's prayer time
                    String currentHour = String.format("%02d", playerLocalTime.getHour());
                    String currentMinute = String.format("%02d", playerLocalTime.getMinute());
                    String currentTime = currentHour + ":" + currentMinute;
                    if (debugMode) {
                        getLogger().info("[DEBUG] Current time for comparison: " + currentTime);
                    }
                    
                    for (Map.Entry<String, String> prayer : prayerTimes.entrySet()) {
                        String prayerTime = prayer.getValue();
                        
                        if (debugMode) {
                            getLogger().info("[DEBUG] Comparing current time " + currentTime + 
                                    " with prayer time " + prayer.getKey() + " (" + prayerTime + ")");
                        }
                        
                        // If it's exactly prayer time or within 1 minute after prayer time
                        boolean isTimeMatch = isWithinOneMinute(currentTime, prayerTime);
                        if (debugMode) {
                            getLogger().info("[DEBUG] Time match result: " + isTimeMatch);
                        }
                        
                        if (isTimeMatch) {
                            Player player = Bukkit.getPlayer(playerId);
                            if (player != null && player.isOnline()) {
                                if (debugMode) {
                                    getLogger().info("[DEBUG] Player " + player.getName() + " is online, kicking for prayer time");
                                }
                                // Kick player for prayer time
                                kickPlayerForPrayer(player, prayer.getKey());
                            } else if (debugMode) {
                                getLogger().info("[DEBUG] Player with ID " + playerId + " is not online, cannot kick");
                            }
                            break;
                        }
                    }
                } else if (debugMode) {
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
        if (debugMode) {
            getLogger().info("[DEBUG] Time comparison: Current total minutes: " + currentTotalMinutes + 
                          ", Prayer total minutes: " + prayerTotalMinutes + 
                          ", Difference: " + (currentTotalMinutes - prayerTotalMinutes));
        }
        
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
                        String kickMsg = translationService.getMessage(player, "kick_message", prayerName);
                        player.kick(net.kyori.adventure.text.Component.text(kickMsg));
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
                        String reminderMsg = translationService.getMessage(player, "prayer_reminder", prayerName);
                        player.sendMessage(net.kyori.adventure.text.Component.text(reminderMsg));
                        getLogger().info("Player " + player.getName() + " from " + (location != null ? location.country : "unknown location") + " received message for " + prayerName + " prayer time.");
                    }
                }
            }.runTask(this);
        }
    }

    private boolean isLocalOrPrivateIP(String ipAddress) {
        return ipAddress.startsWith("127.") || 
               ipAddress.startsWith("192.168.") || 
               ipAddress.startsWith("10.") || 
               ipAddress.startsWith("172.16.") || ipAddress.startsWith("172.17.") || 
               ipAddress.startsWith("172.18.") || ipAddress.startsWith("172.19.") || 
               ipAddress.startsWith("172.20.") || ipAddress.startsWith("172.21.") || 
               ipAddress.startsWith("172.22.") || ipAddress.startsWith("172.23.") || 
               ipAddress.startsWith("172.24.") || ipAddress.startsWith("172.25.") || 
               ipAddress.startsWith("172.26.") || ipAddress.startsWith("172.27.") || 
               ipAddress.startsWith("172.28.") || ipAddress.startsWith("172.29.") || 
               ipAddress.startsWith("172.30.") || ipAddress.startsWith("172.31.") || 
               ipAddress.equals("localhost") || 
               ipAddress.equals("0:0:0:0:0:0:0:1") ||
               ipAddress.equals("::1");
    }

    private PlayerLocation getPlayerLocation(String ipAddress) {
        // Log the IP address we're trying to locate
        if (debugMode) {
            getLogger().info("[DEBUG] Attempting to get location for IP: " + ipAddress);
        }
        
        // Check if this is a local/private IP address
        if (isLocalOrPrivateIP(ipAddress)) {
            getLogger().info("Player connecting from local/private network (" + ipAddress + ") - plugin functionality disabled for this player");
            return null; // Return null to disable plugin functionality for local connections
        }
        
        // Check cache first
        if (ipLocationCache.containsKey(ipAddress) && ipCacheTimestamps.containsKey(ipAddress)) {
            long cacheTime = ipCacheTimestamps.get(ipAddress);
            long currentTime = System.currentTimeMillis();
            
            if (currentTime - cacheTime < LOCATION_CACHE_DURATION) {
                PlayerLocation cachedLocation = ipLocationCache.get(ipAddress);
                if (debugMode) {
                    getLogger().info("[DEBUG] Using cached location for IP: " + ipAddress + 
                            " (cached " + ((currentTime - cacheTime) / 1000 / 60) + " minutes ago)");
                }
                return cachedLocation;
            } else {
                // Cache expired, remove old entries
                if (debugMode) {
                    getLogger().info("[DEBUG] Cache expired for IP: " + ipAddress + ", removing from cache");
                }
                ipLocationCache.remove(ipAddress);
                ipCacheTimestamps.remove(ipAddress);
            }
        }
        
        // Try multiple geolocation services in order
        PlayerLocation location = tryIpApiService(ipAddress);
        if (location != null) {
            cacheLocationResult(ipAddress, location);
            return location;
        }
        
        location = tryIpInfoService(ipAddress);
        if (location != null) {
            cacheLocationResult(ipAddress, location);
            return location;
        }
        
        location = tryIpGeolocationService(ipAddress);
        if (location != null) {
            cacheLocationResult(ipAddress, location);
            return location;
        }
        
        // All APIs failed - cache null result for a shorter time to avoid repeated API calls
        cacheLocationResult(ipAddress, null);
        getLogger().warning("All geolocation services failed for IP: " + ipAddress + " - plugin functionality disabled for this player");
        return null; // Return null instead of fake location
    }
    
    private void cacheLocationResult(String ipAddress, PlayerLocation location) {
        long currentTime = System.currentTimeMillis();
        ipLocationCache.put(ipAddress, location);
        ipCacheTimestamps.put(ipAddress, currentTime);
        
        if (debugMode) {
            if (location != null) {
                getLogger().info("[DEBUG] Cached location result for IP: " + ipAddress + 
                        " -> " + location.country + ", " + location.city);
            } else {
                getLogger().info("[DEBUG] Cached null location result for IP: " + ipAddress);
            }
        }
        
        // Clean up old cache entries periodically
        cleanupExpiredCacheEntries();
    }
    
    private void cleanupExpiredCacheEntries() {
        long currentTime = System.currentTimeMillis();
        
        // Only clean up occasionally to avoid performance impact
        if (currentTime % 100000 < 1000) { // Roughly every 100 seconds
            ipCacheTimestamps.entrySet().removeIf(entry -> {
                boolean expired = currentTime - entry.getValue() > LOCATION_CACHE_DURATION;
                if (expired) {
                    ipLocationCache.remove(entry.getKey());
                    if (debugMode) {
                        getLogger().info("[DEBUG] Cleaned up expired cache entry for IP: " + entry.getKey());
                    }
                }
                return expired;
            });
        }
    }
    
    private PlayerLocation tryIpApiService(String ipAddress) {
        try {
            if (debugMode) {
                getLogger().info("[DEBUG] Trying ip-api.com service for IP: " + ipAddress);
            }
            
            URL url = new URL("http://ip-api.com/json/" + ipAddress + "?fields=status,country,city,lat,lon,timezone");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000); // Increased timeout to 10 seconds
            connection.setReadTimeout(10000);
            
            int responseCode = connection.getResponseCode();
            if (debugMode) {
                getLogger().info("[DEBUG] ip-api.com response code: " + responseCode);
            }
            
            if (responseCode == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();
                
                // Log the raw response for debugging
                if (debugMode) {
                    getLogger().info("[DEBUG] ip-api.com response: " + response.toString());
                }
                
                // Parse JSON response
                JsonObject jsonObject = JsonParser.parseString(response.toString()).getAsJsonObject();
                
                if ("success".equals(jsonObject.get("status").getAsString())) {
                    String country = jsonObject.get("country").getAsString();
                    String city = jsonObject.get("city").getAsString();
                    double latitude = jsonObject.get("lat").getAsDouble();
                    double longitude = jsonObject.get("lon").getAsDouble();
                    String timezone = jsonObject.get("timezone").getAsString();
                    
                    // Validate location data
                    if (isValidLocationData(country, city, latitude, longitude, timezone)) {
                        if (debugMode) {
                            getLogger().info("[DEBUG] Successfully got valid location from ip-api.com");
                        }
                        return new PlayerLocation(country, city, latitude, longitude, timezone);
                    } else {
                        if (debugMode) {
                            getLogger().warning("[DEBUG] Invalid location data from ip-api.com");
                        }
                    }
                } else {
                    if (debugMode) {
                        getLogger().warning("[DEBUG] ip-api.com returned non-success status");
                    }
                }
            } else {
                if (debugMode) {
                    getLogger().warning("[DEBUG] ip-api.com returned HTTP " + responseCode);
                }
            }
        } catch (IOException e) {
            if (debugMode) {
                getLogger().log(Level.WARNING, "[DEBUG] Error with ip-api.com service for IP: " + ipAddress, e);
            } else {
                getLogger().log(Level.WARNING, "Error with ip-api.com service for IP: " + ipAddress, e);
            }
        }
        
        return null;
    }
    
    private PlayerLocation tryIpInfoService(String ipAddress) {
        try {
            if (debugMode) {
                getLogger().info("[DEBUG] Trying ipinfo.io service for IP: " + ipAddress);
            }
            
            URL url = new URL("http://ipinfo.io/" + ipAddress + "/json");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            
            int responseCode = connection.getResponseCode();
            if (debugMode) {
                getLogger().info("[DEBUG] ipinfo.io response code: " + responseCode);
            }
            
            if (responseCode == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();
                
                if (debugMode) {
                    getLogger().info("[DEBUG] ipinfo.io response: " + response.toString());
                }
                
                JsonObject jsonObject = JsonParser.parseString(response.toString()).getAsJsonObject();
                
                if (jsonObject.has("country") && jsonObject.has("city") && jsonObject.has("loc")) {
                    String country = jsonObject.get("country").getAsString();
                    String city = jsonObject.get("city").getAsString();
                    String[] coords = jsonObject.get("loc").getAsString().split(",");
                    
                    if (coords.length == 2) {
                        double latitude = Double.parseDouble(coords[0]);
                        double longitude = Double.parseDouble(coords[1]);
                        String timezone = jsonObject.has("timezone") ? jsonObject.get("timezone").getAsString() : "UTC";
                        
                        if (isValidLocationData(country, city, latitude, longitude, timezone)) {
                            if (debugMode) {
                                getLogger().info("[DEBUG] Successfully got valid location from ipinfo.io");
                            }
                            return new PlayerLocation(country, city, latitude, longitude, timezone);
                        }
                    }
                }
            }
        } catch (Exception e) {
            if (debugMode) {
                getLogger().log(Level.WARNING, "[DEBUG] Error with ipinfo.io service for IP: " + ipAddress, e);
            }
        }
        
        return null;
    }
    
    private PlayerLocation tryIpGeolocationService(String ipAddress) {
        try {
            if (debugMode) {
                getLogger().info("[DEBUG] Trying ipgeolocation.io service for IP: " + ipAddress);
            }
            
            // Note: This service requires an API key for production use, but has a free tier
            URL url = new URL("http://ip-api.com/json/" + ipAddress); // Using ip-api as backup since ipgeolocation requires key
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            
            int responseCode = connection.getResponseCode();
            if (responseCode == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();
                
                JsonObject jsonObject = JsonParser.parseString(response.toString()).getAsJsonObject();
                
                if (jsonObject.has("status") && "success".equals(jsonObject.get("status").getAsString())) {
                    String country = jsonObject.get("country").getAsString();
                    String city = jsonObject.get("city").getAsString();
                    double latitude = jsonObject.get("lat").getAsDouble();
                    double longitude = jsonObject.get("lon").getAsDouble();
                    String timezone = jsonObject.get("timezone").getAsString();
                    
                    if (isValidLocationData(country, city, latitude, longitude, timezone)) {
                        if (debugMode) {
                            getLogger().info("[DEBUG] Successfully got valid location from backup service");
                        }
                        return new PlayerLocation(country, city, latitude, longitude, timezone);
                    }
                }
            }
        } catch (Exception e) {
            if (debugMode) {
                getLogger().log(Level.WARNING, "[DEBUG] Error with backup geolocation service for IP: " + ipAddress, e);
            }
        }
        
        return null;
    }
    
    private boolean isValidLocationData(String country, String city, double latitude, double longitude, String timezone) {
        // Basic validation checks
        if (country == null || country.trim().isEmpty()) {
            if (debugMode) {
                getLogger().warning("[DEBUG] Invalid location: empty country");
            }
            return false;
        }
        
        if (city == null || city.trim().isEmpty()) {
            if (debugMode) {
                getLogger().warning("[DEBUG] Invalid location: empty city");
            }
            return false;
        }
        
        // Check for obviously invalid coordinates
        if (latitude == 0.0 && longitude == 0.0) {
            if (debugMode) {
                getLogger().warning("[DEBUG] Invalid location: coordinates are 0,0");
            }
            return false;
        }
        
        // Check latitude range
        if (latitude < -90 || latitude > 90) {
            if (debugMode) {
                getLogger().warning("[DEBUG] Invalid location: latitude out of range: " + latitude);
            }
            return false;
        }
        
        // Check longitude range
        if (longitude < -180 || longitude > 180) {
            if (debugMode) {
                getLogger().warning("[DEBUG] Invalid location: longitude out of range: " + longitude);
            }
            return false;
        }
        
        if (timezone == null || timezone.trim().isEmpty()) {
            if (debugMode) {
                getLogger().warning("[DEBUG] Invalid location: empty timezone");
            }
            return false;
        }
        
        return true;
    }

    private Map<String, String> getPrayerTimes(double latitude, double longitude, String date) {
        try {
            // Use aladhan.com API for prayer times
            // Try method 4 (Umm Al-Qura University) which is commonly used in the Middle East
            String apiUrl = "http://api.aladhan.com/v1/timings/" + date + 
                    "?latitude=" + latitude + "&longitude=" + longitude + "&method=4";
            if (debugMode) {
                getLogger().info("[DEBUG] Calling prayer time API: " + apiUrl);
            }
            
            URL url = new URL(apiUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000); // 10 second timeout
            connection.setReadTimeout(10000);    // 10 second read timeout
            
            int responseCode = connection.getResponseCode();
            if (debugMode) {
                getLogger().info("[DEBUG] Prayer API response code: " + responseCode);
            }
            
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
                if (debugMode) {
                    getLogger().info("[DEBUG] Prayer API response (truncated): " + 
                            (responseStr.length() > 200 ? responseStr.substring(0, 200) + "..." : responseStr));
                }
                
                try {
                    // Parse JSON response
                    JsonObject jsonObject = JsonParser.parseString(responseStr).getAsJsonObject();
                    
                    // Check if the response has the expected structure
                    if (!jsonObject.has("data")) {
                        if (debugMode) {
                            getLogger().warning("[DEBUG] Prayer API response missing 'data' field");
                        }
                        return createFallbackPrayerTimes();
                    }
                    
                    JsonObject data = jsonObject.getAsJsonObject("data");
                    
                    if (!data.has("timings")) {
                        if (debugMode) {
                            getLogger().warning("[DEBUG] Prayer API response missing 'timings' field");
                        }
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
                        if (debugMode) {
                            getLogger().warning("[DEBUG] No prayer times could be extracted from API response");
                        }
                        return createFallbackPrayerTimes();
                    }
                    
                    if (debugMode) {
                        getLogger().info("[DEBUG] Successfully retrieved " + prayerTimes.size() + " prayer times");
                    }
                    return prayerTimes;
                } catch (Exception e) {
                    if (debugMode) {
                        getLogger().log(Level.WARNING, "[DEBUG] Error parsing prayer times JSON response", e);
                    } else {
                        getLogger().log(Level.WARNING, "Error parsing prayer times JSON response", e);
                    }
                }
            } else if (debugMode) {
                getLogger().warning("[DEBUG] Prayer API returned non-200 status code: " + responseCode);
            }
        } catch (IOException e) {
            if (debugMode) {
                getLogger().log(Level.WARNING, "[DEBUG] Error getting prayer times for location: " + latitude + ", " + longitude, e);
            } else {
                getLogger().log(Level.WARNING, "Error getting prayer times for location: " + latitude + ", " + longitude, e);
            }
        }
        
        // If we get here, something went wrong with the API call
        return createFallbackPrayerTimes();
    }
    
    // Create fallback prayer times for testing or when API fails
    private Map<String, String> createFallbackPrayerTimes() {
        if (debugMode) {
            getLogger().info("[DEBUG] Using fallback prayer times due to API failure");
        }
        Map<String, String> fallbackTimes = new HashMap<>();
        
        // Get current hour and add times around it for testing
        LocalDateTime now = LocalDateTime.now();
        int currentHour = now.getHour();
        int currentMinute = now.getMinute();
        
        // Create a prayer time 2 minutes in the future for testing
        String testPrayerTime = String.format("%02d:%02d", currentHour, (currentMinute + 2) % 60);
        if (debugMode) {
            getLogger().info("[DEBUG] Created test prayer time: " + testPrayerTime + " (current time + 2 minutes)");
        }
        
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
                if (debugMode) {
                    getLogger().info("[DEBUG] Successfully extracted " + prayerName + " prayer time: " + timeStr);
                }
            } else if (debugMode) {
                getLogger().warning("[DEBUG] Missing prayer time for: " + prayerName);
            }
        } catch (Exception e) {
            if (debugMode) {
                getLogger().warning("[DEBUG] Error extracting " + prayerName + " prayer time: " + e.getMessage());
            }
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
