package com.chatapp.client;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Bộ chọn emoji độc lập; dùng tài nguyên PNG nên không phụ thuộc font emoji của Windows/JDK. */
public final class EmojiPicker {
    private EmojiPicker() { }

    public static void show(Component owner, Component invoker, Consumer<String> onSelect) {
        if (invoker == null || onSelect == null) return;
        if (!EmojiSupport.assetsAvailable()) {
            JOptionPane.showMessageDialog(owner,
                    "Chưa tải được bộ ảnh emoji màu.\n\n"
                    + "Hãy kiểm tra project có thư mục:\n"
                    + "src/com/chatapp/client/emoji/assets\n\n"
                    + "Thư mục cần chứa các file PNG, ví dụ 1f600.png, 1f602.png.\n"
                    + "Sau đó chọn Clean and Build Project và chạy lại client.",
                    "Thiếu tài nguyên emoji", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Map<String, String[]> categories = getCategories();
        String[] active = { categories.keySet().iterator().next() };
        JPopupMenu popup = new JPopupMenu();
        popup.setBorder(BorderFactory.createLineBorder(new Color(212, 219, 229)));

        JPanel root = new JPanel(new BorderLayout());
        root.setPreferredSize(new Dimension(390, 400));
        root.setBackground(Color.WHITE);

        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setBackground(Color.WHITE);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(229, 233, 240)),
                BorderFactory.createEmptyBorder(9, 12, 9, 8)));
        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("Chọn emoji");
        title.setFont(new Font("Segoe UI", Font.BOLD, 15));
        title.setForeground(new Color(31, 41, 55));
        JLabel sub = new JLabel("Nhấn biểu tượng để chèn vào tin nhắn");
        sub.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        sub.setForeground(new Color(107, 114, 128));
        titles.add(title); titles.add(Box.createVerticalStrut(2)); titles.add(sub);
        JButton close = new JButton("×");
        close.setFont(new Font("Segoe UI", Font.PLAIN, 20));
        close.setForeground(new Color(100, 116, 139));
        close.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        close.setContentAreaFilled(false); close.setFocusPainted(false);
        close.addActionListener(e -> popup.setVisible(false));
        header.add(titles, BorderLayout.CENTER); header.add(close, BorderLayout.EAST);

        JPanel grid = new JPanel(new GridLayout(0, 8, 3, 3));
        grid.setBackground(Color.WHITE);
        grid.setBorder(BorderFactory.createEmptyBorder(8, 9, 8, 9));
        populate(grid, categories.get(active[0]), popup, onSelect);
        JScrollPane scroll = new JScrollPane(grid);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        scroll.getViewport().setBackground(Color.WHITE);

