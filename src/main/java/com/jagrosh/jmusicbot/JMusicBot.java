/*
 * Copyright 2016 John Grosh (jagrosh).
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
package com.jagrosh.jmusicbot;

import com.jagrosh.jdautilities.command.CommandClient;
import com.jagrosh.jdautilities.command.CommandClientBuilder;
import com.jagrosh.jdautilities.commons.waiter.EventWaiter;
import com.jagrosh.jdautilities.examples.command.*;
import com.jagrosh.jmusicbot.commands.admin.*;
import com.jagrosh.jmusicbot.commands.dj.*;
import com.jagrosh.jmusicbot.commands.general.*;
import com.jagrosh.jmusicbot.commands.music.*;
import com.jagrosh.jmusicbot.commands.owner.*;
import com.jagrosh.jmusicbot.entities.Prompt;
import com.jagrosh.jmusicbot.gui.GUI;
import com.jagrosh.jmusicbot.settings.SettingsManager;
import com.jagrosh.jmusicbot.utils.OtherUtil;
import java.awt.Color;
import java.util.Arrays;
import net.dv8tion.jda.api.*;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.InvalidTokenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Level;

/**
 *
 * @author John Grosh (jagrosh)
 */
public class JMusicBot 
{
    public final static Logger LOG = LoggerFactory.getLogger(JMusicBot.class);
    public final static Permission[] RECOMMENDED_PERMS = {Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY, Permission.MESSAGE_ADD_REACTION,
                                Permission.MESSAGE_EMBED_LINKS, Permission.MESSAGE_ATTACH_FILES, Permission.MESSAGE_MANAGE, Permission.MESSAGE_EXT_EMOJI,
                                Permission.VOICE_CONNECT, Permission.VOICE_SPEAK, Permission.NICKNAME_CHANGE};
    public final static GatewayIntent[] INTENTS = {GatewayIntent.DIRECT_MESSAGES, GatewayIntent.GUILD_MESSAGES, GatewayIntent.GUILD_MESSAGE_REACTIONS, GatewayIntent.GUILD_VOICE_STATES, GatewayIntent.MESSAGE_CONTENT};
    
    /**
     * @param args the command line arguments
     */
    public static void main(String[] args)
    {
        if(args.length > 0)
            switch(args[0].toLowerCase())
            {
                case "generate-config":
                    BotConfig.writeDefaultConfig();
                    return;
                case "configure":
                    Prompt configPrompt = new Prompt("JMusicBot");
                    BotConfig cfg = new BotConfig(configPrompt);
                    cfg.load();
                    return;
                case "multi":
                    startMultiBots();
                    return;
                default:
            }
        startBot();
    }
    
    private static void startBot()
    {
        Prompt prompt = new Prompt("JMusicBot");
        BotConfig cfg = new BotConfig(prompt);
        if(!initConfig(cfg, prompt))
            return;
        startBots(cfg, prompt);
    }
    
    private static void startMultiBots()
    {
        Prompt prompt = new Prompt("JMusicBot");
        BotConfig cfg = new BotConfig(prompt);
        if(!initConfig(cfg, prompt))
            return;
        startBots(cfg, prompt);
    }

