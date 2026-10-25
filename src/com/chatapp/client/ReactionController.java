package com.chatapp.client;

import com.chatapp.protocol.Protocol;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import java.awt.Font;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class ReactionController {
    private final Consumer<String> sendCommand;
    public ReactionController(Consumer<String> sendCommand) { this.sendCommand = sendCommand; }

    public JMenu createReactionMenu(MessageActionController.MessageView message) {
        JMenu menu = new JMenu("Thả cảm xúc");
        menu.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        for (String reaction : Protocol.REACTIONS) {
            JMenuItem item = new JMenuItem(label(reaction));
            item.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 14));
            item.addActionListener(e -> sendCommand.accept(Protocol.join(Protocol.REACT_MSG, message.id(), reaction)));
            menu.add(item);
        }
        return menu;
    }

    public static String label(String reaction) {
        switch (reaction) {
            case "LIKE": return "👍 Like";
            case "LOVE": return "❤️ Love";
            case "HAHA": return "😂 Haha";
            case "WOW": return "😮 Wow";
            case "SAD": return "😢 Sad";
            case "ANGRY": return "😡 Angry";
            default: return reaction;
        }
    }
    public static String emoji(String reaction) {
        switch (reaction) {
            case "LIKE": return "👍";
            case "LOVE": return "❤️";
            case "HAHA": return "😂";
            case "WOW": return "😮";
            case "SAD": return "😢";
            case "ANGRY": return "😡";
            default: return reaction;
        }
    }
    public static String formatSummary(Map<String, Set<String>> reactions) {
        StringBuilder result = new StringBuilder();
        for (String reaction : Protocol.REACTIONS) {
            Set<String> users = reactions.get(reaction);
            if (users != null && !users.isEmpty()) result.append(" ").append(emoji(reaction)).append(" ").append(users.size());
        }
        return result.toString();
    }
    public static boolean isSupported(String reaction) {
        if (reaction == null) return false;
        for (String allowed : Protocol.REACTIONS) if (allowed.equals(reaction)) return true;
        return false;
    }
}
