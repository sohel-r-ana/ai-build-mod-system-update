package com.aibuilder;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The "brain". Two modes:
 *  - fast (default): ONE request -> JSON block program.
 *  - full: imagine -> analyze -> write JSON -> review (4-5 requests, more detail, more cost).
 * Pure Java. Runs on a background thread.
 */
public final class BuildPipeline {
    private BuildPipeline() {}

    private static String system(Config cfg) {
        return """
                You are an expert Minecraft Java Edition builder and sculptor. You can build ANYTHING a player \
                asks for: animals and creatures, vehicles, machines, statues, plants and trees, landscapes, \
                logos, pixel art, furniture, buildings. Build exactly the subject that was requested and make it \
                instantly recognisable: correct proportions, characteristic shapes, fitting colours and \
                materials, small details. NEVER turn a request into a house or building, and never add rooms, \
                floors, roofs, doors, windows or interiors, unless the subject itself has them. \
                Use only real Minecraft Java block ids and valid block-state properties. \
                Build limit: at most %d blocks in total.
                """.formatted(cfg.maxBlocks);
    }

    private static final String COORDS = """
            COORDINATE SYSTEM: [x,y,z] are integers relative to the build origin. x and z are measured \
            from the CENTER column of the build; y=0 is the bottom layer (the ground level), y grows upward. \
            For an ODD width W the x range is -(W-1)/2..(W-1)/2 (11 wide = -5..5). For an EVEN width W the \
            range is -W/2..W/2-1. Same for z (depth). Orientation: north = -z, south = +z, east = +x, west = -x.
            """;

    private static final String SPEC = """
            OUTPUT FORMAT - one single JSON object, nothing else (no commentary, no markdown fences):
            {"name":"short_name","notes":"parts and where they sit","mirror":"x","palette":{"w":"minecraft:stone_bricks"},"ops":[...]}
            - "notes": 2-5 short lines: the parts of the subject and the coordinates/ranges where each sits.
            - "mirror" (optional): "x", "z" or "xz". Every block is automatically copied to its mirror image \
            around the center column, so for symmetric subjects you only build one half. Only use it with odd \
            widths, and avoid direction-dependent blocks (stairs, doors...) facing east/west (or north/south for z) \
            because they are not flipped.
            - Ops run in order; later ops overwrite earlier ones at the same position. The "block" field is a \
            palette key or a full block id (properties allowed: "minecraft:oak_stairs[facing=north,half=bottom]"). \
            "air" clears/carves blocks. "keep" leaves the world untouched.
            Ops:
            {"op":"box","from":[x,y,z],"to":[x,y,z],"block":"w"}                 solid box
            {"op":"hollow","from":[x,y,z],"to":[x,y,z],"block":"w","t":1}       box shell (6 faces), thickness t
            {"op":"walls","from":[x,y,z],"to":[x,y,z],"block":"w","t":1}        only the 4 vertical sides
            {"op":"line","from":[x,y,z],"to":[x,y,z],"block":"w"}               straight line (legs, antennae, rails)
            {"op":"cylinder","center":[x,y,z],"radius":3,"height":10,"axis":"y","block":"w","hollow":false}  \
            starts at center and extends "height" blocks along axis x, y (default) or z. Use axis x/z for \
            things lying down (train cars, tubes, tails).
            {"op":"sphere","center":[x,y,z],"radius":5,"block":"w","hollow":false,"half":"full"}   half: top|bottom|full
            {"op":"ellipsoid","center":[x,y,z],"radii":[rx,ry,rz],"block":"w","hollow":false}   egg/oval shapes \
            (bodies, heads, hills)
            {"op":"pyramid","center":[x,y,z],"size":6,"block":"w","hollow":false}  stepped pyramid, y = bottom layer
            {"op":"block","pos":[x,y,z],"block":"w"}                              one block (eyes, details)
            {"op":"blocks","list":[[x,y,z],[x,y,z]],"block":"w"}                  many single blocks, same type
            RULES:
            - Choose shapes that fit the subject: ellipsoids/spheres/cylinders for organic or round things, \
            boxes/lines for hard-edged things. Do not default to boxes and roofs.
            - ALL parts must touch or overlap each other (nothing floating or scattered) unless the subject \
            really floats. Build the main body first, then attach the other parts to it.
            - Use big primitives for large areas and single blocks for details (eyes, lights, patterns).
            - Carve with "air" BEFORE placing things inside a carved space.
            - Only block ids that exist in Minecraft Java 26.x. No NBT, no command blocks, no chest contents.
            - The result must be detailed and recognisable as the requested subject.
            """;

    private static final String FAST = """
            Write the build as the JSON object described below. Put "notes" first and use it to plan the parts \
            and where they sit, so that they connect properly. Keep the JSON compact (no comments, little \
            whitespace) and use "mirror" for symmetric subjects. If the request gives a size, match it exactly; \
            otherwise pick a sensible size for the subject (e.g. an ant about 15-25 blocks long).
            """;

    public static BuildPlan generate(Config cfg, String provider, String request, Consumer<String> progress)
            throws Exception {
        AiClient.NOTICE.set(progress);
        try {
            BuildPlan plan = "full".equalsIgnoreCase(cfg.mode)
                    ? generateFull(cfg, provider, request, progress)
                    : generateFast(cfg, provider, request, progress);
            plan.analyzeConnectivity();
            return plan;
        } finally {
            AiClient.NOTICE.remove();
        }
    }

    // ------------------------------------------------------------------ fast

