package com.jagrosh.jmusicbot.utils;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple in-process lock manager. It prevents multiple bot instances (within the
 * same JVM) from handling the same guild/voice channel concurrently. No Redis
 * or filesystem dependency; safe for multi-token single-process deployments.
 */
public class VoiceLockManager
{
    private static final Logger log = LoggerFactory.getLogger(VoiceLockManager.class);
    private static final long CMD_LOCK_TTL_MILLIS = Duration.ofSeconds(3).toMillis();

    // guildId -> map(channelId -> botId)
    private static final Map<Long, Map<Long, String>> voiceLocks = new ConcurrentHashMap<>();
    // guildId -> expireMillis
    private static final Map<Long, Long> commandLocks = new ConcurrentHashMap<>();

    /**
     * True if the guild already has as many voice locks as available bot instances.
     * Used only for user-facing "full" notice.
     */
    public static boolean isGuildFullyLocked(long guildId, int capacity)
    {
        if(capacity <= 0)
            return false;
        Map<Long, String> map = voiceLocks.get(guildId);
        return map != null && map.size() >= capacity;
    }

    public static boolean isLockedAndNotOwner(long guildId, long channelId, String botId)
    {
        Map<Long, String> map = voiceLocks.get(guildId);
        if(map == null)
            return false;
        String holder = map.get(channelId);
        return holder != null && !holder.equals(botId);
    }

    /**
     * Returns true if the given bot currently owns the lock for the channel.
     */
    public static boolean isOwner(long guildId, long channelId, String botId)
    {
        Map<Long, String> map = voiceLocks.get(guildId);
        if(map == null)
            return false;
        String holder = map.get(channelId);
        return botId.equals(holder);
    }

    /**
     * Try to lock a guild/channel pair. Returns true if acquired or already
     * locked by the same channel; false if another channel holds the lock.
     */
    public static boolean tryLock(long guildId, long channelId, String botId, int capacity)
    {
        Map<Long, String> map = voiceLocks.computeIfAbsent(guildId, k -> new ConcurrentHashMap<>());
        String holder = map.putIfAbsent(channelId, botId);
        if(holder == null)
        {
            if(capacity > 0 && map.size() > capacity)
            {
                map.remove(channelId, botId);
                return false;
            }
            return true;
        }
        return holder.equals(botId);
    }

    /**
     * Throttle commands per guild. Allows one command every CMD_LOCK_TTL_MILLIS.
     */
    public static boolean tryCommandLock(long guildId)
    {
        long now = System.currentTimeMillis();
        final boolean[] allowed = new boolean[1];
        commandLocks.compute(guildId, (k, exp) -> {
            if(exp != null && exp > now)
            {
                allowed[0] = false;
                return exp; // keep current expiry
            }
            allowed[0] = true;
            return now + CMD_LOCK_TTL_MILLIS;
        });
        return allowed[0];
    }

    public static void releaseForGuild(long guildId)
    {
        voiceLocks.remove(guildId);
        commandLocks.remove(guildId);
    }

    public static void releaseCommandLock(long guildId)
    {
        commandLocks.remove(guildId);
    }
}
