package com.aibuilder;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Settings stored in config/ai-builder/config.json. Pure Java (no Minecraft classes). */
public class Config {
    /** Active provider: "gpt", "gemini", "claude", "groq", "mistral", "openrouter", "sambanova" or "custom". */
    public String provider = "claude";
    /** API keys per provider. Stored on the machine that runs the server (or singleplayer world). */
    public Map<String, String> keys = new HashMap<>();
    /** Previously used keys per provider, newest first (max 5). Used for suggestions. */
    public Map<String, List<String>> keyHistory = new HashMap<>();
    /** Model overrides per provider (see DEFAULT_MODELS). Change with ./model. */
    public Map<String, String> models = new HashMap<>();
    /** Base URL for the "custom" provider (any OpenAI-compatible API), set with ./customurl. */
    public String customUrl = "";
    /** Max tokens the AI may write per answer. Big builds need a lot. */
    public int maxTokens = 16000;
    /** "fast" = one AI request per build (cheap). "full" = imagine, analyze, write, review (4-5 requests). */
    public String mode = "fast";
    /** How many extra review/fix passes (full mode only) the AI does after writing the plan. 0 = off. */
    public int refinePasses = 1;
    /** Blocks placed per server tick (lower = less lag, slower build). */
    public int blocksPerTick = 3000;
    /** Hard cap on blocks in a single build. */
    public int maxBlocks = 250000;
    /** HTTP timeout for one AI request. */
    public int timeoutSeconds = 600;
    /** If true only creative-mode players may use the commands (unless allowedPlayers is set). */
    public boolean requireCreative = true;
    /** If not empty, ONLY these player names may use the commands. */
    public List<String> allowedPlayers = new ArrayList<>();

    private transient Path file;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final Map<String, String> DEFAULT_MODELS = Map.of(
            "gpt", "gpt-5",
            "gemini", "gemini-2.5-pro",
            "claude", "claude-sonnet-5-5",
            "custom", "",
            "groq", "openai/gpt-oss-120b",
            "mistral", "mistral-large-latest",
            "openrouter", "openai/gpt-oss-120b:free",
            "sambanova", "Meta-Llama-3.3-70B-Instruct");

    /** Fixed base URLs of the OpenAI-compatible providers that have their own command. */
    public static final Map<String, String> PROVIDER_URLS = Map.of(
            "groq", "https://api.groq.com/openai/v1",
            "mistral", "https://api.mistral.ai/v1",
            "openrouter", "https://openrouter.ai/api/v1",
            "sambanova", "https://api.sambanova.ai/v1");

    public static Config load(Path dir) {
        Config c = null;
        Path f = dir.resolve("config.json");
        try {
            Files.createDirectories(dir);
            if (Files.exists(f)) {
                c = GSON.fromJson(Files.readString(f), Config.class);
            }
        } catch (Exception e) {
            System.err.println("[AI Builder] Could not read config, using defaults: " + e);
        }
        if (c == null) c = new Config();
        if (c.keys == null) c.keys = new HashMap<>();
        if (c.models == null) c.models = new HashMap<>();
        if (c.keyHistory == null) c.keyHistory = new HashMap<>();
        for (Map.Entry<String, String> e : new HashMap<>(c.keys).entrySet()) {
            if (e.getValue() != null && !e.getValue().isBlank() && !c.history(e.getKey()).contains(e.getValue())) {
                c.rememberKey(e.getKey(), e.getValue());
            }
        }
        if (c.allowedPlayers == null) c.allowedPlayers = new ArrayList<>();
        c.file = f;
        c.save();
        return c;
    }

    public synchronized void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(this));
        } catch (IOException e) {
            System.err.println("[AI Builder] Could not save config: " + e);
        }
    }

    public synchronized List<String> history(String provider) {
        List<String> h = keyHistory.get(provider);
        return h == null ? new ArrayList<>() : new ArrayList<>(h);
    }

    /** Puts the key first in the provider's history (no duplicates, max 5). */
    public synchronized void rememberKey(String provider, String key) {
        List<String> h = history(provider);
        h.remove(key);
        h.add(0, key);
        while (h.size() > 5) h.remove(h.size() - 1);
        keyHistory.put(provider, h);
    }

    public synchronized void forgetKeys(String provider) {
        keyHistory.remove(provider);
        keys.remove(provider);
    }

    public String model(String p) {
        String m = models.get(p);
        return (m == null || m.isBlank()) ? DEFAULT_MODELS.get(p) : m;
    }

    public String key(String p) {
        String k = keys.get(p);
        return (k == null || k.isBlank()) ? null : k.trim();
    }
}
