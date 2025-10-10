/*
 * Copyright 2021 John Grosh <john.a.grosh@gmail.com>.
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

import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jmusicbot.utils.TimeUtil;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.entities.User;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 *
 * @author John Grosh (john.a.grosh@gmail.com)
 */
public class RequestMetadata
{
    public static final RequestMetadata EMPTY = new RequestMetadata((User)null, null);
    
    public final UserInfo user;
    public final RequestInfo requestInfo;

    public RequestMetadata(User user, RequestInfo requestInfo)
    {
        this(user == null ? null : new UserInfo(user.getIdLong(), user.getName(), user.getDiscriminator(), user.getEffectiveAvatarUrl()), requestInfo);
    }

    private RequestMetadata(UserInfo user, RequestInfo requestInfo)
    {
        this.user = user;
        this.requestInfo = requestInfo;
    }
    
    public long getOwner()
    {
        return user == null ? 0L : user.id;
    }

    public static RequestMetadata fromResultHandler(AudioTrack track, CommandEvent event)
    {
        return new RequestMetadata(event.getAuthor(), new RequestInfo(event.getArgs(), track.getInfo().uri, event.getChannel().getIdLong()));
    }

    public RequestMetadata withRequestInfo(RequestInfo requestInfo)
    {
        return new RequestMetadata(user, requestInfo);
    }

    public static class RequestInfo
    {
        public final String query, url;
        public final long startTimestamp;
        public final long channelId;
        public final boolean searchFallbackAttempted;

        public RequestInfo(String query, String url, long channelId)
        {
            this(query, url, tryGetTimestamp(query), channelId, false);
        }

        private RequestInfo(String query, String url, long startTimestamp, long channelId, boolean searchFallbackAttempted)
        {
            this.url = url;
            this.query = query;
            this.startTimestamp = startTimestamp;
            this.channelId = channelId;
            this.searchFallbackAttempted = searchFallbackAttempted;
        }
        public RequestInfo withFallbackAttempted()
        {
            return new RequestInfo(query, url, startTimestamp, channelId, true);
        }

        public boolean canRetrySearch()
        {
            return !searchFallbackAttempted;
        }

        public RequestInfo withResolvedUrl(String resolvedUrl)
        {
            return new RequestInfo(query, resolvedUrl, startTimestamp, channelId, searchFallbackAttempted);
        }

        private static final Pattern youtubeTimestampPattern = Pattern.compile("youtu(?:\\.be|be\\..+)/.*\\?.*(?!.*list=)t=([\\dhms]+)");
        private static long tryGetTimestamp(String url)
        {
            Matcher matcher = youtubeTimestampPattern.matcher(url);
            return matcher.find() ? TimeUtil.parseUnitTime(matcher.group(1)) : 0;
        }
    }
    
    public static class UserInfo
    {
        public final long id;
        public final String username, discrim, avatar;
        
        private UserInfo(long id, String username, String discrim, String avatar)
        {
            this.id = id;
            this.username = username;
            this.discrim = discrim;
            this.avatar = avatar;
        }
    }
}
