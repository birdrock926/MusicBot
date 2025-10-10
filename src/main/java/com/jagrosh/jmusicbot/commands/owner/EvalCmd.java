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
package com.jagrosh.jmusicbot.commands.owner;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.commands.OwnerCommand;
import net.dv8tion.jda.api.entities.ChannelType;

/**
 *
 * @author John Grosh (jagrosh)
 */
public class EvalCmd extends OwnerCommand 
{
    private final Bot bot;
    private final String engine;
    
    public EvalCmd(Bot bot)
    {
        this.bot = bot;
        this.name = "eval";
        this.help = "Nashorn コードを評価します";
        this.aliases = bot.getConfig().getAliases(this.name);
        this.engine = bot.getConfig().getEvalEngine();
        this.guildOnly = false;
    }
    
    @Override
    protected void execute(CommandEvent event) 
    {
        ScriptEngine se = new ScriptEngineManager().getEngineByName(engine);
        if(se == null)
        {
            event.replyError("設定で指定された eval エンジン (`"+engine+"`) が見つかりません。エンジン名が正しくないか、使用中の Java (`"+System.getProperty("java.version")+"`) にそのエンジンが存在しない可能性があります。");
            return;
        }
        se.put("bot", bot);
        se.put("event", event);
        se.put("jda", event.getJDA());
        if (event.getChannelType() != ChannelType.PRIVATE) {
            se.put("guild", event.getGuild());
            se.put("channel", event.getChannel());
        }
        try
        {
            event.reply(event.getClient().getSuccess()+" 評価に成功しました:\n```\n"+se.eval(event.getArgs())+" ```");
        } 
        catch(Exception e)
        {
            event.reply(event.getClient().getError()+" 例外が発生しました:\n```\n"+e+" ```");
        }
    }
    
}