    private static BuildPlan generateFast(Config cfg, String provider, String request, Consumer<String> progress)
            throws Exception {
        String sys = system(cfg);
        List<AiClient.Msg> h = new ArrayList<>();
        progress.accept("Writing the build plan (1 request)...");
        h.add(AiClient.Msg.user("BUILD REQUEST: " + request + "\n\n" + COORDS + "\n" + FAST + "\n" + SPEC));
        return callAndParse(cfg, provider, sys, h, progress, 2);
    }

    // ------------------------------------------------------------------ full

    private static BuildPlan generateFull(Config cfg, String provider, String request, Consumer<String> progress)
            throws Exception {
        String sys = system(cfg);
        List<AiClient.Msg> h = new ArrayList<>();

        progress.accept("Stage 1/3: imagining the build...");
        h.add(AiClient.Msg.user("BUILD REQUEST: " + request + "\n\n" + COORDS + """

                STAGE 1 - IMAGINE. Picture the finished subject exactly as requested. Describe it concretely: \
                overall shape and size in blocks (requested dimensions are MANDATORY and must be matched \
                exactly), its main parts and their proportions, its characteristic details, colours, and the \
                block palette (real Minecraft block ids). Only describe parts the subject really has. \
                Do not write JSON yet."""));
        String design = AiClient.complete(cfg, provider, sys, h);
        h.add(AiClient.Msg.assistant(design));

        progress.accept("Stage 2/3: analyzing every detail...");
        h.add(AiClient.Msg.user("""
                STAGE 2 - ANALYZE. Break your design down into the smallest parts and shapes possible (for \
                example a creature: body segments, head, eyes, legs, feelers; a vehicle: body, wheels, \
                windows, couplings; a building: walls, floors, roof). For every part give the exact \
                coordinates/ranges, the shape type (box, line, cylinder with axis, sphere, ellipsoid...) and \
                the exact block with block-state properties. Then VERIFY: overall size matches the request; \
                all parts touch each other (nothing floating unless it should); symmetry; directions of \
                directional blocks; it stays under the block limit. Fix any problem and give the corrected \
                final breakdown."""));
        String analysis = AiClient.complete(cfg, provider, sys, h);
        h.add(AiClient.Msg.assistant(analysis));

        progress.accept("Stage 3/3: writing the block plan...");
        h.add(AiClient.Msg.user("STAGE 3 - GENERATE. Convert your final breakdown into the block program. "
                + "Include EVERY part and detail from your analysis.\n\n" + COORDS + "\n" + SPEC));
        BuildPlan plan = callAndParse(cfg, provider, sys, h, progress, 3);

        for (int i = 1; i <= cfg.refinePasses; i++) {
            progress.accept("Reviewing the build (pass " + i + "/" + cfg.refinePasses + ")...");
            List<AiClient.Msg> h2 = new ArrayList<>(h);
            h2.add(AiClient.Msg.user("""
                    STAGE 4 - REVIEW. Check the JSON you just wrote against your analysis and the original \
                    request. Check: exact requested dimensions (compute min/max of every axis your ops touch), \
                    parts that float or are disconnected, wrong proportions, missing details, direction of \
                    directional blocks, invalid block ids or properties, anything that looks thin or generic, \
                    and anything that does not belong to the requested subject. Output ONLY the complete \
                    corrected JSON object (same format)."""));
            try {
                String r = AiClient.complete(cfg, provider, sys, h2);
                BuildPlan p2 = parse(r, cfg);
                plan = p2;
                h2.add(AiClient.Msg.assistant(r));
                h = h2;
            } catch (InterruptedException ie) {
                throw ie;
            } catch (Exception e) {
                progress.accept("Review pass failed, keeping the previous plan.");
                break;
            }
        }
        return plan;
    }

    // --------------------------------------------------------------- shared

    /** Sends h, parses the JSON answer; asks the AI to fix it up to maxFixes times. */
    private static BuildPlan callAndParse(Config cfg, String provider, String sys, List<AiClient.Msg> h,
                                          Consumer<String> progress, int maxFixes) throws Exception {
        String out = AiClient.complete(cfg, provider, sys, h);
        h.add(AiClient.Msg.assistant(out));
        Exception last = null;
        for (int attempt = 0; attempt <= maxFixes; attempt++) {
            try {
                return parse(out, cfg);
            } catch (Exception e) {
                last = e;
                if (attempt == maxFixes) break;
                progress.accept("Plan had a problem (" + shorten(e.getMessage()) + "), asking the AI to fix it...");
                h.add(AiClient.Msg.user("Your JSON could not be used: " + e.getMessage()
                        + "\nOutput ONLY the complete corrected JSON object."));
                out = AiClient.complete(cfg, provider, sys, h);
                h.add(AiClient.Msg.assistant(out));
            }
        }
        throw new IOException("The AI produced an invalid plan: " + shorten(last == null ? "?" : last.getMessage()));
    }

    static BuildPlan parse(String text, Config cfg) {
        String t = text.trim();
        int a = t.indexOf('{'), b = t.lastIndexOf('}');
        if (a < 0 || b <= a) throw new IllegalArgumentException("no JSON object found in the answer");
        JsonObject root;
        try {
            root = JsonParser.parseString(t.substring(a, b + 1)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid JSON (maybe cut off - too long?): " + e.getMessage());
        }
        return BuildPlan.fromJson(root, cfg.maxBlocks);
    }

    private static String shorten(String s) {
        if (s == null) return "?";
        return s.length() > 160 ? s.substring(0, 160) + "..." : s;
    }
}
