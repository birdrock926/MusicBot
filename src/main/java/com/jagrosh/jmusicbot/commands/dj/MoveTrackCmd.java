package com.jagrosh.jmusicbot.commands.dj;


import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.audio.QueuedTrack;
import com.jagrosh.jmusicbot.commands.DJCommand;
import com.jagrosh.jmusicbot.queue.AbstractQueue;

/**
 * Command that provides users the ability to move a track in the playlist.
 */
public class MoveTrackCmd extends DJCommand
{

    public MoveTrackCmd(Bot bot)
    {
        super(bot);
        this.name = "movetrack";
        this.help = "現在のキュー内で曲の位置を移動します";
        this.arguments = "<from> <to>";
        this.aliases = bot.getConfig().getAliases(this.name);
        this.bePlaying = true;
    }

    @Override
    public void doCommand(CommandEvent event)
    {
        int from;
        int to;

        String[] parts = event.getArgs().split("\\s+", 2);
        if(parts.length < 2)
        {
            event.replyError("2 つの有効な番号を指定してください。");
            return;
        }

        try
        {
            // Validate the args
            from = Integer.parseInt(parts[0]);
            to = Integer.parseInt(parts[1]);
        }
        catch (NumberFormatException e)
        {
            event.replyError("2 つの有効な番号を入力してください。");
            return;
        }

        if (from == to)
        {
            event.replyError("同じ位置には移動できません。");
            return;
        }

        // Validate that from and to are available
        AudioHandler handler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
        AbstractQueue<QueuedTrack> queue = handler.getQueue();
        if (isUnavailablePosition(queue, from))
        {
            String reply = String.format("`%d` はキュー内の有効な位置ではありません！", from);
            event.replyError(reply);
            return;
        }
        if (isUnavailablePosition(queue, to))
        {
            String reply = String.format("`%d` はキュー内の有効な位置ではありません！", to);
            event.replyError(reply);
            return;
        }

        // Move the track
        QueuedTrack track = queue.moveItem(from - 1, to - 1);
        String trackTitle = track.getTrack().getInfo().title;
        String reply = String.format("**%s** を位置 `%d` から `%d` へ移動しました。", trackTitle, from, to);
        event.replySuccess(reply);
    }

    private static boolean isUnavailablePosition(AbstractQueue<QueuedTrack> queue, int position)
    {
        return (position < 1 || position > queue.size());
    }
}