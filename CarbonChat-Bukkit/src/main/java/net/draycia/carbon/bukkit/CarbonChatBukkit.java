package net.draycia.carbon.bukkit;

import cloud.commandframework.CommandManager;
import cloud.commandframework.execution.CommandExecutionCoordinator;
import cloud.commandframework.paper.PaperCommandManager;
import net.draycia.carbon.api.channels.ChatChannel;
import net.draycia.carbon.bukkit.config.CarbonBukkitSettings;
import net.draycia.carbon.bukkit.listeners.BukkitChatHandler;
import net.draycia.carbon.bukkit.listeners.ItemLinkHandler;
import net.draycia.carbon.bukkit.messaging.BungeeMessageService;
import net.draycia.carbon.bukkit.users.BukkitConsoleUser;
import net.draycia.carbon.bukkit.users.BukkitPlayerUser;
import net.draycia.carbon.bukkit.util.BungeeChannelApi;
import net.draycia.carbon.bukkit.util.CarbonPlaceholders;
import net.draycia.carbon.bukkit.util.FunctionalityConstants;
import net.draycia.carbon.api.CarbonChat;
import net.draycia.carbon.api.CarbonChatProvider;
import net.draycia.carbon.api.config.CarbonTranslations;
import net.draycia.carbon.api.adventure.MessageProcessor;
import net.draycia.carbon.api.channels.ChannelRegistry;
import net.draycia.carbon.api.commands.CommandSettingsRegistry;
import net.draycia.carbon.api.config.CarbonSettings;
import net.draycia.carbon.api.config.ChannelSettings;
import net.draycia.carbon.api.config.MessagingType;
import net.draycia.carbon.api.config.ModerationSettings;
import net.draycia.carbon.api.config.StorageType;
import net.draycia.carbon.api.messaging.MessageService;
import net.draycia.carbon.api.users.CarbonUser;
import net.draycia.carbon.api.users.PlayerUser;
import net.draycia.carbon.common.channels.ChannelManager;
import net.draycia.carbon.common.config.ConfigLoader;
import net.draycia.carbon.common.messaging.EmptyMessageService;
import net.draycia.carbon.api.config.SQLCredentials;
import net.draycia.carbon.common.messaging.RedisMessageService;
import net.draycia.carbon.common.listeners.FilterHandler;
import net.draycia.carbon.common.listeners.CapsHandler;
import net.draycia.carbon.common.listeners.CustomPlaceholderHandler;
import net.draycia.carbon.common.listeners.IgnoredPlayerHandler;
import net.draycia.carbon.common.listeners.LegacyFormatHandler;
import net.draycia.carbon.common.listeners.MuteHandler;
import net.draycia.carbon.common.listeners.PingHandler;
import net.draycia.carbon.bukkit.listeners.PlaceholderHandler;
import net.draycia.carbon.common.listeners.PlayerJoinListener;
import net.draycia.carbon.bukkit.listeners.RelationalPlaceholderHandler;
import net.draycia.carbon.common.listeners.ShadowMuteHandler;
import net.draycia.carbon.common.listeners.UrlLinkHandler;
import net.draycia.carbon.common.listeners.UserFormattingHandler;
import net.draycia.carbon.common.listeners.WhisperPingHandler;
import net.draycia.carbon.common.adventure.AdventureManager;
import net.draycia.carbon.common.commands.misc.CommandRegistrar;
import net.draycia.carbon.common.messaging.MessageManager;
import net.draycia.carbon.api.users.UserService;
import net.draycia.carbon.common.users.JSONUserService;
import net.draycia.carbon.common.users.MySQLUserService;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.ChannelNotRegisteredException;
import org.bukkit.scheduler.BukkitRunnable;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

@SuppressWarnings("initialization.fields.uninitialized")
public final class CarbonChatBukkit extends JavaPlugin implements CarbonChat {

  private static final int BSTATS_PLUGIN_ID = 8720;

  private ChannelManager channelManager;
  private AdventureManager messageProcessor;
  private CommandSettingsRegistry commandSettings;
  private ModerationSettings moderationSettings;
  private CarbonSettings carbonSettings;
  private ChannelSettings channelSettings;
  private CarbonBukkitSettings carbonBukkitSettings;
  private UserService<BukkitPlayerUser> userService;
  private MessageManager messageManager;
  private CarbonTranslations translations;
  private CommandManager<CarbonUser> commandManager;

  private Logger logger;

  private @NonNull Collection<@NonNull String> proxyPlayerNames = new ArrayList<>();

  public static final GsonComponentSerializer GSON_SERIALIZER = GsonComponentSerializer.builder().build();

  @Override
  public void onLoad() {
    CarbonChatProvider.register(this);
  }

