package io.github.xpl0itu.wiiudownloader.android;

import org.json.JSONException;
import org.json.JSONObject;

/** One title as reported by the Go core. */
public final class Title {

    public final String titleId;
    public final String name;
    public final int region;
    public final String regionName;
    public final String kind;
    public final int category;
    public final int version;

    public Title(String titleId, String name, int region, String regionName, String kind, int category, int version) {
        this.titleId = titleId;
        this.name = name;
        this.region = region;
        this.regionName = regionName;
        this.kind = kind;
        this.category = category;
        this.version = version;
    }

    public static Title fromJson(JSONObject json) throws JSONException {
        return new Title(
                json.optString("titleId", ""),
                json.optString("name", ""),
                json.optInt("region", 0),
                json.optString("regionName", "Unknown"),
                json.optString("kind", "Game"),
                json.optInt("category", 0),
                json.optInt("version", -1));
    }

    public String displayName() {
        if (name == null || name.trim().isEmpty()) {
            return titleId;
        }
        return name;
    }

    public String subtitle() {
        StringBuilder builder = new StringBuilder();
        builder.append(kind);
        builder.append(" \u00b7 ").append(regionName);
        builder.append(" \u00b7 ").append(formatTitleId(titleId));
        if (version >= 0) {
            builder.append(" \u00b7 v").append(version);
        }
        return builder.toString();
    }

    private static String formatTitleId(String titleId) {
        if (titleId == null || titleId.length() != 16) {
            return String.valueOf(titleId);
        }
        return titleId.substring(0, 8).toUpperCase() + "-" + titleId.substring(8).toUpperCase();
    }

    @Override
    public String toString() {
        return displayName();
    }
}