    private static boolean initConfig(BotConfig config, Prompt prompt)
    {
        // startup checks
        OtherUtil.checkVersion(prompt);
        OtherUtil.checkJavaVersion(prompt);
        
        // load config
        config.load();
        if(!config.isValid())
            return false;
        LOG.info("Loaded config from " + config.getConfigLocation());

        // set log level from config
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).setLevel(
                Level.toLevel(config.getLogLevel(), Level.INFO));
        return true;
    }

    private static void startBots(BotConfig cfg, Prompt prompt)
    {
        int idx = 1;
        for(String token : cfg.getTokens())
        {
            startBotWithToken(cfg, prompt, token, idx++);
        }
    }

    private static void startBotWithToken(BotConfig config, Prompt prompt, String token, int index)
    {
        EventWaiter waiter = new EventWaiter();
        SettingsManager settings = new SettingsManager();
        Bot bot = new Bot(waiter, config, settings);
        CommandClient client = createCommandClient(config, settings, bot);
        
        if(!prompt.isNoGUI())
        {
            try 
            {
                GUI gui = new GUI(bot);
                bot.setGUI(gui);
                gui.init();

                LOG.info("Loaded config from " + config.getConfigLocation());
            }
            catch(Exception e)
            {
                LOG.error("Could not start GUI. If you are "
                        + "running on a server or in a location where you cannot display a "
                        + "window, please run in nogui mode using the -Dnogui=true flag.");
            }
        }
        
        try
        {
            JDA jda = JDABuilder.create(token, Arrays.asList(INTENTS))
                    .enableCache(CacheFlag.MEMBER_OVERRIDES, CacheFlag.VOICE_STATE)
                    .disableCache(CacheFlag.ACTIVITY, CacheFlag.CLIENT_STATUS, CacheFlag.EMOJI, CacheFlag.ONLINE_STATUS)
                    .setActivity(config.isGameNone() ? null : Activity.playing("loading..."))
                    .setStatus(config.getStatus() == OnlineStatus.UNKNOWN
                            ? OnlineStatus.DO_NOT_DISTURB
                            : config.getStatus())
                    .addEventListeners(client, waiter, new Listener(bot))
                    .setBulkDeleteSplittingEnabled(true)
                    .build();
            bot.setJDA(jda);

            String unsupportedReason = OtherUtil.getUnsupportedBotReason(jda);
            if (unsupportedReason != null)
            {
                prompt.alert(Prompt.Level.ERROR, "JMusicBot-"+index, "JMusicBot cannot be run on this Discord bot: " + unsupportedReason);
                try{ Thread.sleep(5000);}catch(InterruptedException ignored){} 
                jda.shutdown();
                System.exit(1);
            }
            
            if(!"@mention".equals(config.getPrefix()))
            {
                LOG.info("You currently have a custom prefix set. "
                        + "If your prefix is not working, make sure that the 'MESSAGE CONTENT INTENT' is Enabled "
                        + "on https://discord.com/developers/applications/{}/bot",
                        jda.getSelfUser().getId());
            }
        }
        catch (InvalidTokenException ex)
        {
            prompt.alert(Prompt.Level.ERROR, "JMusicBot-"+index, ex + "\n正しい config.txt を編集し、"
                    + "適切なトークン（'secret'ではありません）を使用しているか確認してください。"
                    + "\nConfig Location: " + config.getConfigLocation());
        }
        catch(IllegalArgumentException ex)
        {
            prompt.alert(Prompt.Level.ERROR, "JMusicBot-"+index, "Some aspect of the configuration is "
                    + "invalid: " + ex + "\nConfig Location: " + config.getConfigLocation());
        }
        catch(ErrorResponseException ex)
        {
            prompt.alert(Prompt.Level.ERROR, "JMusicBot-"+index, ex + "\nInvalid reponse returned when "
                    + "attempting to connect, please make sure you're connected to the internet");
        }
    }
    
    private static CommandClient createCommandClient(BotConfig config, SettingsManager settings, Bot bot)
    {
        // instantiate about command
        AboutCommand aboutCommand = new AboutCommand(Color.BLUE.brighter(),
                                "[簡単に自分でホストできる](https://github.com/jagrosh/MusicBot) 音楽ボットです (v" + OtherUtil.getCurrentVersion() + ")",
                                new String[]{"高品質な音楽再生", "FairQueue™ テクノロジー", "セルフホストが簡単"},
                                RECOMMENDED_PERMS);
        aboutCommand.setIsAuthor(false);
        aboutCommand.setReplacementCharacter("\uD83C\uDFB6"); // 🎶
        
        String primaryPrefix = config.getPrefix();
        String alternatePrefix = config.getAltPrefix();
        if(!"@mention".equalsIgnoreCase(primaryPrefix) && alternatePrefix != null
                && "@mention".equalsIgnoreCase(alternatePrefix))
        {
            alternatePrefix = null;
        }

        // set up the command client
        CommandClientBuilder cb = new CommandClientBuilder()
                .setPrefix(primaryPrefix)
                .setAlternativePrefix(alternatePrefix)
                .setOwnerId(Long.toString(config.getOwnerId()))
                .setEmojis(config.getSuccess(), config.getWarning(), config.getError())
                .setHelpWord(config.getHelp())
                .setLinkedCacheSize(200)
                .setGuildSettingsManager(settings)
                .addCommands(aboutCommand,
                        new PingCommand(),
                        new SettingsCmd(bot),
                        
                        new LyricsCmd(bot),
                        new NowplayingCmd(bot),
                        new PlayCmd(bot),
                        new PlaylistsCmd(bot),
                        new QueueCmd(bot),
                        new RemoveCmd(bot),
                        new SearchCmd(bot),
                        new SCSearchCmd(bot),
                        new SeekCmd(bot),
                        new ShuffleCmd(bot),
                        new SkipCmd(bot),

                        new ForceRemoveCmd(bot),
                        new ForceskipCmd(bot),
                        new MoveTrackCmd(bot),
                        new PauseCmd(bot),
                        new PlaynextCmd(bot),
                        new RepeatCmd(bot),
                        new SkiptoCmd(bot),
                        new StopCmd(bot),
                        new VolumeCmd(bot),
                        
                        new PrefixCmd(bot),
                        new QueueTypeCmd(bot),
                        new SetdjCmd(bot),
                        new SkipratioCmd(bot),
                        new SettcCmd(bot),
                        new SetvcCmd(bot),

                        new AutoplaylistCmd(bot),
                        new DebugCmd(bot),
                        new PlaylistCmd(bot),
                        new SetavatarCmd(bot),
                        new SetgameCmd(bot),
                        new SetnameCmd(bot),
                        new SetstatusCmd(bot),
                        new ShutdownCmd(bot)
                );
        
        // enable eval if applicable
        if(config.useEval())
            cb.addCommand(new EvalCmd(bot));
        
        // set status if set in config
        if(config.getStatus() != OnlineStatus.UNKNOWN)
            cb.setStatus(config.getStatus());
        
        // set game
        if(config.getGame() == null)
            cb.useDefaultGame();
        else if(config.isGameNone())
            cb.setActivity(null);
        else
            cb.setActivity(config.getGame());
        
        return cb.build();
    }
}
