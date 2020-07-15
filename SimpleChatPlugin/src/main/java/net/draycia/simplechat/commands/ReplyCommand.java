package net.draycia.simplechat.commands;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.annotation.CommandAlias;
import co.aikar.commands.annotation.CommandPermission;
import co.aikar.commands.annotation.Default;
import net.draycia.simplechat.SimpleChat;
import net.draycia.simplechat.storage.ChatUser;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

@CommandAlias("reply|r")
@CommandPermission("simplechat.reply")
public class ReplyCommand extends BaseCommand {

    private SimpleChat simpleChat;

    public ReplyCommand(SimpleChat simpleChat) {
        this.simpleChat = simpleChat;
    }

    @Default
    public void baseCommand(Player player, String... args) {
        if (args.length == 0) {
            return;
        }

        ChatUser user = simpleChat.getUserService().wrap(player);
        ChatUser targetUser = simpleChat.getUserService().wrap(user.getReplyTarget());

        if (user.getReplyTarget() == null) {
            String message = simpleChat.getConfig().getString("language.no-reply-target");
            Component component = simpleChat.processMessage(message, "br", "\n");
            user.sendMessage(component);

            return;
        }

        targetUser.sendMessage(user, String.join(" ", args));
    }

}