  @Override
  public void onEnable() {
    this.logger = LoggerFactory.getLogger(this.getName());

    // Setup metrics
    final Metrics metrics = new Metrics(this, BSTATS_PLUGIN_ID);

    this.loadConfigs();

    // Setup Adventure
    this.messageProcessor = new AdventureManager(this.carbonSettings.formatType());

    // Initialize managers
    this.channelManager = new ChannelManager(this);

    // Handle messaging service
    final MessagingType messagingType = this.carbonSettings().messagingType();
    final MessageService messageService;

    if (messagingType == MessagingType.REDIS) {
      messageService = new RedisMessageService(this, this.carbonSettings.redisCredentials());
    } else if (messagingType == MessagingType.BUNGEECORD) {
      messageService = new BungeeMessageService(this); // TODO: only support this if bukkit
    } else {
      messageService = new EmptyMessageService();
    }

    this.messageManager = new MessageManager(this, messageService);

    if (this.carbonBukkitSettings.bungeePlayerListEnabled()) {
      new BukkitRunnable() {
        @Override
        public void run() {
          try {
            if (Bukkit.getOnlinePlayers().size() > 0) {
              BungeeChannelApi.of(CarbonChatBukkit.this).getPlayerList("ALL")
                .thenAccept(value -> CarbonChatBukkit.this.proxyPlayerNames = value);
            }
          } catch (final ChannelNotRegisteredException exception) {
            CarbonChatBukkit.this.logger().info("Exception thrown when gathering proxy player list!");
            CarbonChatBukkit.this.logger().info("Aborting attempts to communicate to the proxy.");
            CarbonChatBukkit.this.logger().info("This is merely for command completions, don't worry.");

            this.cancel();
          }
        }
      }.runTaskTimer(this, 20, 5*20);
    }

    // Handle storage service
    final StorageType storageType = this.carbonSettings().storageType();

    final Supplier<Collection<BukkitPlayerUser>> supplier = () -> {
      final List<BukkitPlayerUser> users = new ArrayList<>();

      for (final Player player : Bukkit.getOnlinePlayers()) {
        users.add(this.userService.wrap(player.getUniqueId()));
      }

      return users;
    };

    if (storageType == StorageType.MYSQL) {
      this.logger().info("Enabling MySQL storage!");
      final SQLCredentials credentials = this.carbonSettings().sqlCredentials();
      this.userService = new MySQLUserService<>(this, credentials, supplier, () -> this.proxyPlayerNames,
        BukkitPlayerUser::new, BukkitConsoleUser::new);
    } else if (storageType == StorageType.JSON) {
      this.logger().info("Enabling JSON storage!");
      this.userService = new JSONUserService<>(BukkitPlayerUser.class, this, supplier, () -> this.proxyPlayerNames,
        BukkitPlayerUser::new, BukkitConsoleUser::new);
    } else {
      this.logger().error("Invalid storage type selected! Falling back to JSON.");
      this.userService = new JSONUserService<>(BukkitPlayerUser.class, this, supplier, () -> this.proxyPlayerNames,
        BukkitPlayerUser::new, BukkitConsoleUser::new);
    }

    // Setup listeners
    this.setupCommands();
    this.setupListeners();

    // Setup PlaceholderAPI placeholders
    new CarbonPlaceholders(this).register();

    // Log missing functionality
    if (this.carbonSettings().showTips() && !FunctionalityConstants.HAS_HOVER_EVENT_METHOD) {
      this.logger().error("Item linking disabled! Please use Paper 1.16.2 #172 or newer.");
    }

    this.logger().info("CarbonChat startup finished! Enjoy :)");
  }

  @Override
  public void onDisable() {
    this.userService.onDisable();
  }

  private void setupListeners() {
    final PluginManager pluginManager = this.getServer().getPluginManager();

    // Register chat listeners
    pluginManager.registerEvents(new BukkitChatHandler(this), this);

    new CapsHandler();
    new CustomPlaceholderHandler();
    new FilterHandler();
    new IgnoredPlayerHandler();
    new ItemLinkHandler();
    new LegacyFormatHandler();
    new MuteHandler();
    new PingHandler();
    new PlaceholderHandler();
    new PlayerJoinListener();
    new RelationalPlaceholderHandler();
    new ShadowMuteHandler();
    new UrlLinkHandler();
    new UserFormattingHandler();
    new WhisperPingHandler();
  }

  @SuppressWarnings("return.type.incompatible")
  private void setupCommands() {
    try {
      this.commandManager = new PaperCommandManager<>(this,
        CommandExecutionCoordinator
          .simpleCoordinator(), sender -> {
        if (sender instanceof Player) {
          return this.userService().wrap(((Player) sender).getUniqueId());
        } else {
          return new BukkitConsoleUser();
        }
      }, user -> {
        if (user instanceof PlayerUser) {
          return Bukkit.getPlayer(((PlayerUser) user).uuid());
        } else {
          return Bukkit.getConsoleSender();
        }
      });

      CommandRegistrar.registerCommands(this.commandManager);
    } catch (final Exception e) {
      e.printStackTrace();
    }
  }

