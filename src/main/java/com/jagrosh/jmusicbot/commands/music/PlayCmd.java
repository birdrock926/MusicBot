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

import com.jagrosh.jmusicbot.audio.RequestMetadata;
import com.jagrosh.jmusicbot.utils.TimeUtil;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.jagrosh.jdautilities.command.Command;
import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jdautilities.menu.ButtonMenu;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.audio.QueuedTrack;
import com.jagrosh.jmusicbot.commands.DJCommand;
import com.jagrosh.jmusicbot.commands.MusicCommand;
import com.jagrosh.jmusicbot.playlist.PlaylistLoader.Playlist;
import com.jagrosh.jmusicbot.utils.FormatUtil;
import com.jagrosh.jmusicbot.utils.OtherUtil;
import java.util.concurrent.TimeUnit;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.exceptions.PermissionException;

/**
 *
 * @author John Grosh <john.a.grosh@gmail.com>
 */
public class PlayCmd extends MusicCommand
{
    private final static String LOAD = "\uD83D\uDCE5"; // 📥
    private final static String CANCEL = "\uD83D\uDEAB"; // 🚫
    
    private final String loadingEmoji;
    
    public PlayCmd(Bot bot)
    {
        super(bot);
        this.loadingEmoji = bot.getConfig().getLoading();
        this.name = "play";
        this.arguments = "<タイトル|URL|サブコマンド>";
        this.help = "指定した曲を再生します";
        this.aliases = OtherUtil.mergeCommandAliases(bot.getConfig().getAliases(this.name), "p");
        this.beListening = true;
        this.bePlaying = false;
        this.children = new Command[]{new PlaylistCmd(bot)};
    }

    @Override
    public void doCommand(CommandEvent event) 
    {
        if(event.getArgs().isEmpty() && event.getMessage().getAttachments().isEmpty())
        {
            AudioHandler handler = (AudioHandler)event.getGuild().getAudioManager().getSendingHandler();
            if(handler.getPlayer().getPlayingTrack()!=null && handler.getPlayer().isPaused())
            {
                if(DJCommand.checkDJPermission(event))
                {
                    handler.getPlayer().setPaused(false);
                    event.replySuccess("**"+handler.getPlayer().getPlayingTrack().getInfo().title+"** の再生を再開しました。");
                }
                else
                    event.replyError("プレイヤーを再開できるのはDJのみです！");
                return;
            }
            StringBuilder builder = new StringBuilder(event.getClient().getWarning()+" 再生コマンド一覧:\n");
            builder.append("\n`").append(event.getClient().getPrefix()).append(name).append(" <曲名>` - YouTubeで最初に見つかった曲を再生します");
            builder.append("\n`").append(event.getClient().getPrefix()).append(name).append(" <URL>` - 指定した曲・プレイリスト・配信を再生します");
            for(Command cmd: children)
                builder.append("\n`").append(event.getClient().getPrefix()).append(name).append(" ").append(cmd.getName()).append(" ").append(cmd.getArguments()).append("` - ").append(cmd.getHelp());
            event.reply(builder.toString());
            return;
        }
        String args = event.getArgs().startsWith("<") && event.getArgs().endsWith(">") 
                ? event.getArgs().substring(1,event.getArgs().length()-1) 
                : event.getArgs().isEmpty() ? event.getMessage().getAttachments().get(0).getUrl() : event.getArgs();
        event.reply(loadingEmoji+" 読み込み中... `["+args+"]`", m -> bot.getPlayerManager().loadItemOrdered(event.getGuild(), args, new ResultHandler(m,event,false)));
    }
    
    private class ResultHandler implements AudioLoadResultHandler
    {
        private final Message m;
        private final CommandEvent event;
        private final boolean ytsearch;
        
        private ResultHandler(Message m, CommandEvent event, boolean ytsearch)
        {
            this.m = m;
            this.event = event;
            this.ytsearch = ytsearch;
        }
        
