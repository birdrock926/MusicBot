/*
 * Copyright 2018 John Grosh <john.a.grosh@gmail.com>.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jagrosh.jmusicbot.utils;

import com.jagrosh.jmusicbot.JMusicBot;
import com.jagrosh.jmusicbot.entities.Prompt;
import java.io.*;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.ApplicationInfo;
import net.dv8tion.jda.api.entities.User;
import okhttp3.*;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 *
 * @author John Grosh <john.a.grosh@gmail.com>
 */
public class OtherUtil
{
    public final static String NEW_VERSION_AVAILABLE = "JMusicBot の新しいバージョンが利用可能です！\n"
                    + "現在のバージョン: %s\n"
                    + "新しいバージョン: %s\n\n"
                    + "最新のリリースは https://github.com/jagrosh/MusicBot/releases/latest から入手してください。";
    private final static String WINDOWS_INVALID_PATH = "c:\\windows\\system32\\";
    
    /**
     * gets a Path from a String
     * also fixes the windows tendency to try to start in system32
     * any time the bot tries to access this path, it will instead start in the location of the jar file
     * 
     * @param path the string path
     * @return the Path object
     */
    public static Path getPath(String path)
    {
        Path result = Paths.get(path);
        // special logic to prevent trying to access system32
        if(result.toAbsolutePath().toString().toLowerCase().startsWith(WINDOWS_INVALID_PATH))
        {
            try
            {
                result = Paths.get(new File(JMusicBot.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParentFile().getPath() + File.separator + path);
            }
            catch(URISyntaxException ignored) {}
        }
        return result;
    }
    
    /**
     * Loads a resource from the jar as a string
     * 
     * @param clazz class base object
     * @param name name of resource
     * @return string containing the contents of the resource
     */
    public static String loadResource(Object clazz, String name)
    {
        try(BufferedReader reader = new BufferedReader(new InputStreamReader(clazz.getClass().getResourceAsStream(name))))
        {
            StringBuilder sb = new StringBuilder();
            reader.lines().forEach(line -> sb.append("\r\n").append(line));
            return sb.toString().trim();
        }
        catch(IOException ignored)
        {
            return null;
        }
    }
    
    /**
     * Loads image data from a URL
     * 
     * @param url url of image
     * @return inputstream of url
     */
    public static InputStream imageFromUrl(String url)
    {
        if(url==null)
            return null;
        try 
        {
            URL u = new URL(url);
            URLConnection urlConnection = u.openConnection();
            urlConnection.setRequestProperty("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/49.0.2623.112 Safari/537.36");
            return urlConnection.getInputStream();
        }
        catch(IOException | IllegalArgumentException ignore) {}
        return null;
    }
    
    /**
     * Parses an activity from a string
     * 
     * @param game the game, including the action such as 'playing' or 'watching'
     * @return the parsed activity
     */
    public static Activity parseGame(String game)
    {
        if(game==null || game.trim().isEmpty() || game.trim().equalsIgnoreCase("default"))
            return null;
        String lower = game.toLowerCase();
        if(lower.startsWith("playing"))
            return Activity.playing(makeNonEmpty(game.substring(7).trim()));
        if(lower.startsWith("listening to"))
            return Activity.listening(makeNonEmpty(game.substring(12).trim()));
        if(lower.startsWith("listening"))
            return Activity.listening(makeNonEmpty(game.substring(9).trim()));
        if(lower.startsWith("watching"))
            return Activity.watching(makeNonEmpty(game.substring(8).trim()));
        if(lower.startsWith("streaming"))
        {
            String[] parts = game.substring(9).trim().split("\\s+", 2);
            if(parts.length == 2)
            {
                return Activity.streaming(makeNonEmpty(parts[1]), "https://twitch.tv/"+parts[0]);
            }
        }
        return Activity.playing(game);
    }
   
    public static String makeNonEmpty(String str)
    {
        return str == null || str.isEmpty() ? "\u200B" : str;
    }
    
    public static OnlineStatus parseStatus(String status)
    {
        if(status==null || status.trim().isEmpty())
            return OnlineStatus.ONLINE;
        OnlineStatus st = OnlineStatus.fromKey(status);
        return st == null ? OnlineStatus.ONLINE : st;
    }
    
    public static void checkJavaVersion(Prompt prompt)
    {
        if(!System.getProperty("java.vm.name").contains("64"))
            prompt.alert(Prompt.Level.WARNING, "Java Version",
                    "サポートされているJavaバージョンを使用していない可能性があります。64ビット版のJavaを使用してください。");
    }
    
    public static void checkVersion(Prompt prompt)
    {
        // Get current version number
        String version = getCurrentVersion();
        
        // Check for new version
        String latestVersion = getLatestVersion();
        
        if(latestVersion!=null && !latestVersion.equals(version))
        {
            prompt.alert(Prompt.Level.WARNING, "JMusicBot Version", String.format(NEW_VERSION_AVAILABLE, version, latestVersion));
        }
    }
    
    public static String getCurrentVersion()
    {
        if(JMusicBot.class.getPackage()!=null && JMusicBot.class.getPackage().getImplementationVersion()!=null)
            return JMusicBot.class.getPackage().getImplementationVersion();
        else
            return "UNKNOWN";
    }
    
    public static String getLatestVersion()
    {
        try
        {
            Response response = new OkHttpClient.Builder().build()
                    .newCall(new Request.Builder().get().url("https://api.github.com/repos/jagrosh/MusicBot/releases/latest").build())
                    .execute();
            ResponseBody body = response.body();
            if(body != null)
            {
                try(Reader reader = body.charStream())
                {
                    JSONObject obj = new JSONObject(new JSONTokener(reader));
                    return obj.getString("tag_name");
                }
                finally
                {
                    response.close();
                }
            }
            else
                return null;
        }
        catch(IOException | JSONException | NullPointerException ex)
        {
            return null;
        }
    }

    public static String[] mergeCommandAliases(String[] configuredAliases, String... defaultAliases)
    {
        Set<String> aliasSet = new LinkedHashSet<>();
        if(configuredAliases != null)
        {
            for(String alias : configuredAliases)
                addAliasVariants(aliasSet, alias);
        }
        if(defaultAliases != null)
        {
            for(String alias : defaultAliases)
                addAliasVariants(aliasSet, alias);
        }
        return aliasSet.toArray(new String[0]);
    }

    private static void addAliasVariants(Set<String> aliasSet, String alias)
    {
        if(alias == null)
            return;
        String trimmed = alias.trim();
        if(trimmed.isEmpty())
            return;

        Set<String> caseVariants = new LinkedHashSet<>();
        collectCaseVariants(trimmed.toCharArray(), 0, new StringBuilder(), caseVariants);
        if(caseVariants.isEmpty())
            caseVariants.add(trimmed);

        for(String variant : caseVariants)
        {
            aliasSet.add(variant);
            aliasSet.add(toFullWidth(variant));
        }
    }

    private static void collectCaseVariants(char[] chars, int index, StringBuilder current, Set<String> sink)
    {
        if(index >= chars.length)
        {
            sink.add(current.toString());
            return;
        }

        char c = chars[index];
        if(Character.isLetter(c))
        {
            current.append(Character.toLowerCase(c));
            collectCaseVariants(chars, index + 1, current, sink);
            current.setLength(current.length() - 1);

            current.append(Character.toUpperCase(c));
            collectCaseVariants(chars, index + 1, current, sink);
            current.setLength(current.length() - 1);
        }
        else
        {
            current.append(c);
            collectCaseVariants(chars, index + 1, current, sink);
            current.setLength(current.length() - 1);
        }
    }

    public static String toFullWidth(String input)
    {
        if(input == null || input.isEmpty())
            return input;
        StringBuilder builder = new StringBuilder(input.length());
        for(char c : input.toCharArray())
        {
            if(c == ' ')
            {
                builder.append('\u3000');
            }
            else if(c >= 0x21 && c <= 0x7E)
            {
                builder.append((char)(c + 0xFEE0));
            }
            else
            {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    /**
     * Checks if the bot JMusicBot is being run on is supported & returns the reason if it is not.
     * @return A string with the reason, or null if it is supported.
     */
    public static String getUnsupportedBotReason(JDA jda) 
    {
        if (jda.getSelfUser().getFlags().contains(User.UserFlag.VERIFIED_BOT))
            return "このボットは認証済みです。認証済みボットでのJMusicBotの使用はサポートされていません。";

        ApplicationInfo info = jda.retrieveApplicationInfo().complete();
        if (info.isBotPublic())
            return "\"Public Bot\" が有効になっています。公開ボットとしてJMusicBotを使用することはサポートされていません。次の開発者ダッシュボードで無効化してください: "
                    + "https://discord.com/developers/applications/" + jda.getSelfUser().getId() + "/bot"
                    + "\nまた、https://discord.com/developers/applications/" + jda.getSelfUser().getId() + "/installation で全てのインストールコンテキストを無効にする必要がある場合があります。";

        return null;
    }
}