  @Override
  public void reload() {
    this.loadConfigs();
    this.channelManager.reloadChannels();

    final ChatChannel defaultChannel = this.channelManager.defaultChannel();

    for (final PlayerUser user : this.userService().onlineUsers()) {
      final ChatChannel selectedChannel = user.selectedChannel();

      if (selectedChannel != null) {
        final ChatChannel channel = this.channelManager.channelOrDefault(selectedChannel.key());

        if (channel != null) {
          user.selectedChannel(channel);
        } else if (defaultChannel != null) {
          user.selectedChannel(defaultChannel);
        }
      }
    }
  }

  private void loadConfigs() {
    this.getDataFolder().mkdir();

    try {
      final ConfigLoader<YamlConfigurationLoader> loader = new ConfigLoader<>(YamlConfigurationLoader.class);

      this.carbonSettings = CarbonSettings.loadFrom(loader.loadConfig("config.yml"));
      this.commandSettings = CommandSettingsRegistry.loadFrom(loader.loadConfig("commands.yml"));
      this.channelSettings = ChannelSettings.loadFrom(loader.loadConfig("channels.yml"));
      this.translations = CarbonTranslations.loadFrom(loader.loadConfig("language.yml"));
      this.moderationSettings = ModerationSettings.loadFrom(loader.loadConfig("moderation.yml"));

      this.carbonBukkitSettings = CarbonBukkitSettings.loadFrom(loader.loadConfig("bukkit.yml"));
    } catch(final ConfigurateException ex) {
      ex.printStackTrace();
    }
  }

  @Override
  public @NonNull File dataDirectory() {
    return this.getDataFolder();
  }

  @Override
  public @NonNull Component resolveName(final @NonNull UUID uuid) {
    if (this.messageService() instanceof RedisMessageService) {
      final RedisMessageService redis = (RedisMessageService) this.messageService();

      final String value = redis.get("UUID:" + uuid.toString());

      if (value != null) {
        return Component.text(value);
      } else {
        final String name = Bukkit.getOfflinePlayer(uuid).getName();

        if (name != null) {
          redis.set("UUID:" + uuid.toString(), name);

          return Component.text(name);
        }
      }
    }

    final String name = Bukkit.getOfflinePlayer(uuid).getName();

    if (name != null) {
      return Component.text(name);
    }

    return Component.text(uuid.toString());
  }

  @Override
  public @NonNull UUID resolveUUID(final @NonNull String name) {
    if (this.messageService() instanceof RedisMessageService) {
      final RedisMessageService redis = (RedisMessageService) this.messageService();

      final String value = redis.get("Name:" + name);

      if (value != null) {
        return UUID.fromString(value);
      } else {
        final UUID uuid = Bukkit.getOfflinePlayer(name).getUniqueId();
        redis.set("UUID:" + uuid.toString(), name);

        return uuid;
      }
    }

    return Bukkit.getOfflinePlayer(name).getUniqueId();
  }

  @Override
  public void cacheUUID(final @NonNull String name, final @NonNull UUID uuid) {
    if (this.messageService() instanceof RedisMessageService) {
      ((RedisMessageService) this.messageService()).set(name, uuid.toString());
    }
  }

  public @NonNull MessageManager messageManager() {
    return this.messageManager;
  }

  @Override
  public @NonNull UserService<BukkitPlayerUser> userService() {
    return this.userService;
  }

  @Override
  public @NonNull ChannelRegistry channelRegistry() {
    return this.channelManager.registry();
  }

  @Override
  public @NonNull CarbonTranslations translations() {
    return this.translations;
  }

  @Override
  public @NonNull ModerationSettings moderationSettings() {
    return this.moderationSettings;
  }

  @Override
  public @NonNull CarbonSettings carbonSettings() {
    return this.carbonSettings;
  }

  @Override
  public @NonNull ChannelSettings channelSettings() {
    return this.channelSettings;
  }

  @Override
  public @NonNull CommandSettingsRegistry commandSettings() {
    return this.commandSettings;
  }

  @Override
  public @NonNull CommandManager<CarbonUser> commandManager() {
    return this.commandManager;
  }

  @Override
  public @NonNull MessageProcessor messageProcessor() {
    return this.messageProcessor;
  }

  @Override
  public @NonNull MessageService messageService() {
    return this.messageManager().messageService();
  }

  @Override
  public @NonNull Logger logger() {
    return this.logger;
  }

  @Override
  public @NonNull Path dataFolder() {
    return this.getDataFolder().toPath();
  }

  @Override
  public @NonNull GsonComponentSerializer gsonSerializer() {
    return CarbonChatBukkit.GSON_SERIALIZER;
  }

  @Override
  public @NonNull Audience console() {
    return Bukkit.getConsoleSender();
  }

  @Override
  public @NonNull String version() {
    return this.getDescription().getVersion();
  }
}