        private void loadSingle(AudioTrack track, AudioPlaylist playlist)
        {
            if(bot.getConfig().isTooLong(track))
            {
                m.editMessage(FormatUtil.filter(event.getClient().getWarning()+" このトラック (**"+track.getInfo().title+"**) は許可されている最長時間を超えています: `"
                        + TimeUtil.formatTime(track.getDuration())+"` > `"+ TimeUtil.formatTime(bot.getConfig().getMaxSeconds()*1000)+"`")).queue();
                return;
            }
            AudioHandler handler = (AudioHandler)event.getGuild().getAudioManager().getSendingHandler();
            int pos = handler.addTrack(new QueuedTrack(track, RequestMetadata.fromResultHandler(track, event)))+1;
            String addMsg = FormatUtil.filter(event.getClient().getSuccess()+" **"+track.getInfo().title
                    +"** (`"+ TimeUtil.formatTime(track.getDuration())+"`) を"+(pos==0?"再生開始しました":" キューの"+pos+"番目に追加しました"));
            if(playlist==null || !event.getSelfMember().hasPermission(event.getTextChannel(), Permission.MESSAGE_ADD_REACTION))
                m.editMessage(addMsg).queue();
            else
            {
                new ButtonMenu.Builder()
                        .setText(addMsg+"\n"+event.getClient().getWarning()+" このトラックには **"+playlist.getTracks().size()+"** 件のプレイリストが紐付いています。"+LOAD+" を選ぶとプレイリスト全体を読み込みます。")
                        .setChoices(LOAD, CANCEL)
                        .setEventWaiter(bot.getWaiter())
                        .setTimeout(30, TimeUnit.SECONDS)
                        .setAction(re ->
                        {
                            if(re.getName().equals(LOAD))
                                m.editMessage(addMsg+"\n"+event.getClient().getSuccess()+" 追加で **"+loadPlaylist(playlist, track)+"** 件のトラックを読み込みました！").queue();
                            else
                                m.editMessage(addMsg).queue();
                        }).setFinalAction(m ->
                        {
                            try{ m.clearReactions().queue(); }catch(PermissionException ignore) {}
                        }).build().display(m);
            }
        }

        private void loadSingleWithPlaylist(AudioTrack track, AudioPlaylist playlist)
        {
            if(bot.getConfig().isTooLong(track))
            {
                m.editMessage(FormatUtil.filter(event.getClient().getWarning()+" このトラック (**"+track.getInfo().title+"**) は許可されている最長時間を超えています: `"
                        + TimeUtil.formatTime(track.getDuration())+"` > `"+ TimeUtil.formatTime(bot.getConfig().getMaxSeconds()*1000)+"`")).queue();
                return;
            }

            AudioHandler handler = (AudioHandler)event.getGuild().getAudioManager().getSendingHandler();
            int pos = handler.addTrack(new QueuedTrack(track, RequestMetadata.fromResultHandler(track, event)))+1;
            StringBuilder builder = new StringBuilder();
            builder.append(event.getClient().getSuccess())
                    .append(" **").append(track.getInfo().title)
                    .append("** (`").append(TimeUtil.formatTime(track.getDuration())).append("`) を")
                    .append(pos==0 ? "再生開始しました" : " キューの"+pos+"番目に追加しました");

            int total = playlist.getTracks().size();
            int count = loadPlaylist(playlist, track);

            if(total == 0)
            {
                builder.append("\n").append(event.getClient().getWarning())
                        .append(" プレイリスト")
                        .append(playlist.getName()==null ? "" : " (**"+playlist.getName()+"**) ")
                        .append("を読み込めなかったか、エントリがありませんでした。");
            }
            else if(total == 1)
            {
                // no additional tracks to add
            }
            else if(count == 0)
            {
                builder.append("\n").append(event.getClient().getWarning())
                        .append(" このプレイリスト")
                        .append(playlist.getName()==null ? "" : " (**"+playlist.getName()+"**) ")
                        .append("の他のエントリはすべて許可されている最大長 (`")
                        .append(bot.getConfig().getMaxTime()).append("`) を超えていたため追加できませんでした。");
            }
            else
            {
                builder.append("\n").append(event.getClient().getSuccess())
                        .append(" プレイリスト")
                        .append(playlist.getName()==null ? "を読み込み、`"+total+"` 件のエントリから" : " **"+playlist.getName()+"** を読み込み、`")
                        .append(total).append("` 件のエントリのうち `")
                        .append(count).append("` 件をキューに追加しました！");
                if(count < total-1)
                {
                    builder.append("\n").append(event.getClient().getWarning())
                            .append(" 許可されている最大長 (`")
                            .append(bot.getConfig().getMaxTime()).append("`) を超えるトラックは除外されています。");
                }
            }

            m.editMessage(FormatUtil.filter(builder.toString())).queue();
        }

        private int loadPlaylist(AudioPlaylist playlist, AudioTrack exclude)
        {
            int[] count = {0};
            playlist.getTracks().stream().forEach((track) -> {
                if(!bot.getConfig().isTooLong(track) && !track.equals(exclude))
                {
                    AudioHandler handler = (AudioHandler)event.getGuild().getAudioManager().getSendingHandler();
                    handler.addTrack(new QueuedTrack(track, RequestMetadata.fromResultHandler(track, event)));
                    count[0]++;
                }
            });
            return count[0];
        }
        
        @Override
        public void trackLoaded(AudioTrack track)
        {
            loadSingle(track, null);
        }

