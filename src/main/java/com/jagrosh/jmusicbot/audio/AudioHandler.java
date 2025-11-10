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
package com.jagrosh.jmusicbot.audio;

import com.jagrosh.jmusicbot.playlist.PlaylistLoader.Playlist;
import com.jagrosh.jmusicbot.queue.AbstractQueue;
import com.jagrosh.jmusicbot.settings.QueueType;
import com.jagrosh.jmusicbot.utils.TimeUtil;
import com.jagrosh.jmusicbot.settings.RepeatMode;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import com.jagrosh.jmusicbot.settings.Settings;
import com.jagrosh.jmusicbot.utils.FormatUtil;
import com.jagrosh.jmusicbot.utils.OtherUtil;
import com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeAudioTrack;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.audio.AudioSendHandler;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author John Grosh <john.a.grosh@gmail.com>
 */
public class AudioHandler extends AudioEventAdapter implements AudioSendHandler 
{
    public final static String PLAY_EMOJI  = "\u25B6"; // ▶
    public final static String PAUSE_EMOJI = "\u23F8"; // ⏸
    public final static String STOP_EMOJI  = "\u23F9"; // ⏹


    private static final Logger LOG = LoggerFactory.getLogger(AudioHandler.class);
    private static final long STUCK_RECHECK_DELAY_MS = 750L;
    private static final long RECENT_FRAME_WINDOW_MS = 700L;
    private static final long STUCK_POSITION_TOLERANCE_MS = 250L;
    // Keep roughly 400ms of decoded audio ready so brief hiccups don't surface as pops.
    private static final int JITTER_TARGET_FRAMES = 20;
    private static final int JITTER_MAX_FRAMES = 50;

    private final List<AudioTrack> defaultQueue = new LinkedList<>();
    private final Set<String> votes = new HashSet<>();

    private final PlayerManager manager;
    private final AudioPlayer audioPlayer;
    private final long guildId;

    private final Deque<AudioFrame> frameBuffer = new ArrayDeque<>();
    private AudioFrame lastFrame;
    private volatile long lastFrameProvideTimeMs;
    private AbstractQueue<QueuedTrack> queue;

    protected AudioHandler(PlayerManager manager, Guild guild, AudioPlayer player)
    {
        this.manager = manager;
        this.audioPlayer = player;
        this.guildId = guild.getIdLong();

        this.setQueueType(manager.getBot().getSettingsManager().getSettings(guildId).getQueueType());
        clearFrameState();
    }

    public void setQueueType(QueueType type)
    {
        queue = type.createInstance(queue);
    }

    public int addTrackToFront(QueuedTrack qtrack)
    {
        if(audioPlayer.getPlayingTrack()==null)
        {
            audioPlayer.playTrack(qtrack.getTrack());
            return -1;
        }
        else
        {
            queue.addAt(0, qtrack);
            return 0;
        }
    }
    
    public int addTrack(QueuedTrack qtrack)
    {
        if(audioPlayer.getPlayingTrack()==null)
        {
            audioPlayer.playTrack(qtrack.getTrack());
            return -1;
        }
        else
            return queue.add(qtrack);
    }
    
    public AbstractQueue<QueuedTrack> getQueue()
    {
        return queue;
    }
    
    public void stopAndClear()
    {
        queue.clear();
        defaultQueue.clear();
        audioPlayer.stopTrack();
        //current = null;
        clearFrameState();
    }
    
    public boolean isMusicPlaying(JDA jda)
    {
        return guild(jda).getSelfMember().getVoiceState().inAudioChannel() && audioPlayer.getPlayingTrack()!=null;
    }
    
    public Set<String> getVotes()
    {
        return votes;
    }
    
    public AudioPlayer getPlayer()
    {
        return audioPlayer;
    }
    
    public RequestMetadata getRequestMetadata()
    {
        if(audioPlayer.getPlayingTrack() == null)
            return RequestMetadata.EMPTY;
        RequestMetadata rm = audioPlayer.getPlayingTrack().getUserData(RequestMetadata.class);
        return rm == null ? RequestMetadata.EMPTY : rm;
    }
    
