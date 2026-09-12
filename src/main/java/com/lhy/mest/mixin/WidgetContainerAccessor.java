package com.lhy.mest.mixin;

import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import appeng.client.gui.ICompositeWidget;
import appeng.client.gui.WidgetContainer;

@Mixin(WidgetContainer.class)
public interface WidgetContainerAccessor {
    @Accessor("compositeWidgets")
    Map<String, ICompositeWidget> mest$compositeWidgets();
}
