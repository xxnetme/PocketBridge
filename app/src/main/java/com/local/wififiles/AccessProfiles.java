package com.local.wififiles;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Profiles live only in app preferences. The service consumes the same root/mode keys as before. */
final class AccessProfiles {
    static final String ACTIVE = "activeAccessProfile";
    private static final String PROFILES = "accessProfilesV1";
    private static final int LIMIT = 16;

    static final class Profile {
        final String id, name, root;
        final boolean readOnly;
        Profile(String id, String name, String root, boolean readOnly) {
            this.id = id; this.name = name; this.root = root; this.readOnly = readOnly;
        }
    }

    private AccessProfiles() {}

    static List<Profile> list(Context context) {
        List<Profile> out = new ArrayList<>();
        try {
            JSONArray data = new JSONArray(BridgeOptions.prefs(context).getString(PROFILES, "[]"));
            for (int i = 0; i < Math.min(LIMIT, data.length()); i++) {
                JSONObject entry = data.optJSONObject(i);
                if (entry == null || !(entry.opt("readOnly") instanceof Boolean) || !(entry.opt("root") instanceof String)) continue;
                String id = entry.optString("id"), name = entry.optString("name");
                if (!id.matches("[0-9a-f-]{36}") || name.isEmpty() || name.length() > 48) continue;
                out.add(new Profile(id, name, entry.getString("root"), entry.getBoolean("readOnly")));
            }
        } catch (Exception damaged) { /* Never apply malformed preferences. */ }
        return out;
    }

    static Profile active(Context context) {
        String id;
        try { id = BridgeOptions.prefs(context).getString(ACTIVE, ""); }
        catch (ClassCastException damaged) { return null; }
        for (Profile p : list(context)) {
            if (p.id.equals(id) && p.root.equals(BridgeOptions.root(context)) && p.readOnly == BridgeOptions.readOnly(context)) return p;
        }
        return null;
    }

    static Profile saveCurrent(Context context, String name) throws Exception {
        String title = name.trim();
        List<Profile> profiles = list(context);
        if (title.isEmpty() || title.length() > 48 || profiles.size() >= LIMIT) throw new IOException("Invalid profile name or limit reached");
        for (Profile p : profiles) if (p.name.equalsIgnoreCase(title)) throw new IOException("Profile already exists");
        String root = BridgeOptions.root(context);
        validate(root);
        Profile profile = new Profile(UUID.randomUUID().toString(), title, root, BridgeOptions.readOnly(context));
        profiles.add(profile);
        write(context, profiles, profile.id, false);
        return profile;
    }

    static void delete(Context context, Profile profile) throws Exception {
        List<Profile> profiles = list(context);
        if (!profiles.removeIf(p -> p.id.equals(profile.id))) return;
        Profile active = active(context);
        write(context, profiles, null, active != null && profile.id.equals(active.id));
    }

    static void validate(String root) throws IOException {
        StorageRoot.resolve(Environment.getExternalStorageDirectory(), root);
    }

    static void configure(Context context, String root, boolean readOnly, String profileId) throws IOException {
        validate(root);
        SharedPreferences.Editor changes = BridgeOptions.prefs(context).edit().putString(BridgeOptions.ROOT, root)
                .putBoolean(BridgeOptions.READ_ONLY, readOnly);
        if (profileId == null) changes.remove(ACTIVE);
        else changes.putString(ACTIVE, profileId);
        if (!changes.commit()) throw new IOException("Could not persist access settings");
    }

    private static void write(Context context, List<Profile> profiles, String setActive, boolean unsetActive) throws Exception {
        JSONArray arr = new JSONArray();
        for (Profile p : profiles) arr.put(new JSONObject().put("id", p.id).put("name", p.name)
                .put("root", p.root).put("readOnly", p.readOnly));
        SharedPreferences.Editor e = BridgeOptions.prefs(context).edit().putString(PROFILES, arr.toString());
        if (setActive != null) e.putString(ACTIVE, setActive);
        else if (unsetActive) e.remove(ACTIVE);
        if (!e.commit()) throw new IOException("Could not persist profiles");
    }
}
