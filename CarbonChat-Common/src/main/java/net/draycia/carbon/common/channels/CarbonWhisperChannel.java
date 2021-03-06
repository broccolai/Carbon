package net.draycia.carbon.common.channels;

import net.draycia.carbon.api.CarbonChat;
import net.draycia.carbon.api.CarbonChatProvider;
import net.draycia.carbon.api.channels.WhisperChannel;
import net.draycia.carbon.api.config.ChannelOptions;
import net.draycia.carbon.api.events.ChatComponentEvent;
import net.draycia.carbon.api.events.ChatFormatEvent;
import net.draycia.carbon.api.events.misc.CarbonEvents;
import net.draycia.carbon.api.users.CarbonUser;
import net.draycia.carbon.api.users.ConsoleUser;
import net.draycia.carbon.api.users.PlayerUser;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.Template;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class CarbonWhisperChannel implements WhisperChannel {

  private final @NonNull CarbonChat carbonChat;

  private final @NonNull PlayerUser sender;
  private final @NonNull PlayerUser audience;

  public CarbonWhisperChannel(final @NonNull PlayerUser sender, final @NonNull PlayerUser audience) {
    this.carbonChat = CarbonChatProvider.carbonChat();
    this.sender = sender;
    this.audience = audience;
  }

  @Override
  public @NonNull ChannelOptions options() {
    return ChannelOptions.defaultChannel();
  }

  @Override
  public void options(final @NonNull ChannelOptions options) {

  }

  @Override
  public @NonNull PlayerUser sender() {
    return this.sender;
  }

  @Override
  public @NonNull PlayerUser audience() {
    return this.audience;
  }

  @Override
  public @NonNull TextColor channelColor(final @NonNull CarbonUser user) {
    return NamedTextColor.WHITE;
  }

  @Override
  public @NonNull Map<CarbonUser, Component> parseMessage(final @NonNull PlayerUser user, final @NonNull String message,
                                                          final boolean fromRemote) {
    return this.parseMessage(user, Collections.singleton(this.audience()), message, fromRemote);
  }

  @Override
  public @NonNull Map<CarbonUser, Component> parseMessage(final @NonNull PlayerUser user,
                                                          final @NonNull Collection<@NonNull PlayerUser> recipients,
                                                          final @NonNull String message, final boolean fromRemote) {
    final Map<CarbonUser, Component> result = new HashMap<>();

    // Formats
    final String senderFormat = this.senderFormat();
    final String receiverFormat = this.receiverFormat();

    if (senderFormat == null) {
      throw new IllegalArgumentException("Sender format (whispers) not found!");
    }

    if (receiverFormat == null) {
      throw new IllegalArgumentException("Receiver format (whispers) not found!");
    }

    final ChatFormatEvent senderFormatEvent = new ChatFormatEvent(this.sender, this.sender, this, senderFormat, message);

    CarbonEvents.post(senderFormatEvent);

    if (senderFormatEvent.cancelled() || senderFormatEvent.message().trim().isEmpty()) {
      return Collections.emptyMap();
    }

    final ChatFormatEvent receiverFormatEvent = new ChatFormatEvent(this.sender, this.audience, this, receiverFormat, message);

    CarbonEvents.post(receiverFormatEvent);

    if (receiverFormatEvent.cancelled() || receiverFormatEvent.message().trim().isEmpty()) {
      return Collections.emptyMap();
    }

    // Display names
    final Component senderName = this.sender.nicknameOrName();
    final Component receiverName = this.audience.nicknameOrName();

    // Components
    final TextComponent senderComponent = (TextComponent) this.carbonChat.messageProcessor().processMessage(
      senderFormatEvent.format(),
      Template.of("senderdisplayname", senderName),
      Template.of("sender", this.sender.name()),
      Template.of("receiverdisplayname", receiverName),
      Template.of("receiver", this.audience.name()),
      Template.of("phase", Long.toString(System.currentTimeMillis() % 25)),
      Template.of("message", senderFormatEvent.message()));

    final ChatComponentEvent senderComponentEvent = new ChatComponentEvent(this.sender, this.sender, this,
      senderComponent, senderFormatEvent.message());

    CarbonEvents.post(senderComponentEvent);

    result.put(this.sender, senderComponentEvent.component());

    final TextComponent receiverComponent = (TextComponent) this.carbonChat.messageProcessor().processMessage(
      receiverFormatEvent.format(),
      Template.of("senderdisplayname", senderName),
      Template.of("sender", this.sender.name()),
      Template.of("receiverdisplayname", receiverName),
      Template.of("receiver", this.audience.name()),
      Template.of("phase", Long.toString(System.currentTimeMillis() % 25)),
      Template.of("message", senderFormatEvent.message()));

    final ChatComponentEvent receiverComponentEvent = new ChatComponentEvent(this.sender, this.audience, this,
      receiverComponent, receiverFormatEvent.message());

    CarbonEvents.post(receiverComponentEvent);

    result.put(this.audience, receiverComponentEvent.component());

    final String consoleFormatString = this.consoleFormat();

    if (consoleFormatString != null) {
      final ChatFormatEvent consoleFormatEvent = new ChatFormatEvent(this.sender, null, this,
        consoleFormatString, message);

      CarbonEvents.post(consoleFormatEvent);

      final TextComponent consoleFormat = (TextComponent) this.carbonChat.messageProcessor().processMessage(
        consoleFormatEvent.format(),
        Template.of("senderdisplayname", senderName),
        Template.of("sender", this.sender.name()),
        Template.of("receiverdisplayname", receiverName),
        Template.of("receiver", this.audience.name()),
        Template.of("phase", Long.toString(System.currentTimeMillis() % 25)),
        Template.of("message", senderFormatEvent.message()));

      final ChatComponentEvent consoleEvent = new ChatComponentEvent(this.sender, null, this, consoleFormat,
        consoleFormatEvent.message());

      CarbonEvents.post(consoleEvent);

      if (!consoleEvent.cancelled()) {
        final ConsoleUser consoleUser = this.carbonChat.userService().consoleUser();

        if (consoleUser != null) {
          result.put(consoleUser, consoleEvent.component());
        }
      }
    }

    this.sender.replyTarget(this.audience);
    this.audience.replyTarget(this.sender);

    return result;
  }

  @Override
  public boolean canPlayerUse(final @NonNull PlayerUser user) {
    return user.equals(this.sender);
  }

  @Override
  public boolean canPlayerSee(final @NonNull PlayerUser sender, final @NonNull PlayerUser target, final boolean checkSpying) {
    return (checkSpying && target.spyingWhispers()) || (sender.equals(this.sender) && target.equals(this.audience));
  }

  @Override
  public boolean canPlayerSee(final @NonNull PlayerUser target, final boolean checkSpying) {
    return false;
  }

  @Override
  public void sendComponents(final @NonNull Identity identity,
                             final @NonNull Map<? extends CarbonUser, Component> components) {
    for (final Map.Entry<? extends CarbonUser, Component> entry : components.entrySet()) {
      entry.getKey().sendMessage(identity, entry.getValue());
    }
  }

  @Override
  public void sendComponentsAndLog(final @NonNull Identity identity,
                                   final @NonNull Map<? extends CarbonUser, Component> components) {
    for (final Map.Entry<? extends CarbonUser, Component> entry : components.entrySet()) {
      entry.getKey().sendMessage(Identity.nil(), entry.getValue());

      if (entry.getKey() instanceof ConsoleUser) {
        this.carbonChat.console().sendMessage(identity, entry.getValue());
      }
    }
  }

  @Override
  public @NonNull String name() {
    return "Whisper";
  }

  @Override
  public @NonNull String key() {
    return "whisper";
  }

  @Override
  public boolean ignorable() {
    return false;
  }

  @Override
  public @NonNull List<@NonNull Pattern> itemLinkPatterns() {
    return this.carbonChat.carbonSettings().itemLinkPatterns();
  }

  public @Nullable String senderFormat() {
    return this.carbonChat.carbonSettings().whisperOptions().senderFormat();
  }

  public @Nullable String receiverFormat() {
    return this.carbonChat.carbonSettings().whisperOptions().receiverFormat();
  }

  public @Nullable String consoleFormat() {
    return this.carbonChat.carbonSettings().whisperOptions().consoleFormat();
  }

  @Override
  public @NonNull String switchMessage() {
    return this.carbonChat.carbonSettings().whisperOptions().switchMessage();
  }

  @Override
  public @NonNull String switchOtherMessage() {
    return this.carbonChat.carbonSettings().whisperOptions().switchOtherMessage();
  }

  @Override
  public @NonNull String switchFailureMessage() {
    return this.carbonChat.carbonSettings().whisperOptions().switchFailureMessage();
  }

  @Override
  public @NonNull String cannotIgnoreMessage() {
    throw new UnsupportedOperationException("Lnet/draycia/carbon/common/channels/CarbonWhisperChannel;cannotIgnoreMessage()Ljava/lang/String;");
  }

  @Override
  public @NonNull String toggleOffMessage() {
    return this.carbonChat.carbonSettings().whisperOptions().toggleOffMessage();
  }

  @Override
  public @NonNull String toggleOnMessage() {
    return this.carbonChat.carbonSettings().whisperOptions().toggleOnMessage();
  }

  @Override
  public @NonNull String toggleOtherOnMessage() {
    return this.carbonChat.carbonSettings().whisperOptions().toggleOtherOnMessage();
  }

  @Override
  public @NonNull String toggleOtherOffMessage() {
    return this.carbonChat.carbonSettings().whisperOptions().toggleOtherOffMessage();
  }

  @Override
  public @NonNull String cannotUseMessage() {
    return this.carbonChat.carbonSettings().whisperOptions().cannotUseMessage();
  }
}
