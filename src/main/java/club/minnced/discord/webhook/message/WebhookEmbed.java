package club.minnced.discord.webhook.message;

import org.json.JSONObject;
import org.json.JSONString;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class WebhookEmbed implements JSONString {
    public static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;

    private final Long timestamp;
    private final Integer color;

    private final String description;
    private final String thumbnailUrl;
    private final String imageUrl;

    private final EmbedFooter footer;
    private final EmbedTitle title;
    private final EmbedAuthor author;
    private final List<EmbedField> fields;

    public WebhookEmbed(Long timestamp, Integer color,
                        String description, String thumbnailUrl, String imageUrl,
                        EmbedFooter footer, EmbedTitle title, EmbedAuthor author,
                        List<EmbedField> fields) {
        this.timestamp = timestamp;
        this.color = color;
        this.description = description;
        this.thumbnailUrl = thumbnailUrl;
        this.imageUrl = imageUrl;
        this.footer = footer;
        this.title = title;
        this.author = author;
        this.fields = fields;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public int getColor() {
        return color;
    }

    public String getDescription() {
        return description;
    }

    public String getThumbnailUrl() {
        return thumbnailUrl;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public EmbedFooter getFooter() {
        return footer;
    }

    public EmbedTitle getTitle() {
        return title;
    }

    public EmbedAuthor getAuthor() {
        return author;
    }

    public List<EmbedField> getFields() {
        return fields;
    }

    @Override
    public String toJSONString() {
        JSONObject json = new JSONObject();
        if (description != null)
            json.put("description", description);
        if (timestamp != null)
            json.put("timestamp", TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(timestamp)));
        if (color != null)
            json.put("color", color & 0xFFFFFF);
        if (author != null)
            json.put("author", author);
        if (footer != null)
            json.put("footer", footer);
        if (thumbnailUrl != null)
            json.put("thumbnail",
                     new JSONObject()
                             .put("url", thumbnailUrl));
        if (imageUrl != null)
            json.put("image",
                     new JSONObject()
                             .put("url", imageUrl));
        if (!fields.isEmpty())
            json.put("fields", fields);
        if (title != null) {
            if (title.getUrl() != null)
                json.put("url", title.url);
            json.put("title", title.text);
        }
        return json.toString();
    }

    public static class EmbedField {
        private final boolean inline;
        private final String name, value;

        public EmbedField(boolean inline, String name, String value) {
            this.inline = inline;
            this.name = name;
            this.value = value;
        }

        public boolean isInline() {
            return inline;
        }

        public String getName() {
            return name;
        }

        public String getValue() {
            return value;
        }
    }

    public static class EmbedAuthor {
        private final String name, icon, url;

        public EmbedAuthor(String name, String icon, String url) {
            this.name = name;
            this.icon = icon;
            this.url = url;
        }

        public String getName() {
            return name;
        }

        public String getIcon() {
            return icon;
        }

        public String getUrl() {
            return url;
        }
    }

    public static class EmbedFooter {
        private final String text, icon;

        public EmbedFooter(String text, String icon) {
            this.text = text;
            this.icon = icon;
        }

        public String getText() {
            return text;
        }

        public String getIcon() {
            return icon;
        }
    }

    public static class EmbedTitle {
        private final String text, url;

        public EmbedTitle(String text, String url) {
            this.text = text;
            this.url = url;
        }

        public String getText() {
            return text;
        }

        public String getUrl() {
            return url;
        }
    }
}
