/*
 * Copyright 2016 John Grosh <john.a.grosh@gmail.com>.
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
package com.jagrosh.jmusicbot.commands.music;

import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.audio.RequestMetadata;
import com.jagrosh.jmusicbot.commands.MusicCommand;
import com.jagrosh.jmusicbot.utils.FormatUtil;

/**
 *
 * @author John Grosh <john.a.grosh@gmail.com>
 */
public class SkipCmd extends MusicCommand 
{
    public SkipCmd(Bot bot)
    {
        super(bot);
        this.name = "skip";
        this.help = "再生中の曲をスキップするための投票を行います";
        this.aliases = bot.getConfig().getAliases(this.name);
        this.beListening = true;
        this.bePlaying = true;
    }

    @Override
    public void doCommand(CommandEvent event) 
    {
        AudioHandler handler = (AudioHandler)event.getGuild().getAudioManager().getSendingHandler();
        RequestMetadata rm = handler.getRequestMetadata();
        double skipRatio = bot.getSettingsManager().getSettings(event.getGuild()).getSkipRatio();
        if(skipRatio == -1) {
          skipRatio = bot.getConfig().getSkipRatio();
        }
        if(event.getAuthor().getIdLong() == rm.getOwner() || skipRatio == 0)
        {
            event.reply(event.getClient().getSuccess()+" **"+handler.getPlayer().getPlayingTrack().getInfo().title+"** をスキップしました");
            handler.getPlayer().stopTrack();
        }
        else
        {
            int listeners = (int)event.getSelfMember().getVoiceState().getChannel().getMembers().stream()
                    .filter(m -> !m.getUser().isBot() && !m.getVoiceState().isDeafened()).count();
            String msg;
            if(handler.getVotes().contains(event.getAuthor().getId()))
                msg = event.getClient().getWarning()+" この曲のスキップにはすでに投票しています `[";
            else
            {
                msg = event.getClient().getSuccess()+" この曲をスキップするよう投票しました `[";
                handler.getVotes().add(event.getAuthor().getId());
            }
            int skippers = (int)event.getSelfMember().getVoiceState().getChannel().getMembers().stream()
                    .filter(m -> handler.getVotes().contains(m.getUser().getId())).count();
            int required = (int)Math.ceil(listeners * skipRatio);
            msg += skippers + " 票、必要票数 " + required + "/" + listeners + " ]`";
            if(skippers>=required)
            {
                msg += "\n" + event.getClient().getSuccess() + " **" + handler.getPlayer().getPlayingTrack().getInfo().title
                    + "** をスキップしました " + (rm.getOwner() == 0L ? "(自動再生)" : "(**" + FormatUtil.formatUsername(rm.user) + "** のリクエスト)");
                handler.getPlayer().stopTrack();
            }
            event.reply(msg);
        }
    }
    
}