    public boolean playFromDefault()
    {
        if(!defaultQueue.isEmpty())
        {
            audioPlayer.playTrack(defaultQueue.remove(0));
            return true;
        }
        Settings settings = manager.getBot().getSettingsManager().getSettings(guildId);
        if(settings==null || settings.getDefaultPlaylist()==null)
            return false;
        
        Playlist pl = manager.getBot().getPlaylistLoader().getPlaylist(settings.getDefaultPlaylist());
        if(pl==null || pl.getItems().isEmpty())
            return false;
        pl.loadTracks(manager, (at) -> 
        {
            if(audioPlayer.getPlayingTrack()==null)
                audioPlayer.playTrack(at);
            else
                defaultQueue.add(at);
        }, () -> 
        {
            if(pl.getTracks().isEmpty() && !manager.getBot().getConfig().getStay())
                manager.getBot().closeAudioConnection(guildId);
        });
        return true;
    }
    
    // Audio Events
    @Override
    public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason)
    {
        clearFrameState();
        RepeatMode repeatMode = manager.getBot().getSettingsManager().getSettings(guildId).getRepeatMode();
        // if the track ended normally, and we're in repeat mode, re-add it to the queue
        if(endReason==AudioTrackEndReason.FINISHED && repeatMode != RepeatMode.OFF)
        {
            QueuedTrack clone = new QueuedTrack(track.makeClone(), track.getUserData(RequestMetadata.class));
            if(repeatMode == RepeatMode.ALL)
                queue.add(clone);
            else
                queue.addAt(0, clone);
        }
        
        if(queue.isEmpty())
        {
            if(!playFromDefault())
            {
                manager.getBot().getNowplayingHandler().onTrackUpdate(null);
                if(!manager.getBot().getConfig().getStay())
                    manager.getBot().closeAudioConnection(guildId);
                // unpause, in the case when the player was paused and the track has been skipped.
                // this is to prevent the player being paused next time it's being used.
                player.setPaused(false);
            }
        }
        else
        {
            QueuedTrack qt = queue.pull();
            player.playTrack(qt.getTrack());
        }
    }

    @Override
    public void onTrackException(AudioPlayer player, AudioTrack track, FriendlyException exception)
    {
        LOG.error("Track {} has failed to play", track.getIdentifier(), exception);

        RequestMetadata metadata = track.getUserData(RequestMetadata.class);
        Guild guild = manager.getBot().getJDA() == null ? null : manager.getBot().getJDA().getGuildById(guildId);
        TextChannel channel = resolveNotificationChannel(guild, metadata);

        if(channel != null)
        {
            String message = manager.getBot().getConfig().getError() + " **" + FormatUtil.filter(track.getInfo().title)
                    + "** の再生中にエラーが発生しました";
            if(exception.getMessage() != null && !exception.getMessage().isEmpty())
                message += ": " + FormatUtil.filter(exception.getMessage());
            channel.sendMessage(FormatUtil.filter(message)).queue();
        }

        if(guild != null && shouldRetryWithSearch(metadata))
        {
            String query = metadata.requestInfo.query.trim();
            if(channel != null)
            {
                channel.sendMessage(FormatUtil.filter(manager.getBot().getConfig().getWarning()
                        + " 別の検索結果を探しています... (`" + query + "`)")).queue();
            }

            RequestMetadata retryMetadata = metadata.withRequestInfo(metadata.requestInfo.withFallbackAttempted());
            manager.getBot().getPlayerManager().loadItemOrdered(guild, "ytsearch:" + query,
                    new FallbackResultHandler(guild, channel, retryMetadata, query));
        }
    }

    @Override
    public void onTrackStuck(AudioPlayer player, AudioTrack track, long thresholdMs)
    {
        LOG.warn("Track {} reported stuck ({}ms). Verifying before recovery.", track.getIdentifier(), thresholdMs);

        long stuckAtPosition = track.getPosition();
        manager.getBot().getThreadpool().schedule(() ->
                verifyAndRecoverFromStuck(player, track, stuckAtPosition),
                STUCK_RECHECK_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private void verifyAndRecoverFromStuck(AudioPlayer player, AudioTrack track, long stuckAtPosition)
    {
        if(player.getPlayingTrack() != track)
        {
            LOG.debug("Ignoring stuck event for {} because another track is now playing.", track.getIdentifier());
            return;
        }

        long sinceLastFrame = System.currentTimeMillis() - lastFrameProvideTimeMs;
        long currentPosition = track.getPosition();
        long advanced = Math.max(0L, currentPosition - stuckAtPosition);

        if(sinceLastFrame < RECENT_FRAME_WINDOW_MS || advanced > STUCK_POSITION_TOLERANCE_MS)
        {
            LOG.info("Track {} recovered after stuck notification ({}ms since last frame, advanced {}ms).",
                    track.getIdentifier(), sinceLastFrame, advanced);
            return;
        }

        RequestMetadata metadata = track.getUserData(RequestMetadata.class);
        Guild guild = manager.getBot().getJDA() == null ? null : manager.getBot().getJDA().getGuildById(guildId);
        TextChannel channel = resolveNotificationChannel(guild, metadata);

        boolean canRetry = metadata != null && metadata.requestInfo != null && metadata.requestInfo.canRetryStuck();

        if(canRetry)
        {
            if(channel != null)
            {
                channel.sendMessage(FormatUtil.filter(manager.getBot().getConfig().getWarning()
                        + " 再生が途切れたため曲を再読み込みします...")).queue();
            }

            AudioTrack clone = track.makeClone();
            if(clone != null)
            {
                if(clone.isSeekable())
                    clone.setPosition(currentPosition);
                RequestMetadata updated = metadata.withRequestInfo(metadata.requestInfo.withStuckRetry());
                clone.setUserData(updated);
                clearFrameState();
                player.playTrack(clone);
                return;
            }
        }

        if(channel != null)
        {
            channel.sendMessage(FormatUtil.filter(manager.getBot().getConfig().getWarning()
                    + " 再生が復旧できなかったため、次の曲に進みます。")).queue();
        }

        clearFrameState();
        player.stopTrack();
    }

    private boolean shouldRetryWithSearch(RequestMetadata metadata)
    {
        if(metadata == null || metadata.requestInfo == null)
            return false;
        if(!metadata.requestInfo.canRetrySearch())
            return false;
        String query = metadata.requestInfo.query;
        if(query == null)
            return false;
        query = query.trim();
        if(query.isEmpty())
            return false;
        return !OtherUtil.isUrl(query);
    }

    private TextChannel resolveNotificationChannel(Guild guild, RequestMetadata metadata)
    {
        if(guild == null)
            return null;

        if(metadata != null && metadata.requestInfo != null && metadata.requestInfo.channelId != 0L)
        {
            TextChannel channel = guild.getTextChannelById(metadata.requestInfo.channelId);
            if(channel != null)
                return channel;
        }

        Settings settings = manager.getBot().getSettingsManager().getSettings(guildId);
        return settings == null ? null : settings.getTextChannel(guild);
    }

    private class FallbackResultHandler implements AudioLoadResultHandler
    {
        private final Guild guild;
        private final TextChannel channel;
        private final RequestMetadata metadata;
        private final String query;

        private FallbackResultHandler(Guild guild, TextChannel channel, RequestMetadata metadata, String query)
        {
            this.guild = guild;
            this.channel = channel;
            this.metadata = metadata;
            this.query = query;
        }

        @Override
        public void trackLoaded(AudioTrack track)
        {
            queueFallbackTrack(track);
        }

        @Override
        public void playlistLoaded(AudioPlaylist playlist)
        {
            AudioTrack single = playlist.getSelectedTrack();
            if(single == null && !playlist.getTracks().isEmpty())
                single = playlist.getTracks().get(0);

            if(single != null)
                queueFallbackTrack(single);
            else if(channel != null)
                channel.sendMessage(FormatUtil.filter(manager.getBot().getConfig().getWarning()
                        + " 代替候補が見つかりませんでした (`" + query + "`)")).queue();
        }

        @Override
        public void noMatches()
        {
            if(channel != null)
                channel.sendMessage(FormatUtil.filter(manager.getBot().getConfig().getWarning()
                        + " `" + query + "` に一致する代替結果は見つかりませんでした。"))
                        .queue();
        }

        @Override
        public void loadFailed(FriendlyException exception)
        {
            if(channel != null)
            {
                String message = manager.getBot().getConfig().getError() + " 代替検索の読み込みに失敗しました";
                if(exception.getMessage() != null && !exception.getMessage().isEmpty())
                    message += ": " + FormatUtil.filter(exception.getMessage());
                channel.sendMessage(message).queue();
            }
        }

        private void queueFallbackTrack(AudioTrack track)
        {
            if(track == null)
                return;

            RequestMetadata effectiveMetadata = metadata;
            long start = 0L;
            if(metadata != null && metadata.requestInfo != null)
            {
                start = metadata.requestInfo.startTimestamp;
                effectiveMetadata = metadata.withRequestInfo(metadata.requestInfo.withResolvedUrl(track.getInfo().uri));
            }
            track.setPosition(start);
            QueuedTrack queued = new QueuedTrack(track, effectiveMetadata);
            int position = addTrackToFront(queued);
            if(channel != null)
            {
                if(position == -1)
                {
                    channel.sendMessage(FormatUtil.filter(manager.getBot().getConfig().getSuccess()
                            + " 代替トラック **" + track.getInfo().title + "** の再生を開始しました。"))
                            .queue();
                }
                else
                {
                    channel.sendMessage(FormatUtil.filter(manager.getBot().getConfig().getSuccess()
                            + " 代替トラック **" + track.getInfo().title + "** をキューの先頭に追加しました。"))
                            .queue();
                }
            }
        }
    }

    @Override
    public void onTrackStart(AudioPlayer player, AudioTrack track)
    {
        votes.clear();
        manager.getBot().getNowplayingHandler().onTrackUpdate(track);
        clearFrameState();
    }

    
    // Formatting
    public NowPlayingMessage getNowPlaying(JDA jda)
    {
        if(isMusicPlaying(jda))
        {
            Guild guild = guild(jda);
            AudioTrack track = audioPlayer.getPlayingTrack();
            EmbedBuilder eb = new EmbedBuilder();
            eb.setColor(guild.getSelfMember().getColor());
            RequestMetadata rm = getRequestMetadata();
            if(rm.getOwner() != 0L)
            {
                User u = guild.getJDA().getUserById(rm.user.id);
                if(u==null)
                    eb.setAuthor(FormatUtil.formatUsername(rm.user), null, rm.user.avatar);
                else
                    eb.setAuthor(FormatUtil.formatUsername(u), null, u.getEffectiveAvatarUrl());
            }

            try 
            {
                eb.setTitle(track.getInfo().title, track.getInfo().uri);
            }
            catch(Exception e) 
            {
                eb.setTitle(track.getInfo().title);
            }

            if(track instanceof YoutubeAudioTrack && manager.getBot().getConfig().useNPImages())
            {
                eb.setThumbnail("https://img.youtube.com/vi/"+track.getIdentifier()+"/mqdefault.jpg");
            }
            
            if(track.getInfo().author != null && !track.getInfo().author.isEmpty())
                eb.setFooter("Source: " + track.getInfo().author, null);

            double progress = (double)audioPlayer.getPlayingTrack().getPosition()/track.getDuration();
            eb.setDescription(getStatusEmoji()
                    + " "+FormatUtil.progressBar(progress)
                    + " `[" + TimeUtil.formatTime(track.getPosition()) + "/" + TimeUtil.formatTime(track.getDuration()) + "]` "
                    + FormatUtil.volumeIcon(audioPlayer.getVolume()));
            
            String content = FormatUtil.filter(
                    manager.getBot().getConfig().getSuccess()+" **Now Playing in "
                    + guild.getSelfMember().getVoiceState().getChannel().getAsMention()+"...**");
            return new NowPlayingMessage(content, eb.build());
        }
        else return null;
    }
    
    public NowPlayingMessage getNoMusicPlaying(JDA jda)
    {
        Guild guild = guild(jda);
        String content = FormatUtil.filter(manager.getBot().getConfig().getSuccess()+" **Now Playing...**");
        MessageEmbed embed = new EmbedBuilder()
                .setTitle("No music playing")
                .setDescription(STOP_EMOJI+" "+FormatUtil.progressBar(-1)+" "+FormatUtil.volumeIcon(audioPlayer.getVolume()))
                .setColor(guild.getSelfMember().getColor())
                .build();
        return new NowPlayingMessage(content, embed);
    }

    public String getStatusEmoji()
    {
        return audioPlayer.isPaused() ? PAUSE_EMOJI : PLAY_EMOJI;
    }
    
    // Audio Send Handler methods
    /*@Override
    public boolean canProvide() 
    {
        if (lastFrame == null)
            lastFrame = audioPlayer.provide();

        return lastFrame != null;
    }

    @Override
    public byte[] provide20MsAudio() 
    {
        if (lastFrame == null) 
            lastFrame = audioPlayer.provide();

        byte[] data = lastFrame != null ? lastFrame.getData() : null;
        lastFrame = null;

        return data;
    }*/
    
    @Override
    public boolean canProvide()
    {
        if(lastFrame == null)
            fillFrameBuffer();

        return lastFrame != null;
    }

    @Override
    public ByteBuffer provide20MsAudio()
    {
        if(lastFrame == null)
            fillFrameBuffer();

        if(lastFrame == null)
            return null;

        ByteBuffer buffer = ByteBuffer.wrap(lastFrame.getData());
        lastFrameProvideTimeMs = System.currentTimeMillis();
        lastFrame = null;

        if(!frameBuffer.isEmpty())
            lastFrame = frameBuffer.poll();

        fillFrameBuffer();

        return buffer;
    }

    @Override
    public boolean isOpus() 
    {
        return true;
    }
    
    
    // Private methods
    private Guild guild(JDA jda)
    {
        return jda.getGuildById(guildId);
    }

    private void clearFrameState()
    {
        frameBuffer.clear();
        lastFrame = null;
        lastFrameProvideTimeMs = System.currentTimeMillis();
    }

    private void fillFrameBuffer()
    {
        AudioFrame provided;
        while((provided = audioPlayer.provide()) != null)
        {
            if(lastFrame == null)
                lastFrame = provided;
            else if(frameBuffer.size() < JITTER_MAX_FRAMES)
                frameBuffer.offer(provided);

            if(lastFrame != null && frameBuffer.size() >= JITTER_TARGET_FRAMES)
                break;
        }

        if(lastFrame == null && !frameBuffer.isEmpty())
            lastFrame = frameBuffer.poll();
    }

    public static class NowPlayingMessage
    {
        private final String content;
        private final MessageEmbed embed;

        public NowPlayingMessage(String content, MessageEmbed embed)
        {
            this.content = content;
            this.embed = embed;
        }

        public String getContent()
        {
            return content;
        }

        public MessageEmbed getEmbed()
        {
            return embed;
        }

        public MessageCreateData toCreateData()
        {
            return new MessageCreateBuilder()
                    .setContent(content)
                    .setEmbeds(embed)
                    .build();
        }

        public MessageEditData toEditData()
        {
            return new MessageEditBuilder()
                    .setContent(content)
                    .setEmbeds(embed)
                    .build();
        }
    }
}

