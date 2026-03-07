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
package com.jagrosh.jmusicbot.commands;

import com.jagrosh.jdautilities.command.Command;
import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.settings.Settings;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.utils.VoiceLockManager;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.exceptions.PermissionException;

/**
 *
 * @author John Grosh <john.a.grosh@gmail.com>
 */
public abstract class MusicCommand extends Command 
{
    protected final Bot bot;
    protected boolean bePlaying;
    protected boolean beListening;
    // 追加ガード: コマンド実行者がボットと同じVCにいることを要求するか
    protected boolean requireSameChannel;
    // 追加ガード: ボットが既にVC接続済みでなければ無視するか
    protected boolean requireConnected;
    private static final Map<Long, Long> FULL_NOTICE_TS = new ConcurrentHashMap<>();
    
    public MusicCommand(Bot bot)
    {
        this.bot = bot;
        this.guildOnly = true;
        this.category = new Category("音楽");
    }
    
    @Override
    protected void execute(CommandEvent event) 
    {
        Settings settings = event.getClient().getSettingsFor(event.getGuild());
        GuildMessageChannel allowedChannel = settings.getTextChannel(event.getGuild());
        GuildMessageChannel currentChannel = event.getGuildChannel();
        if(allowedChannel!=null && currentChannel!=null && !currentChannel.equals(allowedChannel))
        {
            try 
            {
                event.getMessage().delete().queue();
            } catch(PermissionException ignore){}
            event.replyInDm(event.getClient().getError()+" このコマンドは "+allowedChannel.getAsMention()+" でのみ使用できます！");
            return;
        }
        // ギルド単位で最初にロックを取ったインスタンスだけが処理する
        if(!VoiceLockManager.tryCommandLock(event.getGuild().getIdLong()))
            return;
        try
        {
            String botId = bot.getJDA().getSelfUser().getId();
            GuildVoiceState userState = event.getMember().getVoiceState();

            bot.getPlayerManager().setUpHandler(event.getGuild()); // no point constantly checking for this later

            // すでに接続中なら、そのVCロックを持っていないBotは無視する
            AudioChannel selfChannel = event.getGuild().getSelfMember().getVoiceState().getChannel();
            if(selfChannel != null && VoiceLockManager.isLockedAndNotOwner(event.getGuild().getIdLong(), selfChannel.getIdLong(), botId))
                return;
            // ボットが未接続なら実行しない（例: dc/stopが別インスタンスに反応するのを防ぐ）
            if(requireConnected && selfChannel == null)
                return;
            // コマンド実行者が別VCにいる場合は無視
            if(requireSameChannel)
            {
                if(userState == null || !userState.inAudioChannel())
                    return;
                if(selfChannel != null && !userState.getChannel().equals(selfChannel))
                    return;
            }
            // 未接続で、かつリスニング不要のコマンドなら無視（例: stop/dc の無駄反応防止）
            if(selfChannel == null && !beListening)
                return;

            if(beListening)
            {
                AudioChannel current = event.getGuild().getSelfMember().getVoiceState().getChannel();
                if(current==null)
                    current = settings.getVoiceChannel(event.getGuild());
                if(!userState.inAudioChannel() || userState.isDeafened())
                {
                    event.replyError("このコマンドを使用するには、いずれかのボイスチャンネルに参加している必要があります！");
                    return;
                }
                // ユーザのVCが他Botにロックされていれば無視
                if(VoiceLockManager.isLockedAndNotOwner(event.getGuild().getIdLong(), userState.getChannel().getIdLong(), botId))
                    return;
                if(current!=null && !userState.getChannel().equals(current))
                {
                    // 別VC担当のボットが誤応答しないよう、全インスタンス埋まっている場合のみ通知
                    maybeNotifyNoCapacity(event);
                    return;
                }

                VoiceChannel afkChannel = userState.getGuild().getAfkChannel();
                if(afkChannel != null && afkChannel.equals(userState.getChannel()))
                {
                    event.replyError("AFK チャンネルではこのコマンドを使用できません！");
                    return;
                }

                if(!event.getGuild().getSelfMember().getVoiceState().inAudioChannel())
                {
                    try 
                    {
                    // 別インスタンスが担当している場合は静かに無視して衝突を避ける
                    if(!VoiceLockManager.tryLock(
                            event.getGuild().getIdLong(),
                            userState.getChannel().getIdLong(),
                            botId,
                            bot.getConfig().getTokens().size()))
                        return;
                        event.getGuild().getAudioManager().openAudioConnection(userState.getChannel());
                    }
                    catch(PermissionException ex) 
                    {
                        event.reply(event.getClient().getError()+" "+userState.getChannel().getAsMention()+" に接続できません！");
                        VoiceLockManager.releaseForGuild(event.getGuild().getIdLong());
                        return;
                    }
                }
            }

            if(bePlaying && !((AudioHandler)event.getGuild().getAudioManager().getSendingHandler()).isMusicPlaying(event.getJDA()))
                return; // 再生していないBotは黙って無視
            
            doCommand(event);
        }
        finally
        {
            VoiceLockManager.releaseCommandLock(event.getGuild().getIdLong());
        }
    }
    
    private void maybeNotifyNoCapacity(CommandEvent event)
    {
        int capacity = bot.getConfig().getTokens().size();
        long guildId = event.getGuild().getIdLong();
        if(capacity <= 0)
            return;
        if(!VoiceLockManager.isGuildFullyLocked(guildId, capacity))
            return;
        long now = System.currentTimeMillis();
        long last = FULL_NOTICE_TS.getOrDefault(guildId, 0L);
        if(now - last < 10_000)
            return; // throttle to avoid spam
        FULL_NOTICE_TS.put(guildId, now);
        event.replyWarning("このサーバーで利用可能なボットはすべて別のボイスチャンネルを担当中です。少し待ってから再度お試しください。");
    }
    
    public abstract void doCommand(CommandEvent event);
}
