package slimeknights.tconstruct.library.modifiers.dynamic;

import com.google.common.collect.ImmutableList;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import io.netty.handler.codec.DecoderException;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.util.GsonHelper;
import slimeknights.mantle.data.GenericLoaderRegistry.IGenericLoader;
import slimeknights.mantle.util.JsonHelper;
import slimeknights.tconstruct.library.modifiers.Modifier;
import slimeknights.tconstruct.library.modifiers.ModifierHook;
import slimeknights.tconstruct.library.modifiers.TinkerHooks;
import slimeknights.tconstruct.library.modifiers.modules.ModifierModule;
import slimeknights.tconstruct.library.modifiers.modules.ModifierModule.ModuleWithHooks;
import slimeknights.tconstruct.library.modifiers.util.ModifierHookMap;
import slimeknights.tconstruct.library.modifiers.util.ModifierLevelDisplay;
import slimeknights.tconstruct.library.tools.nbt.IToolContext;
import slimeknights.tconstruct.library.tools.nbt.IToolStackView;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Modifier consisting of many composed hooks
 */
public class ComposableModifier extends Modifier {
  private final ModifierLevelDisplay levelDisplay;
  private final TooltipDisplay tooltipDisplay;
  @Getter
  private final int priority;
  private final List<ModuleWithHooks> modules;
  protected ComposableModifier(ModifierLevelDisplay levelDisplay, TooltipDisplay tooltipDisplay, int priority, List<ModuleWithHooks> modules) {
    super(ModifierModule.createMap(modules));
    this.levelDisplay = levelDisplay;
    this.tooltipDisplay = tooltipDisplay;
    this.priority = priority;
    this.modules = modules;
  }

  /** Creates a builder instance for datagen */
  public static Builder builder() {
    return new Builder();
  }

  @Override
  public IGenericLoader<? extends Modifier> getLoader() {
    return LOADER;
  }

  /** This method is final to prevent overrides as the constructor no longer calls it */
  @Override
  protected final void registerHooks(ModifierHookMap.Builder hookBuilder) {}

  @Override
  public Component getDisplayName(int level) {
    return levelDisplay.nameForLevel(this, level);
  }

  @Override
  public Component getDisplayName(IToolStackView tool, int level) {
    return getHook(TinkerHooks.DISPLAY_NAME).getDisplayName(tool, this, level, getDisplayName(level));
  }

  @Override
  public float getEffectiveLevel(IToolContext tool, int level) {
    return getHook(TinkerHooks.EFFECTIVE_LEVEL).getEffectiveLevel(tool, this, level);
  }

  @Override
  public boolean shouldDisplay(boolean advanced) {
    return advanced ? tooltipDisplay != TooltipDisplay.NEVER
                    : tooltipDisplay == TooltipDisplay.ALWAYS;
  }

  /** Determines when this modifier shows in tooltips */
  public enum TooltipDisplay { ALWAYS, TINKER_STATION, NEVER }

  public static IGenericLoader<ComposableModifier> LOADER = new IGenericLoader<>() {
    @Override
    public ComposableModifier deserialize(JsonObject json) {
      ModifierLevelDisplay level_display = ModifierLevelDisplay.LOADER.getAndDeserialize(json, "level_display");
      TooltipDisplay tooltipDisplay = TooltipDisplay.ALWAYS;
      int priority = GsonHelper.getAsInt(json, "priority", DEFAULT_PRIORITY);
      if (json.has("tooltip_display")) {
        tooltipDisplay = JsonHelper.getAsEnum(json, "tooltip_display", TooltipDisplay.class);
      }
      List<ModuleWithHooks> modules = JsonHelper.parseList(json, "modules", ModuleWithHooks::deserialize);
      // convert illegal argument to json syntax, bit more expected in this context
      try {
        return new ComposableModifier(level_display, tooltipDisplay, priority, modules);
      } catch (IllegalArgumentException e) {
        throw new JsonSyntaxException(e.getMessage(), e);
      }
    }

    @Override
    public void serialize(ComposableModifier object, JsonObject json) {
      json.add("level_display", ModifierLevelDisplay.LOADER.serialize(object.levelDisplay));
      json.addProperty("tooltip_display", object.tooltipDisplay.name().toLowerCase(Locale.ROOT));
      json.addProperty("priority", object.priority);
      JsonArray modules = new JsonArray();
      for (ModuleWithHooks module : object.modules) {
        modules.add(module.serialize());
      }
      json.add("modules", modules);
    }

    @Override
    public ComposableModifier fromNetwork(FriendlyByteBuf buffer) {
      ModifierLevelDisplay levelDisplay = ModifierLevelDisplay.LOADER.fromNetwork(buffer);
      TooltipDisplay tooltipDisplay = buffer.readEnum(TooltipDisplay.class);
      int priority = buffer.readInt();
      int moduleCount = buffer.readVarInt();
      ImmutableList.Builder<ModuleWithHooks> builder = ImmutableList.builder();
      for (int i = 0; i < moduleCount; i++) {
        builder.add(ModuleWithHooks.fromNetwork(buffer));
      }
      try {
        return new ComposableModifier(levelDisplay, tooltipDisplay, priority, builder.build());
      } catch (IllegalArgumentException e) {
        throw new DecoderException(e.getMessage(), e);
      }
    }

    @Override
    public void toNetwork(ComposableModifier object, FriendlyByteBuf buffer) {
      ModifierLevelDisplay.LOADER.toNetwork(object.levelDisplay, buffer);
      buffer.writeEnum(object.tooltipDisplay);
      buffer.writeInt(object.priority);
      buffer.writeVarInt(object.modules.size());
      for (ModuleWithHooks module : object.modules) {
        module.toNetwork(buffer);
      }
    }
  };

  /** Builder for a composable modifier instance */
  @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
  @Accessors(fluent = true)
  public static class Builder {
    @Setter
    private ModifierLevelDisplay levelDisplay = ModifierLevelDisplay.DEFAULT;
    @Setter
    private TooltipDisplay tooltipDisplay = TooltipDisplay.ALWAYS;
    @Setter
    private int priority = DEFAULT_PRIORITY;
    private final ImmutableList.Builder<ModuleWithHooks> modules = ImmutableList.builder();

    /** Adds a module to the builder */
    public final <T extends ModifierModule> Builder addModule(T object) {
      modules.add(new ModuleWithHooks(object, Collections.emptyList()));
      return this;
    }

    /** Adds a module to the builder */
    @SafeVarargs
    public final <T extends ModifierModule> Builder addModule(T object, ModifierHook<? super T>... hooks) {
      modules.add(new ModuleWithHooks(object, List.of(hooks)));
      return this;
    }

    /** Builds the final instance */
    public ComposableModifier build() {
      return new ComposableModifier(levelDisplay, tooltipDisplay, priority, modules.build());
    }
  }
}
