package dev.e1ixyz.servermanager.discord;

import dev.e1ixyz.servermanager.Config;
import dev.e1ixyz.servermanager.whitelist.WhitelistService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.slf4j.Logger;

import java.util.List;

/**
 * Discord bot that replaces the whitelist website. Guild members run
 * {@code /link <code>} or {@code /whitelist <code>} with the 4-digit code that
 * {@link WhitelistService} issued in-game; on success the account is whitelisted
 * and the player simply rejoins Minecraft.
 *
 * <p>The token comes from the {@code SM_DISCORD_TOKEN} environment variable (never
 * from config). Commands are registered per-guild so they appear instantly.
 */
public final class DiscordBot extends ListenerAdapter {

  private static final List<String> COMMANDS = List.of("link", "whitelist");

  private final Config.Discord cfg;
  private final String token;
  private final WhitelistService whitelist;
  private final Logger log;
  private volatile JDA jda;

  public DiscordBot(Config.Discord cfg, String token, WhitelistService whitelist, Logger log) {
    this.cfg = cfg;
    this.token = token;
    this.whitelist = whitelist;
    this.log = log;
  }

  /** Starts the bot asynchronously. Returns false (and logs why) if it can't start. */
  public boolean start() {
    if (cfg == null || !cfg.enabled) {
      log.info("[discord] bot disabled (discord.enabled=false); skipping.");
      return false;
    }
    if (token == null || token.isBlank()) {
      log.warn("[discord] enabled but SM_DISCORD_TOKEN is not set; bot NOT started.");
      return false;
    }
    if (cfg.guildId == null || cfg.guildId.isBlank()) {
      log.warn("[discord] enabled but discord.guildId is blank; bot NOT started.");
      return false;
    }
    try {
      // createLight = no member/presence/message-content caching; slash commands need no intents.
      this.jda = JDABuilder.createLight(token.trim())
          .addEventListeners(this)
          .build();
      log.info("[discord] connecting to Discord (commands register once ready)...");
      return true;
    } catch (Exception ex) {
      log.error("[discord] failed to start bot", ex);
      return false;
    }
  }

  public void stop() {
    JDA j = this.jda;
    this.jda = null;
    if (j != null) {
      try {
        j.shutdownNow();
      } catch (Exception ignored) {
        // best-effort during proxy shutdown/reload
      }
    }
  }

  @Override
  public void onReady(ReadyEvent event) {
    Guild guild = event.getJDA().getGuildById(cfg.guildId.trim());
    if (guild == null) {
      log.warn("[discord] bot is not in guild {} — cannot register commands. "
          + "Invite it with the 'applications.commands' + 'bot' scopes.", cfg.guildId);
      return;
    }
    SlashCommandData link = Commands.slash("link", "Whitelist yourself with the code shown in-game")
        .addOption(OptionType.STRING, "code", "The code shown when you tried to join Minecraft", true);
    SlashCommandData whitelistCmd = Commands.slash("whitelist", "Whitelist yourself with the code shown in-game")
        .addOption(OptionType.STRING, "code", "The code shown when you tried to join Minecraft", true);
    guild.updateCommands().addCommands(link, whitelistCmd).queue(
        ok -> log.info("[discord] registered /link and /whitelist in guild {} ({})", guild.getName(), guild.getId()),
        err -> log.error("[discord] failed to register slash commands", err));
    log.info("[discord] bot ready as {}", event.getJDA().getSelfUser().getName());
  }

  @Override
  public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
    if (!COMMANDS.contains(event.getName())) return;
    event.deferReply(true).queue(); // ephemeral: only the invoking user sees the result

    OptionMapping opt = event.getOption("code");
    String code = (opt == null) ? "" : opt.getAsString().trim();
    String discordId = event.getUser().getId();

    String reply;
    try {
      WhitelistService.RedeemResult result = whitelist.redeemWithReason(code, null, discordId);
      if (result.ok()) {
        reply = orDefault(cfg.linkSuccess, "Linked! You are now whitelisted — rejoin the Minecraft server.");
        log.info("[discord] {} redeemed a code -> whitelisted", event.getUser().getName());
      } else {
        reply = orDefault(cfg.linkFailure, "That code is invalid or expired. Rejoin Minecraft to get a fresh code.");
        log.info("[discord] {} failed to redeem a code ({})", event.getUser().getName(), result.reason());
      }
    } catch (Exception ex) {
      reply = orDefault(cfg.linkFailure, "Something went wrong. Try again in a moment.");
      log.error("[discord] error redeeming code for {}", discordId, ex);
    }
    event.getHook().sendMessage(reply).queue();
  }

  private static String orDefault(String value, String fallback) {
    return (value == null || value.isBlank()) ? fallback : value;
  }
}