        JPanel nav = new JPanel(new GridLayout(1, categories.size(), 2, 0));
        nav.setBackground(new Color(248, 250, 252));
        nav.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(229, 233, 240)),
                BorderFactory.createEmptyBorder(4, 5, 4, 5)));
        String[] samples = {"😀","👍","❤️","🐻","🍕","⚽","💡","✨","🏁"};
        Map<String, JButton> buttons = new LinkedHashMap<>();
        int idx = 0;
        for (String category : categories.keySet()) {
            String sample = samples[Math.min(idx++, samples.length - 1)];
            JButton b = new JButton(EmojiSupport.iconFor(sample, 23));
            b.setToolTipText(category);
            b.setFocusPainted(false); b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            b.setOpaque(true); b.setBackground(category.equals(active[0]) ? new Color(219,234,254) : new Color(248,250,252));
            b.setBorder(category.equals(active[0])
                    ? BorderFactory.createMatteBorder(0,0,2,0,new Color(37,99,235))
                    : BorderFactory.createEmptyBorder(5,2,5,2));
            buttons.put(category,b); nav.add(b);
            b.addActionListener(e -> {
                active[0] = category;
                populate(grid, categories.get(category), popup, onSelect);
                scroll.getVerticalScrollBar().setValue(0);
                for (Map.Entry<String,JButton> entry : buttons.entrySet()) {
                    boolean selected = entry.getKey().equals(category);
                    entry.getValue().setBackground(selected ? new Color(219,234,254) : new Color(248,250,252));
                    entry.getValue().setBorder(selected
                            ? BorderFactory.createMatteBorder(0,0,2,0,new Color(37,99,235))
                            : BorderFactory.createEmptyBorder(5,2,5,2));
                }
            });
        }
        root.add(header, BorderLayout.NORTH); root.add(scroll, BorderLayout.CENTER); root.add(nav, BorderLayout.SOUTH);
        popup.add(root);
        popup.show(invoker, 0, -root.getPreferredSize().height);
    }

    private static void populate(JPanel grid, String[] emojis, JPopupMenu popup, Consumer<String> onSelect) {
        grid.removeAll();
        if (emojis != null) {
            for (String emoji : emojis) {
                ImageIcon icon = EmojiSupport.iconFor(emoji, 27);
                JButton button = new JButton(icon);
                button.setToolTipText(emoji);
                button.setPreferredSize(new Dimension(39, 37));
                button.setFocusPainted(false); button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                button.setMargin(new Insets(2,2,2,2)); button.setOpaque(true); button.setBackground(Color.WHITE);
                button.setBorder(BorderFactory.createEmptyBorder(2,2,2,2));
                button.addMouseListener(new java.awt.event.MouseAdapter() {
                    @Override public void mouseEntered(java.awt.event.MouseEvent e) {
                        button.setBackground(new Color(239,246,255));
                        button.setBorder(BorderFactory.createLineBorder(new Color(191,219,254)));
                    }
                    @Override public void mouseExited(java.awt.event.MouseEvent e) {
                        button.setBackground(Color.WHITE);
                        button.setBorder(BorderFactory.createEmptyBorder(2,2,2,2));
                    }
                });
                button.addActionListener(e -> { onSelect.accept(emoji); popup.setVisible(false); });
                grid.add(button);
            }
        }
        grid.revalidate(); grid.repaint();
    }

    public static Map<String, String[]> getCategories() {
        Map<String, String[]> categories = new LinkedHashMap<>();
        categories.put("Cảm xúc", new String[]{
            "😀","😃","😄","😁","😆","😅","😂","🤣",
            "😊","😇","🙂","🙃","😉","😌","😍","🥰",
            "😘","😗","😙","😚","😋","😛","😝","😜",
            "🤪","🤨","🧐","🤓","😎","🥸","🤩","🥳",
            "😏","😒","😞","😔","😟","😕","🙁","☹️",
            "😣","😖","😫","😩","🥺","😢","😭","😤",
            "😠","😡","🤬","🤯","😳","🥵","🥶","😱",
            "😨","😰","😥","😓","🤗","🤔","🫣","🤭",
            "🫢","🫡","🤫","🫠","🤥","😶","🫥","😐",
            "😑","😬","🙄","😯","😦","😧","😮","😲",
            "🥱","😴","🤤","😪","😮‍💨","😵","🤐","🥴",
            "🤢","🤮","🤧","😷","🤒","🤕","😈","👿",
            "💀","☠️","👻","👽","🤖","💩","🎃","😺"
        });
        categories.put("Cử chỉ", new String[]{
            "👋","🤚","🖐️","✋","🖖","🫱","🫲","🫳",
            "🫴","👌","🤌","🤏","✌️","🤞","🫰","🤟",
            "🤘","🤙","👈","👉","👆","🖕","👇","☝️",
            "🫵","👍","👎","✊","👊","🤛","🤜","👏",
            "🙌","🫶","👐","🤲","🙏","💅","🤝","💪",
            "🦾","🦿","🖕🏻","👍🏻","👍🏼","👍🏽","👍🏾","👍🏿",
            "👋🏻","👋🏼","👋🏽","👋🏾","👋🏿","👏🏻","👏🏼","👏🏽",
            "👏🏾","👏🏿","🙏🏻","🙏🏼","🙏🏽","🙏🏾","🙏🏿","💃",
            "🕺","🙋","🙋‍♂️","🙋‍♀️","🤦","🤦‍♂️","🤦‍♀️","🤷",
            "🤷‍♂️","🤷‍♀️","🙆","🙅","🙇","💁","🙎","🙍"
        });
        categories.put("Trái tim", new String[]{
            "❤️","🧡","💛","💚","💙","💜","🖤","🩶",
            "🤍","🤎","🩷","🩵","💔","❣️","💕","💞",
            "💓","💗","💖","💘","💝","💟","♥️","❤️‍🔥",
            "❤️‍🩹","💌","💋","😘","😍","🥰","🫶","💑",
            "💏","👩‍❤️‍👨","👨‍❤️‍👨","👩‍❤️‍👩","💐","🌹","🌷","🌸"
        });
        categories.put("Động vật", new String[]{
            "🐶","🐱","🐭","🐹","🐰","🦊","🐻","🐼",
            "🐻‍❄️","🐨","🐯","🦁","🐮","🐷","🐸","🐵",
            "🙈","🙉","🙊","🐒","🐔","🐧","🐦","🐤",
            "🐣","🦆","🦅","🦉","🦇","🐺","🐗","🐴",
            "🦄","🐝","🪱","🐛","🦋","🐌","🐞","🐜",
            "🪰","🪲","🦟","🦗","🕷️","🦂","🐢","🐍",
            "🦎","🦖","🦕","🐙","🦑","🦐","🦞","🦀",
            "🐡","🐠","🐟","🐬","🐳","🐋","🦈","🐊",
            "🐅","🐆","🦓","🦍","🦧","🦣","🐘","🦛",
            "🦏","🐪","🐫","🦒","🦘","🦬","🐃","🐂",
            "🐄","🐎","🐖","🐏","🐑","🦙","🐐","🦌",
            "🐕","🐩","🦮","🐈","🐈‍⬛","🪽","🪿","🦚"
        });
        categories.put("Đồ ăn", new String[]{
            "🍏","🍎","🍐","🍊","🍋","🍌","🍉","🍇",
            "🍓","🫐","🍈","🍒","🍑","🥭","🍍","🥥",
            "🥝","🍅","🍆","🥑","🥦","🥬","🥒","🌶️",
            "🫑","🌽","🥕","🫒","🧄","🧅","🥔","🍠",
            "🥐","🥯","🍞","🥖","🥨","🧀","🥚","🍳",
            "🧈","🥞","🧇","🥓","🥩","🍗","🍖","🌭",
            "🍔","🍟","🍕","🫓","🥪","🥙","🧆","🌮",
            "🌯","🫔","🥗","🥘","🫕","🍝","🍜","🍲",
            "🍛","🍣","🍱","🥟","🍤","🍙","🍚","🍘",
            "🍥","🥠","🥮","🍢","🍡","🍧","🍨","🍦",
            "🥧","🧁","🍰","🎂","🍮","🍭","🍬","🍫",
            "🍿","🍩","🍪","🌰","🥜","🍯","🥛","☕",
            "🧋","🥤","🧃","🧉","🍵","🍺","🥂","🍷"
        });
        categories.put("Hoạt động", new String[]{
            "⚽","🏀","🏈","⚾","🥎","🎾","🏐","🏉",
            "🥏","🎱","🪀","🏓","🏸","🏒","🏑","🥍",
            "🏏","🪃","🥅","⛳","🪁","🏹","🎣","🤿",
            "🥊","🥋","🎽","🛹","🛼","🛷","⛸️","🥌",
            "🎿","⛷️","🏂","🏋️","🤼","🤸","⛹️","🤺",
            "🤾","🏌️","🏇","🧘","🏄","🏊","🤽","🚣",
            "🧗","🚴","🚵","🎖️","🏆","🥇","🥈","🥉",
            "🏅","🎗️","🎫","🎟️","🎪","🤹","🎭","🩰",
            "🎨","🎬","🎤","🎧","🎼","🎹","🥁","🪘",
            "🎷","🎺","🪗","🎸","🪕","🎻","🪇","🪈",
            "🎮","🕹️","🎲","♟️","🧩","🧸","🎯","🎳"
        });
        categories.put("Đồ vật", new String[]{
            "💡","🔦","🕯️","🪔","🧯","🛢️","💸","💵",
            "💴","💶","💷","🪙","💰","💳","🧾","💎",
            "⚖️","🪜","🧰","🪛","🔧","🔨","⚒️","🛠️",
            "⛏️","🪚","🔩","⚙️","🪤","🧱","⛓️","🧲",
            "🔫","💣","🧨","🪓","🔪","🗡️","⚔️","🛡️",
            "🚬","⚰️","🪦","⚱️","🏺","🔮","📿","🧿",
            "💈","⚗️","🔭","🔬","🕳️","🩹","🩺","💊",
            "💉","🧬","🦠","🧫","🧪","🌡️","🧹","🪠",
            "🧺","🧻","🚽","🚰","🛁","🛀","🧼","🪥",
            "🧽","🧴","🛎️","🔑","🗝️","🚪","🪑","🛋️",
            "🛏️","🛌","🖼️","🪞","🪟","🛍️","🎁","🎈"
        });
        categories.put("Biểu tượng", new String[]{
            "✨","⭐","🌟","💫","⚡","🔥","💥","☄️",
            "☀️","🌤️","⛅","🌥️","☁️","🌧️","⛈️","🌩️",
            "❄️","☃️","⛄","🌈","🌊","💧","💦","☔",
            "✅","☑️","✔️","❌","❎","➕","➖","➗",
            "✖️","♾️","💯","‼️","⁉️","❓","❔","❕",
            "❗","🔔","🔕","🎵","🎶","🔊","🔇","📢",
            "🚀","🛸","💤","💬","🗨️","🗯️","💭","♻️",
            "🔞","🚫","⛔","⚠️","🚸","🔰","🔱","〽️",
            "🆗","🆒","🆕","🆙","🆓","🆚","🈯","㊗️",
            "㊙️","🉐","🈹","🈲","🅰️","🅱️","🆎","🅾️"
        });
        categories.put("Cờ", new String[]{
            "🏁","🚩","🎌","🏴","🏳️","🏳️‍🌈","🏳️‍⚧️","🏴‍☠️",
            "🇻🇳","🇺🇸","🇬🇧","🇯🇵","🇰🇷","🇨🇳","🇹🇭","🇸🇬",
            "🇲🇾","🇮🇩","🇵🇭","🇦🇺","🇨🇦","🇫🇷","🇩🇪","🇮🇹",
            "🇪🇸","🇵🇹","🇧🇷","🇮🇳","🇳🇿","🇺🇦","🇷🇺","🇲🇽"
        });
        return categories;
    }


}