        @Override
        public void playlistLoaded(AudioPlaylist playlist)
        {
            if(playlist.getTracks().size()==1 || playlist.isSearchResult())
            {
                AudioTrack single = playlist.getSelectedTrack()==null ? playlist.getTracks().get(0) : playlist.getSelectedTrack();
                loadSingle(single, null);
            }
            else if (playlist.getSelectedTrack()!=null)
            {
                AudioTrack single = playlist.getSelectedTrack();
                loadSingleWithPlaylist(single, playlist);
            }
            else
            {
                int count = loadPlaylist(playlist, null);
                String playlistName = playlist.getName();
                String playlistLabel = playlistName == null ? "プレイリスト" : "プレイリスト(**" + playlistName + "**)";
                if(playlist.getTracks().isEmpty())
                {
                    m.editMessage(FormatUtil.filter(event.getClient().getWarning() + " " + playlistLabel
                            + "を読み込めなかったか、エントリがありませんでした。")).queue();
                }
                else if(count==0)
                {
                    m.editMessage(FormatUtil.filter(event.getClient().getWarning() + " この" + playlistLabel
                            + "のすべてのエントリが許可されている最大長 (`" + bot.getConfig().getMaxTime()
                            + "`) を超えていたため追加できませんでした。")).queue();
                }
                else
                {
                    String successLabel = playlistName == null
                            ? "プレイリストを検出し"
                            : "プレイリスト **" + playlistName + "** を検出し";
                    StringBuilder builder = new StringBuilder()
                            .append(event.getClient().getSuccess()).append(' ')
                            .append(successLabel)
                            .append('`').append(playlist.getTracks().size())
                            .append("` 件のエントリをキューに追加しました！");
                    if(count < playlist.getTracks().size())
                    {
                        builder.append('\n').append(event.getClient().getWarning())
                                .append(" 許可されている最大長 (`")
                                .append(bot.getConfig().getMaxTime())
                                .append("`) を超えるトラックは除外されています。");
                    }
                    m.editMessage(FormatUtil.filter(builder.toString())).queue();
                }
            }
        }

        @Override
        public void noMatches()
        {
            if(ytsearch)
                m.editMessage(FormatUtil.filter(event.getClient().getWarning()+" `"+event.getArgs()+"` に一致する結果は見つかりませんでした。")).queue();
            else
                bot.getPlayerManager().loadItemOrdered(event.getGuild(), "ytsearch:"+event.getArgs(), new ResultHandler(m,event,true));
        }

        @Override
        public void loadFailed(FriendlyException throwable)
        {
            if(throwable.severity==Severity.COMMON)
                m.editMessage(event.getClient().getError()+" 読み込み中にエラーが発生しました: "+throwable.getMessage()).queue();
            else
                m.editMessage(event.getClient().getError()+" トラックの読み込み中にエラーが発生しました。").queue();
        }
    }
    
    public class PlaylistCmd extends MusicCommand
    {
        public PlaylistCmd(Bot bot)
        {
            super(bot);
            this.name = "playlist";
            this.aliases = new String[]{"pl"};
            this.arguments = "<名前>";
            this.help = "指定したプレイリストを再生します";
            this.beListening = true;
            this.bePlaying = false;
        }

        @Override
        public void doCommand(CommandEvent event) 
        {
            if(event.getArgs().isEmpty())
            {
                event.reply(event.getClient().getError()+" プレイリスト名を指定してください。");
                return;
            }
            Playlist playlist = bot.getPlaylistLoader().getPlaylist(event.getArgs());
            if(playlist==null)
            {
                event.replyError("Playlists フォルダーに `"+event.getArgs()+".txt` が見つかりませんでした。");
                return;
            }
            event.getChannel().sendMessage(loadingEmoji+" プレイリスト **"+event.getArgs()+"** を読み込み中... ("+playlist.getItems().size()+" 件)").queue(m ->
            {
                AudioHandler handler = (AudioHandler)event.getGuild().getAudioManager().getSendingHandler();
                playlist.loadTracks(bot.getPlayerManager(), (at)->handler.addTrack(new QueuedTrack(at, RequestMetadata.fromResultHandler(at, event))), () -> {
                    StringBuilder builder = new StringBuilder(playlist.getTracks().isEmpty()
                            ? event.getClient().getWarning()+" トラックを読み込めませんでした！"
                            : event.getClient().getSuccess()+" **"+playlist.getTracks().size()+"** 件のトラックを読み込みました！");
                    if(!playlist.getErrors().isEmpty())
                        builder.append("\n次のトラックは読み込めませんでした:");
                    playlist.getErrors().forEach(err -> builder.append("\n`[").append(err.getIndex()+1).append("]` **").append(err.getItem()).append("**: ").append(err.getReason()));
                    String str = builder.toString();
                    if(str.length()>2000)
                        str = str.substring(0,1994)+" (...)";
                    m.editMessage(FormatUtil.filter(str)).queue();
                });
            });
        }
    }
}
