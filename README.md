# AI Builder (Fabric mod for Minecraft 26.2)

Type what you want in chat, an AI designs it, you drop a structure block, it appears.

## Build it
Needs **JDK 25**.
1. Easiest: generate a fresh 26.2 project at https://fabricmc.net/develop , then copy this
   project's `src/` folder over its `src/` (and keep its Gradle wrapper). Or:
2. Use this folder directly: run `gradle wrapper --gradle-version 9.3.0` once (needs Gradle installed),
   then `./gradlew build` (Windows: `gradlew.bat build`).
3. Jar is in `build/libs/ai-builder-1.0.0.jar`. Put it in `mods/` together with **Fabric API**.

If versions in `gradle.properties` are not found, take the current ones from fabricmc.net/develop.

## Use it (type in chat, or use /name as normal commands)
| Command | What it does |
|---|---|
| `./keygpt "key"` / `./keygemini "key"` / `./keyclaude "key"` | save the key and switch to that AI |
| `./build a 50 block tall and 11x11 block wide watch tower` | AI imagines, analyses every detail, writes the block plan |
| place a **structure block** | it is removed; the build appears with that spot as the **bottom center** |
| `./undo` | revert the last build |
| `./cancel` | stop generating |
| `./provider gpt\|gemini\|claude`, `./model [provider] name` | choose AI / model |
| `./savebuild name`, `./loadbuild name`, `./listbuilds` | reuse builds with no API cost |
| `./keycustom "key"` + `./customurl <url>` + `./model custom <name>` | any OpenAI-compatible AI (OpenRouter, DeepSeek, Groq, Mistral...) |
| `./keygroq` / `./keymistral` / `./keyopenrouter` / `./keysambanova` `"key"` | save the key and switch to that AI (URL built in) |
| `/keygemini` + Tab | suggests your previously used keys (works with every `/key...` command) |
| `./keys [provider]`, `./keygemini 2`, `./forgetkeys <provider>` | list / reuse / delete saved keys |
| `./mode fast` / `./mode full` | fast = 1 AI request per build (default, cheap); full = imagine + analyze + plan + review |
| `./status`, `./help` | info |

Get a structure block with `/give @s structure_block`. Chat commands that start with `./` are
swallowed by the mod, so other players never see your API key.

## Config: `config/ai-builder/config.json`
`refinePasses` (extra AI review passes, default 1), `blocksPerTick`, `maxBlocks`, `maxTokens`,
`requireCreative`, `allowedPlayers` (if set, only those names can use it), `models`.
API keys are stored there in plain text - never share that file.
On a multiplayer server the mod (and the keys) live on the server; API usage is billed to the key owner.

## Notes
- Each build = 3-5 API calls (imagine, analyse, write, review), so it costs money and takes a while.
- Placement is spread over server ticks so big towers do not freeze the game.
